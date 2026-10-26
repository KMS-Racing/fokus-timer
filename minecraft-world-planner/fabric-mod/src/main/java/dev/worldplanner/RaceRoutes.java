package dev.worldplanner;

import com.google.gson.JsonElement;

/**
 * /api/races, /api/races/{id}, POST /api/races/{id}/rename|vehicle|delete|navigate.
 * Strecken anlegen und Tore setzen geht nur im Spiel – dort steht man an der Linie und schaut in Fahrtrichtung.
 */
final class RaceRoutes extends Routes {
    RaceRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        if (!s.config().races()) throw PlannerException.notFound("Rennen sind auf diesem Server ausgeschaltet.");
        if (r.is("GET", "races")) return s.races().listJson(r.user, s.players()::name);
        if (r.is("GET", "races", "*")) return s.races().trackJson(r.seg(1), r.user, r.admin, s.players()::name);
        if (r.is("POST", "races", "*", "rename")) {
            s.races().rename(r.user, r.admin, r.seg(1), r.str("name"));
            return ok();
        }
        if (r.is("POST", "races", "*", "vehicle")) {
            s.races().setVehicle(r.user, r.admin, r.seg(1), r.str("vehicle"));
            return ok();
        }
        if (r.is("POST", "races", "*", "delete")) {
            RaceStore.Track t = track(r.seg(1));
            // Wie beim Auflösen eines Teams: Der Name muss eingetippt werden – sonst ist ein Fehlklick zu teuer
            if (!t.name().equalsIgnoreCase(r.str("confirmName").strip())) throw PlannerException.badRequest("Zum Löschen den Namen der Strecke genau eintippen.");
            String name = s.races().delete(r.user, r.admin, t.id());
            log(r.user, "race.deleted", name(r.user) + " hat die Rennstrecke »" + name + "« gelöscht.", "race", t.id(), "all");
            return ok();
        }
        if (r.is("POST", "races", "*", "navigate")) {
            RaceStore.Track t = track(r.seg(1));
            if (t.gates().isEmpty()) throw PlannerException.badRequest("Die Strecke hat noch keine Tore.");
            RaceStore.Gate start = t.gates().getFirst();
            s.requestWaypoint(r.user, new Waypoints.Target("Start »" + t.name() + "«", "🏁", t.dimension(), start.x(), (int) Math.round(start.y()), start.z()));
            return ok();
        }
        throw unknown();
    }

    private RaceStore.Track track(String id) {
        RaceStore.Track t = s.races().track(id);
        if (t == null) throw PlannerException.notFound("Diese Strecke gibt es nicht (mehr).");
        return t;
    }
}
