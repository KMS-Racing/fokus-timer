package dev.worldplanner;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wegweiser, Land-Grenzen, Actionbar-Vorrang, Geräteliste und die Sicherheits-Nachricht bei neuem Web-Login. */
class NavigationAndSecurityTest {
    @TempDir
    Path dir;
    TestSupport.World w;
    UUID alice, bob;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config());
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        w.discover(-10, -10, 10, 10);
    }

    // ------------------------------------------------------------------ Wegweiser

    @Test
    void arrowFollowsTheViewDirection() {
        // Minecraft: Blick nach Süden = 0°, Westen = 90°, Norden = 180°, Osten = −90°
        assertEquals("↑", Waypoints.arrow(0, 10, 0), "Blick nach Süden, Ziel im Süden = geradeaus");
        assertEquals("←", Waypoints.arrow(10, 0, 0), "Blick nach Süden: Osten liegt links");
        assertEquals("→", Waypoints.arrow(-10, 0, 0), "…Westen rechts");
        assertEquals("↓", Waypoints.arrow(0, -10, 0), "…Norden hinter dir");
        assertEquals("↑", Waypoints.arrow(0, -10, 180), "Blick nach Norden, Ziel im Norden");
        assertEquals("↗", Waypoints.arrow(10, -10, 180), "Blick nach Norden, Ziel im Nordosten = vorne rechts");
        assertEquals("↑", Waypoints.arrow(0, 10, 720), "Drehung über 360° hinaus zählt richtig");
    }

    @Test
    void waypointShowsDistanceHeightArrivalAndPortalHints() {
        Waypoints.Target base = new Waypoints.Target("Basis", "◆", Waypoints.OVERWORLD, 100.5, 70, 200.5);
        Waypoints.Shown far = Waypoints.render(base, Waypoints.OVERWORLD, 100.5, 64, 0.5, 0);
        assertEquals("◆ Basis  ↑ 200 Blöcke · ↑ 6", far.text().getString());
        assertFalse(far.arrived());
        Waypoints.Shown there = Waypoints.render(base, Waypoints.OVERWORLD, 101, 70, 201, 0);
        assertTrue(there.arrived());
        assertEquals("◆ Basis – angekommen!", there.text().getString());

        // Im Nether zeigt der Pfeil zur passenden Portal-Stelle (Oberwelt ÷ 8)
        Waypoints.Shown nether = Waypoints.render(base, Waypoints.NETHER, 12.5, 64, 0, 0);
        assertEquals("◆ Basis (Oberwelt)  ↑ 25 Blöcke bis zur Portal-Stelle", nether.text().getString());
        assertFalse(Waypoints.render(base, Waypoints.NETHER, 12.6, 64, 25, 0).arrived(), "an der Portal-Stelle ist man noch nicht am Ziel");
        assertEquals("◆ Basis liegt in: Oberwelt – von hier aus kein Weg",
                Waypoints.render(base, "minecraft:the_end", 0, 64, 0, 0).text().getString());
        Waypoints.Shown unknownHeight = Waypoints.render(new Waypoints.Target("Hof", "⌂", Waypoints.OVERWORLD, 0, null, 50), Waypoints.OVERWORLD, 0, 64, 0, 0);
        assertEquals("⌂ Hof  ↑ 50 Blöcke", unknownHeight.text().getString(), "ohne Höhe keine erfundene Höhenangabe");
    }

    @Test
    void markersAreFoundOnlyIfVisibleOwnFirst() {
        JsonObject mine = marker("Mine", 10, 10, "private");
        String own = w.markers.create(alice, false, mine);
        JsonObject bobs = marker("Mine", 50, 50, "public");
        w.markers.create(bob, false, bobs);
        w.markers.create(bob, false, marker("Geheimversteck", 80, 80, "private"));
        assertEquals(own, w.markers.findVisible(alice, "mine").id(), "eigener Marker zuerst, ohne Groß/klein");
        assertEquals(50, w.markers.findVisible(w.player(3, "Carl"), "Mine").x(), "fremder öffentlicher Marker");
        assertNull(w.markers.findVisible(alice, "Geheimversteck"), "private Marker anderer gibt es für Alice nicht");
        assertFalse(w.markers.visibleNames(alice).contains("Geheimversteck"));
    }

    @Test
    void landCenterLiesInsideTheLandEvenForLShapes() {
        // L-Form: der Durchschnitt (1,1) liegt außerhalb – die Mitte muss trotzdem ein Chunk des Landes sein
        w.lands.create(alice, false, "Winkel", TestSupport.OVERWORLD, TestSupport.chunks(0, 0, 1, 0, 2, 0, 0, 1, 0, 2));
        LandStore.Shape s = w.lands.shapeByName("winkel");
        assertNotNull(s);
        long center = Chunks.key(Math.floorDiv(s.centerX(), 16), Math.floorDiv(s.centerZ(), 16));
        assertTrue(java.util.Arrays.stream(s.chunks()).anyMatch(c -> c == center), "Mitte liegt in einem Chunk des Landes");
        assertNull(w.lands.shapeByName("gibt es nicht"));
    }

    // ------------------------------------------------------------------ Grenzen und Actionbar

    @Test
    void bordersFollowTheOutlineIncludingHoles() {
        List<LandBorders.Segment> one = LandBorders.segments(TestSupport.chunks(0, 0));
        assertEquals(4, one.size(), "ein Chunk = vier Seiten");
        assertEquals(64, one.stream().mapToDouble(s -> Math.hypot(s.x2() - s.x1(), s.z2() - s.z1())).sum(), 1e-9);
        // 3×3 mit Loch in der Mitte: außen 4 Seiten, innen 4 Seiten
        long[] ring = TestSupport.chunks(0, 0, 1, 0, 2, 0, 0, 1, 2, 1, 0, 2, 1, 2, 2, 2);
        assertEquals(8, LandBorders.segments(ring).size());
        List<double[]> near = LandBorders.points(one, 8, 8);
        assertTrue(near.size() >= 32, "alle 2 Blöcke ein Partikel");
        assertTrue(LandBorders.points(one, 500, 500).isEmpty(), "weit weg: keine Partikel");
    }

    @Test
    void actionBarPrefersImportantThings() {
        ActionBar bar = new ActionBar();
        assertTrue(bar.claim(alice, ActionBar.LAND, 40, 100));
        assertFalse(bar.claim(alice, ActionBar.NAVIGATION, 0, 110), "Land-Name bleibt 2 Sekunden stehen");
        assertTrue(bar.claim(alice, ActionBar.RACE, 10, 110), "Rennzeit ist wichtiger");
        assertTrue(bar.claim(alice, ActionBar.NAVIGATION, 0, 141), "danach wieder der Wegweiser");
        assertTrue(bar.claim(bob, ActionBar.NAVIGATION, 0, 110), "andere Spieler sind unabhängig");
    }

    // ------------------------------------------------------------------ Geräte und Login-Warnung

    @Test
    void deviceLabelsAreCoarse() {
        assertEquals("Firefox · Windows", SessionStore.deviceLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"));
        assertEquals("Chrome · Android", SessionStore.deviceLabel("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"));
        assertEquals("Safari · iOS", SessionStore.deviceLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"));
        assertEquals("Edge · Windows", SessionStore.deviceLabel("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/140.0 Safari/537.36 Edg/140.0"));
        assertEquals("anderes Programm", SessionStore.deviceLabel("Python-urllib/3.12"));
        assertEquals("unbekanntes Gerät", SessionStore.deviceLabel(null));
    }

    @Test
    void sessionsCanBeListedAndLoggedOutOneByOne() throws Exception {
        SessionStore s = w.services.sessions();
        String phone = s.redeem(s.createCode(alice), 30, "1.1.1.1", "Mozilla/5.0 (Linux; Android 14) Chrome/140.0 Mobile Safari/537.36").token();
        String pc = s.redeem(s.createCode(alice), 30, "1.1.1.1", "Mozilla/5.0 (Windows NT 10.0) Firefox/140.0").token();
        s.redeem(s.createCode(bob), 30, "1.1.1.1", null);
        List<SessionStore.SessionInfo> mine = s.sessionsOf(alice, pc);
        assertEquals(2, mine.size(), "nur eigene Geräte");
        assertEquals(1, mine.stream().filter(SessionStore.SessionInfo::current).count());
        assertTrue(mine.stream().anyMatch(i -> i.device().equals("Chrome · Android")));
        String phoneId = mine.stream().filter(i -> !i.current()).findFirst().orElseThrow().id();
        assertFalse(s.logoutSession(bob, phoneId), "fremde Geräte kann man nicht abmelden");
        assertTrue(s.logoutSession(alice, phoneId));
        assertTrue(s.authenticate(phone).isEmpty(), "das Handy ist abgemeldet");
        assertTrue(s.authenticate(pc).isPresent(), "dieses Gerät nicht");
        s.redeem(s.createCode(alice), 30, "1.1.1.1", null);
        assertEquals(1, s.logoutOthers(alice, pc));
        assertTrue(s.authenticate(pc).isPresent());

        TestSupport.World again = w.restart();
        assertEquals("Firefox · Windows", again.services.sessions().sessionsOf(alice, pc).getFirst().device(), "Gerät bleibt nach Neustart");
    }

    @Test
    void newWebLoginIsAnnouncedEvenWithMessagesOff() {
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        GameNotifier notifier = new GameNotifier(w.services);
        notifier.clock = now::get;
        w.prefs.setGame(alice, "notify", false);
        notifier.joined(alice);
        now.addAndGet(4000);
        notifier.poll();
        SessionStore s = w.services.sessions();
        s.redeem(s.createCode(alice), 30, "1.1.1.1", "Mozilla/5.0 (Windows NT 10.0) Firefox/140.0");
        now.addAndGet(2000);
        List<String> msg = notifier.poll().stream().map(o -> o.message().getString()).toList();
        assertEquals(List.of("[Planer] ⚠ Neuer Web-Login auf „Firefox · Windows“. Warst du das nicht, melde dich überall ab: [Überall abmelden]"), msg);
        now.addAndGet(2000);
        assertTrue(notifier.poll().isEmpty(), "nur einmal");
        assertTrue(Notices.toApiJson(Notices.collect(w.services, alice, false, "2026-10-25")).getAsJsonArray("items").isEmpty(),
                "keine Aufgabe auf dem Dashboard");
    }

    private static JsonObject marker(String name, int x, int z, String visibility) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("visibility", visibility);
        return o;
    }
}
