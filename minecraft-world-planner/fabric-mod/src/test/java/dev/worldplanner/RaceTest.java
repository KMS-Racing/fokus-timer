package dev.worldplanner;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rennstrecken: Zeitmessung zwischen den Ticks, ungültige Runden, Bestenliste, Versionen, Texte und API. */
class RaceTest {
    static final String OW = TestSupport.OVERWORLD;
    /** Quadrat: Start/Ziel bei (0,0) Richtung Osten, dann Süden, Westen, Norden – 240 Blöcke pro Runde. */
    static final List<RaceStore.Gate> SQUARE = List.of(
            new RaceStore.Gate(0, 64, 0, -90, 12), new RaceStore.Gate(40, 64, 20, 0, 12),
            new RaceStore.Gate(0, 64, 40, 90, 12), new RaceStore.Gate(-40, 64, 20, 180, 12));
    static final double[][] LOOP = {{40, 0}, {40, 40}, {-40, 40}, {-40, 0}, {0, 0}};

    @TempDir
    Path dir;
    TestSupport.World w;
    UUID alice, bob, carl;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config("maxTracksPerPlayer", 2));
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
    }

    static RaceStore.Track track(String type, String vehicle, List<RaceStore.Gate> gates) {
        return new RaceStore.Track("t", "Test", OW, type, vehicle, new UUID(0, 1), 0, 1, gates);
    }

    /** Fährt einen Weg ab (10 Blöcke pro Sekunde, ein Tick = 50 ms) und sammelt alle Ereignisse. */
    static final class Drive {
        final RaceTimer timer;
        final List<RaceTimer.Event> events = new ArrayList<>();
        double x, z, y = 64, t;
        String mode = "foot";

        Drive(RaceTimer timer, double x, double z) {
            this.timer = timer;
            this.x = x;
            this.z = z;
            events.addAll(timer.tick(x, y, z, true, t, mode));
        }

        Drive to(double tx, double tz) {
            double step = 0.5; // 0,5 Blöcke pro Tick
            while (true) {
                double dx = tx - x, dz = tz - z, d = Math.hypot(dx, dz);
                if (d < 1e-9) return this;
                double move = Math.min(step, d);
                x += dx / d * move;
                z += dz / d * move;
                t += 50 * move / step;
                events.addAll(timer.tick(x, y, z, true, t, mode));
            }
        }

        Drive loop(int times) {
            for (int i = 0; i < times; i++) for (double[] p : LOOP) to(p[0], p[1]);
            return this;
        }

        <T> List<T> of(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    // ------------------------------------------------------------------ Zeitmessung

    @Test
    void lapsAndSectorsAreMeasuredBetweenTheTicks() {
        // Start bei x = -10,25: Die Linie liegt mitten zwischen zwei Ticks – die Zeit muss trotzdem genau stimmen
        Drive d = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), -10.25, 0).loop(2);
        assertEquals(1, d.of(RaceTimer.Started.class).size(), "fliegender Start: einmal los, danach Runde an Runde");
        List<RaceTimer.Lap> laps = d.of(RaceTimer.Lap.class);
        assertEquals(2, laps.size());
        for (RaceTimer.Lap lap : laps) {
            assertTrue(lap.valid() && lap.clean() && !lap.sprint());
            assertEquals(24_000, lap.lapMs(), 1e-6, "240 Blöcke bei 10 Blöcken/s");
            assertEquals(4, lap.sectorsMs().length);
            for (double s : lap.sectorsMs()) assertEquals(6_000, s, 1e-6, "jeder Abschnitt 60 Blöcke");
        }
        assertEquals(List.of(1, 2), laps.stream().map(RaceTimer.Lap::lap).toList());
        assertEquals(6, d.of(RaceTimer.Sector.class).size(), "drei Zwischentore pro Runde");
        assertEquals(12_000, d.of(RaceTimer.Sector.class).get(1).lapMs(), 1e-6);
    }

    @Test
    void wrongWayAndBesideTheGateDoNotCount() {
        Drive backwards = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), 10, 0);
        backwards.to(-40, 0).to(-40, 40).to(40, 40).to(40, 0).to(-10, 0);
        assertTrue(backwards.events.isEmpty(), "gegen die Fahrtrichtung zählt kein Tor");
        Drive beside = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), -10, 7);
        beside.to(10, 7);
        assertTrue(beside.events.isEmpty(), "7 Blöcke neben der Mitte – das Tor ist nur 12 breit");
        Drive above = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), -10, 0);
        above.y = 80;
        above.to(10, 0);
        assertTrue(above.events.isEmpty(), "16 Blöcke über dem Tor");
    }

    @Test
    void skippedGateMakesOnlyThatLapInvalid() {
        Drive d = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), -10, 0);
        d.to(40, 0).to(40, 30).to(-40, 30).to(-40, 0).to(0, 0); // Tor 2 (unten) ausgelassen
        d.loop(1);
        List<RaceTimer.Lap> laps = d.of(RaceTimer.Lap.class);
        assertEquals(2, laps.size());
        assertFalse(laps.get(0).valid());
        assertEquals("Tor 2 ausgelassen", laps.get(0).reason());
        assertNull(laps.get(0).sectorsMs(), "ungültige Runde: keine Abschnittszeiten");
        assertTrue(laps.get(1).valid(), "die nächste Runde zählt wieder");
    }

    @Test
    void shortcutBackToTheStartLineStartsANewLap() {
        Drive d = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), -10, 0);
        d.to(5, 0).to(5, 5).to(-5, 5).to(-5, 0).to(10, 0); // gleich wieder über Start/Ziel
        List<RaceTimer.Lap> laps = d.of(RaceTimer.Lap.class);
        assertEquals(1, laps.size());
        assertEquals("Tor 1 ausgelassen", laps.getFirst().reason());
        assertTrue(d.timer.running(), "neue Runde läuft");
        assertEquals(2, d.timer.lap());
    }

    @Test
    void teleportLagAndDimensionAreCaught() {
        Drive tp = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), -10, 0).to(30, 0);
        tp.x = 40;
        tp.z = 60;
        tp.t += 50;
        tp.events.addAll(tp.timer.tick(tp.x, tp.y, tp.z, true, tp.t, "foot"));
        RaceTimer.Lap lap = tp.of(RaceTimer.Lap.class).getFirst();
        assertEquals("Teleport erkannt", lap.reason());
        assertFalse(tp.timer.running(), "nach einem Teleport geht es erst an der Startlinie weiter");

        Drive lag = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), -10, 0).to(20, 0);
        lag.t += 400; // ein Tick dauert 400 statt 50 ms
        lag.loop(1);
        RaceTimer.Lap laggy = lag.of(RaceTimer.Lap.class).getFirst();
        assertTrue(laggy.valid(), "die Runde ist gültig …");
        assertFalse(laggy.clean(), "… aber ungenau");

        Drive dim = new Drive(new RaceTimer(track("circuit", "any", SQUARE)), -10, 0).to(20, 0);
        List<RaceTimer.Event> ev = dim.timer.tick(20, 64, 0, false, dim.t + 50, "foot");
        assertInstanceOf(RaceTimer.Stopped.class, ev.getFirst());
        assertEquals("Dimension gewechselt", ((RaceTimer.Stopped) ev.getFirst()).reason());
    }

    @Test
    void vehicleRulesAreChecked() {
        Drive walk = new Drive(new RaceTimer(track("circuit", "boat", SQUARE)), -10, 0).loop(1);
        assertEquals("nur im Boot erlaubt", walk.of(RaceTimer.Started.class).getFirst().problem(), "gleich beim Start gewarnt");
        assertEquals("nur im Boot erlaubt", walk.of(RaceTimer.Lap.class).getFirst().reason());
        Drive boat = new Drive(new RaceTimer(track("circuit", "boat", SQUARE)), -10, 0);
        boat.mode = "boat";
        boat.loop(1);
        assertTrue(boat.of(RaceTimer.Lap.class).getFirst().valid());
        assertNull(RaceTimer.modeProblem("elytra", "foot"), "zum Abheben muss man laufen dürfen");
        assertEquals("nur mit Elytra (ohne Reittier) erlaubt", RaceTimer.modeProblem("elytra", "horse"));
        assertEquals("Kreativ- oder Zuschauermodus", RaceTimer.modeProblem("any", "creative"));
        assertEquals("Fliegen ohne Elytra", RaceTimer.modeProblem("any", "fly"));
        assertNull(RaceTimer.modeProblem("any", "other"));
    }

    @Test
    void sprintFinishesAndCanStartAgain() {
        List<RaceStore.Gate> line = List.of(new RaceStore.Gate(0, 64, 0, -90, 8), new RaceStore.Gate(50, 64, 0, -90, 8), new RaceStore.Gate(100, 64, 0, -90, 8));
        Drive d = new Drive(new RaceTimer(track("sprint", "any", line)), -5, 0).to(110, 0);
        RaceTimer.Lap run = d.of(RaceTimer.Lap.class).getFirst();
        assertTrue(run.sprint() && run.valid());
        assertEquals(10_000, run.lapMs(), 1e-6);
        assertEquals(1, d.of(RaceTimer.Sector.class).size());
        assertFalse(d.timer.running(), "im Ziel ist Schluss");
        d.to(110, 20).to(-5, 20).to(-5, 0).to(20, 0);
        assertEquals(2, d.of(RaceTimer.Started.class).size(), "über die Startlinie geht es von vorn los");
        assertEquals("das Ziel", d.timer.gateName(2));
    }

    // ------------------------------------------------------------------ Strecken und Bestenliste

    @Test
    void buildingATrackNeedsEnoughGatesAndOnlyTheBuilder() {
        RaceStore r = w.services.races();
        assertEquals(400, assertThrows(PlannerException.class, () -> r.create(alice, false, "<b>", OW, "circuit", "any")).status);
        assertEquals(400, assertThrows(PlannerException.class, () -> r.create(alice, false, "Kurs", OW, "drag", "any")).status);
        String id = r.create(alice, false, "Kurs", OW, "circuit", "boat");
        assertEquals(409, assertThrows(PlannerException.class, () -> r.create(bob, false, "kurs", OW, "circuit", "any")).status, "Name schon vergeben");
        assertEquals(id, r.editing(alice));
        r.addGate(alice, id, SQUARE.get(0));
        assertEquals(400, assertThrows(PlannerException.class, () -> r.addGate(alice, id, new RaceStore.Gate(1, 64, 1, 0, 12))).status, "zu nah am letzten Tor");
        assertEquals(400, assertThrows(PlannerException.class, () -> r.addGate(bob, id, SQUARE.get(1))).status, "Bob baut hier nicht");
        r.addGate(alice, id, SQUARE.get(1));
        assertEquals(400, assertThrows(PlannerException.class, () -> r.finishEdit(alice, id)).status, "Rundkurs braucht 3 Tore");
        r.addGate(alice, id, SQUARE.get(2));
        RaceStore.Track t = r.finishEdit(alice, id);
        assertTrue(t.ready());
        assertNull(r.editing(alice));
        assertEquals(403, assertThrows(PlannerException.class, () -> r.rename(bob, false, id, "Bobs Kurs")).status);
        UUID chef = w.admin(9, "Chef");
        r.rename(chef, true, id, "Neuer Kurs");
        assertEquals("Neuer Kurs", r.track(id).name(), "Admins dürfen fremde Strecken ändern");

        String draft = r.create(alice, false, "Entwurf", OW, "sprint", "any");
        assertEquals(400, assertThrows(PlannerException.class, () -> r.create(alice, false, "Dritte", OW, "sprint", "any")).status, "Maximum 2 Strecken");
        assertTrue(r.cancelEdit(alice, draft), "neue Strecke ohne Tore verschwindet beim Abbrechen");
        assertNull(r.track(draft));
    }

    @Test
    void leaderboardRecordsPersonalBestsAndPlaces() {
        RaceStore r = w.services.races();
        String id = readyTrack(r);
        RaceStore.Result first = r.recordLap(id, lap(alice, 24_000, 1));
        assertTrue(first.counted() && first.record() && first.personalBest());
        assertNull(first.recordBefore());
        RaceStore.Result bobs = r.recordLap(id, lap(bob, 23_500, 2));
        assertTrue(bobs.record());
        assertEquals(24_000, bobs.recordBefore().ms());
        assertEquals(1, bobs.place());
        RaceStore.Result slower = r.recordLap(id, lap(alice, 25_000, 3));
        assertFalse(slower.personalBest());
        assertEquals(24_000L, slower.previousBest());
        assertEquals(2, slower.place());
        RaceStore.Result tie = r.recordLap(id, lap(carl, 24_000, 4));
        assertEquals(2, tie.place(), "gleiche Zeit = gleicher Platz");
        RaceStore.Result unclean = r.recordLap(id, new RaceStore.Lap(carl, 1_000, new long[]{333, 333, 334}, 5, 1, "foot", false));
        assertFalse(unclean.counted(), "geruckelt: nicht in die Bestenliste");
        assertEquals(List.of("Bob", "Alice", "Carl"), r.leaderboard(id).stream().map(l -> w.players.name(l.player())).toList());
        assertArrayEquals(new long[]{7_833, 7_833, 7_834}, r.bestSectors(id), "schnellste Abschnitte (lila)");
        assertArrayEquals(new long[]{8_000, 8_000, 8_000}, r.personalSectors(id, alice), "Alices beste Abschnitte (grün)");
        assertFalse(r.recordLap(id, lap(alice, 20_000, 6, 7)).counted(), "Runde einer alten Version zählt nicht");
    }

    @Test
    void changingGatesOrVehicleStartsANewVersionAndKeepsOldTimes() throws Exception {
        RaceStore r = w.services.races();
        String id = readyTrack(r);
        r.recordLap(id, lap(alice, 24_000, 1));
        r.startEdit(alice, false, id);
        assertTrue(r.track(id).ready(), "beim Umbauen gilt die alte Strecke weiter");
        for (RaceStore.Gate g : SQUARE) r.addGate(alice, id, g);
        r.finishEdit(alice, id);
        assertEquals(2, r.track(id).version(), "4 statt 3 Tore = neue Version");
        assertTrue(r.leaderboard(id).isEmpty());
        assertEquals(1, r.trackJson(id, alice, false, java.util.Set.of(), w.players::name).get("archived").getAsInt(), "alte Bestzeit bleibt im Archiv");
        r.setVehicle(alice, false, id, "horse");
        assertEquals(3, r.track(id).version());
        r.recordLap(id, lap(bob, 30_000, 2, 3));

        TestSupport.World again = w.restart();
        RaceStore r2 = again.services.races();
        assertEquals(r.trackJson(id, alice, false, java.util.Set.of(), w.players::name), r2.trackJson(id, alice, false, java.util.Set.of(), again.players::name),
                "alles nach dem Neustart gleich");
        assertEquals("horse", r2.track(id).vehicle());
        assertEquals(SQUARE, r2.track(id).gates());
    }

    @Test
    void timesAndMessagesReadLikeFormulaOne() {
        assertEquals("1:23,46", RaceStore.time(83_456));
        assertEquals("9,87", RaceStore.time(9_870));
        assertEquals("1:00,00", RaceStore.time(59_999));
        assertEquals("−0,12", RaceStore.delta(-120));
        assertEquals("+1:02,00", RaceStore.delta(62_000));
        assertEquals("1:02,3", RaceText.tenths(62_349));

        RaceTimer.Lap lap = new RaceTimer.Lap(2, 23_456, new double[]{6000, 6000, 6000, 5456}, true, null, true, false);
        RaceStore.Lap before = new RaceStore.Lap(bob, 24_000, null, 1, 1, "foot", true);
        assertEquals("[Rennen] Runde 2: 23,46 – Streckenrekord! (vorher 24,00 von Bob)",
                RaceText.lap(lap, new RaceStore.Result(true, true, true, 24_100L, before, 1, 3), "Bob").getString());
        assertEquals("[Rennen] Runde 2: 23,46 – persönliche Bestzeit! (−0,64) · Platz 2 von 3",
                RaceText.lap(lap, new RaceStore.Result(true, true, false, 24_100L, before, 2, 3), "Bob").getString());
        assertEquals("[Rennen] Runde 2: 23,46 – +0,46 zu deiner Bestzeit · Platz 2 von 3",
                RaceText.lap(lap, new RaceStore.Result(true, false, false, 23_000L, before, 2, 3), "Bob").getString());
        assertEquals("[Rennen] Runde 2 zählt nicht: Tor 2 ausgelassen (23,46).",
                RaceText.lap(new RaceTimer.Lap(2, 23_456, null, false, "Tor 2 ausgelassen", true, false), null, null).getString());
        assertEquals("[Rennen] Ziel! 23,46 – ungenau: Der Server hat geruckelt, deshalb kommt sie nicht in die Bestenliste.",
                RaceText.lap(new RaceTimer.Lap(1, 23_456, new double[]{23_456}, true, null, false, true), new RaceStore.Result(false, false, false, null, null, 0, 0), null).getString());
        assertEquals("🏁 Test · Runde 2 · 12,3 · S1 6,00 −0,10",
                RaceText.live("Test", 2, false, 12_345, 1, 6000, new long[]{5900}, new long[]{6100}, null).getString());
        assertEquals(net.minecraft.ChatFormatting.LIGHT_PURPLE, RaceText.sectorColor(5800, new long[]{5900}, new long[]{6100}, 0));
        assertEquals(net.minecraft.ChatFormatting.GREEN, RaceText.sectorColor(6000, new long[]{5900}, new long[]{6100}, 0));
        assertEquals(net.minecraft.ChatFormatting.YELLOW, RaceText.sectorColor(6200, new long[]{5900}, new long[]{6100}, 0));
    }

    @Test
    void apiListsTracksAndProtectsChanges() {
        RaceStore r = w.services.races();
        String id = readyTrack(r);
        r.recordLap(id, lap(alice, 24_000, 1));
        JsonObject list = new RaceRoutes(w.services).handle(get(bob, "races")).getAsJsonObject();
        JsonObject first = list.getAsJsonArray("tracks").get(0).getAsJsonObject();
        assertEquals("Kurs", first.get("name").getAsString());
        assertEquals(24_000, first.getAsJsonObject("record").get("ms").getAsLong());
        assertFalse(first.get("mine").getAsBoolean());
        JsonObject detail = new RaceRoutes(w.services).handle(get(bob, "races", id)).getAsJsonObject();
        assertEquals(3, detail.getAsJsonArray("gates").size());
        assertFalse(detail.get("canEdit").getAsBoolean());
        assertEquals(403, assertThrows(PlannerException.class, () -> new RaceRoutes(w.services).handle(post(bob, "{\"name\":\"X\"}", "races", id, "rename"))).status);
        assertEquals(400, assertThrows(PlannerException.class, () -> new RaceRoutes(w.services).handle(post(alice, "{\"confirmName\":\"falsch\"}", "races", id, "delete"))).status);
        assertEquals(409, assertThrows(PlannerException.class, () -> new RaceRoutes(w.services).handle(post(alice, "{}", "races", id, "navigate"))).status, "nicht im Spiel");
        // Wer sich aus Ranglisten ausgeblendet hat: Die Zeit bleibt (sonst wäre die Bestenliste falsch), der Name nicht
        w.prefs.setGame(alice, "rankings", false);
        JsonObject anon = new RaceRoutes(w.services).handle(get(bob, "races", id)).getAsJsonObject().getAsJsonArray("leaderboard").get(0).getAsJsonObject();
        assertEquals("ausgeblendet", anon.get("name").getAsString());
        assertEquals(24_000, anon.get("ms").getAsLong());
        assertFalse(anon.has("uuid"), "auch keine UUID, über die man sie finden könnte");
        JsonObject own = new RaceRoutes(w.services).handle(get(alice, "races", id)).getAsJsonObject().getAsJsonArray("leaderboard").get(0).getAsJsonObject();
        assertEquals("Alice", own.get("name").getAsString(), "sich selbst sieht man");
        new RaceRoutes(w.services).handle(post(alice, "{\"confirmName\":\"kurs\"}", "races", id, "delete"));
        assertNull(r.track(id));
        TestSupport.World off = new TestSupport.World(dir.resolve("aus"), TestSupport.config("races", false));
        assertEquals(404, assertThrows(PlannerException.class, () -> new RaceRoutes(off.services).handle(get(alice, "races"))).status);
    }

    // ------------------------------------------------------------------ Hilfen

    private String readyTrack(RaceStore r) {
        String id = r.create(alice, false, "Kurs", OW, "circuit", "any");
        for (RaceStore.Gate g : SQUARE.subList(0, 3)) r.addGate(alice, id, g);
        r.finishEdit(alice, id);
        return id;
    }

    private static RaceStore.Lap lap(UUID player, long ms, long at) {
        return lap(player, ms, at, 1);
    }

    private static RaceStore.Lap lap(UUID player, long ms, long at, int version) {
        long s = ms / 3; // drei Tore = drei Abschnitte
        return new RaceStore.Lap(player, ms, new long[]{s, s, ms - 2 * s}, at, version, "foot", true);
    }

    private static ApiRequest get(UUID user, String... path) {
        return new ApiRequest("GET", List.of(path), Map.of(), user, false, "127.0.0.1", "t", new ByteArrayInputStream(new byte[0]), 1024);
    }

    private static ApiRequest post(UUID user, String body, String... path) {
        return new ApiRequest("POST", List.of(path), Map.of(), user, false, "127.0.0.1", "t",
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), 1024);
    }
}
