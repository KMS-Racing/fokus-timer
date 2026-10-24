package dev.worldplanner;

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

/** Regeln für Länder: zusammenhängend, keine Überschneidung, Limits, gleichberechtigte Besitzer. */
class LandStoreTest {
    @TempDir
    Path dir;
    TestSupport.World w;
    UUID alice, bob, admin;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config("maxChunksPerPlayer", 5));
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        admin = w.admin(3, "Chef");
        w.discover(-10, -10, 10, 10);
    }

    private int status(Runnable r) {
        return assertThrows(PlannerException.class, r::run).status;
    }

    @Test
    void createWorksWithNegativeCoordinates() {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(-1, -1, 0, -1));
        assertEquals(2, w.lands.ownedChunkCount(alice));
        assertEquals("Base", w.lands.landNameAt(OVERWORLD, Chunks.key(-1, -1)).orElseThrow());
        assertTrue(w.lands.findIdByName("BASE").isPresent(), "Namen sind ohne Groß/klein eindeutig");
        assertEquals(id, w.lands.findIdByName("base").orElseThrow());
    }

    @Test
    void rejectsOverlapDisconnectedUndiscoveredAndBadNames() {
        w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        assertEquals(409, status(() -> w.lands.create(bob, false, "Klau", OVERWORLD, chunks(0, 0))), "Überschneidung");
        assertEquals(400, status(() -> w.lands.create(bob, false, "Insel", OVERWORLD, chunks(3, 3, 5, 5))), "nicht zusammenhängend");
        assertEquals(400, status(() -> w.lands.create(bob, false, "Weit", OVERWORLD, chunks(50, 50))), "unentdeckt");
        assertEquals(409, status(() -> w.lands.create(bob, false, "base", OVERWORLD, chunks(4, 4))), "Name schon vergeben");
        assertEquals(400, status(() -> w.lands.create(bob, false, "<b>", OVERWORLD, chunks(4, 4))), "HTML im Namen");
        assertEquals(400, status(() -> w.lands.create(bob, false, "X", OVERWORLD, chunks(4, 4))), "Name zu kurz");
        assertEquals(400, status(() -> w.lands.create(bob, false, "Gut", "minecraft:fake", chunks(4, 4))), "unbekannte Dimension");
        assertEquals(400, status(() -> w.lands.create(bob, false, "Leer", OVERWORLD, new long[0])), "keine Chunks");
    }

    @Test
    void chunkLimitAppliesButNotToAdmins() {
        assertEquals(400, status(() -> w.lands.create(alice, false, "Groß", OVERWORLD, chunks(0, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0))));
        w.lands.create(admin, true, "Admin-Land", OVERWORLD, chunks(0, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0));
        assertEquals(6, w.lands.ownedChunkCount(admin));
    }

    @Test
    void addChunksMustTouchTheLand() {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        assertEquals(400, status(() -> w.lands.addChunks(alice, false, id, chunks(2, 0))));
        assertEquals(1, w.lands.addChunks(alice, false, id, chunks(1, 0)));
        assertEquals(403, status(() -> w.lands.addChunks(bob, false, id, chunks(-1, 0))), "Fremde dürfen nicht erweitern");
    }

    @Test
    void equalOwnersNobodyCanKickOthers() {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        w.lands.invite(alice, id, bob);
        assertEquals(409, status(() -> w.lands.invite(alice, id, bob)), "doppelte Einladung");
        w.lands.accept(bob, false, id);
        assertEquals(1, w.lands.ownedChunkCount(bob));
        // Bob darf jetzt alles, was ein Besitzer darf …
        w.lands.addChunks(bob, false, id, chunks(1, 0));
        // … aber niemand kann den anderen rauswerfen – das geht nur über Admin-Methoden
        assertFalse(w.lands.leave(bob, id), "Alice bleibt, Land bleibt");
        assertEquals(403, status(() -> w.lands.leave(bob, id)));
        assertTrue(w.lands.leave(alice, id), "letzter Besitzer -> Land frei");
        assertTrue(w.lands.findIdByName("Base").isEmpty());
        assertTrue(w.lands.landNameAt(OVERWORLD, Chunks.key(0, 0)).isEmpty(), "Chunks sind wieder frei");
    }

    @Test
    void acceptRespectsChunkLimit() {
        String big = w.lands.create(alice, false, "Groß", OVERWORLD, chunks(0, 0, 1, 0, 2, 0, 3, 0));
        w.lands.create(bob, false, "Bobs", OVERWORLD, chunks(0, 5, 1, 5));
        w.lands.invite(alice, big, bob);
        assertEquals(400, status(() -> w.lands.accept(bob, false, big)), "2 + 4 > 5");
    }

    @Test
    void declineAndUninviteAndNoInvitation() {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        assertEquals(404, status(() -> w.lands.accept(bob, false, id)), "ohne Einladung");
        w.lands.invite(alice, id, bob);
        w.lands.decline(bob, id);
        assertEquals(404, status(() -> w.lands.decline(bob, id)));
        w.lands.invite(alice, id, bob);
        w.lands.uninvite(alice, id, bob);
        assertEquals(404, status(() -> w.lands.accept(bob, false, id)));
    }

    @Test
    void removingLastChunkDeletesLand() {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0, 1, 0));
        assertFalse(w.lands.removeChunks(alice, false, id, chunks(1, 0)));
        assertEquals(400, status(() -> w.lands.removeChunks(alice, false, id, chunks(5, 5))), "gehört nicht zum Land");
        assertTrue(w.lands.removeChunks(alice, false, id, chunks(0, 0)));
        assertEquals(404, status(() -> w.lands.summary(id)));
    }

    @Test
    void adminCanDeleteAndRemoveOwners() {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        w.lands.invite(alice, id, bob);
        w.lands.accept(bob, false, id);
        assertFalse(w.lands.adminRemoveOwner(id, bob));
        assertTrue(w.lands.adminRemoveOwner(id, alice), "keine Besitzer mehr -> gelöscht");
        String id2 = w.lands.create(alice, false, "Zwei", OVERWORLD, chunks(3, 3));
        w.lands.adminDelete(id2);
        assertTrue(w.lands.findIdByName("Zwei").isEmpty());
    }

    @Test
    void editValidatesColorAndDescription() {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        assertEquals(400, status(() -> w.lands.edit(alice, false, id, null, "red", null)));
        assertEquals(400, status(() -> w.lands.edit(alice, false, id, null, null, "x".repeat(201))));
        assertEquals(403, status(() -> w.lands.edit(bob, false, id, "Meins", null, null)));
        w.lands.edit(alice, false, id, "Neuer Name", "#AABBCC", "Beschreibung");
        assertTrue(w.lands.findIdByName("neuer name").isPresent());
    }

    @Test
    void invitesAreOnlyVisibleToOwners() {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(0, 0));
        w.lands.invite(alice, id, bob);
        var forBob = w.lands.toApiJson(bob, false).get(0).getAsJsonObject();
        assertTrue(forBob.get("invitedMe").getAsBoolean());
        assertFalse(forBob.has("invites"));
        var forAlice = w.lands.toApiJson(alice, false).get(0).getAsJsonObject();
        assertEquals(1, forAlice.getAsJsonArray("invites").size());
    }

    @Test
    void survivesRestart() throws Exception {
        String id = w.lands.create(alice, false, "Base", OVERWORLD, chunks(-3, -3, -2, -3));
        w.lands.invite(alice, id, bob);
        TestSupport.World after = w.restart();
        assertEquals(id, after.lands.findIdByName("Base").orElseThrow());
        assertEquals(2, after.lands.ownedChunkCount(alice));
        assertEquals(1, after.lands.namesInviting(bob).size());
        assertEquals(409, assertThrows(PlannerException.class,
                () -> after.lands.create(bob, false, "Klau", OVERWORLD, chunks(-3, -3))).status, "Index nach Neustart korrekt");
    }
}
