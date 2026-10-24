package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.UUID;

/** /api/teams – Teams, Rollen, Einladungen, Bewerbungen, Übergabe, Auflösen. */
final class TeamRoutes extends Routes {
    TeamRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        TeamStore teams = s.teams();
        if (r.is("GET", "teams")) {
            JsonObject o = new JsonObject();
            o.add("teams", teams.toApiJson(r.user, r.admin));
            return o;
        }
        if (r.is("GET", "teams", "*")) return teams.toApiJson(r.user, r.admin, r.seg(1));
        if (r.is("POST", "teams")) {
            String id = teams.create(r.user, r.str("name"), r.str("tag"), r.optStr("description"), r.optStr("color"),
                    r.optStr("symbol"), r.optStr("visibility"), r.optStr("joinPolicy"));
            log(r.user, "team.created", name(r.user) + " hat das Team [" + teams.tagOf(id) + "] " + teams.nameOf(id) + " gegründet.", "team", id, teamAudience(id));
            return ok("id", id);
        }
        if (!r.post() || r.size() < 3) throw unknown();
        String id = r.seg(1);
        if (!teams.canSee(r.user, r.admin, id)) throw PlannerException.notFound("Dieses Team gibt es nicht (oder es ist privat).");
        String tag = teams.tagOf(id);
        String audience = teamAudience(id);
        JsonObject o = ok();
        if (r.is("POST", "teams", "*", "invites", "*", "withdraw")) {
            teams.withdrawInvite(r.user, r.admin, id, r.seg(3));
            return o;
        }
        if (r.is("POST", "teams", "*", "applications", "*", "accept") || r.is("POST", "teams", "*", "applications", "*", "decline")) {
            boolean accept = r.seg(4).equals("accept");
            UUID applicant = teams.decideApplication(r.user, id, r.seg(3), accept);
            if (accept) log(r.user, "team.joined", name(applicant) + " ist dem Team [" + tag + "] beigetreten (Bewerbung).", "team", id, audience);
            return o;
        }
        if (r.size() != 3) throw unknown();
        switch (r.seg(2)) {
            case "edit" -> teams.edit(r.user, r.admin, id, r.optStr("name"), r.optStr("tag"), r.optStr("description"), r.optStr("color"),
                    r.optStr("symbol"), r.optStr("visibility"), r.optStr("joinPolicy"));
            case "invite" -> o.addProperty("inviteId", teams.invite(r.user, id, r.player("player", s.players())));
            case "accept" -> {
                teams.acceptInvite(r.user, id);
                log(r.user, "team.joined", name(r.user) + " ist dem Team [" + tag + "] beigetreten.", "team", id, audience);
            }
            case "decline" -> teams.declineInvite(r.user, id);
            case "apply" -> o.addProperty("applicationId", teams.apply(r.user, id, r.optStr("message")));
            case "withdraw-application" -> teams.withdrawApplication(r.user, id);
            case "role" -> {
                UUID target = r.player("player", s.players());
                TeamStore.Role role = TeamStore.Role.parse(r.str("role"));
                teams.setRole(r.user, id, target, role);
                log(r.user, "team.role", name(target) + " ist jetzt " + role.label + " in [" + tag + "].", "team", id, "team:" + id);
            }
            case "kick" -> {
                UUID target = r.player("player", s.players());
                teams.kick(r.user, r.admin, id, target);
                s.onTeamMembershipChanged(id);
                log(r.user, "team.kicked", name(target) + " wurde aus [" + tag + "] entfernt.", "team", id, "team:" + id);
            }
            case "leave" -> {
                teams.leave(r.user, id);
                s.onTeamMembershipChanged(id);
                log(r.user, "team.left", name(r.user) + " hat das Team [" + tag + "] verlassen.", "team", id, audience);
            }
            case "transfer" -> teams.offerTransfer(r.user, id, r.player("player", s.players()), r.optStr("confirmName"));
            case "transfer-cancel" -> teams.cancelTransfer(r.user, id);
            case "transfer-accept" -> {
                teams.acceptTransfer(r.user, id);
                log(r.user, "team.owner", name(r.user) + " ist jetzt Owner von [" + tag + "].", "team", id, audience);
            }
            case "dissolve" -> {
                String teamName = teams.nameOf(id);
                TeamStore.Dissolved d = teams.dissolve(r.user, r.admin, id, r.optStr("confirmName"));
                s.onTeamDissolved(d);
                log(r.user, "team.dissolved", "Das Team [" + tag + "] " + teamName + " wurde aufgelöst. Länder und Projekte bleiben erhalten.",
                        "team", id, audience.equals("all") ? "all" : null);
            }
            default -> throw unknown();
        }
        return o;
    }
}
