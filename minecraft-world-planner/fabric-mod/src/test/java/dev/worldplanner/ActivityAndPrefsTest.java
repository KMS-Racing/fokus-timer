package dev.worldplanner;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Aktivitäts-Übersicht (wer sieht was) und Favoriten. */
class ActivityAndPrefsTest {
    @TempDir
    Path dir;

    @Test
    void teamEventsOnlyForMembersNewestFirstAndLimited() throws Exception {
        TestSupport.World w = new TestSupport.World(dir, TestSupport.config("activityLimit", 50));
        w.activity.add(null, "land.created", "öffentlich 1", "land", "a", "all");
        w.activity.add(null, "team.role", "nur Team X", "team", "x", "team:x");
        w.activity.add(null, "land.created", "öffentlich 2", "land", "b", "all");
        JsonArray outsider = w.activity.visibleTo(team -> false, 10);
        assertEquals(2, outsider.size());
        assertEquals("öffentlich 2", outsider.get(0).getAsJsonObject().get("text").getAsString(), "neueste zuerst");
        JsonArray member = w.activity.visibleTo(team -> team.equals("x"), 10);
        assertEquals(3, member.size());
        assertEquals(1, w.activity.visibleTo(team -> true, 1).size(), "max wird eingehalten");

        for (int i = 0; i < 60; i++) w.activity.add(null, "x", "Ereignis " + i, null, null, "all");
        assertEquals(50, w.activity.visibleTo(team -> true, 1000).size(), "nur die letzten activityLimit Einträge");
        TestSupport.World again = w.restart();
        assertEquals("Ereignis 59", again.activity.visibleTo(team -> true, 1).get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void favoritesArePerPlayerAndValidated() throws Exception {
        TestSupport.World w = new TestSupport.World(dir, TestSupport.config());
        UUID alice = w.player(1, "Alice");
        UUID bob = w.player(2, "Bob");
        w.prefs.setFavorite(alice, "lands", "land-1", true);
        w.prefs.setFavorite(alice, "lands", "land-1", true);
        assertEquals(1, w.prefs.get(alice).getAsJsonObject("favorites").getAsJsonArray("lands").size(), "kein Duplikat");
        assertEquals(0, w.prefs.get(bob).getAsJsonObject("favorites").getAsJsonArray("lands").size(), "jeder hat eigene");
        assertEquals(400, assertThrows(PlannerException.class, () -> w.prefs.setFavorite(alice, "dateien", "x", true)).status);
        assertEquals(400, assertThrows(PlannerException.class, () -> w.prefs.setFavorite(alice, "lands", "../etc", true)).status);
        TestSupport.World again = w.restart();
        assertEquals("land-1", again.prefs.get(alice).getAsJsonObject("favorites").getAsJsonArray("lands").get(0).getAsString());
        again.prefs.setFavorite(alice, "lands", "land-1", false);
        assertEquals(0, again.prefs.get(alice).getAsJsonObject("favorites").getAsJsonArray("lands").size());
    }
}
