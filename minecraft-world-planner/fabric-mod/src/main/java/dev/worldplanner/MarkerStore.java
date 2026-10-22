package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bau-Planer-Marker (früher nur im Browser gespeichert, jetzt auf dem Server).
 * "public" sehen alle (auch auf BlueMap), "private" nur der Ersteller.
 */
final class MarkerStore extends JsonStore {
    static final Set<String> TYPES = Set.of("city", "base", "road", "farm", "resource", "portal", "other");
    private static final int WORLD_LIMIT = 30_000_000;

    record Marker(String id, String name, String type, String dimension, int x, int y, int z, String notes,
                  UUID owner, boolean isPublic, long createdAt, long updatedAt) {}

    private final Map<String, Marker> markers = new LinkedHashMap<>();
    private final PlayerDirectory players;
    private final PlannerConfig config;
    private volatile Set<String> knownDimensions = Set.of();

    MarkerStore(Path path, Logger logger, PlayerDirectory players, PlannerConfig config) {
        super(path);
        this.players = players;
        this.config = config;
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("markers")) {
            for (JsonElement el : json.getAsJsonObject().getAsJsonArray("markers")) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    Marker m = new Marker(o.get("id").getAsString(), o.get("name").getAsString(), o.get("type").getAsString(),
                            o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                            o.get("notes").getAsString(), UUID.fromString(o.get("owner").getAsString()),
                            o.get("public").getAsBoolean(), o.get("createdAt").getAsLong(), o.get("updatedAt").getAsLong());
                    markers.put(m.id, m);
                } catch (Exception e) {
                    logger.warn("[World Planner] Ein Marker in {} ist kaputt und wird übersprungen.", path);
                }
            }
        }
    }

    void setKnownDimensions(Set<String> dimensions) {
        knownDimensions = Set.copyOf(dimensions);
    }

    synchronized String create(UUID actor, boolean admin, JsonObject data) {
        long count = markers.values().stream().filter(m -> m.owner.equals(actor)).count();
        if (!admin && count >= config.maxMarkersPerPlayer()) {
            throw PlannerException.badRequest("Du hast schon " + count + " Marker (Maximum " + config.maxMarkersPerPlayer() + ").");
        }
        long now = System.currentTimeMillis();
        Marker m = parse(UUID.randomUUID().toString(), actor, now, now, data);
        markers.put(m.id, m);
        changed();
        return m.id;
    }

    synchronized void update(UUID actor, boolean admin, String id, JsonObject data) {
        Marker old = require(id);
        if (!old.owner.equals(actor) && !admin) throw PlannerException.forbidden("Das ist nicht dein Marker.");
        markers.put(id, parse(id, old.owner, old.createdAt, System.currentTimeMillis(), data));
        changed();
    }

    synchronized void delete(UUID actor, boolean admin, String id) {
        Marker old = require(id);
        if (!old.owner.equals(actor) && !admin) throw PlannerException.forbidden("Das ist nicht dein Marker.");
        markers.remove(id);
        changed();
    }

    /** Öffentliche Marker für BlueMap. */
    synchronized List<Marker> publicMarkers() {
        return markers.values().stream().filter(Marker::isPublic).toList();
    }

    synchronized JsonArray toApiJson(UUID viewer, boolean admin) {
        JsonArray arr = new JsonArray();
        for (Marker m : markers.values()) {
            boolean mine = m.owner.equals(viewer);
            if (!m.isPublic && !mine) continue;
            JsonObject o = toJson(m);
            o.addProperty("ownerName", players.name(m.owner));
            o.addProperty("mine", mine);
            o.addProperty("canEdit", mine || admin);
            arr.add(o);
        }
        return arr;
    }

    private Marker parse(String id, UUID owner, long createdAt, long updatedAt, JsonObject d) {
        String name = text(d, "name", 1, 48, "Name");
        String type = d.has("type") ? d.get("type").getAsString() : "other";
        if (!TYPES.contains(type)) throw PlannerException.badRequest("Unbekannter Marker-Typ.");
        String dimension = d.has("dimension") ? d.get("dimension").getAsString() : "minecraft:overworld";
        if (!knownDimensions.isEmpty() && !knownDimensions.contains(dimension)) throw PlannerException.badRequest("Unbekannte Dimension.");
        int x = coordinate(d, "x", WORLD_LIMIT);
        int y = coordinate(d, "y", 4096);
        int z = coordinate(d, "z", WORLD_LIMIT);
        String notes = d.has("notes") ? d.get("notes").getAsString().strip() : "";
        if (notes.length() > 500) throw PlannerException.badRequest("Notizen sind zu lang (max. 500 Zeichen).");
        boolean isPublic = !d.has("public") || d.get("public").getAsBoolean();
        return new Marker(id, name, type, dimension, x, y, z, notes, owner, isPublic, createdAt, updatedAt);
    }

    private static String text(JsonObject d, String key, int min, int max, String label) {
        String value = d.has(key) ? d.get(key).getAsString().strip() : "";
        if (value.length() < min || value.length() > max) {
            throw PlannerException.badRequest(label + " muss " + min + "–" + max + " Zeichen lang sein.");
        }
        return value;
    }

    private static int coordinate(JsonObject d, String key, int limit) {
        try {
            double value = d.get(key).getAsDouble();
            if (!Double.isFinite(value) || Math.abs(value) > limit) throw new IllegalArgumentException();
            return (int) Math.floor(value);
        } catch (Exception e) {
            throw PlannerException.badRequest("Koordinate " + key.toUpperCase() + " ist ungültig.");
        }
    }

    private Marker require(String id) {
        Marker m = markers.get(id);
        if (m == null) throw PlannerException.notFound("Diesen Marker gibt es nicht (mehr).");
        return m;
    }

    private static JsonObject toJson(Marker m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id);
        o.addProperty("name", m.name);
        o.addProperty("type", m.type);
        o.addProperty("dimension", m.dimension);
        o.addProperty("x", m.x);
        o.addProperty("y", m.y);
        o.addProperty("z", m.z);
        o.addProperty("notes", m.notes);
        o.addProperty("owner", m.owner.toString());
        o.addProperty("public", m.isPublic);
        o.addProperty("createdAt", m.createdAt);
        o.addProperty("updatedAt", m.updatedAt);
        return o;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        List<Marker> all = new ArrayList<>(markers.values());
        all.forEach(m -> arr.add(toJson(m)));
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("markers", arr);
        return root;
    }
}
