package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bauprojekte mit Checkliste. Optional verknüpft mit einem Land und/oder einem Team.
 *
 * Sichtbarkeit: "public" = alle Spieler, "team" = Teammitglieder (+ Beteiligte), "private" = nur Ersteller und Verantwortliche.
 * Bearbeiten: Ersteller, Verantwortliche, bei Team-Projekten alle Teammitglieder ab Rolle "Mitglied".
 * Löschen: Ersteller, bei Team-Projekten Team-Admins, Server-Admins (wenn sie das Projekt sehen dürfen).
 * Fortschritt = erledigte Aufgaben / alle Aufgaben. Ohne Aufgaben gibt es keinen Fortschrittswert.
 */
final class ProjectStore extends JsonStore {
    static final Map<String, String> STATUS = ordered("planned", "Geplant", "in_progress", "In Arbeit", "paused", "Pausiert", "done", "Abgeschlossen");
    static final Map<String, String> PRIORITY = ordered("low", "Niedrig", "normal", "Normal", "high", "Hoch");
    static final Map<String, String> VISIBILITY = ordered("public", "Alle Spieler", "team", "Nur Team", "private", "Nur Beteiligte");
    private static final int MAX_TASKS = 100;
    private static final int MAX_UPDATES = 200;
    private static final int MAX_RESPONSIBLE = 10;
    private static final String DEFAULT_DIMENSION = "minecraft:overworld";

    private static final class Task {
        final String id;
        String text;
        boolean done;
        UUID doneBy;
        long doneAt;

        Task(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private record Update(String id, UUID author, String text, long createdAt) {}

    private record Location(String dimension, int x, int z) {}

    private static final class Project {
        final String id;
        String name, description = "", notes = "", status = "planned", priority = "normal", visibility = "public";
        String startDate, dueDate, landId, teamId;
        Location location;
        final LinkedHashSet<UUID> responsible = new LinkedHashSet<>();
        final List<Task> tasks = new ArrayList<>();
        final List<Update> updates = new ArrayList<>();
        final UUID createdBy;
        final long createdAt;
        long updatedAt;

        Project(String id, UUID createdBy, long createdAt) {
            this.id = id;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        /** Kopie zum Bearbeiten: Erst wenn alles gültig ist, ersetzt sie das Original (kein halbes Speichern). */
        Project copy() {
            Project c = new Project(id, createdBy, createdAt);
            c.name = name;
            c.description = description;
            c.notes = notes;
            c.status = status;
            c.priority = priority;
            c.visibility = visibility;
            c.startDate = startDate;
            c.dueDate = dueDate;
            c.landId = landId;
            c.teamId = teamId;
            c.location = location;
            c.responsible.addAll(responsible);
            c.tasks.addAll(tasks); // Aufgaben ändert apply() nicht, nur beim Anlegen kommen welche dazu
            c.updates.addAll(updates);
            c.updatedAt = updatedAt;
            return c;
        }
    }

    private final Map<String, Project> projects = new LinkedHashMap<>();
    private final PlayerDirectory players;
    private final PlannerConfig config;
    private Access access;
    private volatile Set<String> knownDimensions = Set.of();

    ProjectStore(Path path, Logger logger, PlayerDirectory players, PlannerConfig config) {
        super(path);
        this.players = players;
        this.config = config;
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("projects")) {
            for (JsonElement el : json.getAsJsonObject().getAsJsonArray("projects")) {
                try {
                    Project p = fromJson(el.getAsJsonObject());
                    projects.put(p.id, p);
                } catch (Exception e) {
                    logger.warn("[World Planner] Ein Projekt in {} ist kaputt und wird übersprungen: {}", path, e.getMessage());
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

    synchronized String create(UUID actor, boolean admin, JsonObject data) {
        long own = projects.values().stream().filter(p -> p.createdBy.equals(actor)).count();
        if (!admin && own >= config.maxProjectsPerPlayer()) {
            throw PlannerException.badRequest("Du hast schon " + own + " Projekte (Maximum " + config.maxProjectsPerPlayer() + ").");
        }
        long now = System.currentTimeMillis();
        Project p = new Project(UUID.randomUUID().toString(), actor, now);
        apply(p, actor, data, true);
        projects.put(p.id, p);
        changed();
        return p.id;
    }

    synchronized void update(UUID actor, boolean admin, String id, JsonObject data) {
        Project p = requireVisible(actor, id);
        requireEdit(p, actor, admin);
        Project edited = p.copy();
        apply(edited, actor, data, false);
        projects.put(id, edited);
        touch(edited);
    }

    synchronized void delete(UUID actor, boolean admin, String id) {
        Project p = requireVisible(actor, id);
        boolean teamAdmin = p.teamId != null && access.hasTeamRole(actor, p.teamId, TeamStore.Role.ADMIN);
        if (!p.createdBy.equals(actor) && !teamAdmin && !admin) {
            throw PlannerException.forbidden("Löschen darf nur, wer das Projekt erstellt hat (bei Team-Projekten auch Team-Admins).");
        }
        projects.remove(id);
        changed();
    }

    synchronized String addTask(UUID actor, boolean admin, String id, String text) {
        Project p = requireVisible(actor, id);
        requireEdit(p, actor, admin);
        if (p.tasks.size() >= MAX_TASKS) throw PlannerException.badRequest("Höchstens " + MAX_TASKS + " Aufgaben pro Projekt.");
        Task t = new Task(UUID.randomUUID().toString(), Validate.requiredText(text, 1, 200, "Aufgabe"));
        p.tasks.add(t);
        touch(p);
        return t.id;
    }

    synchronized void editTask(UUID actor, boolean admin, String id, String taskId, String text, Boolean done) {
        Project p = requireVisible(actor, id);
        requireEdit(p, actor, admin);
        Task t = p.tasks.stream().filter(x -> x.id.equals(taskId)).findFirst()
                .orElseThrow(() -> PlannerException.notFound("Diese Aufgabe gibt es nicht (mehr)."));
        if (text != null) t.text = Validate.requiredText(text, 1, 200, "Aufgabe");
        if (done != null && done != t.done) {
            t.done = done;
            t.doneBy = done ? actor : null;
            t.doneAt = done ? System.currentTimeMillis() : 0;
        }
        touch(p);
    }

    synchronized void removeTask(UUID actor, boolean admin, String id, String taskId) {
        Project p = requireVisible(actor, id);
        requireEdit(p, actor, admin);
        if (!p.tasks.removeIf(x -> x.id.equals(taskId))) throw PlannerException.notFound("Diese Aufgabe gibt es nicht (mehr).");
        touch(p);
    }

    /** Projekt-Update (kurze Nachricht im Verlauf). Älteste fallen nach MAX_UPDATES weg. */
    synchronized String addUpdate(UUID actor, boolean admin, String id, String text) {
        Project p = requireVisible(actor, id);
        requireEdit(p, actor, admin);
        Update u = new Update(UUID.randomUUID().toString(), actor, Validate.requiredText(text, 1, 500, "Update"), System.currentTimeMillis());
        p.updates.add(u);
        while (p.updates.size() > MAX_UPDATES) p.updates.removeFirst();
        touch(p);
        return u.id;
    }

    /** Wird ein Land gelöscht, bleibt das Projekt – nur die Verknüpfung fällt weg. */
    synchronized void detachLand(String landId) {
        for (Project p : projects.values()) {
            if (landId.equals(p.landId)) {
                p.landId = null;
                touch(p);
            }
        }
    }

    /** Wird ein Team aufgelöst, bleiben die Projekte. Nur-Team-Projekte werden zu Nur-Beteiligte-Projekten. */
    synchronized void detachTeam(String teamId) {
        for (Project p : projects.values()) {
            if (teamId.equals(p.teamId)) {
                p.teamId = null;
                if (p.visibility.equals("team")) p.visibility = "private";
                touch(p);
            }
        }
    }

    /** Probe-Prüfung (für die Import-Vorschau): null wenn ok, sonst die Fehlermeldung. Speichert nichts. */
    synchronized String problem(UUID actor, boolean admin, JsonObject data) {
        try {
            long own = projects.values().stream().filter(p -> p.createdBy.equals(actor)).count();
            if (!admin && own >= config.maxProjectsPerPlayer()) return "Maximale Anzahl Projekte erreicht.";
            apply(new Project("probe", actor, System.currentTimeMillis()), actor, data, true);
            return null;
        } catch (PlannerException e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------ Abfragen

    synchronized String nameOf(UUID viewer, String id) {
        Project p = projects.get(id);
        return p != null && canView(p, viewer) ? p.name : null;
    }

    synchronized JsonArray toApiJson(UUID viewer, boolean admin) {
        JsonArray arr = new JsonArray();
        for (Project p : projects.values()) if (canView(p, viewer)) arr.add(json(p, viewer, admin));
        return arr;
    }

    synchronized JsonObject toApiJson(UUID viewer, boolean admin, String id) {
        return json(requireVisible(viewer, id), viewer, admin);
    }

    record Overdue(String id, String name, String dueDate) {}

    /** Eigene (angelegt oder verantwortlich), nicht abgeschlossene Projekte mit Zieldatum vor "today" (JJJJ-MM-TT). */
    synchronized List<Overdue> overdueFor(UUID viewer, String today) {
        List<Overdue> out = new ArrayList<>();
        for (Project p : projects.values()) {
            boolean mine = p.createdBy.equals(viewer) || p.responsible.contains(viewer);
            if (mine && !p.status.equals("done") && p.dueDate != null && p.dueDate.compareTo(today) < 0) {
                out.add(new Overdue(p.id, p.name, p.dueDate));
            }
        }
        return out;
    }

    /** Nur Projekte, die der Spieler selbst angelegt hat (für den Export). */
    synchronized JsonArray exportOwn(UUID owner) {
        JsonArray arr = new JsonArray();
        for (Project p : projects.values()) if (p.createdBy.equals(owner)) arr.add(fullStorageJson(p));
        return arr;
    }

    // ------------------------------------------------------------------ Hilfsmethoden

    private void apply(Project p, UUID actor, JsonObject d, boolean creating) {
        if (creating || d.has("name")) p.name = Validate.name(str(d, "name"), 2, 48, "Projektname");
        if (d.has("description")) p.description = Validate.text(str(d, "description"), 1000, "Beschreibung");
        if (d.has("notes")) p.notes = Validate.text(str(d, "notes"), 2000, "Notizen");
        if (d.has("status")) p.status = Validate.oneOf(str(d, "status"), STATUS, "Status");
        if (d.has("priority")) p.priority = Validate.oneOf(str(d, "priority"), PRIORITY, "Priorität");
        if (d.has("startDate")) p.startDate = Validate.optionalDate(str(d, "startDate"), "Startdatum");
        if (d.has("dueDate")) p.dueDate = Validate.optionalDate(str(d, "dueDate"), "Zieldatum");
        if (p.startDate != null && p.dueDate != null && p.dueDate.compareTo(p.startDate) < 0) {
            throw PlannerException.badRequest("Das Zieldatum liegt vor dem Startdatum.");
        }
        if (d.has("teamId")) {
            String teamId = str(d, "teamId");
            if (teamId == null || teamId.isEmpty()) {
                p.teamId = null;
            } else if (!teamId.equals(p.teamId)) {
                if (!access.hasTeamRole(actor, teamId, TeamStore.Role.MEMBER)) {
                    throw PlannerException.forbidden("Team-Projekte kann nur anlegen, wer in dem Team mindestens Mitglied ist.");
                }
                p.teamId = teamId;
            }
        }
        if (d.has("landId")) {
            String landId = str(d, "landId");
            if (landId == null || landId.isEmpty()) {
                p.landId = null;
            } else if (!landId.equals(p.landId)) {
                boolean teamLand = p.teamId != null && p.teamId.equals(access.landTeam(landId));
                if (!access.canLinkLand(actor, landId) && !teamLand) {
                    throw PlannerException.forbidden("Verknüpfen darfst du nur eigene Länder oder Länder deines Teams.");
                }
                p.landId = landId;
            }
        }
        if (d.has("location")) {
            if (d.get("location").isJsonNull()) {
                p.location = null;
            } else {
                if (!d.get("location").isJsonObject()) throw PlannerException.badRequest("Ort ist ungültig.");
                JsonObject l = d.getAsJsonObject("location");
                String dim = l.has("dimension") ? l.get("dimension").getAsString() : DEFAULT_DIMENSION;
                if (!knownDimensions.isEmpty() && !knownDimensions.contains(dim)) throw PlannerException.badRequest("Unbekannte Dimension.");
                p.location = new Location(dim, Validate.coordinate(num(l, "x"), "X"), Validate.coordinate(num(l, "z"), "Z"));
            }
        }
        if (d.has("visibility")) p.visibility = Validate.oneOf(str(d, "visibility"), VISIBILITY, "Sichtbarkeit");
        if (p.visibility.equals("team") && p.teamId == null) {
            throw PlannerException.badRequest("\"Nur Team\" geht nur bei einem Team-Projekt.");
        }
        if (d.has("responsible")) {
            LinkedHashSet<UUID> resp = new LinkedHashSet<>();
            for (JsonElement e : array(d, "responsible")) {
                if (!e.isJsonPrimitive()) throw PlannerException.badRequest("Verantwortliche: bitte Spielernamen angeben.");
                UUID u = players.findByName(e.getAsString())
                        .orElseThrow(() -> PlannerException.notFound("Spieler »" + e.getAsString() + "« war noch nie auf dem Server."));
                if (p.teamId != null && !access.isTeamMember(u, p.teamId)) {
                    throw PlannerException.badRequest(players.name(u) + " ist nicht im Team – bei Team-Projekten können nur Mitglieder verantwortlich sein.");
                }
                resp.add(u);
            }
            if (resp.size() > MAX_RESPONSIBLE) throw PlannerException.badRequest("Höchstens " + MAX_RESPONSIBLE + " Verantwortliche.");
            p.responsible.clear();
            p.responsible.addAll(resp);
        }
        if (creating && d.has("tasks")) {
            for (JsonElement e : array(d, "tasks")) {
                if (p.tasks.size() >= MAX_TASKS) throw PlannerException.badRequest("Höchstens " + MAX_TASKS + " Aufgaben pro Projekt.");
                // Aufgaben als Text oder (beim Import) als {"text": ..., "done": true}
                boolean isObject = e.isJsonObject();
                String text = isObject ? (e.getAsJsonObject().has("text") ? e.getAsJsonObject().get("text").getAsString() : "") : e.getAsString();
                Task t = new Task(UUID.randomUUID().toString(), Validate.requiredText(text, 1, 200, "Aufgabe"));
                if (isObject && e.getAsJsonObject().has("done") && e.getAsJsonObject().get("done").getAsBoolean()) {
                    t.done = true;
                    t.doneBy = actor;
                    t.doneAt = System.currentTimeMillis();
                }
                p.tasks.add(t);
            }
        }
    }

    private boolean canView(Project p, UUID viewer) {
        if (p.visibility.equals("public")) return true;
        if (p.createdBy.equals(viewer) || p.responsible.contains(viewer)) return true;
        return p.visibility.equals("team") && p.teamId != null && access.isTeamMember(viewer, p.teamId);
    }

    private boolean canEdit(Project p, UUID actor, boolean admin) {
        if (p.createdBy.equals(actor) || p.responsible.contains(actor)) return true;
        if (p.teamId != null && access.hasTeamRole(actor, p.teamId, TeamStore.Role.MEMBER)) return true;
        return admin;
    }

    private void requireEdit(Project p, UUID actor, boolean admin) {
        if (!canEdit(p, actor, admin)) throw PlannerException.forbidden("Dieses Projekt dürfen nur Beteiligte bearbeiten.");
    }

    /** Unsichtbare Projekte gibt es für den Fragenden nicht (404) – so verrät die API nichts. */
    private Project requireVisible(UUID viewer, String id) {
        Project p = id == null ? null : projects.get(id);
        if (p == null || !canView(p, viewer)) throw PlannerException.notFound("Dieses Projekt gibt es nicht (mehr).");
        return p;
    }

    private void touch(Project p) {
        p.updatedAt = System.currentTimeMillis();
        changed();
    }

    private JsonObject json(Project p, UUID viewer, boolean admin) {
        JsonObject o = storageJson(p);
        o.add("createdBy", player(p.createdBy));
        JsonArray resp = new JsonArray();
        p.responsible.forEach(u -> resp.add(player(u)));
        o.add("responsible", resp);
        JsonArray tasks = new JsonArray();
        for (Task t : p.tasks) {
            JsonObject to = new JsonObject();
            to.addProperty("id", t.id);
            to.addProperty("text", t.text);
            to.addProperty("done", t.done);
            if (t.doneBy != null) to.add("doneBy", player(t.doneBy));
            if (t.doneAt > 0) to.addProperty("doneAt", t.doneAt);
            tasks.add(to);
        }
        o.add("tasks", tasks);
        JsonArray updates = new JsonArray();
        for (Update u : p.updates) {
            JsonObject uo = new JsonObject();
            uo.addProperty("id", u.id);
            uo.add("author", player(u.author));
            uo.addProperty("text", u.text);
            uo.addProperty("createdAt", u.createdAt);
            updates.add(uo);
        }
        o.add("updates", updates);
        long done = p.tasks.stream().filter(t -> t.done).count();
        JsonObject progress = new JsonObject();
        progress.addProperty("done", done);
        progress.addProperty("total", p.tasks.size());
        o.add("progress", progress);
        JsonObject can = new JsonObject();
        can.addProperty("edit", canEdit(p, viewer, admin));
        boolean teamAdmin = p.teamId != null && access.hasTeamRole(viewer, p.teamId, TeamStore.Role.ADMIN);
        can.addProperty("delete", p.createdBy.equals(viewer) || teamAdmin || admin);
        o.add("can", can);
        o.addProperty("mine", p.createdBy.equals(viewer) || p.responsible.contains(viewer));
        return o;
    }

    private JsonObject player(UUID u) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", u.toString());
        o.addProperty("name", players.name(u));
        return o;
    }

    private static String str(JsonObject d, String key) {
        try {
            return d.get(key).isJsonNull() ? null : d.get(key).getAsString();
        } catch (Exception e) {
            throw PlannerException.badRequest("Feld »" + key + "« ist ungültig.");
        }
    }

    private static JsonArray array(JsonObject d, String key) {
        if (!d.get(key).isJsonArray()) throw PlannerException.badRequest("Feld »" + key + "« muss eine Liste sein.");
        return d.getAsJsonArray(key);
    }

    private static double num(JsonObject d, String key) {
        try {
            return d.get(key).getAsDouble();
        } catch (Exception e) {
            throw PlannerException.badRequest("Koordinate " + key.toUpperCase() + " fehlt oder ist ungültig.");
        }
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------ Speichern

    private JsonObject storageJson(Project p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id);
        o.addProperty("name", p.name);
        o.addProperty("description", p.description);
        o.addProperty("notes", p.notes);
        o.addProperty("status", p.status);
        o.addProperty("priority", p.priority);
        o.addProperty("visibility", p.visibility);
        if (p.startDate != null) o.addProperty("startDate", p.startDate);
        if (p.dueDate != null) o.addProperty("dueDate", p.dueDate);
        if (p.landId != null) o.addProperty("landId", p.landId);
        if (p.teamId != null) o.addProperty("teamId", p.teamId);
        if (p.location != null) {
            JsonObject l = new JsonObject();
            l.addProperty("dimension", p.location.dimension);
            l.addProperty("x", p.location.x);
            l.addProperty("z", p.location.z);
            o.add("location", l);
        }
        o.addProperty("createdBy", p.createdBy.toString());
        o.addProperty("createdAt", p.createdAt);
        o.addProperty("updatedAt", p.updatedAt);
        return o;
    }

    private Project fromJson(JsonObject o) {
        Project p = new Project(o.get("id").getAsString(), UUID.fromString(o.get("createdBy").getAsString()), o.get("createdAt").getAsLong());
        p.name = o.get("name").getAsString();
        p.description = o.has("description") ? o.get("description").getAsString() : "";
        p.notes = o.has("notes") ? o.get("notes").getAsString() : "";
        p.status = o.get("status").getAsString();
        p.priority = o.get("priority").getAsString();
        p.visibility = o.get("visibility").getAsString();
        p.startDate = o.has("startDate") ? o.get("startDate").getAsString() : null;
        p.dueDate = o.has("dueDate") ? o.get("dueDate").getAsString() : null;
        p.landId = o.has("landId") ? o.get("landId").getAsString() : null;
        p.teamId = o.has("teamId") ? o.get("teamId").getAsString() : null;
        if (o.has("location")) {
            JsonObject l = o.getAsJsonObject("location");
            p.location = new Location(l.get("dimension").getAsString(), l.get("x").getAsInt(), l.get("z").getAsInt());
        }
        p.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : p.createdAt;
        if (o.has("responsible")) o.getAsJsonArray("responsible").forEach(e -> p.responsible.add(UUID.fromString(e.getAsString())));
        if (o.has("tasks")) for (JsonElement e : o.getAsJsonArray("tasks")) {
            JsonObject t = e.getAsJsonObject();
            Task task = new Task(t.get("id").getAsString(), t.get("text").getAsString());
            task.done = t.get("done").getAsBoolean();
            task.doneBy = t.has("doneBy") ? UUID.fromString(t.get("doneBy").getAsString()) : null;
            task.doneAt = t.has("doneAt") ? t.get("doneAt").getAsLong() : 0;
            p.tasks.add(task);
        }
        if (o.has("updates")) for (JsonElement e : o.getAsJsonArray("updates")) {
            JsonObject u = e.getAsJsonObject();
            p.updates.add(new Update(u.get("id").getAsString(), UUID.fromString(u.get("author").getAsString()),
                    u.get("text").getAsString(), u.get("createdAt").getAsLong()));
        }
        return p;
    }

    /** Alles, was gespeichert wird – inkl. Verantwortliche, Aufgaben und Updates. */
    private JsonObject fullStorageJson(Project p) {
        JsonObject o = storageJson(p);
        JsonArray resp = new JsonArray();
        p.responsible.forEach(u -> resp.add(u.toString()));
        o.add("responsible", resp);
        JsonArray tasks = new JsonArray();
        for (Task t : p.tasks) {
            JsonObject to = new JsonObject();
            to.addProperty("id", t.id);
            to.addProperty("text", t.text);
            to.addProperty("done", t.done);
            if (t.doneBy != null) to.addProperty("doneBy", t.doneBy.toString());
            if (t.doneAt > 0) to.addProperty("doneAt", t.doneAt);
            tasks.add(to);
        }
        o.add("tasks", tasks);
        JsonArray updates = new JsonArray();
        for (Update u : p.updates) {
            JsonObject uo = new JsonObject();
            uo.addProperty("id", u.id);
            uo.addProperty("author", u.author.toString());
            uo.addProperty("text", u.text);
            uo.addProperty("createdAt", u.createdAt);
            updates.add(uo);
        }
        o.add("updates", updates);
        return o;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        for (Project p : projects.values()) arr.add(fullStorageJson(p));
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("projects", arr);
        return root;
    }
}
