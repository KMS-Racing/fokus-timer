package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Änderungs-Übersicht: nur echte, tatsächlich ausgeführte Aktionen mit Zeitstempel vom Server.
 *
 * Wer darf was sehen ("audience"):
 * - "all"          alle eingeloggten Spieler
 * - "team:<id>"    nur Mitglieder dieses Teams (z. B. Ereignisse in privaten Teams)
 * Private Dinge (private Marker, private Projekte) werden gar nicht erst protokolliert.
 * Aufbewahrung: die letzten "activityLimit" Einträge (Config, Standard 1000). Ältere fallen weg.
 */
final class ActivityLog extends JsonStore {
    record Event(String id, long at, UUID actor, String type, String text, String refType, String refId, String audience) {}

    private final Deque<Event> events = new ArrayDeque<>();
    private final int limit;

    ActivityLog(Path path, Logger logger, int limit) {
        super(path);
        this.limit = limit;
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("events")) {
            for (JsonElement el : json.getAsJsonObject().getAsJsonArray("events")) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    events.addLast(new Event(o.get("id").getAsString(), o.get("at").getAsLong(),
                            o.has("actor") ? UUID.fromString(o.get("actor").getAsString()) : null, o.get("type").getAsString(),
                            o.get("text").getAsString(), o.has("refType") ? o.get("refType").getAsString() : null,
                            o.has("refId") ? o.get("refId").getAsString() : null, o.get("audience").getAsString()));
                } catch (Exception ignored) {
                    // kaputter Eintrag
                }
            }
            while (events.size() > limit) events.removeFirst();
        }
    }

    synchronized void add(UUID actor, String type, String text, String refType, String refId, String audience) {
        events.addLast(new Event(UUID.randomUUID().toString(), System.currentTimeMillis(), actor, type, text, refType, refId, audience));
        while (events.size() > limit) events.removeFirst();
        changed();
    }

    /** Neueste zuerst, höchstens max Einträge, nur was der Betrachter sehen darf. */
    synchronized JsonArray visibleTo(Predicate<String> isMemberOfTeam, int max) {
        JsonArray arr = new JsonArray();
        for (Iterator<Event> it = events.descendingIterator(); it.hasNext() && arr.size() < max; ) {
            Event e = it.next();
            boolean allowed = e.audience.equals("all")
                    || (e.audience.startsWith("team:") && isMemberOfTeam.test(e.audience.substring(5)));
            if (!allowed) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("at", e.at);
            o.addProperty("type", e.type);
            o.addProperty("text", e.text);
            if (e.refType != null) o.addProperty("refType", e.refType);
            if (e.refId != null) o.addProperty("refId", e.refId);
            arr.add(o);
        }
        return arr;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        for (Event e : events) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("at", e.at);
            if (e.actor != null) o.addProperty("actor", e.actor.toString());
            o.addProperty("type", e.type);
            o.addProperty("text", e.text);
            if (e.refType != null) o.addProperty("refType", e.refType);
            if (e.refId != null) o.addProperty("refId", e.refId);
            o.addProperty("audience", e.audience);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("events", arr);
        return root;
    }
}
