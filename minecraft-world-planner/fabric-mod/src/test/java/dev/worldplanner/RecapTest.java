package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wochen-Rückblick: nur echte Dinge der letzten 7 Tage – und nur, was der Spieler auch sonst sehen darf. */
class RecapTest {
    static final long HOUR = 3_600_000L, DAY = 24 * HOUR;

    @TempDir
    Path dir;
    TestSupport.World w;
    UUID alice, bob, carl;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config());
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
        w.discover(-10, -10, 10, 10);
    }

    @Test
    void recapShowsOnlyRealThingsOfTheLastSevenDays() {
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Berlin"));
        StatsStore st = w.services.stats();
        st.observe(alice, StatisticsTest.snapshot(1, 100), now - 3 * DAY, today.minusDays(3));
        st.observe(bob, StatisticsTest.snapshot(2, 50), now - 3 * DAY, today.minusDays(3));
        st.observe(alice, StatisticsTest.snapshot(4, 1500), now, today); // +3 h, +1400 Blöcke, Meilenstein „1.000 Blöcke abgebaut“
        st.observe(bob, StatisticsTest.snapshot(3, 50), now, today); // +1 h

        w.lands.create(alice, false, "Neustadt", TestSupport.OVERWORLD, TestSupport.chunks(0, 0));

        String bridge = w.projects.create(alice, false, json("name", "Brücke"));
        String secret = w.projects.create(carl, false, json("name", "Geheim", "visibility", "private"));
        String reopened = w.projects.create(alice, false, json("name", "Turm"));
        w.projects.update(alice, false, bridge, json("status", "done"));
        w.projects.update(carl, false, secret, json("status", "done"));
        w.projects.update(alice, false, reopened, json("status", "done"));
        w.projects.update(alice, false, reopened, json("status", "in_progress"));
        String task = w.projects.addTask(alice, false, bridge, "Geländer");
        w.projects.editTask(alice, false, bridge, task, null, true);
        w.projects.addTask(alice, false, bridge, "Laternen");

        EventStore ev = w.services.events();
        String party = ev.create(alice, false, json("title", "Bauabend", "start", now - 2 * DAY), now - 3 * DAY);
        ev.answer(bob, party, "yes", now - 3 * DAY);
        String market = ev.create(bob, false, json("title", "Markt", "start", now - DAY), now - 2 * DAY);
        ev.cancel(bob, false, market, "", now - 2 * DAY);
        ev.create(carl, false, json("title", "Party", "start", now + 3 * DAY), now);
        ev.create(carl, false, json("title", "Fest", "start", now + 10 * DAY), now);

        JsonObject r = new RecapRoutes(w.services).handle(get(alice)).getAsJsonObject();
        assertTrue(r.get("to").getAsLong() - r.get("from").getAsLong() == 7 * DAY);

        JsonObject stats = r.getAsJsonObject("stats");
        assertEquals(4 * HOUR, stats.get("playTime").getAsLong(), "Alice +3 h, Bob +1 h – derselbe Zuwachs wie „7 Tage“");
        assertEquals(2, stats.get("activePlayers").getAsInt());
        assertEquals("Alice", top(stats, "dauerzocker").getAsJsonObject("leader").get("name").getAsString());
        assertEquals(3 * HOUR, top(stats, "dauerzocker").getAsJsonObject("leader").get("value").getAsLong());
        assertEquals(1400, top(stats, "abrissbirne").getAsJsonObject("leader").get("value").getAsLong());
        assertEquals(null, top(stats, "friedhof"), "niemand gestorben = keine Kachel");
        JsonArray ms = stats.getAsJsonArray("milestones");
        assertEquals(1, ms.size());
        assertEquals("1.000 Blöcke abgebaut", ms.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("Alice", ms.get(0).getAsJsonObject().get("name").getAsString());

        assertEquals(1, r.getAsJsonObject("lands").get("count").getAsInt());
        assertEquals("Neustadt", r.getAsJsonObject("lands").getAsJsonArray("latest").get(0).getAsJsonObject().get("name").getAsString());

        JsonObject projects = r.getAsJsonObject("projects");
        assertEquals(List.of("Brücke"), names(projects.getAsJsonArray("done"), "name"), "Carls privates Projekt sieht Alice nicht, der Turm ist wieder offen");
        assertEquals(1, projects.get("tasksDone").getAsInt());

        JsonObject events = r.getAsJsonObject("events");
        assertEquals(List.of("Bauabend"), names(events.getAsJsonArray("past"), "title"), "abgesagte zählen nicht");
        assertEquals(2, events.getAsJsonArray("past").get(0).getAsJsonObject().get("coming").getAsInt(), "Alice und Bob");
        assertEquals(1, events.get("upcoming").getAsInt(), "die Party in 3 Tagen, nicht das Fest in 10 Tagen");

        JsonObject forCarl = new RecapRoutes(w.services).handle(get(carl)).getAsJsonObject();
        assertEquals(List.of("Brücke", "Geheim"), names(forCarl.getAsJsonObject("projects").getAsJsonArray("done"), "name"));
    }

    @Test
    void withoutStatisticsAndEventsThoseAreasAreMissing() {
        TestSupport.World off = new TestSupport.World(dir.resolve("aus"), TestSupport.config("statistics", false, "events", false));
        JsonObject r = new RecapRoutes(off.services).handle(get(alice)).getAsJsonObject();
        assertFalse(r.has("stats"));
        assertFalse(r.has("events"));
        assertEquals(0, r.getAsJsonObject("lands").get("count").getAsInt());
    }

    private static JsonObject top(JsonObject stats, String id) {
        for (var t : stats.getAsJsonArray("tops")) if (t.getAsJsonObject().get("id").getAsString().equals(id)) return t.getAsJsonObject();
        return null;
    }

    private static List<String> names(JsonArray arr, String key) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.getAsJsonObject().get(key).getAsString()));
        return out;
    }

    private static JsonObject json(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number n) o.addProperty((String) kv[i], n);
            else o.addProperty((String) kv[i], kv[i + 1].toString());
        }
        return o;
    }

    private static ApiRequest get(UUID user) {
        return new ApiRequest("GET", List.of("recap"), Map.of(), user, false, "127.0.0.1", "t", new ByteArrayInputStream(new byte[0]), 1024);
    }
}
