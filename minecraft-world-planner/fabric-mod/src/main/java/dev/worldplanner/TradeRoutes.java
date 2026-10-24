package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** /api/trades – Web-Auktionshaus. Überträgt keine Items: dokumentiert Angebote, Gebote und Übergaben. */
final class TradeRoutes extends Routes {
    TradeRoutes(PlannerServices services) {
        super(services);
    }

    @Override
    JsonElement handle(ApiRequest r) {
        TradeStore trades = s.trades();
        if (r.is("GET", "trades")) return trades.toApiJson(r.user, r.admin);
        if (r.is("GET", "trades", "*")) return trades.toApiJson(r.user, r.admin, r.seg(1));
        String rid = r.post() ? r.requestId() : null;
        if (r.is("POST", "trades")) {
            JsonObject result = trades.create(r.user, rid, r.body());
            if (!result.has("repeated")) {
                log(r.user, "trade.created", name(r.user) + " bietet im Handel an: »" + r.optStr("title") + "«.", "trade", result.get("id").getAsString(), "all");
            }
            return result;
        }
        if (!r.post() || r.size() < 3) throw unknown();
        String id = r.seg(1);
        if (r.is("POST", "trades", "*", "offers", "*", "withdraw")) return trades.withdrawOffer(r.user, rid, id, r.seg(3));
        if (r.is("POST", "trades", "*", "offers", "*", "accept")) {
            JsonObject result = trades.acceptOffer(r.user, rid, id, r.seg(3));
            logSold(r, id, result);
            return result;
        }
        if (r.size() != 3) throw unknown();
        return switch (r.seg(2)) {
            case "bid" -> trades.bid(r.user, rid, id, r.lng("amount"));
            case "buy" -> {
                JsonObject result = trades.buy(r.user, rid, id);
                logSold(r, id, result);
                yield result;
            }
            case "offer" -> trades.makeOffer(r.user, rid, id, r.str("text"));
            case "cancel" -> trades.cancel(r.user, r.admin, rid, id, r.optStr("reason"));
            case "confirm" -> trades.confirm(r.user, rid, id);
            case "dispute" -> trades.dispute(r.user, rid, id, r.str("reason"));
            case "resolve" -> trades.resolve(r.user, r.admin, rid, id, r.bool("completed", false), r.str("note"));
            default -> throw unknown();
        };
    }

    /** Wer gekauft hat, ist privat – öffentlich steht nur, dass es weg ist. */
    private void logSold(ApiRequest r, String id, JsonObject result) {
        if (result.has("repeated")) return;
        JsonObject l = s.trades().toApiJson(r.user, r.admin, id);
        log(null, "trade.sold", "»" + l.get("title").getAsString() + "« ist " + (l.get("kind").getAsString().equals("trade") ? "vereinbart" : "verkauft") + ".",
                "trade", id, "all");
    }
}
