package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/recap – Wochen-Rückblick für das Dashboard: die letzten 7 Tage aus echten Daten (Statistik, neue Länder,
 * abgeschlossene Projekte, erledigte Aufgaben, Events). Jeder Bereich zeigt nur, was der Spieler auch sonst sehen darf.
 */
final class RecapRoutes extends Routes {
    static final long WEEK_MS = 7L * 24 * 3600_000;

    RecapRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        if (!r.is("GET", "recap")) throw unknown();
        long now = System.currentTimeMillis(), from = now - WEEK_MS;
        JsonObject o = new JsonObject();
        o.addProperty("from", from);
        o.addProperty("to", now);
        if (s.config().statistics()) o.add("stats", s.stats().weekJson(LocalDate.now(s.config().zone()), from, s.players()::name));

        List<LandStore.Ref> lands = s.lands().createdSince(from);
        JsonObject l = new JsonObject();
        l.addProperty("count", lands.size());
        JsonArray latest = new JsonArray();
        lands.stream().limit(5).forEach(x -> {
            JsonObject j = new JsonObject();
            j.addProperty("id", x.id());
            j.addProperty("name", x.name());
            latest.add(j);
        });
        l.add("latest", latest);
        o.add("lands", l);

        o.add("projects", s.projects().weekJson(r.user, from));

        if (s.config().events()) {
            JsonObject e = new JsonObject();
            JsonArray past = new JsonArray();
            for (EventStore.Note n : s.events().startedBetween(r.user, from, now)) {
                JsonObject j = new JsonObject();
                j.addProperty("id", n.id());
                j.addProperty("title", n.title());
                j.addProperty("start", n.start());
                j.addProperty("coming", n.coming());
                past.add(j);
            }
            e.add("past", past);
            e.addProperty("upcoming", s.events().notesFor(r.user, now).stream()
                    .filter(n -> !n.cancelled() && n.start() > now && n.start() <= now + WEEK_MS).count());
            o.add("events", e);
        }
        return o;
    }
}
