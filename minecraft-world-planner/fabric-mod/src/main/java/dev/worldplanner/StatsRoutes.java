package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** /api/stats/server, /api/stats/rankings?range=all|today|yesterday|week, /api/stats/players, /api/stats/players/{uuid} */
final class StatsRoutes extends Routes {
    StatsRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        if (!s.config().statistics()) throw PlannerException.notFound("Die Statistik ist auf diesem Server ausgeschaltet.");
        LocalDate today = LocalDate.now(s.config().zone());
        Set<UUID> hidden = s.prefs().statsHidden(); // vorher holen: StatsStore fragt keine anderen Speicher (keine Verklemmung)
        Set<UUID> online = s.config().showOnlinePlayers() ? s.onlinePlayers() : null;
        if (r.is("GET", "stats", "server")) {
            JsonObject o = s.stats().serverJson(today, hidden, online, s.players()::name);
            o.addProperty("timeZone", s.config().timeZone());
            return o;
        }
        if (r.is("GET", "stats", "rankings")) {
            return s.stats().rankingsJson(r.query.getOrDefault("range", "all"), today, r.user, hidden, s.players()::name);
        }
        if (r.is("GET", "stats", "players")) return s.stats().playersJson(r.user, hidden, online, s.players()::name);
        if (r.is("GET", "stats", "players", "*")) {
            UUID id;
            try {
                id = UUID.fromString(r.seg(2));
            } catch (IllegalArgumentException e) {
                throw PlannerException.badRequest("Ungültige Spieler-ID.");
            }
            JsonObject o = s.stats().playerJson(id, r.user, today, hidden, online != null && online.contains(id), s.players()::name);
            if (o == null) throw PlannerException.notFound("Keine Statistik für diesen Spieler – noch nie online gewesen oder ausgeblendet.");
            o.addProperty("timeZone", s.config().timeZone());
            o.addProperty("afkMinutes", s.config().afkMinutes());
            if (online != null && !online.contains(id)) o.addProperty("lastSeen", s.players().lastSeen(id));
            return o;
        }
        throw unknown();
    }
}
