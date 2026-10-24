package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** /api/projects – Bauprojekte mit Checkliste. */
final class ProjectRoutes extends Routes {
    ProjectRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        ProjectStore projects = s.projects();
        if (r.is("GET", "projects")) {
            JsonObject o = new JsonObject();
            o.add("projects", projects.toApiJson(r.user, r.admin));
            return o;
        }
        if (r.is("GET", "projects", "*")) return projects.toApiJson(r.user, r.admin, r.seg(1));
        if (r.is("POST", "projects")) {
            String id = projects.create(r.user, r.admin, r.body());
            logProject(r, id, "project.created", name(r.user) + " hat das Projekt »" + projects.nameOf(r.user, id) + "« angelegt.");
            return ok("id", id);
        }
        if (!r.post() || r.size() < 3) throw unknown();
        String id = r.seg(1);
        if (r.is("POST", "projects", "*", "edit")) {
            String before = statusOf(r, id);
            projects.update(r.user, r.admin, id, r.body());
            String after = statusOf(r, id);
            if (!before.equals(after)) {
                logProject(r, id, "project.status", "Projekt »" + projects.nameOf(r.user, id) + "«: " + ProjectStore.STATUS.get(after) + ".");
            }
            return ok();
        }
        if (r.is("POST", "projects", "*", "delete")) {
            projects.delete(r.user, r.admin, id);
            return ok();
        }
        if (r.is("POST", "projects", "*", "tasks")) return ok("taskId", projects.addTask(r.user, r.admin, id, r.str("text")));
        if (r.is("POST", "projects", "*", "tasks", "*", "edit")) {
            Boolean done = r.body().has("done") ? r.bool("done", false) : null;
            projects.editTask(r.user, r.admin, id, r.seg(3), r.optStr("text"), done);
            return ok();
        }
        if (r.is("POST", "projects", "*", "tasks", "*", "delete")) {
            projects.removeTask(r.user, r.admin, id, r.seg(3));
            return ok();
        }
        if (r.is("POST", "projects", "*", "updates")) {
            String updateId = projects.addUpdate(r.user, r.admin, id, r.str("text"));
            logProject(r, id, "project.update", name(r.user) + " hat ein Update zu »" + projects.nameOf(r.user, id) + "« geschrieben.");
            return ok("updateId", updateId);
        }
        throw unknown();
    }

    private String statusOf(ApiRequest r, String id) {
        return s.projects().toApiJson(r.user, r.admin, id).get("status").getAsString();
    }

    /** Öffentliche Projekte für alle, Team-Projekte fürs Team, private gar nicht. */
    private void logProject(ApiRequest r, String id, String type, String text) {
        JsonObject p = s.projects().toApiJson(r.user, r.admin, id);
        String visibility = p.get("visibility").getAsString();
        String audience = switch (visibility) {
            case "public" -> "all";
            case "team" -> p.has("teamId") ? "team:" + p.get("teamId").getAsString() : null;
            default -> null;
        };
        log(r.user, type, text, "project", id, audience);
    }
}
