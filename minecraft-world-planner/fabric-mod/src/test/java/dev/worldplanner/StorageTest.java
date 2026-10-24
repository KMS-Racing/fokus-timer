package dev.worldplanner;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static dev.worldplanner.TestSupport.OVERWORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Speichern: kaputte Dateien werden gesichert statt überschrieben, Einstellungen werden ergänzt. */
class StorageTest {
    @TempDir
    Path dir;

    @Test
    void brokenDataFileIsBackedUpNotOverwritten() throws Exception {
        Path file = dir.resolve("lands.json");
        Files.writeString(file, "{ das ist kein json");
        TestSupport.World w = new TestSupport.World(dir, TestSupport.config());
        assertFalse(Files.exists(file), "kaputte Datei wurde umbenannt");
        try (Stream<Path> files = Files.list(dir)) {
            Path backup = files.filter(p -> p.getFileName().toString().startsWith("lands.json.kaputt-")).findFirst().orElseThrow();
            assertEquals("{ das ist kein json", Files.readString(backup), "Inhalt der Sicherung ist unverändert");
        }
        assertTrue(w.lands.allNames().isEmpty());
    }

    @Test
    void writeIsAtomicAndLeavesNoTempFile() throws Exception {
        Path file = dir.resolve("x.json");
        JsonObject o = new JsonObject();
        o.addProperty("a", 1);
        JsonFiles.write(file, o, true);
        JsonFiles.write(file, o, false);
        assertEquals(1, JsonFiles.read(file).getAsJsonObject().get("a").getAsInt());
        assertFalse(Files.exists(dir.resolve("x.json.tmp")));
    }

    @Test
    void discoveryCountsEachChunkOnceAndSurvivesRestart() throws Exception {
        TestSupport.World w = new TestSupport.World(dir, TestSupport.config());
        w.discovery.markArea(OVERWORLD, -1, -1, 1); // 3x3
        w.discovery.markArea(OVERWORLD, 0, 0, 1); // überlappt: nur 7 neue? nein – 3x3 um 0/0 überlappt 4 Chunks
        assertEquals(9 + 5, w.discovery.count(OVERWORLD));
        TestSupport.World after = w.restart();
        assertEquals(14, after.discovery.count(OVERWORLD));
        assertTrue(after.discovery.isDiscovered(OVERWORLD, Chunks.key(-2, -2)));
        assertFalse(after.discovery.isDiscovered(OVERWORLD, Chunks.key(-3, -3)));
    }

    @Test
    void configFileGetsMissingKeysAndBrokenConfigIsLeftAlone() throws Exception {
        Path file = dir.resolve("world-planner.json");
        Files.writeString(file, "{\"port\": 9999, \"maxChunksPerPlayer\": \"viele\"}");
        PlannerConfig c = PlannerConfig.load(file, TestSupport.LOG);
        assertEquals(9999, c.port());
        assertEquals(64, c.maxChunksPerPlayer(), "ungültiger Wert -> Standard");
        assertTrue(Files.readString(file).contains("\"tradeUnit\""), "neue Optionen werden ergänzt");

        Files.writeString(file, "{ kaputt");
        PlannerConfig broken = PlannerConfig.load(file, TestSupport.LOG);
        assertEquals("127.0.0.1", broken.bind(), "sichere Standardwerte");
        assertEquals("{ kaputt", Files.readString(file), "kaputte Config wird NICHT überschrieben");
    }

    @Test
    void configClampsDangerousValues() {
        PlannerConfig c = TestSupport.config("port", 70000, "sessionDays", 0, "discoverRadiusChunks", 999, "tradeUnit", "x".repeat(50));
        assertEquals(65535, c.port());
        assertEquals(1, c.sessionDays());
        assertEquals(32, c.discoverRadiusChunks());
        assertEquals("Diamanten", c.tradeUnit());
    }

    @Test
    void cspOriginOnlyAcceptsHttpUrlsWithCleanHosts() {
        assertEquals("https://karte.example.de:8443", ApiServer.origin("https://karte.example.de:8443/pfad?x=1").orElseThrow());
        assertEquals("http://127.0.0.1:8100", ApiServer.origin("http://127.0.0.1:8100/").orElseThrow());
        assertTrue(ApiServer.origin("javascript:alert(1)").isEmpty());
        assertTrue(ApiServer.origin("ftp://x.de").isEmpty());
        assertTrue(ApiServer.origin("").isEmpty());
        assertTrue(ApiServer.origin("http://a b/").isEmpty());
        assertTrue(ApiServer.origin("http://x.de; script-src *").isEmpty(), "kein Einschleusen in die CSP");
    }
}
