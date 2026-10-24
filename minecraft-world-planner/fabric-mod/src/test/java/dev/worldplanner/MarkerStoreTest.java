package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static dev.worldplanner.TestSupport.OVERWORLD;
import static dev.worldplanner.TestSupport.chunks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Marker 2.0: Sichtbarkeit (privat/Team/Land/alle), Rechte, Validierung, alte Webseiten. */
class MarkerStoreTest {
    @TempDir
    Path dir;
    TestSupport.World w;
    UUID alice, bob, carl, op;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config());
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
        op = w.admin(9, "Chef");
        w.discover(-5, -5, 5, 5);
    }

    private int status(Runnable r) {
        return assertThrows(PlannerException.class, r::run).status;
    }

    private static JsonObject marker(String name, int x, int z, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("x", x);
        o.addProperty("z", z);
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    private boolean sees(UUID viewer, String id) {
        for (JsonElement e : w.markers.toApiJson(viewer, false)) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return true;
        return false;
    }

    @Test
    void visibilityLevels() {
        String team = w.teams.create(alice, "Bauteam", "BAU", null, null, null, null, null);
        w.teams.invite(alice, team, bob);
        w.teams.acceptInvite(bob, team);
        String land = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        w.lands.invite(alice, land, carl);
        w.lands.accept(carl, false, land);

        String priv = w.markers.create(alice, false, marker("Geheimversteck", 1, 1, "visibility", "private"));
        String forTeam = w.markers.create(alice, false, marker("Teamlager", 2, 2, "visibility", "team", "teamId", team));
        String forLand = w.markers.create(alice, false, marker("Landtruhe", 3, 3, "visibility", "land", "landId", land));
        String pub = w.markers.create(alice, false, marker("Spawnportal", 4, 4, "visibility", "public", "type", "portal"));

        assertTrue(sees(alice, priv) && sees(alice, forTeam) && sees(alice, forLand) && sees(alice, pub));
        assertFalse(sees(bob, priv));
        assertTrue(sees(bob, forTeam), "Teammitglied");
        assertFalse(sees(bob, forLand), "Bob besitzt das Land nicht");
        assertTrue(sees(carl, forLand), "Mitbesitzer");
        assertFalse(sees(carl, forTeam), "Carl ist nicht im Team");
        assertTrue(sees(carl, pub));
        assertEquals(1, w.markers.publicMarkers().size(), "nur der öffentliche Marker geht an BlueMap");
    }

    @Test
    void invisibleMarkersDoNotExistAndAdminsLeavePrivateOnesAlone() {
        String priv = w.markers.create(alice, false, marker("Geheim", 1, 1, "visibility", "private"));
        assertEquals(404, status(() -> w.markers.update(bob, false, priv, marker("Gehackt", 1, 1))), "404 statt 403");
        assertEquals(404, status(() -> w.markers.delete(op, true, priv)), "auch Admins sehen private Marker nicht");
        String pub = w.markers.create(alice, false, marker("Laden", 2, 2));
        assertEquals(403, status(() -> w.markers.update(bob, false, pub, marker("Meins", 2, 2))), "sichtbar, aber fremd");
        w.markers.update(op, true, pub, marker("Laden (moderiert)", 2, 2));
        w.markers.delete(op, true, pub);
    }

    @Test
    void validation() {
        assertEquals(400, status(() -> w.markers.create(alice, false, marker("", 0, 0))), "Name fehlt");
        assertEquals(400, status(() -> w.markers.create(alice, false, marker("Weit", 40_000_000, 0))), "außerhalb der Welt");
        assertEquals(400, status(() -> w.markers.create(alice, false, marker("Typ", 0, 0, "type", "raumschiff"))));
        assertEquals(400, status(() -> w.markers.create(alice, false, marker("Farbe", 0, 0, "color", "lila"))));
        assertEquals(400, status(() -> w.markers.create(alice, false, marker("Symbol", 0, 0, "symbol", "<b>"))));
        assertEquals(400, status(() -> w.markers.create(alice, false, marker("Team", 0, 0, "visibility", "team"))), "Team fehlt");
        assertEquals(400, status(() -> w.markers.create(alice, false, marker("Dim", 0, 0, "dimension", "minecraft:mond"))));
        String bobsLand = w.lands.create(bob, false, "Bobs", OVERWORLD, chunks(4, 4));
        assertEquals(403, status(() -> w.markers.create(alice, false, marker("Fremd", 0, 0, "landId", bobsLand))), "nur eigene Länder verknüpfen");
        w.markers.create(alice, false, marker("Höhle", 10, 10, "symbol", "★", "color", "#ff8800"));
        assertEquals(409, status(() -> w.markers.create(alice, false, marker("höhle", 11, 9))), "doppelt an fast derselben Stelle");
    }

    @Test
    void heightIsOptional() {
        JsonObject withY = marker("Turmspitze", 0, 0);
        withY.addProperty("y", 120);
        String a = w.markers.create(alice, false, withY);
        String b = w.markers.create(alice, false, marker("Irgendwo", 50, 50));
        JsonArray all = w.markers.toApiJson(alice, false);
        for (JsonElement e : all) {
            JsonObject m = e.getAsJsonObject();
            if (m.get("id").getAsString().equals(a)) assertEquals(120, m.get("y").getAsInt());
            if (m.get("id").getAsString().equals(b)) assertFalse(m.has("y"), "ohne Höhe bleibt die Höhe leer statt erfunden");
        }
    }

    @Test
    void oldWebsiteSendsPublicFlag() {
        JsonObject legacy = marker("Altes Format", 5, 5);
        legacy.addProperty("public", false);
        String id = w.markers.create(alice, false, legacy);
        assertFalse(sees(bob, id), "public=false wird zu privat");
        assertEquals("private", w.markers.toApiJson(alice, false).get(0).getAsJsonObject().get("visibility").getAsString());
    }

    @Test
    void deletedLandTurnsLandMarkersPrivate() {
        String land = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        String id = w.markers.create(alice, false, marker("Landtruhe", 3, 3, "visibility", "land", "landId", land));
        w.lands.delete(alice, false, land);
        w.services.onLandDeleted(land);
        JsonObject m = w.markers.toApiJson(alice, false).get(0).getAsJsonObject();
        assertEquals(id, m.get("id").getAsString(), "Marker bleibt");
        assertEquals("private", m.get("visibility").getAsString());
        assertFalse(m.has("landId"));
    }

    @Test
    void markerLimit() {
        w = new TestSupport.World(dir.resolve("klein"), TestSupport.config("maxMarkersPerPlayer", 1));
        UUID a = w.player(1, "Alice");
        w.markers.create(a, false, marker("Eins", 0, 0));
        assertEquals(400, assertThrows(PlannerException.class, () -> w.markers.create(a, false, marker("Zwei", 100, 100))).status);
    }
}
