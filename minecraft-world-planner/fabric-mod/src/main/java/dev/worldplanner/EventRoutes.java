package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * /api/events, /api/events/{id}, POST /api/events (anlegen), /api/events/{id}/edit|answer|cancel|delete|navigate.
 * Die Zeiten sind Millisekunden seit 1970 (UTC) – Webseite und Chat zeigen sie in der Zeitzone des Servers (Config timeZone).
 */
final class EventRoutes extends Routes {
    EventRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        if (!s.config().events()) throw PlannerException.notFound("Events sind auf diesem Server ausgeschaltet.");
        EventStore events = s.events();
        long now = System.currentTimeMillis();
        if (r.is("GET", "events")) {
            JsonObject o = new JsonObject();
            o.add("events", events.listJson(r.user, r.admin, now));
            o.addProperty("serverTime", now);
            return o;
        }
        if (r.is("GET", "events", "*")) return events.eventJson(r.seg(1), r.user, r.admin, now);
        if (r.is("POST", "events")) {
            String id = events.create(r.user, r.admin, r.body(), now);
            log(r.user, "event.created", name(r.user) + " lädt ein: »" + events.titleOf(r.user, id) + "« am "
                    + EventStore.when(events.eventJson(id, r.user, r.admin, now).get("start").getAsLong(), s.config().zone()), "event", id, events.audienceOf(id));
            return ok("id", id);
        }
        if (!r.post() || r.size() != 3) throw unknown();
        String id = r.seg(1);
        switch (r.seg(2)) {
            case "edit" -> events.update(r.user, r.admin, id, r.body(), now);
            case "answer" -> events.answer(r.user, id, r.optStr("answer"), now);
            case "cancel" -> {
                events.cancel(r.user, r.admin, id, r.optStr("reason"), now);
                log(r.user, "event.cancelled", name(r.user) + " hat »" + events.titleOf(r.user, id) + "« abgesagt.", "event", id, events.audienceOf(id));
            }
            case "delete" -> {
                String audience = events.audienceOf(id);
                String title = events.delete(r.user, r.admin, id);
                log(r.user, "event.deleted", name(r.user) + " hat das Event »" + title + "« gelöscht.", "event", id, audience);
            }
            case "navigate" -> {
                Waypoints.Target target = events.target(r.user, id);
                if (target == null) throw PlannerException.badRequest("Das Event hat keinen Treffpunkt.");
                s.requestWaypoint(r.user, target);
            }
            default -> throw unknown();
        }
        return ok();
    }
}
