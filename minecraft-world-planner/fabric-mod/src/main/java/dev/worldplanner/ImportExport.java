package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Export und Import der eigenen Planungsdaten (Länder, Marker, Projekte, Favoriten).
 *
 * Format (versioniert):
 *   {"format": "world-planner-export", "version": 1, "exportedAt": ..., "exportedBy": {...},
 *    "lands": [...], "markers": [...], "projects": [...], "favorites": {...}}
 *
 * Import-Regeln:
 * - Es wird nie etwas überschrieben oder gelöscht – es wird nur NEU angelegt, und zwar als der importierende Spieler.
 * - Erst kommt eine Vorschau (apply=false): was würde angelegt, was übersprungen und warum.
 * - Erst mit apply=true wird importiert. Jedes Element einzeln; was nicht passt, wird übersprungen (kein Teil-Chaos).
 * - Verknüpfungen zu Teams und Ländern werden beim Import gelöst (sie gehören evtl. jemand anderem).
 *   Team-/Land-Sichtbarkeit wird dann "privat", damit nichts ungewollt sichtbar wird.
 * Ein vollständiges Server-Backup ist etwas anderes: Das sind die Dateien in world/world-planner/ (siehe BACKUP.md).
 */
final class ImportExport {
    static final String FORMAT = "world-planner-export";
    static final int VERSION = 1;
    private static final int MAX_ITEMS = 500;

    private final PlannerServices s;

    ImportExport(PlannerServices services) {
        this.s = services;
    }

    JsonObject export(UUID user) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("version", VERSION);
        o.addProperty("exportedAt", System.currentTimeMillis());
        JsonObject by = new JsonObject();
        by.addProperty("uuid", user.toString());
        by.addProperty("name", s.players().name(user));
        o.add("exportedBy", by);
        o.add("lands", s.lands().exportOwn(user));
        o.add("markers", s.markers().exportOwn(user));
        o.add("projects", s.projects().exportOwn(user));
        o.add("favorites", s.prefs().get(user).get("favorites"));
        return o;
    }

    /** apply=false: nur Vorschau. apply=true: wirklich anlegen. Antwort: Zusammenfassung pro Bereich. */
    JsonObject importData(UUID user, boolean admin, JsonElement raw, boolean apply) {
        JsonObject data = normalize(raw);
        JsonObject result = new JsonObject();
        result.addProperty("applied", apply);
        result.add("lands", importLands(user, admin, data.getAsJsonArray("lands"), apply));
        result.add("markers", importMarkers(user, admin, data.getAsJsonArray("markers"), apply));
        result.add("projects", importProjects(user, admin, data.getAsJsonArray("projects"), apply));
        return result;
    }

    /** Akzeptiert das aktuelle Format und die alte Marker-Datei der Webseite ({"markers": [...]} oder eine Liste). */
    private static JsonObject normalize(JsonElement raw) {
        JsonObject out = new JsonObject();
        out.add("lands", new JsonArray());
        out.add("markers", new JsonArray());
        out.add("projects", new JsonArray());
        if (raw == null || raw.isJsonNull()) throw PlannerException.badRequest("Keine Daten zum Importieren.");
        if (raw.isJsonArray()) {
            out.add("markers", raw.getAsJsonArray());
            return checkSize(out);
        }
        if (!raw.isJsonObject()) throw PlannerException.badRequest("Die Datei ist keine gültige Export-Datei.");
        JsonObject o = raw.getAsJsonObject();
        if (o.has("format")) {
            if (!FORMAT.equals(o.get("format").getAsString())) throw PlannerException.badRequest("Unbekanntes Dateiformat.");
            int version = o.has("version") ? o.get("version").getAsInt() : 0;
            if (version != VERSION) throw PlannerException.badRequest("Diese Export-Version (" + version + ") kenne ich nicht.");
        } else if (!o.has("markers")) {
            throw PlannerException.badRequest("Die Datei ist keine gültige Export-Datei.");
        }
        for (String key : new String[]{"lands", "markers", "projects"}) {
            if (o.has(key)) {
                if (!o.get(key).isJsonArray()) throw PlannerException.badRequest("»" + key + "« muss eine Liste sein.");
                out.add(key, o.getAsJsonArray(key));
            }
        }
        return checkSize(out);
    }

    private static JsonObject checkSize(JsonObject data) {
        for (String key : new String[]{"lands", "markers", "projects"}) {
            if (data.getAsJsonArray(key).size() > MAX_ITEMS) throw PlannerException.badRequest("Zu viele Einträge in »" + key + "« (max. " + MAX_ITEMS + ").");
        }
        return data;
    }

    private JsonObject importLands(UUID user, boolean admin, JsonArray items, boolean apply) {
        Summary sum = new Summary();
        Set<String> seenNames = new HashSet<>();
        int plannedChunks = 0;
        for (JsonElement el : items) {
            String label = "?";
            try {
                JsonObject l = el.getAsJsonObject();
                label = l.get("name").getAsString();
                String dim = l.get("dimension").getAsString();
                long[] chunks = flatChunks(l.getAsJsonArray("chunks"));
                if (!seenNames.add(label.toLowerCase(Locale.ROOT))) {
                    sum.skip(label, "steht doppelt in der Datei");
                    continue;
                }
                if (s.lands().findIdByName(label).isPresent()) {
                    sum.skip(label, "ein Land mit diesem Namen gibt es schon");
                    continue;
                }
                JsonObject check = s.lands().check(user, admin, null, dim, chunks);
                String problem = landProblem(check, plannedChunks, admin);
                if (problem != null) {
                    sum.skip(label, problem);
                    continue;
                }
                if (apply) {
                    String id = s.lands().create(user, admin, label, dim, chunks, str(l, "color"), str(l, "description"));
                    if (l.has("notes")) s.lands().edit(user, admin, id, null, null, null, l.get("notes").getAsString());
                } else {
                    plannedChunks += chunks.length;
                }
                sum.create(label);
            } catch (PlannerException e) {
                sum.skip(label, e.getMessage());
            } catch (Exception e) {
                sum.skip(label, "Eintrag ist unvollständig oder kaputt");
            }
        }
        return sum.json();
    }

    private String landProblem(JsonObject check, int plannedChunks, boolean admin) {
        if (check.getAsJsonArray("conflicts").size() > 0) return "Chunks gehören schon anderen Ländern (keine Überschneidungen erlaubt)";
        if (check.getAsJsonArray("undiscovered").size() > 0) return "enthält noch unentdeckte Chunks";
        if (!check.get("connected").getAsBoolean()) return "Chunks hängen nicht zusammen";
        if (check.get("tooMany").getAsBoolean()) return "zu viele Chunks auf einmal";
        int after = check.get("chunksAfter").getAsInt() + plannedChunks;
        if (!admin && after > check.get("maxChunks").getAsInt()) return "du hättest dann mehr als " + check.get("maxChunks").getAsInt() + " Chunks";
        return null;
    }

    private JsonObject importMarkers(UUID user, boolean admin, JsonArray items, boolean apply) {
        Summary sum = new Summary();
        for (JsonElement el : items) {
            String label = "?";
            try {
                JsonObject m = el.getAsJsonObject().deepCopy();
                label = m.has("name") ? m.get("name").getAsString() : "?";
                // Verknüpfungen lösen – Team und Land gehören evtl. jemand anderem
                String vis = m.has("visibility") ? m.get("visibility").getAsString()
                        : m.has("public") && !m.get("public").getAsBoolean() ? "private" : "public";
                if (vis.equals("team") || vis.equals("land")) vis = "private";
                m.addProperty("visibility", vis);
                m.remove("teamId");
                m.remove("landId");
                m.remove("public");
                // Alte Web-Exporte hatten Typen wie "City" – auf die neuen Schlüssel abbilden
                if (m.has("type")) {
                    String t = m.get("type").getAsString().toLowerCase(Locale.ROOT);
                    m.addProperty("type", s.markers().types().containsKey(t) ? t : "other");
                }
                String problem = s.markers().problem(user, admin, m);
                if (problem != null) {
                    sum.skip(label, problem);
                    continue;
                }
                if (apply) s.markers().create(user, admin, m);
                sum.create(label);
            } catch (PlannerException e) {
                sum.skip(label, e.getMessage());
            } catch (Exception e) {
                sum.skip(label, "Eintrag ist unvollständig oder kaputt");
            }
        }
        return sum.json();
    }

    private JsonObject importProjects(UUID user, boolean admin, JsonArray items, boolean apply) {
        Summary sum = new Summary();
        for (JsonElement el : items) {
            String label = "?";
            try {
                JsonObject p = el.getAsJsonObject().deepCopy();
                label = p.has("name") ? p.get("name").getAsString() : "?";
                JsonObject clean = new JsonObject();
                for (String key : new String[]{"name", "description", "notes", "status", "priority", "startDate", "dueDate", "location", "tasks"}) {
                    if (p.has(key)) clean.add(key, p.get(key));
                }
                String vis = p.has("visibility") ? p.get("visibility").getAsString() : "public";
                clean.addProperty("visibility", vis.equals("team") ? "private" : vis);
                String problem = s.projects().problem(user, admin, clean);
                if (problem != null) {
                    sum.skip(label, problem);
                    continue;
                }
                if (apply) s.projects().create(user, admin, clean);
                sum.create(label);
            } catch (PlannerException e) {
                sum.skip(label, e.getMessage());
            } catch (Exception e) {
                sum.skip(label, "Eintrag ist unvollständig oder kaputt");
            }
        }
        return sum.json();
    }

    private static long[] flatChunks(JsonArray arr) {
        if (arr.size() % 2 != 0) throw PlannerException.badRequest("Chunk-Liste ist ungültig.");
        long[] out = new long[arr.size() / 2];
        for (int i = 0; i < out.length; i++) out[i] = Chunks.key(arr.get(i * 2).getAsInt(), arr.get(i * 2 + 1).getAsInt());
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** Zählt, was angelegt würde / wurde, und was mit welchem Grund übersprungen wird. */
    private static final class Summary {
        private final JsonArray created = new JsonArray();
        private final JsonArray skipped = new JsonArray();

        void create(String name) {
            created.add(name);
        }

        void skip(String name, String reason) {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("reason", reason);
            skipped.add(o);
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("create", created.size());
            o.add("created", created);
            o.add("skipped", skipped);
            return o;
        }
    }
}
