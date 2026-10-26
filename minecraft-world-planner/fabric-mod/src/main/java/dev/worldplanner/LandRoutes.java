package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** /api/lands – Länder (Claims). */
final class LandRoutes extends Routes {
    LandRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        LandStore lands = s.lands();
        if (r.is("GET", "lands")) {
            JsonObject o = new JsonObject();
            o.addProperty("version", lands.version());
            o.add("lands", lands.toApiJson(r.user, r.admin));
            return o;
        }
        if (r.is("POST", "lands")) {
            String id = lands.create(r.user, r.admin, r.str("name"), r.str("dimension"), r.chunks("chunks"), r.optStr("color"), r.optStr("description"));
            log(r.user, "land.created", name(r.user) + " hat das Land »" + lands.nameOf(id) + "« erstellt.", "land", id, "all");
            return ok("id", id);
        }
        // Vorschau vor dem Speichern: Was würde passieren? Ändert nichts.
        if (r.is("POST", "lands", "check")) {
            return lands.check(r.user, r.admin, r.optStr("landId"), r.str("dimension"), r.chunks("chunks"));
        }
        if (!r.post() || r.size() != 3) throw unknown();
        String id = r.seg(1);
        String landName = lands.nameOf(id);
        JsonObject o = ok();
        switch (r.seg(2)) {
            case "chunks" -> {
                if (r.body().has("add")) lands.addChunks(r.user, r.admin, id, r.chunks("add"));
                if (r.body().has("remove")) {
                    boolean deleted = lands.removeChunks(r.user, r.admin, id, r.chunks("remove"));
                    o.addProperty("deleted", deleted);
                    if (deleted) deleted(r, id, landName);
                }
            }
            case "navigate" -> {
                LandStore.Shape shape = lands.shapeById(id);
                s.requestWaypoint(r.user, new Waypoints.Target(shape.name(), "⌂", shape.dimension(), shape.centerX() + 0.5, null, shape.centerZ() + 0.5));
            }
            case "edit" -> lands.edit(r.user, r.admin, id, r.optStr("name"), r.optStr("color"), r.optStr("description"), r.optStr("notes"));
            case "invite" -> lands.invite(r.user, id, r.player("player", s.players()));
            case "uninvite" -> lands.uninvite(r.user, id, r.player("player", s.players()));
            case "accept" -> {
                lands.accept(r.user, r.admin, id);
                log(r.user, "land.joined", name(r.user) + " besitzt jetzt »" + landName + "« mit.", "land", id, "all");
            }
            case "decline" -> lands.decline(r.user, id);
            case "leave" -> {
                boolean deleted = lands.leave(r.user, id);
                o.addProperty("deleted", deleted);
                if (deleted) deleted(r, id, landName);
                else log(r.user, "land.left", name(r.user) + " ist aus »" + landName + "« ausgetreten.", "land", id, "all");
            }
            case "delete" -> {
                lands.delete(r.user, r.admin, id);
                deleted(r, id, landName);
            }
            case "remove-owner" -> {
                if (!r.admin) throw PlannerException.forbidden("Nur Admins dürfen Besitzer entfernen.");
                java.util.UUID target = r.player("player", s.players());
                boolean deleted = lands.adminRemoveOwner(id, target);
                o.addProperty("deleted", deleted);
                if (deleted) deleted(r, id, landName);
                else log(r.user, "land.owner_removed", "Ein Admin hat " + name(target) + " aus »" + landName + "« entfernt.", "land", id, "all");
            }
            case "team" -> {
                String teamId = r.optStr("teamId");
                lands.setTeam(r.user, r.admin, id, teamId);
                if (teamId != null && !teamId.isEmpty()) {
                    log(r.user, "land.team", "»" + landName + "« gehört jetzt zum Team [" + s.teams().tagOf(teamId) + "].", "land", id, teamAudience(teamId));
                }
            }
            default -> throw unknown();
        }
        return o;
    }

    private void deleted(ApiRequest r, String id, String landName) {
        s.onLandDeleted(id);
        log(r.user, "land.deleted", "Das Land »" + landName + "« wurde aufgelöst.", "land", id, "all");
    }
}
