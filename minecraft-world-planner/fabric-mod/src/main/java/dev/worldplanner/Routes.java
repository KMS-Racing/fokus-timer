package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.UUID;

/** Basis für die API-Bereiche (/api/lands, /api/teams, ...). Jeder Bereich prüft seine Rechte selbst. */
abstract class Routes {
    protected final PlannerServices s;

    Routes(PlannerServices services) {
        this.s = services;
    }

    /** Gibt die Antwort zurück oder wirft PlannerException (404, wenn es den Pfad nicht gibt). */
    abstract JsonElement handle(ApiRequest r);

    protected static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    protected static JsonObject ok(String key, String value) {
        JsonObject o = ok();
        o.addProperty(key, value);
        return o;
    }

    protected static PlannerException unknown() {
        return PlannerException.notFound("Unbekannte Adresse.");
    }

    protected String name(UUID player) {
        return s.players().name(player);
    }

    /** Ereignis für die Aktivitäts-Übersicht. */
    protected void log(UUID actor, String type, String text, String refType, String refId, String audience) {
        if (audience != null) s.activity().add(actor, type, text, refType, refId, audience);
    }

    /** Öffentliche Teams: alle sehen das Ereignis, private nur das Team. */
    protected String teamAudience(String teamId) {
        return s.teams().isPublic(teamId) ? "all" : "team:" + teamId;
    }
}
