package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Web-Auktionshaus. WICHTIG: Die Website überträgt keine Items und kein Geld. Sie verwaltet Angebote, Gebote und
 * den Übergabe-Status. Die Übergabe machen Käufer und Verkäufer selbst im Spiel und bestätigen sie hier.
 * Preise sind vereinbarte Handelspreise in der Einheit aus der Config ("tradeUnit", z. B. Diamanten).
 *
 * Arten: "auction" (Gebote, Höchstgebot gewinnt bei Ablauf, optional Sofortkauf), "fixed" (Festpreis, erster Käufer),
 * "trade" (Tausch/Hilfe: Gegenangebote als Text, Ersteller nimmt eines an; "side" = biete oder suche).
 *
 * Sicherheit:
 * - Alle Änderungen laufen durch eine Sperre (synchronized) – parallele Gebote werden in eindeutiger Reihenfolge
 *   verarbeitet, es kann keinen widersprüchlichen Gewinner geben.
 * - Jede Änderung wird sofort auf die Platte geschrieben, BEVOR der Server "OK" sagt. Klappt das nicht, wird sie
 *   im Speicher zurückgenommen.
 * - Doppelte Anfragen (gleiche requestId) liefern das erste Ergebnis, statt die Aktion zu wiederholen.
 * - Der Ablauf wird auf dem Server erledigt (Timer jede Sekunde + vor jedem Zugriff), nicht im Browser.
 */
final class TradeStore extends JsonStore {
    static final Map<String, String> KINDS = ordered("auction", "Auktion", "fixed", "Festpreis", "trade", "Tausch / Hilfe");
    static final Map<String, String> SIDES = ordered("offer", "Biete", "request", "Suche");
    static final Map<String, String> BUILTIN_CATEGORIES = ordered(
            "blocks", "Blöcke", "building", "Baumaterialien", "ores", "Erze und Ressourcen", "tools", "Werkzeuge",
            "armor", "Rüstung", "weapons", "Waffen", "enchanted", "Verzauberte Gegenstände", "redstone", "Redstone",
            "farming", "Landwirtschaft", "decoration", "Dekoration", "help_build", "Hilfe beim Bauen",
            "help_fight", "Hilfe beim Kämpfen", "help_other", "Andere Hilfe", "other", "Sonstiges");
    static final Map<String, String> STATUS = ordered("active", "Aktiv", "sold", "Verkauft", "unsold", "Nicht verkauft", "cancelled", "Abgebrochen");
    static final Map<String, String> HANDOVER = ordered("pending", "Übergabe offen", "completed", "Übergabe bestätigt",
            "disputed", "Streitfall", "resolved_completed", "Streit geklärt: erledigt", "resolved_cancelled", "Streit geklärt: abgebrochen");
    private static final int MAX_PRICE = 1_000_000;
    private static final int MAX_BIDS = 500;
    private static final int MAX_OFFERS = 100;
    private static final int MAX_REMEMBERED_REQUESTS = 5000;
    private static final long REQUEST_MEMORY_MS = 24 * 3600 * 1000L;
    /** Gebot kurz vor Schluss verlängert die Auktion – so lohnt sich "Last-Second-Sniping" nicht. */
    static final long SNIPE_WINDOW_MS = 2 * 60_000L;

    private record Bid(String id, UUID bidder, int amount, long at) {}

    private static final class Offer {
        final String id;
        final UUID from;
        final String text;
        final long at;
        String status = "open"; // open, accepted, declined, withdrawn

        Offer(String id, UUID from, String text, long at) {
            this.id = id;
            this.from = from;
            this.text = text;
            this.at = at;
        }

        Offer copy() {
            Offer o = new Offer(id, from, text, at);
            o.status = status;
            return o;
        }
    }

    private record HistoryEntry(long at, UUID by, String action, String detail) {}

    private static final class Listing {
        String id, kind, side, title, category, description, itemName, unit, wants, teamId;
        int quantity;
        Integer startPrice, minIncrement, buyNowPrice, price;
        UUID seller, createdBy, buyer;
        long createdAt, endsAt, soldAt;
        String status, soldVia, acceptedOffer;
        Integer finalPrice;
        // Übergabe (nur nach Verkauf)
        String handover;
        long sellerConfirmedAt, buyerConfirmedAt;
        String disputeReason, resolutionNote;
        UUID disputeBy, resolvedBy;
        List<Bid> bids = new ArrayList<>();
        List<Offer> offers = new ArrayList<>();
        List<HistoryEntry> history = new ArrayList<>();

        Listing copy() {
            Listing c = new Listing();
            c.id = id; c.kind = kind; c.side = side; c.title = title; c.category = category; c.description = description;
            c.itemName = itemName; c.unit = unit; c.wants = wants; c.teamId = teamId; c.quantity = quantity;
            c.startPrice = startPrice; c.minIncrement = minIncrement; c.buyNowPrice = buyNowPrice; c.price = price;
            c.seller = seller; c.createdBy = createdBy; c.buyer = buyer; c.createdAt = createdAt; c.endsAt = endsAt;
            c.soldAt = soldAt; c.status = status; c.soldVia = soldVia; c.acceptedOffer = acceptedOffer; c.finalPrice = finalPrice;
            c.handover = handover; c.sellerConfirmedAt = sellerConfirmedAt; c.buyerConfirmedAt = buyerConfirmedAt;
            c.disputeReason = disputeReason; c.resolutionNote = resolutionNote; c.disputeBy = disputeBy; c.resolvedBy = resolvedBy;
            c.bids = new ArrayList<>(bids);
            c.offers = new ArrayList<>();
            offers.forEach(o -> c.offers.add(o.copy()));
            c.history = new ArrayList<>(history);
            return c;
        }

        Bid highest() {
            return bids.isEmpty() ? null : bids.getLast();
        }
    }

    private final Map<String, Listing> listings = new LinkedHashMap<>();
    /** requestId -> Ergebnis, damit ein doppelter Klick nichts doppelt ausführt. */
    private final LinkedHashMap<String, JsonObject> processed = new LinkedHashMap<>();
    private final Map<String, Long> processedAt = new HashMap<>();
    private final PlayerDirectory players;
    private final PlannerConfig config;
    private final Map<String, String> categories;
    private final RateLimiter actions;
    private final Logger logger;
    private Access access;
    /** Uhr – in Tests austauschbar, um Abläufe ohne Warten zu prüfen. */
    volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** Nur für Tests: simuliert eine volle Festplatte. */
    volatile boolean failWrites;
    /** Ein Ablauf wurde im Speicher erledigt, aber noch nicht gespeichert (z. B. weil danach ein Fehler kam). */
    private boolean unsaved;

    TradeStore(Path path, Logger logger, PlayerDirectory players, PlannerConfig config) {
        super(path);
        this.logger = logger;
        this.players = players;
        this.config = config;
        this.actions = new RateLimiter(config.tradeActionsPerMinute(), 60_000);
        Map<String, String> c = new LinkedHashMap<>(BUILTIN_CATEGORIES);
        config.extraTradeCategories().forEach(c::putIfAbsent);
        this.categories = Collections.unmodifiableMap(c);
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject()) {
            JsonObject root = json.getAsJsonObject();
            if (root.has("listings")) for (JsonElement el : root.getAsJsonArray("listings")) {
                try {
                    Listing l = fromJson(el.getAsJsonObject());
                    listings.put(l.id, l);
                } catch (Exception e) {
                    logger.warn("[World Planner] Ein Angebot in {} ist kaputt und wird übersprungen: {}", path, e.getMessage());
                }
            }
            if (root.has("processed")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("processed").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                processed.put(e.getKey(), o.getAsJsonObject("result"));
                processedAt.put(e.getKey(), o.get("at").getAsLong());
            }
        }
    }

    void setAccess(Access access) {
        this.access = access;
    }

    Map<String, String> categories() {
        return categories;
    }

    // ------------------------------------------------------------------ Aktionen

    synchronized JsonObject create(UUID actor, String requestId, JsonObject d) {
        return once(actor, requestId, () -> {
            limit(actor);
            settleAll(clock.getAsLong());
            long active = listings.values().stream().filter(l -> l.status.equals("active") && l.createdBy.equals(actor)).count();
            if (active >= config.maxActiveListingsPerPlayer()) {
                throw PlannerException.badRequest("Du hast schon " + active + " aktive Angebote (Maximum " + config.maxActiveListingsPerPlayer() + ").");
            }
            Listing l = parseNew(actor, d);
            listings.put(l.id, l);
            history(l, actor, "created", KINDS.get(l.kind));
            try {
                persist();
            } catch (PlannerException e) {
                listings.remove(l.id);
                throw e;
            }
            JsonObject r = ok();
            r.addProperty("id", l.id);
            return r;
        });
    }

    synchronized JsonObject bid(UUID actor, String requestId, String id, long amountRaw) {
        return once(actor, requestId, () -> {
            limit(actor);
            long now = clock.getAsLong();
            Listing l = require(id);
            settle(l, now);
            if (!l.kind.equals("auction")) throw PlannerException.badRequest("Auf dieses Angebot kann man nicht bieten.");
            requireActive(l, now);
            requireNotSellerSide(l, actor, "Auf dein eigenes Angebot kannst du nicht bieten.");
            int amount = Validate.range(amountRaw, 1, MAX_PRICE, "Gebot");
            Bid top = l.highest();
            if (top != null && top.bidder.equals(actor)) throw PlannerException.conflict("Du bist schon Höchstbietender.");
            int minimum = top == null ? l.startPrice : top.amount + l.minIncrement;
            if (amount < minimum) {
                throw PlannerException.conflict("Dein Gebot ist zu niedrig. Mindestens " + minimum + " " + l.unit + ".");
            }
            if (l.buyNowPrice != null && amount >= l.buyNowPrice) {
                throw PlannerException.badRequest("Ab " + l.buyNowPrice + " " + l.unit + " nimm bitte den Sofortkauf.");
            }
            if (l.bids.size() >= MAX_BIDS) throw PlannerException.badRequest("Diese Auktion hat die maximale Anzahl Gebote erreicht.");
            Listing backup = l.copy();
            l.bids.add(new Bid(UUID.randomUUID().toString(), actor, amount, now));
            history(l, actor, "bid", amount + " " + l.unit);
            boolean extended = l.endsAt - now < SNIPE_WINDOW_MS;
            if (extended) {
                l.endsAt = now + SNIPE_WINDOW_MS;
                history(l, null, "extended", "Gebot kurz vor Schluss – 2 Minuten verlängert");
            }
            commit(l, backup);
            JsonObject r = ok();
            r.addProperty("amount", amount);
            r.addProperty("endsAt", l.endsAt);
            r.addProperty("extended", extended);
            return r;
        });
    }

    /** Sofortkauf (Auktion mit Sofortkaufpreis) oder Kauf zum Festpreis. Kann nur einmal gelingen. */
    synchronized JsonObject buy(UUID actor, String requestId, String id) {
        return once(actor, requestId, () -> {
            limit(actor);
            long now = clock.getAsLong();
            Listing l = require(id);
            settle(l, now);
            requireActive(l, now);
            requireNotSellerSide(l, actor, "Dein eigenes Angebot kannst du nicht kaufen.");
            Listing backup = l.copy();
            if (l.kind.equals("auction")) {
                if (l.buyNowPrice == null) throw PlannerException.badRequest("Diese Auktion hat keinen Sofortkauf.");
                sell(l, actor, l.buyNowPrice, "buy_now", now);
            } else if (l.kind.equals("fixed")) {
                sell(l, actor, l.price, "fixed", now);
            } else {
                throw PlannerException.badRequest("Bei Tausch-Angeboten schickst du ein Gegenangebot.");
            }
            commit(l, backup);
            JsonObject r = ok();
            r.addProperty("price", l.finalPrice);
            return r;
        });
    }

    synchronized JsonObject makeOffer(UUID actor, String requestId, String id, String text) {
        return once(actor, requestId, () -> {
            limit(actor);
            long now = clock.getAsLong();
            Listing l = require(id);
            settle(l, now);
            if (!l.kind.equals("trade")) throw PlannerException.badRequest("Gegenangebote gibt es nur bei Tausch-Angeboten.");
            requireActive(l, now);
            requireNotSellerSide(l, actor, "Auf dein eigenes Angebot kannst du nicht antworten.");
            for (Offer o : l.offers) {
                if (o.from.equals(actor) && o.status.equals("open")) throw PlannerException.conflict("Du hast schon ein offenes Gegenangebot. Zieh es zurück, um ein neues zu schicken.");
            }
            if (l.offers.size() >= MAX_OFFERS) throw PlannerException.badRequest("Dieses Angebot hat schon zu viele Antworten.");
            Listing backup = l.copy();
            Offer o = new Offer(UUID.randomUUID().toString(), actor, Validate.requiredText(text, 3, 300, "Gegenangebot"), now);
            l.offers.add(o);
            history(l, actor, "offer", null);
            commit(l, backup);
            JsonObject r = ok();
            r.addProperty("offerId", o.id);
            return r;
        });
    }

    synchronized JsonObject withdrawOffer(UUID actor, String requestId, String id, String offerId) {
        return once(actor, requestId, () -> {
            Listing l = require(id);
            Offer o = offer(l, offerId);
            if (!o.from.equals(actor)) throw PlannerException.forbidden("Das ist nicht dein Gegenangebot.");
            if (!o.status.equals("open")) throw PlannerException.conflict("Das Gegenangebot ist nicht mehr offen.");
            Listing backup = l.copy();
            l.offers.stream().filter(x -> x.id.equals(offerId)).findFirst().ifPresent(x -> x.status = "withdrawn");
            history(l, actor, "offer_withdrawn", null);
            commit(l, backup);
            return ok();
        });
    }

    synchronized JsonObject acceptOffer(UUID actor, String requestId, String id, String offerId) {
        return once(actor, requestId, () -> {
            long now = clock.getAsLong();
            Listing l = require(id);
            settle(l, now);
            requireActive(l, now);
            requireSellerSide(l, actor);
            Offer o = offer(l, offerId);
            if (!o.status.equals("open")) throw PlannerException.conflict("Das Gegenangebot ist nicht mehr offen.");
            Listing backup = l.copy();
            for (Offer x : l.offers) {
                if (x.status.equals("open")) x.status = x.id.equals(offerId) ? "accepted" : "declined";
            }
            l.acceptedOffer = offerId;
            sell(l, o.from, null, "offer", now);
            commit(l, backup);
            return ok();
        });
    }

    synchronized JsonObject cancel(UUID actor, boolean admin, String requestId, String id, String reason) {
        return once(actor, requestId, () -> {
            long now = clock.getAsLong();
            Listing l = require(id);
            settle(l, now);
            if (!l.status.equals("active")) throw PlannerException.conflict("Das Angebot ist nicht mehr aktiv.");
            boolean sellerSide = isSellerSide(l, actor);
            if (!sellerSide && !admin) throw PlannerException.forbidden("Nur der Verkäufer kann das Angebot abbrechen.");
            if (!admin && l.kind.equals("auction") && !l.bids.isEmpty()) {
                throw PlannerException.conflict("Es gibt schon Gebote – eine laufende Auktion kann nicht mehr zurückgezogen werden. Bei einem echten Problem hilft ein Admin.");
            }
            String why = Validate.text(reason, 200, "Grund");
            if (admin && !sellerSide && why.isEmpty()) throw PlannerException.badRequest("Bitte einen Grund angeben (Admin-Abbruch).");
            Listing backup = l.copy();
            l.status = "cancelled";
            for (Offer x : l.offers) if (x.status.equals("open")) x.status = "declined";
            history(l, actor, admin && !sellerSide ? "cancelled_by_admin" : "cancelled", why.isEmpty() ? null : why);
            commit(l, backup);
            return ok();
        });
    }

    /** Käufer oder Verkäufer bestätigt, dass die Übergabe im Spiel passiert ist. */
    synchronized JsonObject confirm(UUID actor, String requestId, String id) {
        return once(actor, requestId, () -> {
            Listing l = require(id);
            if (!l.status.equals("sold") || l.handover == null) throw PlannerException.conflict("Hier gibt es keine offene Übergabe.");
            if (!l.handover.equals("pending")) throw PlannerException.conflict("Die Übergabe ist nicht mehr offen (" + HANDOVER.get(l.handover) + ").");
            boolean sellerSide = isSellerSide(l, actor);
            boolean buyer = actor.equals(l.buyer);
            if (!sellerSide && !buyer) throw PlannerException.forbidden("Nur Käufer und Verkäufer bestätigen die Übergabe.");
            Listing backup = l.copy();
            long now = clock.getAsLong();
            if (sellerSide && l.sellerConfirmedAt == 0) l.sellerConfirmedAt = now;
            if (buyer && l.buyerConfirmedAt == 0) l.buyerConfirmedAt = now;
            history(l, actor, "confirmed", sellerSide ? "Verkäufer" : "Käufer");
            if (l.sellerConfirmedAt > 0 && l.buyerConfirmedAt > 0) {
                l.handover = "completed";
                history(l, actor, "handover_completed", null);
            }
            commit(l, backup);
            return ok();
        });
    }

    synchronized JsonObject dispute(UUID actor, String requestId, String id, String reason) {
        return once(actor, requestId, () -> {
            Listing l = require(id);
            if (!l.status.equals("sold") || !"pending".equals(l.handover)) throw PlannerException.conflict("Ein Streitfall geht nur bei einer offenen Übergabe.");
            if (!isSellerSide(l, actor) && !actor.equals(l.buyer)) throw PlannerException.forbidden("Nur Käufer und Verkäufer können einen Streitfall melden.");
            String why = Validate.requiredText(reason, 5, 300, "Grund"); // erst prüfen, dann ändern
            Listing backup = l.copy();
            l.handover = "disputed";
            l.disputeBy = actor;
            l.disputeReason = why;
            history(l, actor, "disputed", l.disputeReason);
            commit(l, backup);
            return ok();
        });
    }

    /** Nur Server-Admins: Streitfall klären. Keine automatische Strafe – nur der Status und eine Notiz. */
    synchronized JsonObject resolve(UUID actor, boolean admin, String requestId, String id, boolean completed, String note) {
        return once(actor, requestId, () -> {
            if (!admin) throw PlannerException.forbidden("Nur Admins klären Streitfälle.");
            Listing l = require(id);
            if (!"disputed".equals(l.handover)) throw PlannerException.conflict("Hier gibt es keinen offenen Streitfall.");
            String text = Validate.requiredText(note, 3, 300, "Notiz"); // erst prüfen, dann ändern
            Listing backup = l.copy();
            l.handover = completed ? "resolved_completed" : "resolved_cancelled";
            l.resolvedBy = actor;
            l.resolutionNote = text;
            history(l, actor, "resolved", (completed ? "erledigt: " : "abgebrochen: ") + l.resolutionNote);
            commit(l, backup);
            return ok();
        });
    }

    /** Vom Timer jede Sekunde aufgerufen: abgelaufene Angebote abschließen. */
    synchronized int settleExpired() {
        int changed = settleAll(clock.getAsLong());
        if (changed > 0 || unsaved) {
            try {
                persist();
            } catch (PlannerException e) {
                logger.error("[World Planner] Abgelaufene Auktionen konnten nicht gespeichert werden – nächster Versuch in 1 s.");
            }
        }
        return changed;
    }

    /** Team aufgelöst: Angebote bleiben, verkauft wird dann im Namen des Erstellers. */
    synchronized void detachTeam(String teamId) {
        boolean any = false;
        for (Listing l : listings.values()) {
            if (teamId.equals(l.teamId)) {
                l.teamId = null;
                l.seller = l.createdBy;
                history(l, null, "team_dissolved", null);
                any = true;
            }
        }
        if (any) {
            try {
                persist();
            } catch (PlannerException e) {
                logger.error("[World Planner] Konnte Angebote nach Team-Auflösung nicht speichern.");
            }
        }
    }

    // ------------------------------------------------------------------ Webseite

    synchronized JsonObject toApiJson(UUID viewer, boolean admin) {
        settleExpired();
        JsonArray arr = new JsonArray();
        for (Listing l : listings.values()) arr.add(json(l, viewer, admin));
        JsonObject o = new JsonObject();
        o.addProperty("serverTime", clock.getAsLong()); // damit die Restzeit im Browser stimmt
        o.addProperty("unit", config.tradeUnit());
        o.add("listings", arr);
        return o;
    }

    synchronized JsonObject toApiJson(UUID viewer, boolean admin, String id) {
        Listing l = require(id);
        settle(l, clock.getAsLong());
        JsonObject o = json(l, viewer, admin);
        o.addProperty("serverTime", clock.getAsLong());
        return o;
    }

    /**
     * Ein Hinweis zum Handel für einen Spieler (siehe Notices). "key" ändert sich, wenn etwas Neues passiert
     * (z. B. ein neues Höchstgebot) – daran erkennt das Spiel, dass es eine neue Nachricht schicken soll.
     * Zu erledigen: outbid, handover_seller, handover_buyer, dispute_admin. Nur Neuigkeiten: bid, offer, disputed,
     * resolved, completed, unsold.
     */
    record Note(String type, String listingId, String title, String key, Integer amount, String unit, String other) {}

    /** Nur was der Spieler auch auf der Webseite sehen darf (z. B. Bieter-Namen nur für die Verkäufer-Seite). */
    synchronized List<Note> notesFor(UUID viewer, boolean admin) {
        settleExpired();
        long now = clock.getAsLong();
        List<Note> out = new ArrayList<>();
        for (Listing l : listings.values()) {
            boolean sellerSide = isSellerSide(l, viewer);
            boolean buyer = viewer.equals(l.buyer);
            boolean active = l.status.equals("active") && now < l.endsAt;
            Bid top = l.highest();
            if (active && top != null && !top.bidder.equals(viewer) && l.bids.stream().anyMatch(b -> b.bidder.equals(viewer))) {
                out.add(new Note("outbid", l.id, l.title, top.id, top.amount, l.unit, null));
            }
            if (active && sellerSide && top != null) out.add(new Note("bid", l.id, l.title, top.id, top.amount, l.unit, players.name(top.bidder)));
            if (active && sellerSide) {
                for (Offer o : l.offers) if (o.status.equals("open")) out.add(new Note("offer", l.id, l.title, o.id, null, l.unit, players.name(o.from)));
            }
            if (l.status.equals("unsold") && sellerSide) out.add(new Note("unsold", l.id, l.title, "", null, l.unit, null));
            if (!l.status.equals("sold") || l.handover == null) continue;
            if (l.handover.equals("pending")) {
                if (sellerSide && l.sellerConfirmedAt == 0) out.add(new Note("handover_seller", l.id, l.title, "", l.finalPrice, l.unit, players.name(l.buyer)));
                if (buyer && l.buyerConfirmedAt == 0) out.add(new Note("handover_buyer", l.id, l.title, l.soldVia, l.finalPrice, l.unit, players.name(l.seller)));
            }
            boolean participant = sellerSide || buyer;
            if (l.handover.equals("disputed")) {
                if (admin) out.add(new Note("dispute_admin", l.id, l.title, "", null, l.unit, null));
                if (participant && !viewer.equals(l.disputeBy)) out.add(new Note("disputed", l.id, l.title, "", null, l.unit, players.name(l.disputeBy)));
            }
            if (participant && l.handover.startsWith("resolved_")) out.add(new Note("resolved", l.id, l.title, l.handover, null, l.unit, null));
            if (participant && l.handover.equals("completed")) out.add(new Note("completed", l.id, l.title, "", null, l.unit, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ Regeln

    private Listing parseNew(UUID actor, JsonObject d) {
        long now = clock.getAsLong();
        Listing l = new Listing();
        l.id = UUID.randomUUID().toString();
        l.kind = Validate.oneOf(str(d, "kind"), KINDS, "Art");
        l.side = l.kind.equals("trade") ? Validate.oneOf(d.has("side") ? str(d, "side") : "offer", SIDES, "Biete/Suche") : "offer";
        l.title = Validate.requiredText(str(d, "title"), 3, 64, "Titel");
        l.category = Validate.oneOf(str(d, "category"), categories, "Kategorie");
        l.description = Validate.text(str(d, "description"), 1000, "Beschreibung");
        boolean help = l.category.startsWith("help_");
        l.itemName = Validate.text(str(d, "itemName"), 64, "Item");
        if (l.itemName.isEmpty() && !help && !l.kind.equals("trade")) throw PlannerException.badRequest("Bitte angeben, welches Item du anbietest.");
        l.quantity = d.has("quantity") && !d.get("quantity").isJsonNull() ? Validate.range(lng(d, "quantity"), 1, 100_000, "Menge") : 1;
        l.unit = config.tradeUnit();
        l.wants = Validate.text(str(d, "wants"), 200, "Gegenleistung");
        int maxHours = config.maxListingDays() * 24;
        int hours = d.has("durationHours") ? Validate.range(lng(d, "durationHours"), 1, maxHours, "Dauer (Stunden)") : maxHours;
        l.createdAt = now;
        l.endsAt = now + hours * 3_600_000L;
        switch (l.kind) {
            case "auction" -> {
                l.startPrice = Validate.range(lng(d, "startPrice"), 1, MAX_PRICE, "Startgebot");
                l.minIncrement = d.has("minIncrement") && !d.get("minIncrement").isJsonNull()
                        ? Validate.range(lng(d, "minIncrement"), 1, MAX_PRICE, "Mindestschritt") : config.tradeMinIncrement();
                if (d.has("buyNowPrice") && !d.get("buyNowPrice").isJsonNull() && !str(d, "buyNowPrice").isBlank()) {
                    l.buyNowPrice = Validate.range(lng(d, "buyNowPrice"), 1, MAX_PRICE, "Sofortkaufpreis");
                    if (l.buyNowPrice <= l.startPrice) throw PlannerException.badRequest("Der Sofortkaufpreis muss über dem Startgebot liegen.");
                }
            }
            case "fixed" -> l.price = Validate.range(lng(d, "price"), 1, MAX_PRICE, "Preis");
            default -> {
                if (l.wants.isEmpty()) throw PlannerException.badRequest(l.side.equals("offer")
                        ? "Schreib dazu, was du dafür haben möchtest." : "Schreib dazu, was du dafür bietest.");
            }
        }
        l.createdBy = actor;
        String teamId = str(d, "teamId");
        if (teamId != null && !teamId.isBlank()) {
            if (!access.hasTeamRole(actor, teamId, TeamStore.Role.ADMIN)) {
                throw PlannerException.forbidden("Im Namen eines Teams verkaufen dürfen nur Team-Admins und der Owner.");
            }
            l.teamId = teamId;
        }
        l.seller = actor;
        l.status = "active";
        return l;
    }

    /** Läuft ein Angebot ab, wird es hier abgeschlossen – egal ob jemand die Seite offen hat. */
    private boolean settle(Listing l, long now) {
        if (!l.status.equals("active") || now < l.endsAt) return false;
        unsaved = true;
        if (l.kind.equals("auction") && l.highest() != null) {
            Bid top = l.highest();
            sell(l, top.bidder, top.amount, "bid", l.endsAt);
        } else {
            l.status = "unsold";
            for (Offer x : l.offers) if (x.status.equals("open")) x.status = "declined";
            history(l, null, "expired", null);
        }
        return true;
    }

    private int settleAll(long now) {
        int n = 0;
        for (Listing l : listings.values()) if (settle(l, now)) n++;
        return n;
    }

    private void sell(Listing l, UUID buyer, Integer price, String via, long at) {
        l.status = "sold";
        l.buyer = buyer;
        l.finalPrice = price;
        l.soldVia = via;
        l.soldAt = at;
        l.handover = "pending";
        history(l, buyer, "sold", via + (price == null ? "" : ": " + price + " " + l.unit));
    }

    private void requireActive(Listing l, long now) {
        if (!l.status.equals("active")) throw PlannerException.conflict("Das Angebot ist nicht mehr aktiv (" + STATUS.get(l.status) + ").");
        if (now >= l.endsAt) throw PlannerException.conflict("Das Angebot ist abgelaufen.");
    }

    /** Verkäufer-Seite: der Ersteller, bei Team-Angeboten auch Team-Admins und der Owner. */
    private boolean isSellerSide(Listing l, UUID actor) {
        if (actor.equals(l.seller) || actor.equals(l.createdBy)) return true;
        return l.teamId != null && access.hasTeamRole(actor, l.teamId, TeamStore.Role.ADMIN);
    }

    private void requireSellerSide(Listing l, UUID actor) {
        if (!isSellerSide(l, actor)) throw PlannerException.forbidden("Das darf nur der Verkäufer.");
    }

    /** Wer verkauft (oder im verkaufenden Team ist), darf weder bieten noch kaufen. */
    private void requireNotSellerSide(Listing l, UUID actor, String message) {
        if (isSellerSide(l, actor) || (l.teamId != null && access.isTeamMember(actor, l.teamId))) throw PlannerException.forbidden(message);
    }

    private void limit(UUID actor) {
        if (!actions.tryAcquire(actor.toString(), clock.getAsLong())) {
            throw new PlannerException(429, "Zu viele Handels-Aktionen in kurzer Zeit. Warte einen Moment.");
        }
    }

    /**
     * Führt eine Aktion höchstens einmal pro requestId aus. Ein zweiter Klick (oder eine wiederholte Anfrage)
     * mit derselben ID bekommt das erste Ergebnis – es entsteht kein zweites Gebot und kein zweiter Kauf.
     */
    private JsonObject once(UUID actor, String requestId, Supplier<JsonObject> action) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9-]{8,64}")) {
            throw PlannerException.badRequest("requestId fehlt oder ist ungültig.");
        }
        String key = actor + ":" + requestId;
        JsonObject earlier = processed.get(key);
        if (earlier != null) {
            JsonObject r = earlier.deepCopy();
            r.addProperty("repeated", true);
            return r;
        }
        JsonObject result = action.get();
        remember(key, result);
        return result;
    }

    private void remember(String key, JsonObject result) {
        long now = clock.getAsLong();
        processed.put(key, result);
        processedAt.put(key, now);
        for (Iterator<Map.Entry<String, JsonObject>> it = processed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonObject> e = it.next();
            Long at = processedAt.get(e.getKey());
            if (processed.size() <= MAX_REMEMBERED_REQUESTS && at != null && now - at < REQUEST_MEMORY_MS) break;
            it.remove();
            processedAt.remove(e.getKey());
        }
        try {
            persist();
        } catch (PlannerException ignored) {
            // Die Aktion selbst ist schon gespeichert; schlimmstenfalls wird ein Duplikat nach einem Neustart abgelehnt.
        }
    }

    /** Speichern; klappt es nicht, wird die Änderung am Angebot zurückgenommen. */
    private void commit(Listing l, Listing backup) {
        try {
            persist();
        } catch (PlannerException e) {
            listings.put(backup.id, backup);
            throw e;
        }
    }

    private void persist() {
        changed();
        try {
            if (failWrites) throw new IOException("Test: Schreiben schlägt fehl");
            JsonFiles.write(path, toJson(), false);
            takeDirtySnapshot(); // ist jetzt gespeichert
            unsaved = false;
        } catch (IOException e) {
            logger.error("[World Planner] Handel konnte nicht gespeichert werden: {}", e.getMessage());
            throw new PlannerException(503, "Speichern fehlgeschlagen – es wurde nichts geändert. Bitte später nochmal versuchen.");
        }
    }

    private void history(Listing l, UUID by, String action, String detail) {
        l.history.add(new HistoryEntry(clock.getAsLong(), by, action, detail));
    }

    private Listing require(String id) {
        Listing l = id == null ? null : listings.get(id);
        if (l == null) throw PlannerException.notFound("Dieses Angebot gibt es nicht (mehr).");
        return l;
    }

    private Offer offer(Listing l, String offerId) {
        return l.offers.stream().filter(o -> o.id.equals(offerId)).findFirst()
                .orElseThrow(() -> PlannerException.notFound("Dieses Gegenangebot gibt es nicht."));
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    // ------------------------------------------------------------------ Ausgabe (mit Privatsphäre)

    private JsonObject json(Listing l, UUID viewer, boolean admin) {
        boolean sellerSide = isSellerSide(l, viewer);
        boolean buyer = viewer.equals(l.buyer);
        boolean participant = sellerSide || buyer || admin;
        JsonObject o = new JsonObject();
        o.addProperty("id", l.id);
        o.addProperty("kind", l.kind);
        o.addProperty("kindLabel", KINDS.get(l.kind));
        o.addProperty("side", l.side);
        o.addProperty("title", l.title);
        o.addProperty("category", l.category);
        o.addProperty("categoryLabel", categories.getOrDefault(l.category, l.category));
        o.addProperty("description", l.description);
        o.addProperty("itemName", l.itemName);
        o.addProperty("quantity", l.quantity);
        o.addProperty("unit", l.unit);
        o.addProperty("wants", l.wants);
        if (l.startPrice != null) o.addProperty("startPrice", l.startPrice);
        if (l.minIncrement != null) o.addProperty("minIncrement", l.minIncrement);
        if (l.buyNowPrice != null) o.addProperty("buyNowPrice", l.buyNowPrice);
        if (l.price != null) o.addProperty("price", l.price);
        o.add("seller", player(l.seller));
        o.add("createdBy", player(l.createdBy));
        if (l.teamId != null) o.addProperty("teamId", l.teamId);
        o.addProperty("createdAt", l.createdAt);
        o.addProperty("endsAt", l.endsAt);
        o.addProperty("status", l.status);
        o.addProperty("statusLabel", l.kind.equals("trade") && l.status.equals("sold") ? "Vereinbart" : STATUS.get(l.status));
        o.addProperty("mine", sellerSide);
        // Gebote: Beträge sieht jeder, WER geboten hat nur die Verkäufer-Seite (und Admins). Eigene Gebote sind markiert.
        Bid top = l.highest();
        o.addProperty("bidCount", l.bids.size());
        if (top != null) o.addProperty("highestBid", top.amount);
        Map<UUID, Integer> alias = new LinkedHashMap<>();
        JsonArray bids = new JsonArray();
        for (Bid b : l.bids) {
            JsonObject bo = new JsonObject();
            bo.addProperty("amount", b.amount);
            bo.addProperty("at", b.at);
            boolean mine = b.bidder.equals(viewer);
            bo.addProperty("mine", mine);
            if (sellerSide || admin || mine) bo.add("bidder", player(b.bidder));
            else bo.addProperty("alias", "Bieter " + alias.computeIfAbsent(b.bidder, u -> alias.size() + 1));
            bids.add(bo);
        }
        o.add("bids", bids);
        boolean iBid = l.bids.stream().anyMatch(b -> b.bidder.equals(viewer));
        o.addProperty("iBid", iBid);
        if (top != null) o.addProperty("iAmHighest", top.bidder.equals(viewer));
        // Gegenangebote: die Verkäufer-Seite sieht alle, jeder andere nur sein eigenes
        JsonArray offers = new JsonArray();
        int openOffers = 0;
        for (Offer x : l.offers) {
            if (x.status.equals("open")) openOffers++;
            if (!(sellerSide || admin || x.from.equals(viewer))) continue;
            JsonObject oo = new JsonObject();
            oo.addProperty("id", x.id);
            oo.add("from", player(x.from));
            oo.addProperty("text", x.text);
            oo.addProperty("at", x.at);
            oo.addProperty("status", x.status);
            oo.addProperty("mine", x.from.equals(viewer));
            offers.add(oo);
        }
        o.add("offers", offers);
        o.addProperty("openOfferCount", openOffers);
        if (l.status.equals("sold")) {
            if (l.finalPrice != null) o.addProperty("finalPrice", l.finalPrice);
            o.addProperty("soldVia", l.soldVia);
            o.addProperty("soldAt", l.soldAt);
            o.addProperty("iWon", buyer);
            if (participant) {
                o.add("buyer", player(l.buyer));
                JsonObject h = new JsonObject();
                h.addProperty("status", l.handover);
                h.addProperty("label", HANDOVER.get(l.handover));
                if (l.sellerConfirmedAt > 0) h.addProperty("sellerConfirmedAt", l.sellerConfirmedAt);
                if (l.buyerConfirmedAt > 0) h.addProperty("buyerConfirmedAt", l.buyerConfirmedAt);
                if (l.disputeReason != null) {
                    h.addProperty("disputeReason", l.disputeReason);
                    h.add("disputeBy", player(l.disputeBy));
                }
                if (l.resolutionNote != null) {
                    h.addProperty("resolutionNote", l.resolutionNote);
                    h.add("resolvedBy", player(l.resolvedBy));
                }
                o.add("handover", h);
            }
        }
        if (participant) {
            JsonArray hist = new JsonArray();
            for (HistoryEntry e : l.history) {
                JsonObject he = new JsonObject();
                he.addProperty("at", e.at);
                if (e.by != null) he.add("by", player(e.by));
                he.addProperty("action", e.action);
                if (e.detail != null) he.addProperty("detail", e.detail);
                hist.add(he);
            }
            o.add("history", hist);
        }
        long now = clock.getAsLong();
        boolean active = l.status.equals("active") && now < l.endsAt;
        boolean outsider = !sellerSide && !(l.teamId != null && access.isTeamMember(viewer, l.teamId));
        JsonObject can = new JsonObject();
        can.addProperty("bid", active && outsider && l.kind.equals("auction"));
        can.addProperty("buy", active && outsider && (l.kind.equals("fixed") || (l.kind.equals("auction") && l.buyNowPrice != null)));
        can.addProperty("offer", active && outsider && l.kind.equals("trade")
                && l.offers.stream().noneMatch(x -> x.from.equals(viewer) && x.status.equals("open")));
        can.addProperty("cancel", active && ((sellerSide && !(l.kind.equals("auction") && !l.bids.isEmpty())) || admin));
        can.addProperty("acceptOffer", active && sellerSide && l.kind.equals("trade"));
        boolean pending = l.status.equals("sold") && "pending".equals(l.handover);
        can.addProperty("confirm", pending && ((sellerSide && l.sellerConfirmedAt == 0) || (buyer && l.buyerConfirmedAt == 0)));
        can.addProperty("dispute", pending && (sellerSide || buyer));
        can.addProperty("resolve", admin && "disputed".equals(l.handover));
        o.add("can", can);
        if (l.kind.equals("auction") && active) o.addProperty("minimumBid", top == null ? l.startPrice : top.amount + l.minIncrement);
        return o;
    }

    private JsonObject player(UUID u) {
        JsonObject o = new JsonObject();
        if (u == null) return o;
        o.addProperty("uuid", u.toString());
        o.addProperty("name", players.name(u));
        return o;
    }

    private static String str(JsonObject d, String key) {
        try {
            return d.has(key) && !d.get(key).isJsonNull() ? d.get(key).getAsString() : null;
        } catch (Exception e) {
            throw PlannerException.badRequest("Feld »" + key + "« ist ungültig.");
        }
    }

    private static long lng(JsonObject d, String key) {
        try {
            double v = d.get(key).getAsDouble();
            if (!Double.isFinite(v) || v != Math.floor(v)) throw new IllegalArgumentException();
            return (long) v;
        } catch (Exception e) {
            throw PlannerException.badRequest("»" + key + "« muss eine ganze Zahl sein.");
        }
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------ Speichern

    private static JsonObject toJson(Listing l) {
        JsonObject o = new JsonObject();
        o.addProperty("id", l.id);
        o.addProperty("kind", l.kind);
        o.addProperty("side", l.side);
        o.addProperty("title", l.title);
        o.addProperty("category", l.category);
        o.addProperty("description", l.description);
        o.addProperty("itemName", l.itemName);
        o.addProperty("quantity", l.quantity);
        o.addProperty("unit", l.unit);
        o.addProperty("wants", l.wants);
        if (l.startPrice != null) o.addProperty("startPrice", l.startPrice);
        if (l.minIncrement != null) o.addProperty("minIncrement", l.minIncrement);
        if (l.buyNowPrice != null) o.addProperty("buyNowPrice", l.buyNowPrice);
        if (l.price != null) o.addProperty("price", l.price);
        if (l.teamId != null) o.addProperty("teamId", l.teamId);
        o.addProperty("seller", l.seller.toString());
        o.addProperty("createdBy", l.createdBy.toString());
        if (l.buyer != null) o.addProperty("buyer", l.buyer.toString());
        o.addProperty("createdAt", l.createdAt);
        o.addProperty("endsAt", l.endsAt);
        o.addProperty("status", l.status);
        if (l.soldVia != null) o.addProperty("soldVia", l.soldVia);
        if (l.soldAt > 0) o.addProperty("soldAt", l.soldAt);
        if (l.finalPrice != null) o.addProperty("finalPrice", l.finalPrice);
        if (l.acceptedOffer != null) o.addProperty("acceptedOffer", l.acceptedOffer);
        if (l.handover != null) o.addProperty("handover", l.handover);
        if (l.sellerConfirmedAt > 0) o.addProperty("sellerConfirmedAt", l.sellerConfirmedAt);
        if (l.buyerConfirmedAt > 0) o.addProperty("buyerConfirmedAt", l.buyerConfirmedAt);
        if (l.disputeReason != null) o.addProperty("disputeReason", l.disputeReason);
        if (l.disputeBy != null) o.addProperty("disputeBy", l.disputeBy.toString());
        if (l.resolutionNote != null) o.addProperty("resolutionNote", l.resolutionNote);
        if (l.resolvedBy != null) o.addProperty("resolvedBy", l.resolvedBy.toString());
        JsonArray bids = new JsonArray();
        for (Bid b : l.bids) {
            JsonObject bo = new JsonObject();
            bo.addProperty("id", b.id);
            bo.addProperty("bidder", b.bidder.toString());
            bo.addProperty("amount", b.amount);
            bo.addProperty("at", b.at);
            bids.add(bo);
        }
        o.add("bids", bids);
        JsonArray offers = new JsonArray();
        for (Offer x : l.offers) {
            JsonObject oo = new JsonObject();
            oo.addProperty("id", x.id);
            oo.addProperty("from", x.from.toString());
            oo.addProperty("text", x.text);
            oo.addProperty("at", x.at);
            oo.addProperty("status", x.status);
            offers.add(oo);
        }
        o.add("offers", offers);
        JsonArray hist = new JsonArray();
        for (HistoryEntry e : l.history) {
            JsonObject he = new JsonObject();
            he.addProperty("at", e.at);
            if (e.by != null) he.addProperty("by", e.by.toString());
            he.addProperty("action", e.action);
            if (e.detail != null) he.addProperty("detail", e.detail);
            hist.add(he);
        }
        o.add("history", hist);
        return o;
    }

    private static Listing fromJson(JsonObject o) {
        Listing l = new Listing();
        l.id = o.get("id").getAsString();
        l.kind = o.get("kind").getAsString();
        l.side = o.get("side").getAsString();
        l.title = o.get("title").getAsString();
        l.category = o.get("category").getAsString();
        l.description = o.get("description").getAsString();
        l.itemName = o.get("itemName").getAsString();
        l.quantity = o.get("quantity").getAsInt();
        l.unit = o.get("unit").getAsString();
        l.wants = o.get("wants").getAsString();
        l.startPrice = o.has("startPrice") ? o.get("startPrice").getAsInt() : null;
        l.minIncrement = o.has("minIncrement") ? o.get("minIncrement").getAsInt() : null;
        l.buyNowPrice = o.has("buyNowPrice") ? o.get("buyNowPrice").getAsInt() : null;
        l.price = o.has("price") ? o.get("price").getAsInt() : null;
        l.teamId = o.has("teamId") ? o.get("teamId").getAsString() : null;
        l.seller = UUID.fromString(o.get("seller").getAsString());
        l.createdBy = UUID.fromString(o.get("createdBy").getAsString());
        l.buyer = o.has("buyer") ? UUID.fromString(o.get("buyer").getAsString()) : null;
        l.createdAt = o.get("createdAt").getAsLong();
        l.endsAt = o.get("endsAt").getAsLong();
        l.status = o.get("status").getAsString();
        l.soldVia = o.has("soldVia") ? o.get("soldVia").getAsString() : null;
        l.soldAt = o.has("soldAt") ? o.get("soldAt").getAsLong() : 0;
        l.finalPrice = o.has("finalPrice") ? o.get("finalPrice").getAsInt() : null;
        l.acceptedOffer = o.has("acceptedOffer") ? o.get("acceptedOffer").getAsString() : null;
        l.handover = o.has("handover") ? o.get("handover").getAsString() : null;
        l.sellerConfirmedAt = o.has("sellerConfirmedAt") ? o.get("sellerConfirmedAt").getAsLong() : 0;
        l.buyerConfirmedAt = o.has("buyerConfirmedAt") ? o.get("buyerConfirmedAt").getAsLong() : 0;
        l.disputeReason = o.has("disputeReason") ? o.get("disputeReason").getAsString() : null;
        l.disputeBy = o.has("disputeBy") ? UUID.fromString(o.get("disputeBy").getAsString()) : null;
        l.resolutionNote = o.has("resolutionNote") ? o.get("resolutionNote").getAsString() : null;
        l.resolvedBy = o.has("resolvedBy") ? UUID.fromString(o.get("resolvedBy").getAsString()) : null;
        for (JsonElement e : o.getAsJsonArray("bids")) {
            JsonObject b = e.getAsJsonObject();
            l.bids.add(new Bid(b.get("id").getAsString(), UUID.fromString(b.get("bidder").getAsString()), b.get("amount").getAsInt(), b.get("at").getAsLong()));
        }
        for (JsonElement e : o.getAsJsonArray("offers")) {
            JsonObject x = e.getAsJsonObject();
            Offer offer = new Offer(x.get("id").getAsString(), UUID.fromString(x.get("from").getAsString()), x.get("text").getAsString(), x.get("at").getAsLong());
            offer.status = x.get("status").getAsString();
            l.offers.add(offer);
        }
        for (JsonElement e : o.getAsJsonArray("history")) {
            JsonObject h = e.getAsJsonObject();
            l.history.add(new HistoryEntry(h.get("at").getAsLong(), h.has("by") ? UUID.fromString(h.get("by").getAsString()) : null,
                    h.get("action").getAsString(), h.has("detail") ? h.get("detail").getAsString() : null));
        }
        return l;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        listings.values().forEach(l -> arr.add(toJson(l)));
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("listings", arr);
        JsonObject proc = new JsonObject();
        processed.forEach((k, v) -> {
            JsonObject e = new JsonObject();
            e.addProperty("at", processedAt.getOrDefault(k, 0L));
            e.add("result", v);
            proc.add(k, e);
        });
        root.add("processed", proc);
        return root;
    }
}
