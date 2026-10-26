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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nachrichten im Spiel (ohne laufenden Server): Zusammenfassung nach dem Einloggen, jede Neuigkeit genau einmal,
 * Pause bei vielen Geboten, "/planer nachrichten aus" wird beachtet.
 */
class GameNotifierTest {
    @TempDir
    Path dir;
    TestSupport.World w;
    GameNotifier notifier;
    UUID alice, bob, carl;
    AtomicLong now = new AtomicLong(1_761_350_400_000L); // 25.10.2025

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config("tradeActionsPerMinute", 1000, "maxTeamsPerPlayer", 3));
        w.trades.clock = now::get;
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
        w.discover(-10, -10, 10, 10);
        notifier = new GameNotifier(w.services);
        notifier.clock = now::get;
    }

    /** Nächster Durchlauf des Timers, 2 Sekunden später. Gibt die Texte für einen Spieler zurück. */
    private List<String> poll(UUID player) {
        now.addAndGet(2000);
        return notifier.poll().stream().filter(o -> o.player().equals(player)).map(o -> o.message().getString()).toList();
    }

    private String land(String name, int x) {
        return w.lands.create(alice, false, name, TestSupport.OVERWORLD, TestSupport.chunks(x, 0));
    }

    @Test
    void summaryComesShortlyAfterJoiningAndOnlyIfSomethingIsOpen() {
        w.lands.invite(alice, land("Kims Base", 0), bob);
        notifier.joined(bob);
        notifier.joined(carl);
        now.addAndGet(1000);
        assertTrue(notifier.poll().isEmpty(), "nicht sofort – erst nach den Join-Meldungen");
        List<String> msg = poll(bob);
        assertEquals(1, msg.size());
        assertTrue(msg.getFirst().startsWith("[Planer] Zu erledigen (1):"), msg.getFirst());
        assertTrue(msg.getFirst().contains("Einladung: Land »Kims Base« mitbesitzen [Annehmen]"));
        assertTrue(msg.getFirst().contains("Ausschalten: /planer nachrichten aus"));
        assertFalse(msg.getFirst().contains("[Öffnen]"), "ohne publicUrl kein Link zur Webseite");
        assertTrue(poll(carl).isEmpty(), "nichts offen = keine Nachricht");
        assertTrue(poll(bob).isEmpty(), "die Zusammenfassung kommt nur einmal");
    }

    @Test
    void newThingsArriveLiveExactlyOnce() {
        notifier.joined(bob);
        poll(bob);
        poll(bob);
        String land = land("Hof", 1);
        w.lands.invite(alice, land, bob);
        List<String> msg = poll(bob);
        assertEquals(List.of("[Planer] ✉ Einladung: Land »Hof« mitbesitzen [Annehmen]"), msg);
        assertTrue(poll(bob).isEmpty(), "nicht wiederholen");
        w.lands.decline(bob, land);
        assertTrue(poll(bob).isEmpty(), "Erledigtes meldet sich nicht");
        w.lands.invite(alice, land, bob);
        assertEquals(1, poll(bob).size(), "eine neue Einladung ist wieder neu");
    }

    @Test
    void whatWasAlreadyThereAtJoinIsNotRepeatedLive() {
        String team = w.teams.create(alice, "Kart Racing", "KMS", null, null, null, "public", null);
        w.teams.invite(alice, team, bob);
        w.teams.acceptInvite(bob, team);
        notifier.joined(bob);
        poll(bob);
        poll(bob);
        assertTrue(poll(bob).isEmpty(), "die Mitgliedschaft von vorher ist keine Neuigkeit");
        String other = w.teams.create(carl, "Bauteam", "BAU", null, null, null, "public", null);
        w.teams.invite(carl, other, bob);
        w.teams.acceptInvite(bob, other);
        assertEquals(List.of("[Planer] • Du bist jetzt im Team [BAU] Bauteam"), poll(bob));
    }

    @Test
    void manyBidsArePausedButTheLatestStillArrives() {
        JsonObject d = new JsonObject();
        d.addProperty("kind", "auction");
        d.addProperty("title", "Elytra");
        d.addProperty("category", "armor");
        d.addProperty("itemName", "Elytra");
        d.addProperty("startPrice", 10);
        d.addProperty("durationHours", 2);
        String a = w.trades.create(alice, UUID.randomUUID().toString(), d).get("id").getAsString();
        notifier.joined(alice);
        poll(alice);
        poll(alice);
        w.trades.bid(bob, UUID.randomUUID().toString(), a, 10);
        assertEquals(List.of("[Planer] • Neues Gebot auf »Elytra«: 10 Diamanten von Bob"), poll(alice));
        w.trades.bid(carl, UUID.randomUUID().toString(), a, 12);
        assertTrue(poll(alice).isEmpty(), "zweites Gebot innerhalb von 15 s: erst einmal Pause");
        w.trades.bid(bob, UUID.randomUUID().toString(), a, 14);
        assertTrue(poll(alice).isEmpty());
        for (int i = 0; i < 5; i++) assertTrue(poll(alice).isEmpty()); // die Pause läuft (15 s seit dem ersten Gebot)
        List<String> later = poll(alice);
        assertEquals(List.of("[Planer] • Neues Gebot auf »Elytra«: 14 Diamanten von Bob"), later, "danach nur das neueste – kein Stau alter Gebote");
        assertTrue(poll(alice).isEmpty());
    }

    @Test
    void outbidIsNotPaused() {
        JsonObject d = new JsonObject();
        d.addProperty("kind", "auction");
        d.addProperty("title", "Netherite");
        d.addProperty("category", "ores");
        d.addProperty("itemName", "Netherite");
        d.addProperty("startPrice", 1);
        d.addProperty("durationHours", 2);
        String a = w.trades.create(alice, UUID.randomUUID().toString(), d).get("id").getAsString();
        notifier.joined(bob);
        poll(bob);
        poll(bob);
        w.trades.bid(bob, UUID.randomUUID().toString(), a, 1);
        w.trades.bid(carl, UUID.randomUUID().toString(), a, 2);
        assertEquals(List.of("[Planer] ⚠ Überboten: »Netherite« (jetzt 2 Diamanten)"), poll(bob));
        w.trades.bid(bob, UUID.randomUUID().toString(), a, 3);
        w.trades.bid(carl, UUID.randomUUID().toString(), a, 4);
        assertEquals(List.of("[Planer] ⚠ Überboten: »Netherite« (jetzt 4 Diamanten)"), poll(bob),
                "wer überboten wird, soll es sofort wissen – sonst verpasst er die Auktion");
    }

    @Test
    void switchedOffMeansNothingAndNoFloodWhenSwitchedOnAgain() {
        w.prefs.setGame(bob, "notify", false);
        w.lands.invite(alice, land("Burg", 2), bob);
        notifier.joined(bob);
        poll(bob);
        assertTrue(poll(bob).isEmpty(), "keine Zusammenfassung");
        w.lands.invite(alice, land("Mühle", 4), bob);
        assertTrue(poll(bob).isEmpty(), "keine Live-Nachricht");
        w.prefs.setGame(bob, "notify", true);
        assertTrue(poll(bob).isEmpty(), "beim Wiedereinschalten keine Nachholung");
        w.lands.invite(alice, land("Turm", 6), bob);
        assertEquals(1, poll(bob).size(), "Neues danach kommt wieder");
    }

    @Test
    void manyNewThingsAtOnceAreShortened() {
        notifier.joined(bob);
        poll(bob);
        poll(bob);
        for (int i = 0; i < 5; i++) w.lands.invite(alice, land("Land" + i, i * 2), bob);
        List<String> msg = poll(bob);
        assertEquals(4, msg.size(), "drei einzeln, dann ein Hinweis");
        assertEquals("[Planer] … und 2 weitere Hinweise – /planer zeigt, was zu erledigen ist.", msg.get(3));
    }

    @Test
    void longSummaryIsCutAndLinksNeedAPublicUrl() {
        TestSupport.World web = new TestSupport.World(dir.resolve("web"), TestSupport.config("publicUrl", "https://planer.example/"));
        UUID owner = web.player(1, "Alice"), guest = web.player(2, "Bob");
        web.discover(-20, -20, 20, 20);
        for (int i = 0; i < 8; i++) web.lands.invite(owner, web.lands.create(owner, false, "Land" + i, TestSupport.OVERWORLD, TestSupport.chunks(i * 2, 0)), guest);
        GameNotifier n = new GameNotifier(web.services);
        n.clock = now::get;
        n.joined(guest);
        now.addAndGet(5000);
        String summary = n.poll().getFirst().message().getString();
        assertTrue(summary.startsWith("[Planer] Zu erledigen (8):"));
        assertEquals(GameText.SUMMARY_LINES, summary.split("\n").length - 3, "Kopfzeile, 6 Punkte, „… und 2 weitere“, Ausschalten");
        assertTrue(summary.contains("… und 2 weitere [Alle auf der Webseite]"));
        assertTrue(summary.contains("[Öffnen]"), "mit publicUrl gibt es Links");

        // /planer zeigt mehr; ohne Webseiten-Link steht dabei, wo der Rest zu finden ist
        List<Notices.Notice> many = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) many.add(new Notices.Notice("t" + i, true, true, Notices.Level.INFO, "Punkt " + i, "#/dashboard", null, null));
        String overview = GameText.summary(many, "", false).getString();
        assertEquals(1 + GameText.OVERVIEW_LINES + 1, overview.split("\n").length, overview);
        assertTrue(overview.endsWith("… und 3 weitere – alle stehen auf der Webseite im Dashboard"), overview);
        assertTrue(GameText.summary(many, "", true).getString().contains("… und 9 weitere – /planer zeigt mehr"), "beim Einloggen der Hinweis auf /planer");
    }

    @Test
    void playersWhoLeftGetNothing() {
        notifier.joined(bob);
        poll(bob);
        notifier.left(bob);
        w.lands.invite(alice, land("Hafen", 3), bob);
        assertTrue(poll(bob).isEmpty());
    }

    @Test
    void eventsAskOnceAndRemindTenMinutesBefore() {
        notifier.joined(bob);
        notifier.joined(carl);
        poll(bob);
        poll(bob);
        w.prefs.setGame(carl, "notify", false);
        JsonObject data = new JsonObject();
        data.addProperty("title", "Bauabend");
        data.addProperty("start", now.get() + 3_600_000L);
        String id = w.services.events().create(alice, false, data, now.get());
        List<String> ask = poll(bob);
        assertEquals(1, ask.size(), ask.toString());
        assertTrue(ask.getFirst().startsWith("[Planer] ✉ Event: »Bauabend« am ") && ask.getFirst().endsWith("– kommst du? [Komme]"), ask.getFirst());
        assertTrue(poll(bob).isEmpty(), "nur einmal");
        assertTrue(poll(carl).isEmpty(), "Nachrichten aus = keine Einladung");
        w.services.events().answer(bob, id, "yes", now.get());
        assertTrue(poll(bob).isEmpty(), "beantwortet: nichts Neues");
        now.addAndGet(50 * 60_000L);
        assertEquals(List.of("[Planer] ⚠ In 10 Minuten: »Bauabend«"), poll(bob), "Erinnerung (ohne Treffpunkt kein Knopf)");
        assertTrue(poll(bob).isEmpty(), "die Erinnerung kommt nur einmal");
        now.addAndGet(10 * 60_000L);
        assertEquals(List.of("[Planer] • Jetzt: »Bauabend« beginnt"), poll(bob));
        assertTrue(poll(carl).isEmpty(), "Carl hat nicht zugesagt (und Nachrichten aus)");
    }

}
