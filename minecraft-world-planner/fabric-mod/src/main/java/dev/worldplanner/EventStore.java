package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Events mit Kalender: Bauwettbewerb, Treffen, Party … mit Beginn, Dauer, optionalem Treffpunkt und Zusagen.
 *
 * Sichtbarkeit: "all" = alle Spieler, "team" = nur Mitglieder des Teams.
 * Anlegen: jeder (Team-Events nur Mitglieder ab Rolle "Mitglied"), höchstens maxEventsPerPlayer anstehende pro Spieler.
 * Ändern, Absagen, Löschen: wer es angelegt hat, und Server-Admins (wenn sie das Event sehen dürfen).
 * Zusagen: jeder, der das Event sieht, nur für sich selbst – solange es nicht abgesagt und nicht vorbei ist.
 * Erinnerungen im Spiel kommen über Notices/GameNotifier (wie alle Nachrichten: mit "/planer nachrichten aus" abschaltbar).
 *
 * Sperren: Events fragen Länder (Treffpunkt) und Teams (Sichtbarkeit) – wie Projekte. Niemand fragt Events.
 */
final class EventStore extends JsonStore {
    static final Map<String, String> KINDS = ordered("build", "Bauwettbewerb", "meet", "Treffen", "party", "Party", "market", "Markt",
            "together", "Gemeinsam bauen", "other", "Sonstiges");
    static final Map<String, String> VISIBILITY = ordered("all", "Alle Spieler", "team", "Nur Team");
    static final Map<String, String> ANSWERS = ordered("yes", "Komme", "maybe", "Vielleicht", "no", "Komme nicht");
    static final int MIN_MINUTES = 15, MAX_MINUTES = 24 * 60;
    static final long MAX_AHEAD_MS = 366L * 24 * 3600_000;
    /** So lange vor dem Beginn kommt die Erinnerung, so lange nach dem Beginn "läuft gerade". */
    static final long SOON_MS = 10 * 60_000L, JUST_STARTED_MS = 10 * 60_000L;
    /** Neue Events stehen so lange vorher bei "Zu erledigen" (zusagen oder absagen). */
    static final long ASK_AHEAD_MS = 7L * 24 * 3600_000;
    /** Eine Verschiebung wird bis zu einen Tag lang gemeldet. */
    static final long MOVED_NOTICE_MS = 24 * 3600_000L;
    private static final String DEFAULT_DIMENSION = "minecraft:overworld";
    private static final String[] WEEKDAYS = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};

    record Place(String dimension, int x, Integer y, int z) {}

    private static final class Event {
        final String id;
        final UUID organizer;
        final long created;
        String title, description = "", kind = "meet", visibility = "all", teamId, landId, cancelReason = "";
        Place place;
        long start, updated, cancelledAt, movedAt, previousStart;
        int minutes = 60;
        final LinkedHashMap<UUID, String> answers = new LinkedHashMap<>();

        Event(String id, UUID organizer, long created) {
            this.id = id;
            this.organizer = organizer;
            this.created = created;
            this.updated = created;
        }

        long end() {
            return start + minutes * 60_000L;
        }

        boolean cancelled() {
            return cancelledAt > 0;
        }

        /** Kopie zum Bearbeiten: Erst wenn alles gültig ist, ersetzt sie das Original. */
        Event copy() {
            Event c = new Event(id, organizer, created);
            c.title = title;
            c.description = description;
            c.kind = kind;
            c.visibility = visibility;
            c.teamId = teamId;
            c.landId = landId;
            c.cancelReason = cancelReason;
            c.place = place;
            c.start = start;
            c.updated = updated;
            c.cancelledAt = cancelledAt;
            c.movedAt = movedAt;
            c.previousStart = previousStart;
            c.minutes = minutes;
            c.answers.putAll(answers);
            return c;
        }
    }

    /** Was Notices und /planer events über ein Event wissen müssen. */
    record Note(String id, String title, String kind, long start, int minutes, String myAnswer, boolean organizer, boolean cancelled,
                String cancelReason, long movedAt, long previousStart, String landName, Place place, int coming) {}

    private final Map<String, Event> events = new LinkedHashMap<>();
    private final PlayerDirectory players;
    private final LandStore lands;
    private final PlannerConfig config;
    private Access access;
    private volatile Set<String> knownDimensions = Set.of();

    EventStore(Path path, Logger logger, PlayerDirectory players, LandStore lands, PlannerConfig config) {
        super(path);
        this.players = players;
        this.lands = lands;
        this.config = config;
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("events")) {
            for (JsonElement el : json.getAsJsonObject().getAsJsonArray("events")) {
                try {
                    Event e = fromJson(el.getAsJsonObject());
                    events.put(e.id, e);
                } catch (Exception ex) {
                    logger.warn("[World Planner] Ein Event in {} ist kaputt und wird übersprungen: {}", path, ex.getMessage());
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

    // ------------------------------------------------------------------ Ändern

    synchronized String create(UUID actor, boolean admin, JsonObject data, long now) {
        long upcoming = events.values().stream().filter(e -> e.organizer.equals(actor) && !e.cancelled() && e.end() > now).count();
        if (!admin && upcoming >= config.maxEventsPerPlayer()) {
            throw PlannerException.badRequest("Du hast schon " + upcoming + " anstehende Events (Maximum " + config.maxEventsPerPlayer() + ").");
        }
        Event e = new Event(UUID.randomUUID().toString(), actor, now);
        apply(e, actor, data, true, now);
        e.answers.put(actor, "yes"); // wer einlädt, kommt selbst (kann es danach ändern)
        events.put(e.id, e);
        changed();
        return e.id;
    }

    synchronized void update(UUID actor, boolean admin, String id, JsonObject data, long now) {
        Event e = requireOpen(requireVisible(actor, id), now);
        requireOrganizer(e, actor, admin);
        Event edited = e.copy();
        apply(edited, actor, data, false, now);
        // Wer schon geantwortet hat, soll eine Verschiebung mitbekommen (Notices meldet sie einen Tag lang)
        if (edited.start != e.start && edited.answers.keySet().stream().anyMatch(u -> !u.equals(e.organizer))) {
            edited.movedAt = now;
            edited.previousStart = e.start;
        }
        edited.updated = now;
        events.put(id, edited);
        changed();
    }

    /** Eigene Antwort: "yes", "maybe", "no" – oder null = Antwort zurücknehmen. */
    synchronized void answer(UUID actor, String id, String answer, long now) {
        Event e = requireOpen(requireVisible(actor, id), now);
        if (answer == null) e.answers.remove(actor);
        else e.answers.put(actor, Validate.oneOf(answer, ANSWERS, "Antwort"));
        e.updated = now;
        changed();
    }

    synchronized void cancel(UUID actor, boolean admin, String id, String reason, long now) {
        Event e = requireOpen(requireVisible(actor, id), now);
        requireOrganizer(e, actor, admin);
        e.cancelReason = Validate.text(reason, 200, "Grund");
        e.cancelledAt = now;
        e.updated = now;
        changed();
    }

    /** @return der Titel (für die Aktivität) */
    synchronized String delete(UUID actor, boolean admin, String id) {
        Event e = requireVisible(actor, id);
        requireOrganizer(e, actor, admin);
        events.remove(id);
        changed();
        return e.title;
    }

    /** Wird ein Land gelöscht, bleibt der Treffpunkt als Koordinate stehen (die Mitte des Landes) – nur der Name fällt weg. */
    synchronized void detachLand(String landId) {
        for (Event e : events.values()) {
            if (landId.equals(e.landId)) {
                e.landId = null;
                changed();
            }
        }
    }

    /** Wird ein Team aufgelöst, werden seine anstehenden Team-Events abgesagt (sie bleiben nur noch für den Veranstalter sichtbar). */
    synchronized void detachTeam(String teamId, long now) {
        for (Event e : events.values()) {
            if (!teamId.equals(e.teamId)) continue;
            e.teamId = null;
            if (!e.cancelled() && e.end() > now) {
                e.cancelledAt = now;
                e.cancelReason = "Das Team wurde aufgelöst.";
            }
            changed();
        }
    }

    // ------------------------------------------------------------------ Abfragen

    synchronized String titleOf(UUID viewer, String id) {
        Event e = events.get(id);
        return e != null && canView(e, viewer) ? e.title : null;
    }

    /** Für die Aktivität: "all" oder "team:<id>". */
    synchronized String audienceOf(String id) {
        Event e = events.get(id);
        if (e == null) return null;
        return e.visibility.equals("team") ? (e.teamId == null ? null : "team:" + e.teamId) : "all";
    }

    /** Treffpunkt als Ziel für den Wegweiser, oder null. */
    synchronized Waypoints.Target target(UUID viewer, String id) {
        Event e = requireVisible(viewer, id);
        Place p = placeOf(e);
        if (p == null) return null;
        return new Waypoints.Target(e.title, "📅", p.dimension(), p.x() + 0.5, p.y(), p.z() + 0.5);
    }

    synchronized JsonArray listJson(UUID viewer, boolean admin, long now) {
        JsonArray arr = new JsonArray();
        events.values().stream().filter(e -> canView(e, viewer)).sorted(Comparator.comparingLong((Event e) -> e.start))
                .forEach(e -> arr.add(json(e, viewer, admin, now)));
        return arr;
    }

    synchronized JsonObject eventJson(String id, UUID viewer, boolean admin, long now) {
        return json(requireVisible(viewer, id), viewer, admin, now);
    }

    /** Alle Events, die der Spieler sieht und die noch nicht lange vorbei sind (für Hinweise und /planer events). */
    synchronized List<Note> notesFor(UUID viewer, long now) {
        List<Note> out = new ArrayList<>();
        for (Event e : events.values()) {
            if (!canView(e, viewer) || e.end() + JUST_STARTED_MS < now) continue;
            out.add(note(e, viewer));
        }
        out.sort(Comparator.comparingLong(Note::start));
        return out;
    }

    /** Events, die im Zeitraum [from, to) begonnen haben (für den Wochen-Rückblick), ohne abgesagte. */
    synchronized List<Note> startedBetween(UUID viewer, long from, long to) {
        List<Note> out = new ArrayList<>();
        for (Event e : events.values()) {
            if (canView(e, viewer) && !e.cancelled() && e.start >= from && e.start < to) out.add(note(e, viewer));
        }
        out.sort(Comparator.comparingLong(Note::start));
        return out;
    }

    // ------------------------------------------------------------------ Zeit als Text (Chat)

    /** "Sa 02.11. 20:00" in der Zeitzone des Servers. */
    static String when(long ms, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(zone);
        return String.format("%s %02d.%02d. %02d:%02d", WEEKDAYS[t.getDayOfWeek().getValue() - 1], t.getDayOfMonth(), t.getMonthValue(),
                t.getHour(), t.getMinute());
    }

    /** "in 7 Minuten", "in 1 Minute", "gleich". */
    static String inMinutes(long ms) {
        long min = (ms + 59_999) / 60_000;
        if (min <= 0) return "gleich";
        return "in " + min + (min == 1 ? " Minute" : " Minuten");
    }

    // ------------------------------------------------------------------ Hilfsmethoden

    private Note note(Event e, UUID viewer) {
        String landName = e.landId == null ? null : lands.nameOf(e.landId);
        int coming = (int) e.answers.values().stream().filter("yes"::equals).count();
        return new Note(e.id, e.title, e.kind, e.start, e.minutes, e.answers.get(viewer), e.organizer.equals(viewer), e.cancelled(),
                e.cancelReason, e.movedAt, e.previousStart, landName, placeOf(e), coming);
    }

    private boolean canView(Event e, UUID viewer) {
        if (e.organizer.equals(viewer)) return true;
        if (!e.visibility.equals("team")) return true;
        return e.teamId != null && access.isTeamMember(viewer, e.teamId);
    }

    private Event requireVisible(UUID viewer, String id) {
        Event e = id == null ? null : events.get(id);
        if (e == null || !canView(e, viewer)) throw PlannerException.notFound("Dieses Event gibt es nicht (mehr).");
        return e;
    }

    private static Event requireOpen(Event e, long now) {
        if (e.cancelled()) throw PlannerException.conflict("Das Event ist abgesagt.");
        if (e.end() <= now) throw PlannerException.conflict("Das Event ist schon vorbei.");
        return e;
    }

    private static void requireOrganizer(Event e, UUID actor, boolean admin) {
        if (!e.organizer.equals(actor) && !admin) throw PlannerException.forbidden("Das darf nur, wer das Event angelegt hat.");
    }

    /** Treffpunkt: das Land (seine Mitte) oder die gespeicherte Koordinate. */
    private Place placeOf(Event e) {
        if (e.landId != null) {
            try {
                LandStore.Shape s = lands.shapeById(e.landId);
                return new Place(s.dimension(), s.centerX(), null, s.centerZ());
            } catch (PlannerException gone) {
                // Land gerade gelöscht – dann gilt die gespeicherte Koordinate
            }
        }
        return e.place;
    }

    private void apply(Event e, UUID actor, JsonObject d, boolean creating, long now) {
        if (creating || d.has("title")) e.title = Validate.name(str(d, "title"), 2, 60, "Titel");
        if (d.has("description")) e.description = Validate.text(str(d, "description"), 1000, "Beschreibung");
        if (d.has("kind")) e.kind = Validate.oneOf(str(d, "kind"), KINDS, "Art");
        if (creating || d.has("start")) {
            long raw = (long) num(d, "start"), start = raw - raw % 60_000; // auf die Minute
            // Gleicher Beginn beim Bearbeiten (z. B. nur die Beschreibung, auch während das Event läuft): nichts zu prüfen
            if (creating || start != e.start) {
                if (raw < now + 60_000) throw PlannerException.badRequest("Der Beginn muss in der Zukunft liegen.");
                if (raw > now + MAX_AHEAD_MS) throw PlannerException.badRequest("Höchstens ein Jahr im Voraus.");
                e.start = start;
            }
        }
        if (d.has("minutes")) e.minutes = Validate.range((long) num(d, "minutes"), MIN_MINUTES, MAX_MINUTES, "Dauer (Minuten)");
        if (d.has("teamId")) {
            String teamId = str(d, "teamId");
            if (teamId == null || teamId.isEmpty()) {
                e.teamId = null;
            } else if (!teamId.equals(e.teamId)) {
                if (!access.hasTeamRole(actor, teamId, TeamStore.Role.MEMBER)) {
                    throw PlannerException.forbidden("Team-Events kann nur anlegen, wer in dem Team mindestens Mitglied ist.");
                }
                e.teamId = teamId;
            }
        }
        if (d.has("visibility")) e.visibility = Validate.oneOf(str(d, "visibility"), VISIBILITY, "Sichtbarkeit");
        if (e.visibility.equals("team") && e.teamId == null) throw PlannerException.badRequest("\"Nur Team\" geht nur mit einem Team.");
        if (d.has("landId")) {
            String landId = str(d, "landId");
            if (landId == null || landId.isEmpty()) {
                e.landId = null;
            } else {
                if (!access.landExists(landId)) throw PlannerException.notFound("Dieses Land gibt es nicht (mehr).");
                LandStore.Shape s = lands.shapeById(landId);
                e.landId = landId;
                e.place = new Place(s.dimension(), s.centerX(), null, s.centerZ()); // bleibt, falls das Land später gelöscht wird
            }
        }
        if (d.has("place") && e.landId == null) {
            if (d.get("place").isJsonNull()) {
                e.place = null;
            } else {
                if (!d.get("place").isJsonObject()) throw PlannerException.badRequest("Treffpunkt ist ungültig.");
                JsonObject p = d.getAsJsonObject("place");
                String dim = p.has("dimension") ? p.get("dimension").getAsString() : DEFAULT_DIMENSION;
                if (!knownDimensions.isEmpty() && !knownDimensions.contains(dim)) throw PlannerException.badRequest("Unbekannte Dimension.");
                Integer y = p.has("y") && !p.get("y").isJsonNull() ? Validate.height(num(p, "y")) : null;
                e.place = new Place(dim, Validate.coordinate(num(p, "x"), "X"), y, Validate.coordinate(num(p, "z"), "Z"));
            }
        }
    }

    private JsonObject json(Event e, UUID viewer, boolean admin, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id);
        o.addProperty("title", e.title);
        o.addProperty("description", e.description);
        o.addProperty("kind", e.kind);
        o.addProperty("start", e.start);
        o.addProperty("end", e.end());
        o.addProperty("minutes", e.minutes);
        o.addProperty("visibility", e.visibility);
        if (e.teamId != null) o.addProperty("teamId", e.teamId);
        if (e.landId != null) {
            o.addProperty("landId", e.landId);
            String name = lands.nameOf(e.landId);
            if (name != null) o.addProperty("landName", name);
        }
        Place p = placeOf(e);
        o.add("place", p == null ? JsonNull.INSTANCE : placeJson(p));
        o.add("organizer", person(e.organizer));
        o.addProperty("mine", e.organizer.equals(viewer));
        o.addProperty("canEdit", (e.organizer.equals(viewer) || admin) && !e.cancelled() && e.end() > now);
        o.addProperty("canDelete", e.organizer.equals(viewer) || admin);
        String mine = e.answers.get(viewer);
        if (mine != null) o.addProperty("myAnswer", mine);
        JsonObject counts = new JsonObject();
        JsonArray people = new JsonArray();
        for (String a : ANSWERS.keySet()) counts.addProperty(a, 0);
        e.answers.forEach((u, a) -> {
            counts.addProperty(a, counts.get(a).getAsInt() + 1);
            JsonObject x = person(u);
            x.addProperty("answer", a);
            people.add(x);
        });
        o.add("counts", counts);
        o.add("answers", people);
        o.addProperty("state", e.cancelled() ? "cancelled" : now < e.start ? "upcoming" : now < e.end() ? "running" : "past");
        if (e.cancelled()) {
            o.addProperty("cancelledAt", e.cancelledAt);
            o.addProperty("cancelReason", e.cancelReason);
        }
        if (e.movedAt > 0) {
            o.addProperty("movedAt", e.movedAt);
            o.addProperty("previousStart", e.previousStart);
        }
        o.addProperty("created", e.created);
        o.addProperty("updated", e.updated);
        return o;
    }

    private JsonObject person(UUID u) {
        JsonObject x = new JsonObject();
        x.addProperty("uuid", u.toString());
        x.addProperty("name", players.name(u));
        return x;
    }

    private static JsonObject placeJson(Place p) {
        JsonObject x = new JsonObject();
        x.addProperty("dimension", p.dimension());
        x.addProperty("x", p.x());
        if (p.y() != null) x.addProperty("y", p.y());
        x.addProperty("z", p.z());
        return x;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        for (Event e : events.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("organizer", e.organizer.toString());
            o.addProperty("created", e.created);
            o.addProperty("updated", e.updated);
            o.addProperty("title", e.title);
            o.addProperty("description", e.description);
            o.addProperty("kind", e.kind);
            o.addProperty("start", e.start);
            o.addProperty("minutes", e.minutes);
            o.addProperty("visibility", e.visibility);
            if (e.teamId != null) o.addProperty("teamId", e.teamId);
            if (e.landId != null) o.addProperty("landId", e.landId);
            if (e.place != null) o.add("place", placeJson(e.place));
            if (e.cancelledAt > 0) {
                o.addProperty("cancelledAt", e.cancelledAt);
                o.addProperty("cancelReason", e.cancelReason);
            }
            if (e.movedAt > 0) {
                o.addProperty("movedAt", e.movedAt);
                o.addProperty("previousStart", e.previousStart);
            }
            JsonObject answers = new JsonObject();
            e.answers.forEach((u, a) -> answers.addProperty(u.toString(), a));
            o.add("answers", answers);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("events", arr);
        return root;
    }

    private static Event fromJson(JsonObject o) {
        Event e = new Event(o.get("id").getAsString(), UUID.fromString(o.get("organizer").getAsString()), o.get("created").getAsLong());
        e.updated = o.has("updated") ? o.get("updated").getAsLong() : e.created;
        e.title = o.get("title").getAsString();
        e.description = o.has("description") ? o.get("description").getAsString() : "";
        e.kind = o.has("kind") && KINDS.containsKey(o.get("kind").getAsString()) ? o.get("kind").getAsString() : "other";
        e.start = o.get("start").getAsLong();
        e.minutes = o.has("minutes") ? Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, o.get("minutes").getAsInt())) : 60;
        e.visibility = o.has("visibility") && VISIBILITY.containsKey(o.get("visibility").getAsString()) ? o.get("visibility").getAsString() : "all";
        e.teamId = o.has("teamId") ? o.get("teamId").getAsString() : null;
        e.landId = o.has("landId") ? o.get("landId").getAsString() : null;
        if (o.has("place") && o.get("place").isJsonObject()) {
            JsonObject p = o.getAsJsonObject("place");
            e.place = new Place(p.get("dimension").getAsString(), p.get("x").getAsInt(), p.has("y") ? p.get("y").getAsInt() : null, p.get("z").getAsInt());
        }
        e.cancelledAt = o.has("cancelledAt") ? o.get("cancelledAt").getAsLong() : 0;
        e.cancelReason = o.has("cancelReason") ? o.get("cancelReason").getAsString() : "";
        e.movedAt = o.has("movedAt") ? o.get("movedAt").getAsLong() : 0;
        e.previousStart = o.has("previousStart") ? o.get("previousStart").getAsLong() : 0;
        if (o.has("answers")) {
            for (Map.Entry<String, JsonElement> a : o.getAsJsonObject("answers").entrySet()) {
                if (ANSWERS.containsKey(a.getValue().getAsString())) e.answers.put(UUID.fromString(a.getKey()), a.getValue().getAsString());
            }
        }
        return e;
    }

    private static String str(JsonObject d, String key) {
        if (!d.has(key) || d.get(key).isJsonNull()) return null;
        if (!d.get(key).isJsonPrimitive()) throw PlannerException.badRequest("Ungültige Angabe für " + key + ".");
        return d.get(key).getAsString();
    }

    private static double num(JsonObject d, String key) {
        try {
            return d.get(key).getAsDouble();
        } catch (Exception e) {
            throw PlannerException.badRequest("Zahl fehlt oder ist ungültig: " + key);
        }
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
