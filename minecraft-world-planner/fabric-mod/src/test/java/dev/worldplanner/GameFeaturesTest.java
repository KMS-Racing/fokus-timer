package dev.worldplanner;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spiel-Funktionen ohne laufenden Server: Todespunkt, Land-Anzeige, persönliche Schalter, Team-Chat-Auswahl. */
class GameFeaturesTest {
    @TempDir
    Path dir;
    TestSupport.World w;
    UUID alice, bob;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config("maxMarkersPerPlayer", 3, "maxTeamsPerPlayer", 2));
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        w.discover(-10, -10, 10, 10);
    }

    // ------------------------------------------------------------------ Todespunkt

    @Test
    void deathPointIsOnePrivateMarkerThatMoves() {
        assertNull(DeathPoints.save(w.services, alice, false, new DeathPoints.Point(TestSupport.OVERWORLD, 100, 12, -40)));
        String id = w.prefs.game(alice).deathMarkerId();
        MarkerStore.Marker m = w.markers.ownMarker(alice, id);
        assertEquals("Todespunkt", m.name());
        assertEquals("death", m.type());
        assertEquals("☠", m.symbol());
        assertEquals("private", m.visibility(), "privat – niemand sonst sieht, wo du gestorben bist");
        assertEquals(0, w.markers.toApiJson(bob, false).size(), "auch nicht über die API");

        assertNull(DeathPoints.save(w.services, alice, false, new DeathPoints.Point("minecraft:the_nether", -7, 70, 3)));
        assertEquals(id, w.prefs.game(alice).deathMarkerId(), "kein zweiter Marker");
        assertEquals(1, w.markers.countOwnedBy(alice));
        DeathPoints.Point p = DeathPoints.load(w.services, alice);
        assertEquals(new DeathPoints.Point("minecraft:the_nether", -7, 70, 3), p, "verschoben");
    }

    @Test
    void deathPointKeepsWhatThePlayerChanged() {
        DeathPoints.save(w.services, alice, false, new DeathPoints.Point(TestSupport.OVERWORLD, 1, 64, 1));
        String id = w.prefs.game(alice).deathMarkerId();
        JsonObject edit = new JsonObject();
        edit.addProperty("name", "Meine Sachen");
        edit.addProperty("visibility", "public");
        w.markers.update(alice, false, id, edit);
        DeathPoints.save(w.services, alice, false, new DeathPoints.Point(TestSupport.OVERWORLD, 50, 30, 50));
        MarkerStore.Marker m = w.markers.ownMarker(alice, id);
        assertEquals("Meine Sachen", m.name());
        assertEquals("public", m.visibility(), "wer ihn geteilt hat, behält das");
        assertEquals(50, m.x());

        w.markers.delete(alice, false, id);
        assertNull(DeathPoints.load(w.services, alice), "gelöscht = kein Todespunkt");
        DeathPoints.save(w.services, alice, false, new DeathPoints.Point(TestSupport.OVERWORLD, 2, 64, 2));
        assertNotEquals(id, w.prefs.game(alice).deathMarkerId(), "nach dem Löschen gibt es einen neuen");
    }

    @Test
    void deathPointRespectsTheMarkerLimit() {
        for (int i = 0; i < 3; i++) {
            JsonObject d = new JsonObject();
            d.addProperty("name", "Ort " + i);
            d.addProperty("x", i * 10);
            d.addProperty("z", 0);
            w.markers.create(alice, false, d);
        }
        String problem = DeathPoints.save(w.services, alice, false, new DeathPoints.Point(TestSupport.OVERWORLD, 5, 64, 5));
        assertNotNull(problem, "Limit erreicht – ehrlich melden statt still nichts zu tun");
        assertTrue(problem.contains("Maximum 3"), problem);
        assertNull(w.prefs.game(alice).deathMarkerId());
        assertNull(DeathPoints.save(w.services, alice, true, new DeathPoints.Point(TestSupport.OVERWORLD, 5, 64, 5)), "Admins haben kein Limit");
    }

    @Test
    void deathTextsShowDistanceDirectionAndUnknownHeight() {
        DeathPoints.Point p = new DeathPoints.Point(TestSupport.OVERWORLD, 300, 40, -400);
        String where = GameText.deathWhere(p, TestSupport.OVERWORLD, 0.5, 0.5, "").getString();
        assertEquals("[Planer] ☠ Dein Todespunkt: 300 / 40 / -400 (Oberwelt) – 500 Blöcke Richtung Nordosten. [Kopieren]", where);
        String other = GameText.deathWhere(p, "minecraft:the_nether", 0, 0, "").getString();
        assertTrue(other.contains("du bist gerade in: Nether"), other);
        String unknown = GameText.deathWhere(new DeathPoints.Point(TestSupport.OVERWORLD, 1, null, 1), TestSupport.OVERWORLD, 1.5, 1.5, "").getString();
        assertTrue(unknown.contains("1 / ? / 1") && unknown.contains("direkt dort"), "unbekannte Höhe wird nicht erfunden: " + unknown);
    }

    // ------------------------------------------------------------------ Land-Anzeige

    @Test
    void landNameShowsWhenEnteringAndWildernessWhenLeaving() {
        String land = w.lands.create(alice, false, "Kims Base", TestSupport.OVERWORLD, TestSupport.chunks(0, 0, 1, 0));
        LandEnterTracker t = new LandEnterTracker();
        assertNull(t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(5, 5)), "eingeloggt in der Wildnis: nichts");
        Component enter = t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(0, 0));
        assertEquals("▶ Kims Base · von Alice", enter.getString());
        assertNull(t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(0, 0)), "gleicher Chunk");
        assertNull(t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(1, 0)), "gleiches Land");
        assertEquals("Wildnis", t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(2, 0)).getString());
        assertNull(t.update(w.services, bob, "minecraft:the_nether", Chunks.key(0, 0)), "andere Dimension, kein Land dort");
        assertEquals("▶ Kims Base · dein Land", t.update(w.services, alice, TestSupport.OVERWORLD, Chunks.key(1, 0)).getString());

        w.lands.invite(alice, land, bob);
        w.lands.accept(bob, false, land);
        LandEnterTracker fresh = new LandEnterTracker();
        assertEquals("▶ Kims Base · von Alice +1", fresh.update(w.services, w.player(3, "Carl"), TestSupport.OVERWORLD, Chunks.key(0, 0)).getString());
    }

    @Test
    void onlyPublicTeamTagsAppearAndTheSwitchIsRespected() {
        String land = w.lands.create(alice, false, "Werft", TestSupport.OVERWORLD, TestSupport.chunks(3, 3));
        String team = w.teams.create(alice, "Kart Racing", "KMS", null, null, null, "public", null);
        w.lands.setTeam(alice, false, land, team);
        LandEnterTracker t = new LandEnterTracker();
        assertEquals("▶ Werft [KMS] · von Alice", t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(3, 3)).getString());
        w.teams.edit(alice, false, team, null, null, null, null, null, "private", null);
        t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(4, 4));
        assertEquals("▶ Werft · von Alice", t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(3, 3)).getString(),
                "private Teams verraten sich nicht");

        w.prefs.setGame(bob, "landEnter", false);
        assertNull(t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(4, 4)));
        assertNull(t.update(w.services, bob, TestSupport.OVERWORLD, Chunks.key(3, 3)), "ausgeschaltet");
    }

    // ------------------------------------------------------------------ Schalter und Team-Chat

    @Test
    void gameSwitchesArePersonalAndSurviveARestart() throws Exception {
        assertTrue(w.prefs.game(alice).notices(), "Standard: an");
        w.prefs.setGame(alice, "notify", false);
        w.prefs.setGame(alice, "deathMarker", false);
        w.prefs.setFavorite(alice, "lands", "abc-1", true);
        DeathPoints.save(w.services, bob, false, new DeathPoints.Point(TestSupport.OVERWORLD, 1, 2, 3));
        String bobsMarker = w.prefs.game(bob).deathMarkerId();
        assertEquals(400, assertThrows(PlannerException.class, () -> w.prefs.setGame(alice, "alles", false)).status);

        TestSupport.World again = w.restart();
        assertFalse(again.prefs.game(alice).notices());
        assertTrue(again.prefs.game(alice).landEnter());
        assertFalse(again.prefs.game(alice).deathMarker());
        assertTrue(again.prefs.game(bob).notices(), "Bobs Schalter sind unabhängig");
        assertEquals(bobsMarker, again.prefs.game(bob).deathMarkerId());
        assertEquals("abc-1", again.prefs.get(alice).getAsJsonObject("favorites").getAsJsonArray("lands").get(0).getAsString(),
                "Favoriten bleiben erhalten");
        JsonObject api = again.prefs.get(alice).getAsJsonObject("game");
        assertFalse(api.get("notify").getAsBoolean());
        assertFalse(api.has("deathMarkerId"), "die Marker-id gehört nicht in die API");
    }

    @Test
    void teamChatUsesTheHighestRoleAndIncludesPrivateTeams() {
        assertNull(w.teams.chatTeamOf(alice));
        String club = w.teams.create(bob, "Club", "CLB", null, null, null, "public", null);
        w.teams.invite(bob, club, alice);
        w.teams.acceptInvite(alice, club);
        String secret = w.teams.create(alice, "Geheim", "PST", null, null, null, "private", null);
        TeamStore.ChatTeam t = w.teams.chatTeamOf(alice);
        assertEquals(secret, t.id(), "Owner von Geheim > Mitglied im Club");
        assertEquals(1, t.members().size());
        assertEquals(club, w.teams.chatTeamOf(bob).id());
        assertEquals(2, w.teams.chatTeamOf(bob).members().size());
        String line = GameText.teamChat(w.teams.chatTeamOf(bob), "Bob", "Treffen am Spawn?").getString();
        assertEquals("[Team CLB] Bob: Treffen am Spawn?", line);
    }

    @Test
    void directionMatchesTheWebsite() {
        // dieselben Fälle wie tests/web/coords.test.mjs
        assertEquals("Norden", GameText.direction(0, 0, 0, -10));
        assertEquals("Osten", GameText.direction(0, 0, 10, 0));
        assertEquals("Südwesten", GameText.direction(0, 0, -10, 10));
        assertEquals("hier", GameText.direction(5, 5, 5, 5));
    }

    @Test
    void newConfigSwitchesDefaultToOnAndCanBeTurnedOff() {
        PlannerConfig d = PlannerConfig.defaults();
        assertTrue(d.gameNotifications() && d.landEnterMessages() && d.deathMarkers() && d.teamChat());
        PlannerConfig off = TestSupport.config("gameNotifications", false, "teamChat", false);
        assertFalse(off.gameNotifications());
        assertFalse(off.teamChat());
        assertTrue(off.toJson().has("landEnterMessages"), "neue Schalter erscheinen in der Config-Datei");
    }
}
