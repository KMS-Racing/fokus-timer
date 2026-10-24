package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web-Auktionshaus: Gebotsregeln, parallele Zugriffe, Ablauf auf dem Server, doppelte Anfragen,
 * Speicherfehler, Privatsphäre, Übergabe und Streitfall. Es werden keine Items übertragen – geprüft wird,
 * dass die dokumentierten Zustände stimmen und nie widersprüchlich werden.
 */
class TradeStoreTest {
    @TempDir
    Path dir;
    TestSupport.World w;
    UUID seller, bob, carl, dana, op;
    AtomicLong now = new AtomicLong(1_700_000_000_000L);

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config("tradeActionsPerMinute", 1000));
        w.trades.clock = now::get;
        seller = w.player(1, "Sina");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
        dana = w.player(4, "Dana");
        op = w.admin(9, "Chef");
    }

    private int status(Runnable r) {
        return assertThrows(PlannerException.class, r::run).status;
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

    private String auction(UUID by, int start, Integer buyNow) {
        JsonObject d = data("kind", "auction", "title", "Diamantschwert", "category", "weapons", "itemName", "Diamantschwert",
                "startPrice", start, "durationHours", 1);
        if (buyNow != null) d.addProperty("buyNowPrice", buyNow);
        return w.trades.create(by, rid(), d).get("id").getAsString();
    }

    private String fixed(UUID by, int price) {
        return w.trades.create(by, rid(), data("kind", "fixed", "title", "Elytra", "category", "armor", "itemName", "Elytra",
                "price", price, "durationHours", 24)).get("id").getAsString();
    }

    private JsonObject view(UUID viewer, String id) {
        return w.trades.toApiJson(viewer, false, id);
    }

    @Test
    void createValidation() {
        assertEquals(400, status(() -> w.trades.create(seller, rid(), data("kind", "auction", "title", "Schwert", "category", "weapons",
                "itemName", "Schwert"))), "Startgebot fehlt");
        assertEquals(400, status(() -> w.trades.create(seller, rid(), data("kind", "auction", "title", "Schwert", "category", "weapons",
                "itemName", "Schwert", "startPrice", 10, "buyNowPrice", 10))), "Sofortkauf muss über dem Startgebot liegen");
        assertEquals(400, status(() -> w.trades.create(seller, rid(), data("kind", "fixed", "title", "Elytra", "category", "armor",
                "itemName", "Elytra"))), "Preis fehlt");
        assertEquals(400, status(() -> w.trades.create(seller, rid(), data("kind", "fixed", "title", "Elytra", "category", "armor",
                "price", 5))), "Item fehlt");
        assertEquals(400, status(() -> w.trades.create(seller, rid(), data("kind", "fixed", "title", "Elytra", "category", "magie",
                "itemName", "Elytra", "price", 5))), "unbekannte Kategorie");
        assertEquals(400, status(() -> w.trades.create(seller, rid(), data("kind", "fixed", "title", "Elytra", "category", "armor",
                "itemName", "Elytra", "price", 5, "durationHours", 24 * 15))), "länger als maxListingDays");
        assertEquals(400, status(() -> w.trades.create(seller, rid(), data("kind", "fixed", "title", "Elytra", "category", "armor",
                "itemName", "Elytra", "price", 2.5))), "Preis muss ganz sein");
        assertEquals(400, status(() -> w.trades.create(seller, rid(), data("kind", "trade", "title", "Suche Hilfe", "category", "help_build",
                "side", "request"))), "Tausch braucht eine Gegenleistung");
        assertEquals(400, status(() -> w.trades.create(seller, "zu-kurz", data("kind", "fixed"))), "requestId ungültig");
        // Hilfe (Dienstleistung) braucht kein Item
        w.trades.create(seller, rid(), data("kind", "fixed", "title", "Ich helfe beim Bauen", "category", "help_build", "price", 3));
        w.trades.create(seller, rid(), data("kind", "trade", "title", "Suche Hilfe bei der Burg", "category", "help_build",
                "side", "request", "wants", "16 Eisenblöcke"));
    }

    @Test
    void biddingRules() {
        String id = auction(seller, 10, 50);
        assertEquals(403, status(() -> w.trades.bid(seller, rid(), id, 20)), "Verkäufer bietet nicht mit");
        assertEquals(409, status(() -> w.trades.bid(bob, rid(), id, 9)), "unter dem Startgebot");
        w.trades.bid(bob, rid(), id, 10);
        assertEquals(409, status(() -> w.trades.bid(bob, rid(), id, 12)), "schon Höchstbietender");
        assertEquals(409, status(() -> w.trades.bid(carl, rid(), id, 10)), "muss das Höchstgebot + Mindestschritt erreichen");
        w.trades.bid(carl, rid(), id, 11);
        assertEquals(400, status(() -> w.trades.bid(bob, rid(), id, 50)), "ab Sofortkaufpreis: Sofortkauf nehmen");
        assertEquals(400, status(() -> w.trades.bid(bob, rid(), id, 0)));
        assertEquals(400, status(() -> w.trades.bid(bob, rid(), fixed(seller, 5), 6)), "auf Festpreis bietet man nicht");
        JsonObject v = view(bob, id);
        assertEquals(11, v.get("highestBid").getAsInt());
        assertEquals(12, v.get("minimumBid").getAsInt());
        assertTrue(v.get("iBid").getAsBoolean());
        assertFalse(v.get("iAmHighest").getAsBoolean(), "Bob wurde überboten");
    }

    @Test
    void parallelBidsNeverContradictEachOther() throws Exception {
        String id = auction(seller, 1, null);
        List<UUID> bidders = new ArrayList<>();
        for (int i = 0; i < 8; i++) bidders.add(w.player(100 + i, "Bieter" + i));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        List<Future<?>> jobs = new ArrayList<>();
        for (UUID b : bidders) {
            jobs.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 50; i++) {
                    try {
                        JsonObject cur = w.trades.toApiJson(b, false, id);
                        int min = cur.get("minimumBid").getAsInt();
                        w.trades.bid(b, rid(), id, min + ThreadLocalRandom.current().nextInt(0, 3));
                        accepted.incrementAndGet();
                    } catch (PlannerException e) {
                        if (e.status != 409) unexpected.add(e); // zu niedrig / schon Höchstbietender ist normal
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : jobs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(unexpected.isEmpty(), "unerwartete Fehler: " + unexpected);

        JsonObject v = w.trades.toApiJson(seller, false, id);
        JsonArray bids = v.getAsJsonArray("bids");
        assertEquals(accepted.get(), bids.size(), "jedes bestätigte Gebot ist gespeichert, kein anderes");
        assertTrue(bids.size() > 8);
        int last = 0;
        String lastBidder = null;
        for (JsonElement e : bids) {
            JsonObject b = e.getAsJsonObject();
            int amount = b.get("amount").getAsInt();
            String bidder = b.getAsJsonObject("bidder").get("uuid").getAsString();
            assertTrue(amount >= last + 1, "Gebote steigen streng (Mindestschritt 1): " + last + " -> " + amount);
            assertNotEquals(lastBidder, bidder, "niemand überbietet sich selbst");
            last = amount;
            lastBidder = bidder;
        }
        assertEquals(last, v.get("highestBid").getAsInt(), "Höchstgebot = letztes Gebot");

        // Nach dem Ablauf gewinnt genau das Höchstgebot
        now.addAndGet(2 * 3_600_000L);
        assertEquals(1, w.trades.settleExpired());
        v = w.trades.toApiJson(seller, false, id);
        assertEquals("sold", v.get("status").getAsString());
        assertEquals(last, v.get("finalPrice").getAsInt());
        assertEquals(lastBidder, v.getAsJsonObject("buyer").get("uuid").getAsString());
    }

    @Test
    void parallelBuyNowHasExactlyOneWinner() throws Exception {
        String id = fixed(seller, 20);
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < 16; i++) buyers.add(w.player(200 + i, "Käufer" + i));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<UUID> winners = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> rejected = new ConcurrentLinkedQueue<>();
        List<Future<?>> jobs = new ArrayList<>();
        for (UUID b : buyers) {
            jobs.add(pool.submit(() -> {
                start.await();
                try {
                    w.trades.buy(b, rid(), id);
                    winners.add(b);
                } catch (PlannerException e) {
                    rejected.add(e.status);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : jobs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, winners.size(), "genau ein Käufer");
        assertEquals(15, rejected.size());
        assertTrue(rejected.stream().allMatch(s -> s == 409), "alle anderen: nicht mehr aktiv");
        JsonObject v = w.trades.toApiJson(seller, false, id);
        assertEquals(winners.peek().toString(), v.getAsJsonObject("buyer").get("uuid").getAsString());
        assertEquals(20, v.get("finalPrice").getAsInt());
        assertEquals("pending", v.getAsJsonObject("handover").get("status").getAsString());
    }

    @Test
    void sameRequestIdIsExecutedOnlyOnce() throws Exception {
        String id = auction(seller, 5, null);
        String request = rid();
        JsonObject first = w.trades.bid(bob, request, id, 5);
        JsonObject again = w.trades.bid(bob, request, id, 5);
        assertTrue(again.get("repeated").getAsBoolean());
        assertEquals(first.get("amount"), again.get("amount"));
        assertEquals(1, view(seller, id).get("bidCount").getAsInt(), "kein zweites Gebot");
        // Dieselbe ID eines anderen Spielers ist eine eigene Anfrage
        assertEquals(409, status(() -> w.trades.bid(carl, request, id, 5)), "zählt als neues (zu niedriges) Gebot");
        // Auch nach einem Neustart bleibt es bei einem Gebot
        TestSupport.World again2 = w.restart();
        again2.trades.clock = now::get;
        assertTrue(again2.trades.bid(bob, request, id, 5).get("repeated").getAsBoolean());
        assertEquals(1, again2.trades.toApiJson(seller, false, id).get("bidCount").getAsInt());
    }

    @Test
    void expiryIsDecidedOnTheServer() {
        String withBids = auction(seller, 5, null);
        String withoutBids = auction(seller, 5, null);
        String fixed = fixed(seller, 9);
        w.trades.bid(bob, rid(), withBids, 7);
        long ends = view(seller, withBids).get("endsAt").getAsLong();
        now.set(ends - 1);
        assertEquals(0, w.trades.settleExpired());
        now.set(ends + 1);
        assertEquals(2, w.trades.settleExpired(), "beide Auktionen sind abgelaufen, der Festpreis läuft noch 24 h");
        JsonObject sold = view(bob, withBids);
        assertEquals("sold", sold.get("status").getAsString());
        assertTrue(sold.get("iWon").getAsBoolean());
        assertEquals(7, sold.get("finalPrice").getAsInt());
        assertEquals("unsold", view(seller, withoutBids).get("status").getAsString());
        assertEquals(409, status(() -> w.trades.bid(carl, rid(), withoutBids, 10)), "abgelaufen");

        now.addAndGet(25 * 3_600_000L);
        // Auch ohne Timer: jeder Lesezugriff schließt Abgelaufenes ab
        assertEquals("unsold", w.trades.toApiJson(carl, false, fixed).get("status").getAsString());
        assertEquals(409, status(() -> w.trades.buy(carl, rid(), fixed)));
    }

    @Test
    void lateBidsExtendTheAuction() {
        String id = auction(seller, 5, null);
        long ends = view(seller, id).get("endsAt").getAsLong();
        now.set(ends - 30_000); // 30 Sekunden vor Schluss
        JsonObject r = w.trades.bid(bob, rid(), id, 5);
        assertTrue(r.get("extended").getAsBoolean());
        assertEquals(now.get() + TradeStore.SNIPE_WINDOW_MS, r.get("endsAt").getAsLong());
        now.addAndGet(60_000);
        assertEquals(0, w.trades.settleExpired(), "läuft wegen der Verlängerung noch");
        w.trades.bid(carl, rid(), id, 6);
        now.addAndGet(TradeStore.SNIPE_WINDOW_MS + 1);
        assertEquals(1, w.trades.settleExpired());
        assertEquals(carl.toString(), view(seller, id).getAsJsonObject("buyer").get("uuid").getAsString());
        // Frühe Gebote verlängern nicht
        String other = auction(seller, 5, null);
        assertFalse(w.trades.bid(bob, rid(), other, 5).get("extended").getAsBoolean());
    }

    @Test
    void failedWriteChangesNothing() {
        String id = auction(seller, 5, 100);
        w.trades.failWrites = true;
        String request = rid();
        assertEquals(503, status(() -> w.trades.bid(bob, request, id, 5)));
        assertEquals(503, status(() -> w.trades.buy(carl, rid(), id)));
        assertEquals(503, status(() -> w.trades.create(seller, rid(), data("kind", "fixed", "title", "Bogen", "category", "weapons",
                "itemName", "Bogen", "price", 3))));
        w.trades.failWrites = false;
        JsonObject v = view(seller, id);
        assertEquals(0, v.get("bidCount").getAsInt(), "kein halbes Gebot im Speicher");
        assertEquals("active", v.get("status").getAsString(), "kein halber Verkauf");
        assertEquals(1, w.trades.toApiJson(seller, false).getAsJsonArray("listings").size(), "das neue Angebot wurde nicht angelegt");
        // Dieselbe Anfrage klappt, sobald das Speichern wieder geht
        assertFalse(w.trades.bid(bob, request, id, 5).has("repeated"));
        assertEquals(1, view(seller, id).get("bidCount").getAsInt());
    }

    @Test
    void biddersStayPrivate() {
        String id = auction(seller, 5, null);
        w.trades.bid(bob, rid(), id, 5);
        w.trades.bid(carl, rid(), id, 6);
        JsonArray outsider = view(dana, id).getAsJsonArray("bids");
        for (JsonElement e : outsider) {
            assertFalse(e.getAsJsonObject().has("bidder"), "Fremde sehen nicht, wer geboten hat");
            assertTrue(e.getAsJsonObject().get("alias").getAsString().startsWith("Bieter "));
        }
        JsonArray asBob = view(bob, id).getAsJsonArray("bids");
        assertEquals("Bob", asBob.get(0).getAsJsonObject().getAsJsonObject("bidder").get("name").getAsString(), "eigenes Gebot mit Namen");
        assertFalse(asBob.get(1).getAsJsonObject().has("bidder"), "fremdes Gebot ohne Namen");
        JsonArray asSeller = view(seller, id).getAsJsonArray("bids");
        assertEquals("Carl", asSeller.get(1).getAsJsonObject().getAsJsonObject("bidder").get("name").getAsString());

        now.addAndGet(2 * 3_600_000L);
        w.trades.settleExpired();
        JsonObject outsiderView = view(dana, id);
        assertFalse(outsiderView.has("buyer"), "Käufer ist privat");
        assertFalse(outsiderView.has("handover"));
        assertFalse(outsiderView.has("history"));
        assertTrue(view(carl, id).has("handover"));
    }

    @Test
    void tradeOffers() {
        String id = w.trades.create(seller, rid(), data("kind", "trade", "title", "Suche Hilfe beim Farmbau", "category", "help_build",
                "side", "request", "wants", "Ich gebe 2 Stacks Eisen")).get("id").getAsString();
        assertEquals(400, status(() -> w.trades.buy(bob, rid(), id)), "bei Tausch gibt es keinen Kauf");
        String bobOffer = w.trades.makeOffer(bob, rid(), id, "Ich helfe 2 Stunden").get("offerId").getAsString();
        assertEquals(409, status(() -> w.trades.makeOffer(bob, rid(), id, "Noch ein Angebot")), "nur ein offenes pro Spieler");
        String carlOffer = w.trades.makeOffer(carl, rid(), id, "Ich baue die Farm komplett").get("offerId").getAsString();
        assertEquals(2, view(dana, id).get("openOfferCount").getAsInt(), "Zähler ist öffentlich");
        assertEquals(0, view(dana, id).getAsJsonArray("offers").size(), "Inhalte nicht");
        assertEquals(1, view(bob, id).getAsJsonArray("offers").size(), "nur das eigene");
        assertEquals(2, view(seller, id).getAsJsonArray("offers").size(), "Ersteller sieht alle");
        assertEquals(403, status(() -> w.trades.acceptOffer(bob, rid(), id, carlOffer)), "nur der Ersteller nimmt an");
        assertEquals(403, status(() -> w.trades.withdrawOffer(carl, rid(), id, bobOffer)));
        w.trades.withdrawOffer(bob, rid(), id, bobOffer);
        assertEquals(409, status(() -> w.trades.acceptOffer(seller, rid(), id, bobOffer)), "zurückgezogen");
        w.trades.acceptOffer(seller, rid(), id, carlOffer);
        JsonObject v = view(carl, id);
        assertEquals("sold", v.get("status").getAsString());
        assertEquals("Vereinbart", v.get("statusLabel").getAsString());
        assertTrue(v.get("iWon").getAsBoolean());
        assertFalse(v.has("finalPrice"), "Tausch hat keinen Preis");
    }

    @Test
    void handoverAndDispute() {
        String a = fixed(seller, 10);
        w.trades.buy(bob, rid(), a);
        assertEquals(403, status(() -> w.trades.confirm(carl, rid(), a)), "nur Beteiligte bestätigen");
        w.trades.confirm(seller, rid(), a);
        assertEquals("pending", view(seller, a).getAsJsonObject("handover").get("status").getAsString(), "einer allein reicht nicht");
        assertFalse(view(seller, a).getAsJsonObject("can").get("confirm").getAsBoolean(), "nicht doppelt bestätigen");
        w.trades.confirm(bob, rid(), a);
        assertEquals("completed", view(seller, a).getAsJsonObject("handover").get("status").getAsString());
        assertEquals(409, status(() -> w.trades.dispute(bob, rid(), a, "doch nicht bekommen")), "nach Abschluss kein Streit mehr");

        String b = fixed(seller, 10);
        w.trades.buy(carl, rid(), b);
        assertEquals(400, status(() -> w.trades.dispute(carl, rid(), b, "x")), "Grund zu kurz");
        assertEquals("pending", view(carl, b).getAsJsonObject("handover").get("status").getAsString(), "abgelehnter Streit ändert nichts");
        w.trades.dispute(carl, rid(), b, "Ich habe nichts bekommen");
        assertEquals(409, status(() -> w.trades.confirm(seller, rid(), b)), "im Streitfall keine Bestätigung");
        assertEquals(403, status(() -> w.trades.resolve(seller, false, rid(), b, true, "Alles gut")), "nur Admins klären");
        assertEquals(400, status(() -> w.trades.resolve(op, true, rid(), b, true, "")), "Notiz fehlt");
        assertEquals("disputed", view(carl, b).getAsJsonObject("handover").get("status").getAsString(), "abgelehnte Klärung ändert nichts");
        w.trades.resolve(op, true, rid(), b, false, "Rückgabe im Spiel vereinbart");
        JsonObject h = w.trades.toApiJson(carl, false, b).getAsJsonObject("handover");
        assertEquals("resolved_cancelled", h.get("status").getAsString());
        assertEquals("Rückgabe im Spiel vereinbart", h.get("resolutionNote").getAsString());
    }

    @Test
    void cancelRules() {
        String withBid = auction(seller, 5, null);
        w.trades.bid(bob, rid(), withBid, 5);
        assertEquals(409, status(() -> w.trades.cancel(seller, false, rid(), withBid, null)), "Auktion mit Geboten läuft weiter");
        assertEquals(400, status(() -> w.trades.cancel(op, true, rid(), withBid, "")), "Admin braucht einen Grund");
        w.trades.cancel(op, true, rid(), withBid, "Betrugsverdacht");
        assertEquals("cancelled", view(bob, withBid).get("status").getAsString());

        String f = fixed(seller, 5);
        assertEquals(403, status(() -> w.trades.cancel(bob, false, rid(), f, null)));
        w.trades.cancel(seller, false, rid(), f, null);
        assertEquals(409, status(() -> w.trades.buy(bob, rid(), f)));
    }

    @Test
    void teamListings() {
        String team = w.teams.create(seller, "Händler", "HAND", null, null, null, null, null);
        w.teams.invite(seller, team, bob);
        w.teams.acceptInvite(bob, team);
        assertEquals(403, status(() -> w.trades.create(bob, rid(), data("kind", "fixed", "title", "Teamware", "category", "blocks",
                "itemName", "Stein", "price", 1, "teamId", team))), "nur Team-Admins verkaufen fürs Team");
        String id = w.trades.create(seller, rid(), data("kind", "fixed", "title", "Teamware", "category", "blocks",
                "itemName", "Stein", "price", 1, "teamId", team)).get("id").getAsString();
        assertEquals(403, status(() -> w.trades.buy(bob, rid(), id)), "Teammitglieder kaufen nicht beim eigenen Team");
        w.teams.setRole(seller, team, bob, TeamStore.Role.ADMIN);
        assertTrue(view(bob, id).get("mine").getAsBoolean(), "Team-Admins gehören zur Verkäufer-Seite");
        w.trades.buy(carl, rid(), id);
        w.trades.confirm(bob, rid(), id); // Team-Admin bestätigt für das Team
        w.trades.confirm(carl, rid(), id);
        assertEquals("completed", view(seller, id).getAsJsonObject("handover").get("status").getAsString());
    }

    @Test
    void limits() {
        w = new TestSupport.World(dir.resolve("limits"), TestSupport.config("tradeActionsPerMinute", 3, "maxActiveListingsPerPlayer", 2));
        w.trades.clock = now::get;
        UUID s = w.player(1, "Sina");
        fixed(s, 1);
        fixed(s, 2);
        assertEquals(400, status(() -> fixed(s, 3)), "höchstens 2 aktive Angebote");
        assertEquals(429, status(() -> fixed(s, 4)), "danach greift die Bremse (3 Aktionen pro Minute)");
        now.addAndGet(61_000);
        assertEquals(400, status(() -> fixed(s, 5)), "nach einer Minute wieder erlaubt, aber das Limit bleibt");
    }

    @Test
    void listingsSurviveRestart() throws Exception {
        String id = auction(seller, 5, 40);
        w.trades.bid(bob, rid(), id, 6);
        TestSupport.World again = w.restart();
        again.trades.clock = now::get;
        JsonObject v = again.trades.toApiJson(seller, false, id);
        assertEquals(6, v.get("highestBid").getAsInt());
        assertEquals(40, v.get("buyNowPrice").getAsInt());
        again.trades.buy(carl, rid(), id);
        assertEquals("sold", again.trades.toApiJson(seller, false, id).get("status").getAsString());
    }
}
