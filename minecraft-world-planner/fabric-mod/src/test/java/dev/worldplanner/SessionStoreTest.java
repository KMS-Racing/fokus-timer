package dev.worldplanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Login per Einmal-Code. */
class SessionStoreTest {
    @TempDir
    Path dir;

    @Test
    void codeWorksOnceAndIsCaseAndDashInsensitive() {
        SessionStore s = new SessionStore(dir.resolve("sessions.json"), TestSupport.LOG);
        UUID alice = TestSupport.uuid(1);
        String code = s.createCode(alice);
        assertTrue(code.matches("[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}"), code);
        var login = s.redeem(code.toLowerCase().replace("-", " "), 30, "1.1.1.1");
        assertEquals(alice, login.uuid());
        assertEquals(alice, s.authenticate(login.token()).orElseThrow());
        assertEquals(401, assertThrows(PlannerException.class, () -> s.redeem(code, 30, "1.1.1.1")).status, "Code nur einmal");
    }

    @Test
    void newCodeInvalidatesOldCode() {
        SessionStore s = new SessionStore(dir.resolve("sessions.json"), TestSupport.LOG);
        UUID alice = TestSupport.uuid(1);
        String first = s.createCode(alice);
        String second = s.createCode(alice);
        assertThrows(PlannerException.class, () -> s.redeem(first, 30, "1.1.1.1"));
        s.redeem(second, 30, "1.1.1.1");
    }

    @Test
    void logoutAndLogoutAll() {
        SessionStore s = new SessionStore(dir.resolve("sessions.json"), TestSupport.LOG);
        UUID alice = TestSupport.uuid(1);
        String t1 = s.redeem(s.createCode(alice), 30, "1.1.1.1").token();
        String t2 = s.redeem(s.createCode(alice), 30, "1.1.1.1").token();
        s.logout(t1);
        assertTrue(s.authenticate(t1).isEmpty());
        assertTrue(s.authenticate(t2).isPresent());
        assertEquals(1, s.logoutAll(alice));
        assertTrue(s.authenticate(t2).isEmpty());
        assertTrue(s.authenticate(null).isEmpty());
        assertTrue(s.authenticate("").isEmpty());
    }

    @Test
    void bruteForceIsSlowedDownPerClientWithoutLockingOutOthers() {
        SessionStore s = new SessionStore(dir.resolve("sessions.json"), TestSupport.LOG);
        for (int i = 0; i < 30; i++) assertEquals(401, assertThrows(PlannerException.class, () -> s.redeem("AAAA-AAAA", 30, "6.6.6.6")).status);
        assertEquals(429, assertThrows(PlannerException.class, () -> s.redeem("AAAA-AAAA", 30, "6.6.6.6")).status);
        String code = s.createCode(TestSupport.uuid(1));
        assertEquals(429, assertThrows(PlannerException.class, () -> s.redeem(code, 30, "6.6.6.6")).status, "der Angreifer wartet eine Minute");
        assertEquals(TestSupport.uuid(1), s.redeem(code, 30, "7.7.7.7").uuid(), "andere Spieler können sich trotzdem einloggen");
    }

    @Test
    void globalSafetyNetStopsDistributedGuessing() {
        SessionStore s = new SessionStore(dir.resolve("sessions.json"), TestSupport.LOG);
        for (int i = 0; i < 300; i++) {
            String client = "10.0." + (i / 20) + "." + (i % 20); // viele verschiedene Absender, je unter 30
            assertThrows(PlannerException.class, () -> s.redeem("AAAA-AAAA", 30, client));
        }
        assertEquals(429, assertThrows(PlannerException.class, () -> s.redeem("AAAA-AAAA", 30, "9.9.9.9")).status);
    }

    @Test
    void onlyHashesAreStoredAndSessionsSurviveRestart() throws Exception {
        Path file = dir.resolve("sessions.json");
        SessionStore s = new SessionStore(file, TestSupport.LOG);
        UUID alice = TestSupport.uuid(1);
        String token = s.redeem(s.createCode(alice), 30, "1.1.1.1").token();
        JsonFiles.write(file, s.takeDirtySnapshot(), false);
        assertFalse(Files.readString(file).contains(token), "Das Token selbst steht nie in der Datei");
        SessionStore after = new SessionStore(file, TestSupport.LOG);
        assertEquals(alice, after.authenticate(token).orElseThrow());
    }
}
