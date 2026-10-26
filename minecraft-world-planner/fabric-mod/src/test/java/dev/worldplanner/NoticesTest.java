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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hinweise ("Zu erledigen" und Neuigkeiten) kommen nur aus dem echten Zustand – dieselben Regeln wie das Dashboard,
 * und niemand erfährt dabei etwas, das er auf der Webseite nicht sehen dürfte.
 */
class NoticesTest {
    static final String TODAY = "2026-10-25";
    @TempDir
    Path dir;
    TestSupport.World w;
    UUID alice, bob, carl, dana, op;
    AtomicLong now = new AtomicLong(1_700_000_000_000L);

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config("tradeActionsPerMinute", 1000, "tradeUnit", "Diamanten"));
        w.trades.clock = now::get;
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
        dana = w.player(4, "Dana");
        op = w.admin(9, "Chef");
        w.discover(-10, -10, 10, 10);
    }

    private List<Notices.Notice> notices(UUID player) {
        return Notices.collect(w.services, player, w.players.isAdmin(player), TODAY);
    }

    private Notices.Notice find(UUID player, String idPrefix) {
        return notices(player).stream().filter(n -> n.id().startsWith(idPrefix)).findFirst().orElse(null);
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static JsonObject data(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number n) o.addProperty((String) kv[i], n);
            else o.addProperty((String) kv[i], v == null ? null : v.toString());
        }
        return o;
    }

    @Test
    void landInviteIsATodoWithAcceptCommand() {
        String land = w.lands.create(alice, false, "Kims Base", TestSupport.OVERWORLD, TestSupport.chunks(0, 0));
        assertNull(find(bob, "land-invite:"), "ohne Einladung nichts");
        w.lands.invite(alice, land, bob);
        Notices.Notice n = find(bob, "land-invite:");
        assertNotNull(n);
        assertTrue(n.todo() && n.live());
        assertEquals("Einladung: Land »Kims Base« mitbesitzen", n.text(), "derselbe Text wie auf dem Dashboard");
        assertEquals("/land annehmen \"Kims Base\"", n.command(), "Name mit Leerzeichen in Anführungszeichen");
        assertEquals("#/claims/" + land, n.href());
        assertNull(find(carl, "land-invite:"), "nur der Eingeladene");
        w.lands.accept(bob, false, land);
        assertNull(find(bob, "land-invite:"), "nach dem Annehmen erledigt");
    }

    @Test
    void teamInvitesTransfersAndApplications() {
        String team = w.teams.create(alice, "Kart Racing", "KMS", null, null, null, "public", "apply");
        w.teams.invite(alice, team, bob);
        Notices.Notice inv = find(bob, "team-invite:");
        assertNotNull(inv);
        assertEquals("Team-Einladung von Alice: [KMS] Kart Racing", inv.text());
        assertEquals("/planer team annehmen KMS", inv.command(), "im Spiel annehmen per Knopf (seit 0.6.2)");
        w.teams.acceptInvite(bob, team);
        assertNull(find(bob, "team-invite:"));
        Notices.Notice member = find(bob, "member:" + team);
        assertNotNull(member, "Mitgliedschaft als Neuigkeit");
        assertFalse(member.todo(), "…aber nicht zu erledigen");

        // Bewerbung: der Owner sieht sie, ein einfaches Mitglied nicht (wie auf der Webseite)
        w.teams.apply(carl, team, "Ich fahre gerne Kart");
        Notices.Notice apps = find(alice, "applications:" + team);
        assertNotNull(apps);
        assertEquals("1 offene Bewerbung bei [KMS]", apps.text());
        assertTrue(apps.todo());
        assertFalse(apps.live(), "die Zusammenfassung pro Team kommt nicht live – dafür die einzelne Bewerbung");
        Notices.Notice single = find(alice, "application:");
        assertNotNull(single);
        assertEquals("Neue Bewerbung von Carl bei [KMS]", single.text());
        assertNull(find(bob, "applications:"), "Mitglieder entscheiden nicht über Bewerbungen");
        w.teams.apply(dana, team, "Ich auch");
        assertEquals("2 offene Bewerbungen bei [KMS]", find(alice, "applications:" + team).text());

        w.teams.offerTransfer(alice, team, bob, "Kart Racing");
        Notices.Notice tr = find(bob, "transfer:" + team);
        assertNotNull(tr);
        assertEquals("Dir wird das Team [KMS] übergeben – annehmen oder ablehnen", tr.text());
        assertEquals("/planer team annehmen KMS", tr.command());
        assertEquals("[Übernehmen]", tr.commandLabel());
        assertNull(find(alice, "transfer:"), "nur der Empfänger muss etwas tun");
        w.teams.acceptTransfer(bob, team);
        assertNull(find(bob, "transfer:"));
    }

    @Test
    void outbidAndNewBidsKeepBiddersPrivate() {
        String a = w.trades.create(alice, rid(), data("kind", "auction", "title", "Diamantschwert", "category", "weapons",
                "itemName", "Diamantschwert", "startPrice", 10, "durationHours", 2)).get("id").getAsString();
        w.trades.bid(bob, rid(), a, 10);
        assertNull(find(bob, "outbid:"), "Höchstbietender ist nicht überboten");
        Notices.Notice first = find(alice, "bid:" + a);
        assertNotNull(first);
        assertEquals("Neues Gebot auf »Diamantschwert«: 10 Diamanten von Bob", first.text(), "die Verkäufer-Seite sieht den Bieter");
        w.trades.bid(carl, rid(), a, 1500);
        Notices.Notice outbid = find(bob, "outbid:" + a);
        assertNotNull(outbid);
        assertEquals("Überboten: »Diamantschwert« (jetzt 1.500 Diamanten)", outbid.text());
        assertFalse(outbid.text().contains("Carl"), "Bieter sehen nicht, wer sie überboten hat");
        assertNull(find(bob, "bid:"), "Bieter bekommen keine Verkäufer-Neuigkeiten");
        Notices.Notice second = find(alice, "bid:" + a);
        assertTrue(!second.id().equals(first.id()) && second.group().equals("listing:" + a), "neues Gebot = neue id, gleiche Gruppe");
        w.trades.bid(bob, rid(), a, 1600);
        assertNull(find(bob, "outbid:"), "wieder vorne = erledigt");
        assertNotNull(find(carl, "outbid:"));
    }

    @Test
    void handoverDisputeAndResolution() {
        String f = w.trades.create(alice, rid(), data("kind", "fixed", "title", "Elytra", "category", "armor", "itemName", "Elytra",
                "price", 20, "durationHours", 24)).get("id").getAsString();
        w.trades.buy(dana, rid(), f);
        assertEquals("»Elytra« ist an Dana verkauft (20 Diamanten) – Übergabe im Spiel machen und bestätigen", find(alice, "handover:").text());
        assertEquals("Du hast »Elytra« gekauft (20 Diamanten) – nach der Übergabe bestätigen", find(dana, "handover:").text());
        assertNull(find(bob, "handover:"), "Unbeteiligte sehen nichts");
        w.trades.confirm(alice, rid(), f);
        assertNull(find(alice, "handover:"), "bestätigt = erledigt");
        assertNotNull(find(dana, "handover:"), "die andere Seite muss noch");
        w.trades.dispute(dana, rid(), f, "Ich habe nichts bekommen");
        assertNull(find(dana, "handover:"), "im Streitfall gibt es nichts zu bestätigen");
        assertEquals(Notices.Level.DANGER, find(op, "dispute:").level(), "Admins klären Streitfälle");
        assertTrue(find(op, "dispute:").todo());
        assertNull(find(alice, "dispute:"), "Spieler bekommen keine Admin-Aufgabe");
        Notices.Notice told = find(alice, "disputed:");
        assertEquals("Dana hat für »Elytra« einen Streitfall gemeldet – ein Admin klärt ihn", told.text());
        assertNull(find(dana, "disputed:"), "wer meldet, weiß es schon");
        w.trades.resolve(op, true, rid(), f, true, "Übergabe nachgeholt");
        assertNull(find(op, "dispute:"));
        assertEquals("Streitfall zu »Elytra« geklärt: erledigt", find(dana, "resolved:").text());
        assertNotNull(find(alice, "resolved:"));
    }

    @Test
    void wonAuctionAndAcceptedOffer() {
        String a = w.trades.create(alice, rid(), data("kind", "auction", "title", "Netherite", "category", "ores",
                "itemName", "Netherite", "startPrice", 5, "durationHours", 1)).get("id").getAsString();
        w.trades.bid(bob, rid(), a, 7);
        now.addAndGet(3_600_000L + 1000);
        assertEquals("Du hast die Auktion »Netherite« gewonnen (7 Diamanten) – nach der Übergabe bestätigen", find(bob, "handover:").text(),
                "Ablauf zählt sofort, auch ohne dass jemand die Seite offen hat");
        String t = w.trades.create(alice, rid(), data("kind", "trade", "title", "Hilfe beim Bauen", "category", "help_build",
                "wants", "Eisen", "durationHours", 24)).get("id").getAsString();
        String offer = w.trades.makeOffer(carl, rid(), t, "10 Eisen").get("offerId").getAsString();
        assertEquals("Neues Gegenangebot von Carl für »Hilfe beim Bauen«", find(alice, "offer:" + offer).text());
        w.trades.acceptOffer(alice, rid(), t, offer);
        assertEquals("Dein Gegenangebot für »Hilfe beim Bauen« wurde angenommen – nach der Übergabe bestätigen", find(carl, "handover:" + t).text());
    }

    @Test
    void overdueProjectsOnlyOwnAndOpen() {
        String late = w.projects.create(alice, false, data("name", "Burg", "dueDate", "2026-10-24"));
        w.projects.create(alice, false, data("name", "Brücke", "dueDate", TODAY)); // heute fällig = noch nicht überfällig
        Notices.Notice n = find(alice, "overdue:");
        assertNotNull(n);
        assertEquals("Projekt überfällig: »Burg«", n.text());
        assertEquals(1, notices(alice).stream().filter(x -> x.id().startsWith("overdue:")).count());
        assertNull(find(bob, "overdue:"), "fremde Projekte gehen dich nichts an");
        w.projects.update(alice, false, late, data("status", "done"));
        assertNull(find(alice, "overdue:"), "abgeschlossen = nicht mehr überfällig");
    }

    @Test
    void apiListsOnlyTodosInOrder() {
        String land = w.lands.create(alice, false, "Hof", TestSupport.OVERWORLD, TestSupport.chunks(2, 2));
        w.lands.invite(alice, land, bob);
        String team = w.teams.create(carl, "Bauteam", "BAU", null, null, null, "public", null);
        w.teams.invite(carl, team, bob);
        JsonObject api = Notices.toApiJson(notices(bob));
        var items = api.getAsJsonArray("items");
        assertEquals(2, items.size(), "Neuigkeiten gehören nicht in die Liste");
        assertEquals("land-invite:" + land, items.get(0).getAsJsonObject().get("id").getAsString(), "Reihenfolge wie auf dem Dashboard");
        assertEquals("invite", items.get(1).getAsJsonObject().get("kind").getAsString());
        assertTrue(Notices.toApiJson(notices(dana)).getAsJsonArray("items").isEmpty());
    }
}
