package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bau-Planer-Marker (Orte, POIs). Sichtbarkeit:
 * - "private": nur der Ersteller
 * - "team": Mitglieder des gewählten Teams
 * - "land": Besitzer des verknüpften Landes
 * - "public": alle Spieler (und BlueMap)
 * Unsichtbare Marker gibt es für den Fragenden nicht (404) – die API verrät nicht, dass sie existieren.
 */
final class MarkerStore extends JsonStore {
    /** Eingebaute Kategorien. Weitere kann der Admin über "extraMarkerTypes" in der Config ergänzen. */
    static final Map<String, String> BUILTIN_TYPES = ordered(
            "base", "Basis", "farm", "Farm", "village", "Dorf", "portal", "Portal", "spawner", "Spawner",
            "resource", "Ressource", "project", "Projekt", "meeting", "Treffpunkt", "danger", "Gefahr",
            "city", "Stadt", "road", "Straße", "death", "Todespunkt", "other", "Sonstiges");
    static final Map<String, String> VISIBILITY = ordered("private", "Nur ich", "team", "Mein Team", "land", "Besitzer des Landes", "public", "Alle");
    static final Set<String> SYMBOLS = Set.of("★", "●", "▲", "■", "◆", "✚", "⚑", "☠", "⌂", "⚒", "♥", "☼");
    private static final int SCHEMA = 2;

    record Marker(String id, String name, String type, String dimension, int x, Integer y, int z, String notes,
                  UUID owner, String visibility, String teamId, String landId, String color, String symbol,
                  long createdAt, long updatedAt) {
        boolean isPublic() {
            return visibility.equals("public");
        }
    }

    private final Map<String, Marker> markers = new LinkedHashMap<>();
    private final PlayerDirectory players;
    private final PlannerConfig config;
    private final Map<String, String> types;
    private Access access;
    private volatile Set<String> knownDimensions = Set.of();

    MarkerStore(Path path, Logger logger, PlayerDirectory players, PlannerConfig config) {
        super(path);
        this.players = players;
        this.config = config;
        Map<String, String> t = new LinkedHashMap<>(BUILTIN_TYPES);
        config.extraMarkerTypes().forEach(t::putIfAbsent);
        this.types = Collections.unmodifiableMap(t);
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("markers")) {
            backupBeforeMigration(logger, json.getAsJsonObject(), SCHEMA);
            for (JsonElement el : json.getAsJsonObject().getAsJsonArray("markers")) {
                try {
                    Marker m = fromJson(el.getAsJsonObject());
                    markers.put(m.id, m);
                } catch (Exception e) {
                    logger.warn("[World Planner] Ein Marker in {} ist kaputt und wird übersprungen.", path);
                }
            }
        }
    }

    void setAccess(Access access) {
        this.access = access;
    }

    void setKnownDimensions(Set<String> dimensions) {
        knownDimensions = Set.copyOf(dimensions);
    }

    Map<String, String> types() {
        return types;
    }

    // ------------------------------------------------------------------ Ändern

    synchronized String create(UUID actor, boolean admin, JsonObject data) {
        long count = markers.values().stream().filter(m -> m.owner.equals(actor)).count();
        if (!admin && count >= config.maxMarkersPerPlayer()) {
            throw PlannerException.badRequest("Du hast schon " + count + " Marker (Maximum " + config.maxMarkersPerPlayer() + ").");
        }
        long now = System.currentTimeMillis();
        Marker m = parse(UUID.randomUUID().toString(), actor, actor, now, now, data, null);
        requireNoDuplicate(m);
        markers.put(m.id, m);
        changed();
        return m.id;
    }

    synchronized void update(UUID actor, boolean admin, String id, JsonObject data) {
        Marker old = requireVisible(actor, id);
        requireEdit(old, actor, admin);
        Marker m = parse(id, old.owner, actor, old.createdAt, System.currentTimeMillis(), data, old);
        requireNoDuplicate(m);
        markers.put(id, m);
        changed();
    }

    synchronized void delete(UUID actor, boolean admin, String id) {
        Marker old = requireVisible(actor, id);
        requireEdit(old, actor, admin);
        markers.remove(id);
        changed();
    }

    /** Land gelöscht: Verknüpfung lösen. "Nur Land-Besitzer"-Marker werden privat (niemand verliert Daten). */
    synchronized void detachLand(String landId) {
        markers.replaceAll((id, m) -> !landId.equals(m.landId) ? m : with(m, m.visibility.equals("land") ? "private" : m.visibility, m.teamId, null));
        changed();
    }

    /** Team aufgelöst: Team-Marker werden privat für ihren Ersteller. */
    synchronized void detachTeam(String teamId) {
        markers.replaceAll((id, m) -> !teamId.equals(m.teamId) ? m : with(m, m.visibility.equals("team") ? "private" : m.visibility, null, m.landId));
        changed();
    }

    /** Probe-Prüfung (für die Import-Vorschau): null wenn ok, sonst die Fehlermeldung. Speichert nichts. */
    synchronized String problem(UUID actor, boolean admin, JsonObject data) {
        try {
            long count = markers.values().stream().filter(m -> m.owner.equals(actor)).count();
            if (!admin && count >= config.maxMarkersPerPlayer()) return "Maximale Anzahl Marker erreicht.";
            long now = System.currentTimeMillis();
            requireNoDuplicate(parse("probe", actor, actor, now, now, data, null));
            return null;
        } catch (PlannerException e) {
            return e.getMessage();
        }
    }

    /** Für /planer weg: ein Marker, den der Spieler sehen darf – eigene zuerst. Name ohne Groß/klein, oder null. */
    synchronized Marker findVisible(UUID viewer, String name) {
        if (name == null) return null;
        String wanted = name.strip();
        Marker other = null;
        for (Marker m : markers.values()) {
            if (!m.name.equalsIgnoreCase(wanted) || !canSee(m, viewer)) continue;
            if (m.owner.equals(viewer)) return m;
            if (other == null) other = m;
        }
        return other;
    }

    /** Marker per id, wenn der Spieler ihn sehen darf – sonst 404 (wie überall). */
    synchronized Marker visible(UUID viewer, String id) {
        return requireVisible(viewer, id);
    }

    /** Namen aller Marker, die der Spieler sehen darf (für die Vorschläge mit Tab). */
    synchronized List<String> visibleNames(UUID viewer) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Marker m : markers.values()) if (canSee(m, viewer)) names.add(m.name);
        return List.copyOf(names);
    }

    /** Eigener Marker mit dieser id oder null (z. B. für den Todespunkt). */
    synchronized Marker ownMarker(UUID owner, String id) {
        Marker m = id == null ? null : markers.get(id);
        return m != null && m.owner.equals(owner) ? m : null;
    }

    synchronized int countOwnedBy(UUID owner) {
        return (int) markers.values().stream().filter(m -> m.owner.equals(owner)).count();
    }

    // ------------------------------------------------------------------ Abfragen

    /** Öffentliche Marker für BlueMap. */
    synchronized List<Marker> publicMarkers() {
        return markers.values().stream().filter(Marker::isPublic).toList();
    }

    synchronized JsonArray toApiJson(UUID viewer, boolean admin) {
        JsonArray arr = new JsonArray();
        for (Marker m : markers.values()) {
            if (!canSee(m, viewer)) continue;
            arr.add(apiJson(m, viewer, admin));
        }
        return arr;
    }

    synchronized JsonArray exportOwn(UUID owner) {
        JsonArray arr = new JsonArray();
        for (Marker m : markers.values()) if (m.owner.equals(owner)) arr.add(toJson(m));
        return arr;
    }

    // ------------------------------------------------------------------ Hilfsmethoden

    private boolean canSee(Marker m, UUID viewer) {
        if (m.isPublic() || m.owner.equals(viewer)) return true;
        return switch (m.visibility) {
            case "team" -> m.teamId != null && access.isTeamMember(viewer, m.teamId);
            case "land" -> m.landId != null && access.isLandOwner(viewer, m.landId);
            default -> false;
        };
    }

    /** Bearbeiten: der Ersteller. Admins dürfen nur moderieren, was andere sehen können (nicht private Marker). */
    private void requireEdit(Marker m, UUID actor, boolean admin) {
        if (!m.owner.equals(actor) && !(admin && !m.visibility.equals("private"))) {
            throw PlannerException.forbidden("Das ist nicht dein Marker.");
        }
    }

    private Marker requireVisible(UUID viewer, String id) {
        Marker m = id == null ? null : markers.get(id);
        if (m == null || !canSee(m, viewer)) throw PlannerException.notFound("Diesen Marker gibt es nicht (mehr).");
        return m;
    }

    /** Gleicher Name (ohne Groß/klein) vom selben Spieler fast an derselben Stelle = doppelt. */
    private void requireNoDuplicate(Marker m) {
        for (Marker other : markers.values()) {
            if (other.id.equals(m.id) || !other.owner.equals(m.owner) || !other.dimension.equals(m.dimension)) continue;
            if (other.name.equalsIgnoreCase(m.name) && Math.abs(other.x - m.x) <= 2 && Math.abs(other.z - m.z) <= 2) {
                throw PlannerException.conflict("Den Marker »" + m.name + "« hast du an dieser Stelle schon.");
            }
        }
    }

    private Marker parse(String id, UUID owner, UUID actor, long createdAt, long updatedAt, JsonObject d, Marker old) {
        String name = Validate.requiredText(str(d, "name", old == null ? null : old.name), 1, 48, "Name");
        String type = Validate.oneOf(str(d, "type", old == null ? "other" : old.type), types, "Typ");
        String dimension = str(d, "dimension", old == null ? "minecraft:overworld" : old.dimension);
        if (!knownDimensions.isEmpty() && !knownDimensions.contains(dimension)) throw PlannerException.badRequest("Unbekannte Dimension.");
        int x = d.has("x") ? Validate.coordinate(num(d, "x"), "X") : require(old, "X").x;
        int z = d.has("z") ? Validate.coordinate(num(d, "z"), "Z") : require(old, "Z").z;
        Integer y = d.has("y") ? (d.get("y").isJsonNull() || d.get("y").getAsString().isBlank() ? null : Validate.height(num(d, "y")))
                : old == null ? null : old.y;
        String notes = Validate.text(str(d, "notes", old == null ? "" : old.notes), 500, "Notizen");
        String color = str(d, "color", old == null ? null : old.color);
        color = color == null || color.isBlank() ? null : Validate.color(color);
        String symbol = str(d, "symbol", old == null ? null : old.symbol);
        symbol = symbol == null || symbol.isBlank() ? null : Validate.oneOf(symbol, SYMBOLS, "Symbol");

        // Ältere Webseiten schicken "public": true/false statt "visibility"
        String visibility = d.has("visibility") ? str(d, "visibility", null)
                : d.has("public") ? (d.get("public").getAsBoolean() ? "public" : "private")
                : old == null ? "public" : old.visibility;
        visibility = Validate.oneOf(visibility, VISIBILITY, "Sichtbarkeit");
        String landId = str(d, "landId", old == null ? null : old.landId);
        if (landId != null && landId.isBlank()) landId = null;
        if (landId != null && (old == null || !landId.equals(old.landId)) && !access.canLinkLand(actor, landId)) {
            throw PlannerException.forbidden("Verknüpfen darfst du nur eigene Länder oder Länder deines Teams.");
        }
        String teamId = str(d, "teamId", old == null ? null : old.teamId);
        if (teamId != null && teamId.isBlank()) teamId = null;
        if (teamId != null && (old == null || !teamId.equals(old.teamId)) && !access.isTeamMember(owner, teamId)) {
            throw PlannerException.forbidden("Team-Marker kann nur anlegen, wer im Team ist.");
        }
        if (visibility.equals("team") && teamId == null) throw PlannerException.badRequest("Für \"Mein Team\" bitte ein Team auswählen.");
        if (visibility.equals("land") && landId == null) throw PlannerException.badRequest("Für \"Besitzer des Landes\" bitte ein Land verknüpfen.");
        return new Marker(id, name, type, dimension, x, y, z, notes, owner, visibility, teamId, landId, color, symbol, createdAt, updatedAt);
    }

    private static Marker require(Marker old, String label) {
        if (old == null) throw PlannerException.badRequest("Koordinate " + label + " fehlt.");
        return old;
    }

    private static Marker with(Marker m, String visibility, String teamId, String landId) {
        return new Marker(m.id, m.name, m.type, m.dimension, m.x, m.y, m.z, m.notes, m.owner, visibility, teamId, landId,
                m.color, m.symbol, m.createdAt, System.currentTimeMillis());
    }

    private JsonObject apiJson(Marker m, UUID viewer, boolean admin) {
        JsonObject o = toJson(m);
        o.addProperty("ownerName", players.name(m.owner));
        o.addProperty("typeLabel", types.getOrDefault(m.type, m.type));
        o.addProperty("public", m.isPublic()); // für ältere Webseiten
        boolean mine = m.owner.equals(viewer);
        o.addProperty("mine", mine);
        o.addProperty("canEdit", mine || (admin && !m.visibility.equals("private")));
        return o;
    }

    private static String str(JsonObject d, String key, String fallback) {
        if (!d.has(key)) return fallback;
        try {
            return d.get(key).isJsonNull() ? null : d.get(key).getAsString();
        } catch (Exception e) {
            throw PlannerException.badRequest("Feld »" + key + "« ist ungültig.");
        }
    }

    private static double num(JsonObject d, String key) {
        try {
            return d.get(key).getAsDouble();
        } catch (Exception e) {
            throw PlannerException.badRequest("Koordinate " + key.toUpperCase(Locale.ROOT) + " ist ungültig.");
        }
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------ Speichern

    private static JsonObject toJson(Marker m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id);
        o.addProperty("name", m.name);
        o.addProperty("type", m.type);
        o.addProperty("dimension", m.dimension);
        o.addProperty("x", m.x);
        if (m.y != null) o.addProperty("y", m.y);
        o.addProperty("z", m.z);
        o.addProperty("notes", m.notes);
        o.addProperty("owner", m.owner.toString());
        o.addProperty("visibility", m.visibility);
        if (m.teamId != null) o.addProperty("teamId", m.teamId);
        if (m.landId != null) o.addProperty("landId", m.landId);
        if (m.color != null) o.addProperty("color", m.color);
        if (m.symbol != null) o.addProperty("symbol", m.symbol);
        o.addProperty("createdAt", m.createdAt);
        o.addProperty("updatedAt", m.updatedAt);
        return o;
    }

    private static Marker fromJson(JsonObject o) {
        String visibility = o.has("visibility") ? o.get("visibility").getAsString()
                : o.has("public") && o.get("public").getAsBoolean() ? "public" : "private"; // Schema 1
        return new Marker(o.get("id").getAsString(), o.get("name").getAsString(), o.get("type").getAsString(),
                o.get("dimension").getAsString(), o.get("x").getAsInt(),
                o.has("y") && !o.get("y").isJsonNull() ? o.get("y").getAsInt() : null, o.get("z").getAsInt(),
                o.has("notes") ? o.get("notes").getAsString() : "", UUID.fromString(o.get("owner").getAsString()), visibility,
                o.has("teamId") ? o.get("teamId").getAsString() : null, o.has("landId") ? o.get("landId").getAsString() : null,
                o.has("color") ? o.get("color").getAsString() : null, o.has("symbol") ? o.get("symbol").getAsString() : null,
                o.get("createdAt").getAsLong(), o.has("updatedAt") ? o.get("updatedAt").getAsLong() : o.get("createdAt").getAsLong());
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        markers.values().forEach(m -> arr.add(toJson(m)));
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA);
        root.add("markers", arr);
        return root;
    }
}
