package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statistik wie bei CraftAttack: Kennzahlen aus Minecrafts Zahlen, Zuwachs pro Tag, Ranglisten, Ausblenden, Server-Werte. */
class StatisticsTest {
    static final LocalDate D0 = LocalDate.of(2026, 10, 20);
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @TempDir
    Path dir;
    TestSupport.World w;
    StatsStore st;
    UUID alice, bob, carl, dana;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config());
        st = w.services.stats();
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
        dana = w.player(4, "Dana");
    }

    // ------------------------------------------------------------------ Kennzahlen

    @Test
    void valuesComeFromMinecraftsOwnCounters() {
        StatValues.Raw raw = new StatValues.Raw(
                Map.of("minecraft:play_time", 72_000, "minecraft:deaths", 2, "minecraft:mob_kills", 5, "minecraft:walk_one_cm", 100_000,
                        "minecraft:sprint_one_cm", 50_000, "minecraft:fall_one_cm", 99_999, "minecraft:climb_one_cm", 500,
                        "minecraft:aviate_one_cm", 200_000, "minecraft:eat_cake_slice", 3, "minecraft:time_since_death", 1200),
                Map.of("minecraft:diamond_ore", 2, "minecraft:deepslate_diamond_ore", 3, "minecraft:stone", 100),
                Map.of("minecraft:stone", 40, "minecraft:carrot", 10, "minecraft:bread", 7, "minecraft:totem_of_undying", 1, "minecraft:diamond_pickaxe", 500),
                Map.of("minecraft:stick", 8, "minecraft:torch", 16),
                Map.of("minecraft:zombie", 4, "minecraft:cow", 1),
                Map.of("minecraft:zombie", 1));
        Set<String> blocks = Set.of("minecraft:stone", "minecraft:carrot");
        Set<String> food = Set.of("minecraft:carrot", "minecraft:bread");
        StatValues.Snapshot s = StatValues.compute(raw, blocks::contains, food::contains);
        Map<String, Long> v = s.values();
        assertEquals(3_600_000L, v.get("playTime"), "Ticks → Millisekunden (72.000 Ticks = 1 Stunde)");
        assertEquals(5, v.get("diamonds"), "Diamanterz und Tiefenschiefer-Diamanterz");
        assertEquals(105, v.get("mined"));
        assertEquals(40, v.get("placed"), "Karotten zählen nicht (Minecraft mischt dort Pflanzen und Essen)");
        assertEquals(10, v.get("eaten"), "Brot und Kuchenstücke – keine Karotten");
        assertEquals(350_000, v.get("distance"), "Gehen, Sprinten, Elytra – ohne Fallen und Klettern");
        assertEquals(200_000, v.get("elytra"));
        assertEquals(24, v.get("crafted"));
        assertEquals(1, v.get("totems"));
        assertEquals(60_000, v.get("sinceDeath"));
        assertEquals(List.of("minecraft:stone", "minecraft:deepslate_diamond_ore", "minecraft:diamond_ore"), List.copyOf(s.tops().get("mined").keySet()));
        assertEquals(Map.of("minecraft:stone", 40), s.tops().get("placed"));

        assertEquals("3,5 km", StatValues.format("distance", 350_000));
        assertEquals("120 m", StatValues.format("distance", 12_000));
        assertEquals("1 h 0 min", StatValues.format("playTime", 3_600_000));
        assertEquals("3 Tage 5 h", StatValues.duration((3 * 24 + 5) * 3_600_000L));
        assertEquals("12,5 ♥", StatValues.format("damageDealt", 250));
        assertEquals("1.234", StatValues.format("mined", 1234));
    }

    @Test
    void statsFilesOfOfflinePlayersAreRead() {
        // Aufbau wie eine echte Datei aus world/players/stats/ (Minecraft 26.3)
        StatValues.Raw raw = StatValues.fromStatsFile(JsonParser.parseString("""
                {"stats":{"minecraft:killed_by":{"minecraft:zombie":1},"minecraft:custom":{"minecraft:time_since_rest":20479,
                "minecraft:play_time":175868,"minecraft:deaths":4,"minecraft:time_since_death":20479},"minecraft:mined":{"minecraft:stone":3}},
                "DataVersion":4790}"""));
        assertNotNull(raw);
        StatValues.Snapshot s = StatValues.compute(raw, k -> false, k -> false);
        assertEquals(175_868L * 50, s.values().get("playTime"));
        assertEquals(4, s.values().get("deaths"));
        assertEquals(Map.of("minecraft:zombie", 1), s.tops().get("killedBy"));
        StatValues.Raw old = StatValues.fromStatsFile(JsonParser.parseString("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_one_minute\":1000}}}"));
        assertEquals(1000, old.custom().get("minecraft:play_time"), "alter Name der Spielzeit (vor 1.17)");
        assertNull(StatValues.fromStatsFile(JsonParser.parseString("{\"stat.playOneMinute\":5}")), "uraltes Format: lieber nichts als Unsinn");
    }

    @Test
    void namesAreGermanWhereKnownOtherwiseBuiltFromTheId() {
        assertEquals("Tiefenschiefer-Diamanterz", StatNames.block("minecraft:deepslate_diamond_ore"));
        assertEquals("Skelett", StatNames.mob("minecraft:skeleton"));
        assertEquals("Sulfur Cube", StatNames.mob("minecraft:sulfur_cube"), "unbekannt: aus der ID, keine geratene Übersetzung");
        assertEquals("Ruby Block (create)", StatNames.block("create:ruby_block"));
        assertEquals("Fallschaden", StatNames.cause("type:fall"));
        assertEquals("Zombie", StatNames.cause("mob:minecraft:zombie"));
        assertEquals("Sulfur Cube Hot", StatNames.cause("type:sulfurCubeHot"));
    }

    // ------------------------------------------------------------------ Zuwachs und Meilensteine

    @Test
    void growthPerDayStartsAtTheFirstMeasurement() {
        assertTrue(st.observe(alice, snap(1, 100), 1, D0).isEmpty(), "erster Stand: keine Meilensteine (sonst Schwall bei der Installation)");
        StatValues.Category mined = StatValues.category("abrissbirne");
        assertTrue(st.ranking(mined, "today", D0, Set.of()).isEmpty(), "was vor der Installation war, ist kein Zuwachs von heute");
        assertEquals(100, st.ranking(mined, "all", D0, Set.of()).getFirst().value());

        assertEquals(List.of("1.000 Blöcke abgebaut"), st.observe(alice, snap(3, 1500), 2, D0));
        assertEquals(1400, st.ranking(mined, "today", D0, Set.of()).getFirst().value());
        assertEquals(2 * 3_600_000L, st.ranking(StatValues.category("dauerzocker"), "today", D0, Set.of()).getFirst().value());

        LocalDate d1 = D0.plusDays(1);
        st.observe(alice, snap(4, 2000), 3, d1);
        assertEquals(500, st.ranking(mined, "today", d1, Set.of()).getFirst().value());
        assertEquals(1400, st.ranking(mined, "yesterday", d1, Set.of()).getFirst().value());

        // drei Tage nicht gespielt: der Stand bleibt einfach stehen
        LocalDate d5 = D0.plusDays(5);
        st.observe(alice, snap(5, 2600), 4, d5);
        assertEquals(600, st.ranking(mined, "today", d5, Set.of()).getFirst().value());
        assertTrue(st.ranking(mined, "yesterday", d5, Set.of()).isEmpty(), "gestern nichts abgebaut");
        assertEquals(2500, st.ranking(mined, "week", d5, Set.of()).getFirst().value(), "7 Tage, aber erst seit der ersten Messung");

        // nach 50 Tagen sind alte Tage gelöscht – der letzte davor bleibt als Ausgangswert
        LocalDate d50 = D0.plusDays(50);
        st.observe(alice, snap(6, 2700), 5, d50);
        assertEquals(100, st.ranking(mined, "week", d50, Set.of()).getFirst().value(), "2700 − Stand vom Tag 5");
        assertTrue(st.ranking(StatValues.category("unsterblich"), "week", d50, Set.of()).isEmpty(), "Momentwert: kein Zuwachs");
    }

    @Test
    void resetMinecraftStatsDoNotGiveNegativeNumbers() {
        st.observe(alice, snap(10, 5000), 1, D0);
        st.observe(alice, snap(0, 0), 2, D0); // Admin hat die Statistik-Datei gelöscht
        assertTrue(st.ranking(StatValues.category("abrissbirne"), "today", D0, Set.of()).isEmpty());
        st.observe(alice, snap(1, 50), 3, D0);
        assertEquals(50, st.ranking(StatValues.category("abrissbirne"), "today", D0, Set.of()).getFirst().value());
        JsonArray events = st.playerJson(alice, alice, D0, Set.of(), false, w.players::name).getAsJsonArray("events");
        List<String> texts = new java.util.ArrayList<>();
        events.forEach(e -> texts.add(e.getAsJsonObject().get("text").getAsString()));
        assertEquals(List.of("Erste Stunde gespielt", "Minecraft-Statistik wurde zurückgesetzt"), texts, "neueste zuerst");
    }

    @Test
    void milestonesCountOnlyTheHighestNewOne() {
        Map<String, Long> before = Map.of("deaths", 0L, "diamonds", 0L, "playTime", 9 * 3_600_000L);
        Map<String, Long> after = Map.of("deaths", 1L, "diamonds", 70L, "playTime", 10 * 3_600_000L);
        assertEquals(List.of("10 Stunden gespielt", "64 Diamanten abgebaut", "Erster Tod"), StatValues.milestones(before, after));
        assertTrue(StatValues.milestones(after, after).isEmpty());
    }

    // ------------------------------------------------------------------ Ranglisten und Ausblenden

    @Test
    void rankingsShareThePlaceOnTiesAndSkipZeroAndHidden() {
        st.observe(alice, snap(1, 100), 1, D0);
        st.observe(bob, snap(1, 80), 1, D0);
        st.observe(carl, snap(1, 80), 1, D0);
        st.observe(dana, snap(1, 50), 1, D0);
        st.observe(w.player(5, "Eve"), snap(1, 0), 1, D0);
        List<StatsStore.Entry> list = st.ranking(StatValues.category("abrissbirne"), "all", D0, Set.of());
        assertEquals(4, list.size(), "0 Blöcke = nicht in der Liste");
        assertEquals(List.of(1, 2, 2, 4), List.of(StatsStore.place(list, 0), StatsStore.place(list, 1), StatsStore.place(list, 2), StatsStore.place(list, 3)));

        w.prefs.setGame(bob, "rankings", false);
        Set<UUID> hidden = w.prefs.statsHidden();
        JsonObject r = st.rankingsJson("all", D0, carl, hidden, w.players::name);
        JsonObject cat = category(r, "abrissbirne");
        assertEquals(List.of("Alice", "Carl", "Dana"), names(cat.getAsJsonArray("entries")));
        assertEquals(2, cat.getAsJsonObject("me").get("place").getAsInt());
        assertFalse(category(st.rankingsJson("today", D0, carl, hidden, w.players::name), "unsterblich").get("available").getAsBoolean());
        assertTrue(st.rankingsJson("all", D0, bob, hidden, w.players::name).get("meHidden").getAsBoolean());
        assertEquals(400, assertThrows(PlannerException.class, () -> st.rankingsJson("immer", D0, carl, hidden, w.players::name)).status);
    }

    @Test
    void hiddenPlayersAreOnlyVisibleToThemselves() throws Exception {
        st.observe(alice, snap(2, 100), 1, D0);
        st.observe(bob, snap(5, 900), 1, D0);
        st.sessionStarted(bob, 1000, 1);
        st.sessionEnded(bob, 1000 + 7_200_000);
        w.prefs.setGame(bob, "rankings", false);
        Set<UUID> hidden = w.prefs.statsHidden();

        assertNull(st.playerJson(bob, alice, D0, hidden, false, w.players::name), "andere sehen die Seite nicht");
        JsonObject own = st.playerJson(bob, bob, D0, hidden, false, w.players::name);
        assertTrue(own.get("hidden").getAsBoolean(), "Bob selbst schon – mit Hinweis");
        assertEquals(0, own.getAsJsonObject("places").size(), "ausgeblendet = kein Platz");

        JsonObject server = st.serverJson(D0, hidden, Set.of(alice), w.players::name);
        assertEquals(100, server.getAsJsonObject("totals").get("mined").getAsLong(), "Summen ohne ausgeblendete Spieler");
        assertEquals(1, server.get("hiddenPlayers").getAsInt());
        assertEquals(1, server.get("online").getAsInt());
        JsonObject longest = server.getAsJsonObject("records").getAsJsonObject("longestSession");
        assertEquals(7_200_000, longest.get("ms").getAsLong(), "der Rekord bleibt …");
        assertFalse(longest.has("name"), "… aber ohne Bobs Namen");
        assertFalse(st.serverJson(D0, hidden, null, w.players::name).has("online"), "showOnlinePlayers aus: keine Zahl");
        assertEquals(List.of("Alice"), names(st.playersJson(alice, hidden, Set.of(), w.players::name).getAsJsonArray("players")));

        // über die API (wie die Webseite)
        assertEquals(404, assertThrows(PlannerException.class, () -> api(alice, "players", bob.toString())).status);
        assertEquals("Bob", api(bob, "players", bob.toString()).getAsJsonObject().get("name").getAsString());
        assertEquals(400, assertThrows(PlannerException.class, () -> api(alice, "players", "../../etc")).status);
    }

    @Test
    void playerPageShowsGrowthPerHourHistoryAndPlaces() {
        st.observe(alice, snap(1, 100), 1, D0.minusDays(8));
        st.observe(bob, snap(1, 500), 1, D0.minusDays(8));
        st.observe(alice, snap(10, 1000), 2, D0); // Alice holt auf: vor 7 Tagen Platz 2, jetzt Platz 1
        st.death(alice, "type:fall", "Gestorben: Fallschaden", 3);
        st.death(alice, "type:fall", "Gestorben: Fallschaden", 4);
        st.death(alice, "mob:minecraft:zombie", "Gestorben: Zombie", 5);
        JsonObject p = st.playerJson(alice, bob, D0, Set.of(), true, w.players::name);
        assertEquals(100.0, p.getAsJsonObject("perHour").get("mined").getAsDouble(), "1000 Blöcke in 10 Stunden");
        JsonObject place = p.getAsJsonObject("places").getAsJsonObject("abrissbirne");
        assertEquals(1, place.get("place").getAsInt());
        assertEquals(2, place.get("weekAgo").getAsInt());
        JsonArray causes = p.getAsJsonArray("causes");
        assertEquals("Fallschaden", causes.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals(2, causes.get(0).getAsJsonObject().get("count").getAsInt());
        JsonArray history = p.getAsJsonArray("history");
        assertEquals(30, history.size());
        JsonObject todayRow = history.get(29).getAsJsonObject();
        assertEquals(D0.toString(), todayRow.get("date").getAsString());
        assertEquals(900, todayRow.get("mined").getAsLong());
        assertTrue(history.get(0).getAsJsonObject().get("mined").isJsonNull(), "vor der ersten Messung: unbekannt statt 0");
        assertEquals("Gestorben: Zombie", p.getAsJsonArray("events").get(0).getAsJsonObject().get("text").getAsString(), "neueste zuerst");
    }

    // ------------------------------------------------------------------ Server, AFK, Speichern

    @Test
    void serverHeatmapAndDaysCountEveryone() {
        ZonedDateTime monday8pm = ZonedDateTime.of(2026, 10, 19, 20, 0, 0, 0, BERLIN);
        st.sampleServer(Set.of(alice, bob), 1, monday8pm);
        st.sampleServer(Set.of(alice), 2, monday8pm.plusMinutes(1));
        st.sampleServer(Set.of(carl), 3, monday8pm.plusDays(1));
        w.prefs.setGame(bob, "rankings", false);
        JsonObject o = st.serverJson(monday8pm.toLocalDate().plusDays(1), w.prefs.statsHidden(), Set.of(), w.players::name);
        assertEquals(1.5, o.getAsJsonArray("heat").get(0).getAsJsonArray().get(20).getAsDouble(), "Montag 20 Uhr: im Schnitt 1,5 online");
        assertTrue(o.getAsJsonArray("heat").get(0).getAsJsonArray().get(3).isJsonNull(), "keine Messung = keine erfundene 0");
        assertEquals(1.0, o.getAsJsonArray("heat").get(1).getAsJsonArray().get(20).getAsDouble(), "Dienstag");
        JsonArray days = o.getAsJsonArray("days");
        assertEquals(2, days.get(0).getAsJsonObject().get("players").getAsInt(), "anonyme Zählung: Bob zählt mit");
        assertEquals(3, days.get(0).getAsJsonObject().get("minutes").getAsInt());
        assertEquals(1, days.get(1).getAsJsonObject().get("players").getAsInt(), "neuer Tag, neue Zählung");
        assertEquals(2, o.getAsJsonObject("records").getAsJsonObject("maxOnline").get("count").getAsInt());
    }

    @Test
    void afkStartsAfterTheThresholdAndGapsDoNotCount() {
        ActivityTracker a = new ActivityTracker(5 * 60_000L);
        a.check(alice, 0, 64, 0, 0, 0, 0);
        for (int sec = 1; sec <= 360; sec++) a.check(alice, 0, 64, 0, 0, 0, sec * 1000L);
        assertTrue(a.isAfk(alice, 360_000));
        long[] t = a.drain(alice);
        assertEquals(299_000, t[0], "die ersten 5 Minuten ohne Bewegung zählen noch als aktiv");
        assertEquals(61_000, t[1]);
        a.check(alice, 0, 64, 0, 45, 0, 361_000); // umgeschaut
        assertFalse(a.isAfk(alice, 361_000));
        a.check(alice, 3, 64, 0, 45, 0, 421_000); // Server hing eine Minute
        assertEquals(1000 + 5000, a.drain(alice)[0], "eine Lücke zählt höchstens 5 Sekunden");
        assertEquals(0, a.drain(alice)[0], "abgeholt ist abgeholt");
    }

    @Test
    void everythingSurvivesARestartAndImportsDoNotOverwriteNewerData() throws Exception {
        st.observe(alice, snap(2, 300), 10, D0);
        st.addTime(alice, 60_000, 30_000, ZonedDateTime.of(2026, 10, 20, 18, 30, 0, 0, BERLIN));
        st.sessionStarted(alice, 100, 3);
        st.sessionEnded(alice, 100 + 3_600_000);
        st.death(alice, "type:lava", "Gestorben: Lava", 20);
        st.sampleServer(Set.of(alice), 30, ZonedDateTime.of(2026, 10, 20, 18, 31, 0, 0, BERLIN));
        JsonObject before = st.playerJson(alice, alice, D0, Set.of(), false, w.players::name);
        JsonObject serverBefore = st.serverJson(D0, Set.of(), Set.of(), w.players::name);

        TestSupport.World again = w.restart();
        StatsStore st2 = again.services.stats();
        assertEquals(before, st2.playerJson(alice, alice, D0, Set.of(), false, again.players::name));
        assertEquals(serverBefore, st2.serverJson(D0, Set.of(), Set.of(), again.players::name));
        assertEquals(60_000, before.getAsJsonObject("values").get("activeTime").getAsLong());
        assertEquals(1, before.getAsJsonArray("heat").get(1).getAsJsonArray().get(18).getAsInt(), "Dienstag 18 Uhr: 1,5 Minuten → 1");

        assertFalse(st2.importFile(alice, snapshot(1, 1), 5, 40, D0), "Datei älter als unser Stand: nicht übernehmen");
        assertTrue(st2.importFile(alice, snapshot(3, 400), 50, 60, D0));
        assertFalse(st2.importFile(alice, snapshot(3, 400), 100, 110, D0), "neuere Datei, aber gleiche Zahlen: nichts zu tun");
        assertTrue(st2.importFile(bob, snapshot(1, 10), 1, 60, D0), "neuer Spieler aus der Datei");
    }

    @Test
    void apiAndSwitches() throws Exception {
        st.observe(alice, snap(1, 10), 1, D0);
        JsonObject r = api(alice, "rankings").getAsJsonObject();
        assertEquals("all", r.get("range").getAsString());
        assertEquals(StatValues.CATEGORIES.size(), r.getAsJsonArray("categories").size());
        assertTrue(api(alice, "server").getAsJsonObject().has("timeZone"));
        assertTrue(w.prefs.game(alice).rankings(), "Standard: sichtbar");
        w.prefs.setGame(alice, "rankings", false);
        assertFalse(w.restart().prefs.game(alice).rankings(), "bleibt nach Neustart");

        TestSupport.World off = new TestSupport.World(dir.resolve("aus"), TestSupport.config("statistics", false));
        PlannerException e = assertThrows(PlannerException.class, () -> new StatsRoutes(off.services).handle(request(alice, List.of("stats", "server"), Map.of())));
        assertEquals(404, e.status);
        assertEquals("Europe/Berlin", TestSupport.config("timeZone", "Mars/Olympus").timeZone(), "unbekannte Zeitzone → Standard");
    }

    @Test
    void chatSummaryShowsTheBestRanks() {
        Map<String, Long> v = new LinkedHashMap<>();
        v.put("playTime", 3 * 3_600_000L);
        v.put("deaths", 2L);
        v.put("mobKills", 5L);
        v.put("diamonds", 5L);
        v.put("mined", 1500L);
        v.put("placed", 40L);
        v.put("distance", 350_000L);
        Map<String, Integer> places = new LinkedHashMap<>();
        places.put("dauerzocker", 1);
        places.put("friedhof", 3);
        places.put("diamantenbank", 1);
        places.put("abrissbirne", 2);
        StatsStore.Summary s = new StatsStore.Summary(v, Map.of("playTime", 2 * 3_600_000L), places, 970_000);
        assertEquals("""
                [Planer] Statistik von Alice (Stand: gerade eben)
                 Spielzeit: 3 h 0 min (heute +2 h 0 min)
                 Tode: 2 · Mobs besiegt: 5 · Diamanten abgebaut: 5
                 Blöcke abgebaut: 1.500 · Blöcke platziert: 40 · Strecke: 3,5 km
                 Ränge: Dauerzocker Platz 1 · Diamantenbank Platz 1 · Abrissbirne Platz 2""",
                GameText.statsSummary("Alice", s, 1_000_000, "", "#/statistik/spieler/x").getString());

        st.observe(alice, snap(1, 10), 1, D0);
        st.observe(bob, snap(1, 30), 1, D0);
        StatValues.Category c = StatValues.category("abrissbirne");
        assertEquals("[Planer] Abrissbirne – Blöcke abgebaut\n 1. Bob 30\n 2. Alice 10",
                GameText.ranking(c, st.ranking(c, "all", D0, Set.of()), w.players::name, alice, "").getString());
    }

    // ------------------------------------------------------------------ Hilfen

    /** Stand mit Spielzeit in Stunden und abgebauten Blöcken. */
    private static StatValues.Snapshot snap(long hours, long mined) {
        return snapshot(hours, mined);
    }

    static StatValues.Snapshot snapshot(long hours, long mined) {
        Map<String, Long> v = new LinkedHashMap<>();
        v.put("playTime", hours * 3_600_000L);
        v.put("mined", mined);
        v.put("deaths", 0L);
        v.put("sinceDeath", hours * 3_600_000L);
        return new StatValues.Snapshot(v, Map.of("mined", Map.of("minecraft:stone", (int) mined)));
    }

    private com.google.gson.JsonElement api(UUID user, String... path) {
        List<String> p = new java.util.ArrayList<>(List.of("stats"));
        p.addAll(List.of(path));
        return new StatsRoutes(w.services).handle(request(user, p, Map.of()));
    }

    private static ApiRequest request(UUID user, List<String> path, Map<String, String> query) {
        return new ApiRequest("GET", path, query, user, false, "127.0.0.1", "t", new ByteArrayInputStream(new byte[0]), 1024);
    }

    private static JsonObject category(JsonObject rankings, String id) {
        for (var e : rankings.getAsJsonArray("categories")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError(id);
    }

    private static List<String> names(JsonArray arr) {
        List<String> out = new java.util.ArrayList<>();
        arr.forEach(e -> out.add(e.getAsJsonObject().get("name").getAsString()));
        return out;
    }
}
