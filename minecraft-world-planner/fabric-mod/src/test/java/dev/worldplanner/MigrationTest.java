package dev.worldplanner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Daten aus v0.2 (Schema 1) werden ohne Verlust übernommen, vorher wird die alte Datei gesichert. */
class MigrationTest {
    @TempDir
    Path dir;

    @Test
    void version02FilesAreKeptAndBackedUp() throws Exception {
        UUID alice = TestSupport.uuid(1);
        UUID bob = TestSupport.uuid(2);
        String lands = """
                {"schemaVersion": 1, "lands": [{"id": "land-1", "name": "Altstadt", "dimension": "minecraft:overworld",
                  "color": "#4caf50", "description": "aus v0.2", "createdAt": 1700000000000, "createdBy": "%s",
                  "owners": ["%s", "%s"], "invites": [], "chunks": [0, 0, 1, 0, -1, 0]}]}
                """.formatted(alice, alice, bob);
        String markers = """
                {"schemaVersion": 1, "markers": [
                  {"id": "m-1", "name": "Rathaus", "type": "city", "dimension": "minecraft:overworld", "x": 5, "y": 64, "z": 5,
                   "notes": "", "owner": "%s", "public": true, "createdAt": 1700000000000, "updatedAt": 1700000000000},
                  {"id": "m-2", "name": "Versteck", "type": "base", "dimension": "minecraft:overworld", "x": 9, "y": 30, "z": 9,
                   "notes": "", "owner": "%s", "public": false, "createdAt": 1700000000000, "updatedAt": 1700000000000}]}
                """.formatted(alice, alice);
        Files.writeString(dir.resolve("lands.json"), lands);
        Files.writeString(dir.resolve("markers.json"), markers);

        TestSupport.World w = new TestSupport.World(dir, TestSupport.config());
        assertTrue(Files.exists(dir.resolve("lands.json.v1.bak")), "Sicherung der alten Länder");
        assertTrue(Files.exists(dir.resolve("markers.json.v1.bak")), "Sicherung der alten Marker");
        assertEquals(lands, Files.readString(dir.resolve("lands.json.v1.bak")), "Sicherung ist unverändert");

        assertEquals(3, w.lands.ownedChunkCount(bob), "Mitbesitzer bleibt Mitbesitzer");
        assertEquals("aus v0.2", w.lands.toApiJson(alice, false).get(0).getAsJsonObject().get("description").getAsString());
        assertEquals(1, w.markers.publicMarkers().size(), "public=true → öffentlich");
        assertEquals(2, w.markers.countOwnedBy(alice), "auch der private Marker ist noch da");

        // Nach dem Speichern steht das neue Schema in der Datei, die Sicherung bleibt
        w.markers.create(alice, false, JsonParser.parseString("{\"name\": \"Neu\", \"x\": 50, \"z\": 50}").getAsJsonObject());
        w.save();
        JsonObject saved = JsonParser.parseString(Files.readString(dir.resolve("markers.json"))).getAsJsonObject();
        assertEquals(2, saved.get("schemaVersion").getAsInt());
        assertTrue(Files.exists(dir.resolve("markers.json.v1.bak")));

        // Ein zweiter Start überschreibt die Sicherung nicht und legt keine neue an
        new TestSupport.World(dir, TestSupport.config());
        assertEquals(lands, Files.readString(dir.resolve("lands.json.v1.bak")));
        assertFalse(Files.exists(dir.resolve("markers.json.v2.bak")));
    }
}
