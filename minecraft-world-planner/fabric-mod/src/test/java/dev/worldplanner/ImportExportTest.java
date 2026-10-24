package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/** Export/Import: Vorschau ändert nichts, es wird nur neu angelegt, Verknüpfungen werden gelöst. */
class ImportExportTest {
    @TempDir
    Path dir;
    TestSupport.World source, target;
    UUID alice, bob;

    @BeforeEach
    void setUp() {
        source = new TestSupport.World(dir.resolve("quelle"), TestSupport.config());
        target = new TestSupport.World(dir.resolve("ziel"), TestSupport.config());
        alice = source.player(1, "Alice");
        bob = target.player(2, "Bob");
        source.discover(-5, -5, 5, 5);
    }

    private JsonObject exportAlice() {
        String team = source.teams.create(alice, "Bauteam", "BAU", null, null, null, null, null);
        String land = source.lands.create(alice, false, "Alices Land", OVERWORLD, chunks(0, 0, 1, 0), "#123456", "Mein Zuhause");
        source.lands.edit(alice, false, land, null, null, null, "geheime Notiz");
        JsonObject m = new JsonObject();
        m.addProperty("name", "Teamlager");
        m.addProperty("x", 5);
        m.addProperty("z", 5);
        m.addProperty("visibility", "team");
        m.addProperty("teamId", team);
        source.markers.create(alice, false, m);
        JsonObject p = new JsonObject();
        p.addProperty("name", "Leuchtturm");
        JsonArray tasks = new JsonArray();
        tasks.add("Fundament");
        p.add("tasks", tasks);
        p.addProperty("teamId", team);
        p.addProperty("visibility", "team");
        source.projects.create(alice, false, p);
        source.prefs.setFavorite(alice, "lands", land, true);
        return new ImportExport(source.services).export(alice);
    }

    @Test
    void exportContainsOwnDataWithVersion() {
        JsonObject e = exportAlice();
        assertEquals(ImportExport.FORMAT, e.get("format").getAsString());
        assertEquals(ImportExport.VERSION, e.get("version").getAsInt());
        assertEquals(1, e.getAsJsonArray("lands").size());
        assertEquals("geheime Notiz", e.getAsJsonArray("lands").get(0).getAsJsonObject().get("notes").getAsString(), "eigene Notizen sind dabei");
        assertEquals(1, e.getAsJsonArray("markers").size());
        assertEquals(1, e.getAsJsonArray("projects").size());
        assertEquals(1, e.getAsJsonObject("favorites").getAsJsonArray("lands").size());
    }

    @Test
    void previewChangesNothingAndApplyCreatesAsTheImporter() {
        // Die Datei geht als Text durch – wie beim Herunterladen und Hochladen
        JsonObject file = JsonParser.parseString(exportAlice().toString()).getAsJsonObject();
        target.discover(-5, -5, 5, 5);
        ImportExport io = new ImportExport(target.services);
        long landVersion = target.lands.version();
        JsonObject preview = io.importData(bob, false, file, false);
        assertFalse(preview.get("applied").getAsBoolean());
        assertEquals(1, preview.getAsJsonObject("lands").get("create").getAsInt());
        assertEquals(landVersion, target.lands.version(), "Vorschau legt nichts an");
        assertEquals(0, target.markers.countOwnedBy(bob));

        JsonObject result = io.importData(bob, false, file, true);
        assertEquals(1, result.getAsJsonObject("lands").get("create").getAsInt());
        assertEquals(1, result.getAsJsonObject("markers").get("create").getAsInt());
        assertEquals(1, result.getAsJsonObject("projects").get("create").getAsInt());
        assertEquals(1, target.lands.ownedLandCount(bob), "angelegt als der importierende Spieler");
        JsonObject marker = target.markers.toApiJson(bob, false).get(0).getAsJsonObject();
        assertEquals("private", marker.get("visibility").getAsString(), "Team-Sichtbarkeit wird beim Import privat");
        assertFalse(marker.has("teamId"));
        JsonObject project = target.projects.toApiJson(bob, false).get(0).getAsJsonObject();
        assertEquals("private", project.get("visibility").getAsString());
        assertEquals(1, project.getAsJsonArray("tasks").size());

        // Zweiter Import: nichts doppelt
        JsonObject again = io.importData(bob, false, file, true);
        assertEquals(0, again.getAsJsonObject("lands").get("create").getAsInt());
        assertEquals("ein Land mit diesem Namen gibt es schon",
                again.getAsJsonObject("lands").getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals(0, again.getAsJsonObject("markers").get("create").getAsInt(), "Marker an derselben Stelle ist ein Duplikat");
    }

    @Test
    void landsInUndiscoveredOrTakenAreasAreSkipped() {
        JsonObject file = exportAlice();
        ImportExport io = new ImportExport(target.services);
        JsonObject undiscovered = io.importData(bob, false, file, true);
        assertEquals(0, undiscovered.getAsJsonObject("lands").get("create").getAsInt());
        assertTrue(undiscovered.getAsJsonObject("lands").getAsJsonArray("skipped").get(0).getAsJsonObject()
                .get("reason").getAsString().contains("unentdeckt"));
        target.discover(-5, -5, 5, 5);
        UUID carl = target.player(3, "Carl");
        target.lands.create(carl, false, "Carls Land", OVERWORLD, chunks(1, 0));
        JsonObject taken = io.importData(bob, false, file, true);
        assertTrue(taken.getAsJsonObject("lands").getAsJsonArray("skipped").get(0).getAsJsonObject()
                .get("reason").getAsString().contains("gehören schon"), "nie fremde Chunks überschreiben");
    }

    @Test
    void oldWebsiteMarkerFileIsAccepted() {
        JsonArray legacy = JsonParser.parseString("""
                [{"name": "Hauptstadt", "type": "City", "x": 100, "y": 70, "z": -50, "public": true},
                 {"name": "Kaputt", "x": "abc", "z": 1}]
                """).getAsJsonArray();
        JsonObject result = new ImportExport(target.services).importData(bob, false, legacy, true);
        assertEquals(1, result.getAsJsonObject("markers").get("create").getAsInt());
        assertEquals(1, result.getAsJsonObject("markers").getAsJsonArray("skipped").size(), "kaputter Eintrag wird übersprungen, nicht abgebrochen");
        JsonObject m = target.markers.toApiJson(bob, false).get(0).getAsJsonObject();
        assertEquals("city", m.get("type").getAsString());
        assertEquals("public", m.get("visibility").getAsString());
    }

    @Test
    void rejectsUnknownFormats() {
        ImportExport io = new ImportExport(target.services);
        assertEquals(400, assertThrows(PlannerException.class,
                () -> io.importData(bob, false, JsonParser.parseString("{\"format\": \"etwas-anderes\"}"), false)).status);
        assertEquals(400, assertThrows(PlannerException.class,
                () -> io.importData(bob, false, JsonParser.parseString("{\"format\": \"world-planner-export\", \"version\": 99}"), false)).status);
        assertEquals(400, assertThrows(PlannerException.class,
                () -> io.importData(bob, false, JsonParser.parseString("\"hallo\""), false)).status);
        JsonArray tooMany = new JsonArray();
        for (int i = 0; i < 501; i++) tooMany.add(new JsonObject());
        assertEquals(400, assertThrows(PlannerException.class, () -> io.importData(bob, false, tooMany, false)).status);
    }
}
