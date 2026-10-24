package dev.worldplanner;

import com.google.gson.JsonArray;
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

/** Bauprojekte: Sichtbarkeit, Rechte, Aufgaben/Fortschritt, Verknüpfungen, keine halben Änderungen. */
class ProjectStoreTest {
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

    private static JsonObject data(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number n) o.addProperty((String) kv[i], n);
            else if (v instanceof Boolean b) o.addProperty((String) kv[i], b);
            else if (v instanceof JsonArray a) o.add((String) kv[i], a);
            else if (v instanceof JsonObject j) o.add((String) kv[i], j);
            else o.addProperty((String) kv[i], v == null ? null : v.toString());
        }
        return o;
    }

    private static JsonArray list(String... items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return a;
    }

    @Test
    void createWithTasksAndProgress() {
        String id = w.projects.create(alice, false, data("name", "Burg", "tasks", list("Fundament", "Mauern", "Dach")));
        JsonObject p = w.projects.toApiJson(alice, false, id);
        assertEquals(0, p.getAsJsonObject("progress").get("done").getAsInt());
        assertEquals(3, p.getAsJsonObject("progress").get("total").getAsInt());
        String task = p.getAsJsonArray("tasks").get(0).getAsJsonObject().get("id").getAsString();
        w.projects.editTask(alice, false, id, task, null, true);
        p = w.projects.toApiJson(alice, false, id);
        assertEquals(1, p.getAsJsonObject("progress").get("done").getAsInt());
        assertEquals("Alice", p.getAsJsonArray("tasks").get(0).getAsJsonObject().getAsJsonObject("doneBy").get("name").getAsString());
        assertEquals("planned", p.get("status").getAsString(), "Standard-Status");
    }

    @Test
    void validation() {
        assertEquals(400, status(() -> w.projects.create(alice, false, data("name", "X"))), "Name zu kurz");
        assertEquals(400, status(() -> w.projects.create(alice, false, data("name", "Burg", "status", "fertig"))), "unbekannter Status");
        assertEquals(400, status(() -> w.projects.create(alice, false, data("name", "Burg", "dueDate", "2026-13-40"))), "kaputtes Datum");
        assertEquals(400, status(() -> w.projects.create(alice, false,
                data("name", "Burg", "startDate", "2026-05-10", "dueDate", "2026-05-01"))), "Ziel vor Start");
        assertEquals(400, status(() -> w.projects.create(alice, false, data("name", "Burg", "visibility", "team"))), "Nur-Team ohne Team");
        assertEquals(400, status(() -> w.projects.create(alice, false, data("name", "Burg", "location", data("x", 1e9, "z", 0)))), "Koordinate außerhalb der Welt");
        assertEquals(400, status(() -> w.projects.create(alice, false, data("name", "Burg", "tasks", "keine Liste"))), "Aufgaben müssen eine Liste sein");
        assertEquals(404, status(() -> w.projects.create(alice, false, data("name", "Burg", "responsible", list("Niemand")))), "unbekannter Spieler");
    }

    @Test
    void privateProjectsDoNotExistForOthers() {
        String id = w.projects.create(alice, false, data("name", "Geheimbasis", "visibility", "private", "responsible", list("Bob")));
        assertEquals(404, status(() -> w.projects.toApiJson(carl, false, id)));
        assertEquals(404, status(() -> w.projects.update(carl, false, id, data("name", "Gehackt"))), "404 statt 403: verrät nichts");
        assertEquals(0, w.projects.toApiJson(carl, false).size());
        assertTrue(w.projects.toApiJson(bob, false, id).get("mine").getAsBoolean(), "Verantwortliche sehen es");
        assertEquals(404, status(() -> w.projects.delete(op, true, id)), "Server-Admins sehen private Projekte nicht");
    }

    @Test
    void editAndDeleteRights() {
        String id = w.projects.create(alice, false, data("name", "Brücke", "responsible", list("Bob")));
        w.projects.update(bob, false, id, data("status", "in_progress"));
        assertEquals(403, status(() -> w.projects.update(carl, false, id, data("status", "done"))), "Fremde dürfen nur ansehen");
        assertEquals(403, status(() -> w.projects.addTask(carl, false, id, "Hack")));
        assertEquals(403, status(() -> w.projects.delete(bob, false, id)), "Löschen nur durch den Ersteller");
        w.projects.update(op, true, id, data("notes", "Moderiert"));
        w.projects.delete(alice, false, id);
        assertEquals(404, status(() -> w.projects.toApiJson(alice, false, id)));
    }

    @Test
    void failedUpdateChangesNothing() {
        String id = w.projects.create(alice, false, data("name", "Burg", "description", "alt"));
        assertEquals(400, status(() -> w.projects.update(alice, false, id, data("name", "Neue Burg", "description", "neu", "status", "kaputt"))));
        JsonObject p = w.projects.toApiJson(alice, false, id);
        assertEquals("Burg", p.get("name").getAsString(), "Name darf nicht halb geändert werden");
        assertEquals("alt", p.get("description").getAsString());
    }

    @Test
    void teamProjects() {
        String team = w.teams.create(alice, "Bauteam", "BAU", null, null, null, null, null);
        w.teams.invite(alice, team, bob);
        w.teams.acceptInvite(bob, team);
        assertEquals(403, status(() -> w.projects.create(carl, false, data("name", "Fremd", "teamId", team))), "nur Teammitglieder");
        assertEquals(400, status(() -> w.projects.create(alice, false,
                data("name", "Turm", "teamId", team, "responsible", list("Carl")))), "Verantwortliche müssen im Team sein");
        String id = w.projects.create(alice, false, data("name", "Turm", "teamId", team, "visibility", "team"));
        w.projects.addTask(bob, false, id, "Steine sammeln");
        assertEquals(404, status(() -> w.projects.toApiJson(carl, false, id)), "Nur-Team-Projekt ist für Fremde unsichtbar");

        // Anwärter dürfen ansehen, aber nicht bearbeiten
        w.teams.setRole(alice, team, bob, TeamStore.Role.RECRUIT);
        assertFalse(w.projects.toApiJson(bob, false, id).getAsJsonObject("can").get("edit").getAsBoolean());
        assertEquals(403, status(() -> w.projects.addTask(bob, false, id, "noch was")));

        // Team-Admins dürfen Team-Projekte löschen
        w.teams.setRole(alice, team, bob, TeamStore.Role.ADMIN);
        w.projects.delete(bob, false, id);
    }

    @Test
    void landLinksAndCleanup() {
        String own = w.lands.create(alice, false, "Alices Land", OVERWORLD, chunks(0, 0));
        String foreign = w.lands.create(bob, false, "Bobs Land", OVERWORLD, chunks(3, 3));
        assertEquals(403, status(() -> w.projects.create(alice, false, data("name", "Klau", "landId", foreign))), "nur eigene Länder verknüpfen");
        String id = w.projects.create(alice, false, data("name", "Garten", "landId", own));
        assertEquals(own, w.projects.toApiJson(alice, false, id).get("landId").getAsString());
        w.lands.delete(alice, false, own);
        w.services.onLandDeleted(own);
        JsonObject p = w.projects.toApiJson(alice, false, id);
        assertFalse(p.has("landId"), "Projekt bleibt, Verknüpfung weg");
        assertEquals("Garten", p.get("name").getAsString());
    }

    @Test
    void projectLimit() {
        w = new TestSupport.World(dir.resolve("klein"), TestSupport.config("maxProjectsPerPlayer", 2));
        UUID a = w.player(1, "Alice");
        UUID boss = w.admin(2, "Chef");
        w.projects.create(a, false, data("name", "Eins"));
        w.projects.create(a, false, data("name", "Zwei"));
        assertEquals(400, status(() -> w.projects.create(a, false, data("name", "Drei"))));
        for (int i = 0; i < 3; i++) w.projects.create(boss, true, data("name", "Admin " + i));
    }

    @Test
    void projectsSurviveRestartWithTasksAndUpdates() throws Exception {
        String id = w.projects.create(alice, false, data("name", "Hafen", "priority", "high",
                "location", data("dimension", OVERWORLD, "x", -120, "z", 340), "tasks", list("Steg")));
        w.projects.addUpdate(alice, false, id, "Der Steg ist halb fertig");
        TestSupport.World again = w.restart();
        JsonObject p = again.projects.toApiJson(alice, false, id);
        assertEquals("high", p.get("priority").getAsString());
        assertEquals(-120, p.getAsJsonObject("location").get("x").getAsInt());
        assertEquals(1, p.getAsJsonArray("tasks").size());
        assertEquals("Der Steg ist halb fertig", p.getAsJsonArray("updates").get(0).getAsJsonObject().get("text").getAsString());
    }
}
