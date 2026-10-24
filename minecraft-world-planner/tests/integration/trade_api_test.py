"""API-Test für das Web-Auktionshaus: Gebote, gleichzeitige Gebote/Käufe über HTTP, doppelte Anfragen,
Privatsphäre, Übergabe, Streitfall, Tausch/Hilfe. Die Website überträgt KEINE Items – geprüft werden die
dokumentierten Zustände.
Aufruf: python3 trade_api_test.py <code-A> <code-B> <code-D> <code-admin>   (A, B, D keine OPs)
"""
import os
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, by_id, call, login, rid, suffix  # noqa: E402

if len(sys.argv) < 5:
    raise SystemExit(__doc__)
check = Checker("Handel-API")
a, a_name = login(sys.argv[1])
b, b_name = login(sys.argv[2])
d, d_name = login(sys.argv[3])
op, op_name = login(sys.argv[4])
run = suffix()


def create(token, **fields):
    return call("api/trades", dict(fields, requestId=rid()), token)


def view(token, lid):
    return call(f"api/trades/{lid}", token=token)[1]


def parallel(jobs):
    """Startet alle Anfragen gleichzeitig (Barriere) und gibt die Ergebnisse in Reihenfolge zurück."""
    barrier = threading.Barrier(len(jobs))
    results = [None] * len(jobs)

    def run_job(i, fn):
        barrier.wait()
        results[i] = fn()

    threads = [threading.Thread(target=run_job, args=(i, fn)) for i, fn in enumerate(jobs)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    return results


# --- Übersicht
st, all_ = call("api/trades", token=a)
check("Liste mit Serverzeit und Einheit", st == 200 and isinstance(all_["serverTime"], int) and all_["unit"], all_.get("unit"))
st, r = call("api/trades", {"kind": "fixed", "title": "Ohne ID", "category": "tools", "itemName": "Axt", "price": 1}, a)
check("ohne requestId -> 400", st == 400, r)

# --- Auktion und Gebotsregeln
st, r = create(a, kind="auction", title="Diamantschwert " + run, category="weapons", itemName="Diamantschwert",
               quantity=1, startPrice=2, buyNowPrice=50, durationHours=2, description="Schärfe V")
auction = r.get("id")
check("A erstellt eine Auktion", st == 200, r)
st, r = create(a, kind="auction", title="Kaputt " + run, category="weapons", itemName="Stock", startPrice=10, buyNowPrice=5)
check("Sofortkauf unter Startgebot -> 400", st == 400, r)
st, r = create(a, kind="fixed", title="Kaputt " + run, category="raketen", itemName="Stock", price=5)
check("unbekannte Kategorie -> 400", st == 400, r)
first = rid()
st, r = call(f"api/trades/{auction}/bid", {"requestId": first, "amount": 2}, b)
check("B bietet das Startgebot", st == 200 and r.get("amount") == 2, r)
st, r = call(f"api/trades/{auction}/bid", {"requestId": first, "amount": 2}, b)
check("gleiche requestId nochmal: kein zweites Gebot", st == 200 and r.get("repeated") is True and view(a, auction)["bidCount"] == 1, r)
st, r = call(f"api/trades/{auction}/bid", {"requestId": rid(), "amount": 3}, b)
check("B kann sich nicht selbst überbieten", st == 409, r)
st, r = call(f"api/trades/{auction}/bid", {"requestId": rid(), "amount": 2}, d)
check("Gebot unter Höchstgebot + Schritt -> 409", st == 409 and "Mindestens 3" in r.get("error", ""), r)
st, r = call(f"api/trades/{auction}/bid", {"requestId": rid(), "amount": 5}, a)
check("Verkäufer bietet nicht mit", st == 403, r)
st, r = call(f"api/trades/{auction}/bid", {"requestId": rid(), "amount": 2.5}, d)
check("Kommazahl -> 400", st == 400, r)
st, r = call(f"api/trades/{auction}/bid", {"requestId": rid(), "amount": 60}, d)
check("ab Sofortkaufpreis: Sofortkauf nehmen (400)", st == 400, r)

# --- Gleichzeitige Gebote über HTTP: drei Bieter, je mehrere Runden
accepted = []
lock = threading.Lock()


def bid_round(token):
    def job():
        cur = view(token, auction)
        st, r = call(f"api/trades/{auction}/bid", {"requestId": rid(), "amount": cur["minimumBid"]}, token)
        if st == 200:
            with lock:
                accepted.append(r["amount"])
        return st
    return job


codes = []
for _ in range(6):
    codes += parallel([bid_round(b), bid_round(d), bid_round(op)])
check("gleichzeitige Gebote: nur 200 oder 409", set(codes) <= {200, 409}, codes)
v = view(a, auction)
amounts = [x["amount"] for x in v["bids"]]
bidders = [x["bidder"]["name"] for x in v["bids"]]
check("jedes bestätigte Gebot ist gespeichert, kein anderes (1 + bestätigte)", v["bidCount"] == 1 + len(accepted),
      (v["bidCount"], len(accepted)))
check("pro gleichzeitiger Runde gewinnt höchstens ein Bieter mit demselben Mindestgebot",
      len(accepted) == len(set(accepted)), sorted(accepted))
check("Gebote steigen streng", all(y > x for x, y in zip(amounts, amounts[1:])), amounts)
check("niemand hat sich selbst überboten", all(x != y for x, y in zip(bidders, bidders[1:])), bidders)
check("Höchstgebot = letztes Gebot", v["highestBid"] == amounts[-1], (v["highestBid"], amounts[-1]))

# --- Privatsphäre
outsider = view(d, auction)
foreign = [x for x in outsider["bids"] if not x["mine"]]
check("Bieter sehen fremde Gebote nur als »Bieter N«", foreign and all("bidder" not in x and x["alias"].startswith("Bieter ") for x in foreign), foreign[:2])
check("eigene Gebote sind markiert", all(x["bidder"]["name"] == d_name for x in outsider["bids"] if x["mine"]))
check("Verkäufer sieht alle Bieter", all("bidder" in x for x in view(a, auction)["bids"]))
check("Fremde sehen keinen Verlauf", "history" not in outsider)

# --- Sofortkauf: drei gleichzeitig, genau einer gewinnt
st, r = create(a, kind="fixed", title="Elytra " + run, category="armor", itemName="Elytra", price=7, durationHours=24)
fixed = r.get("id")
results = parallel([lambda t=t: call(f"api/trades/{fixed}/buy", {"requestId": rid()}, t)[0] for t in (b, d, op)])
check("gleichzeitiger Kauf: genau ein Gewinner", sorted(results) == [200, 409, 409], results)
fv = view(a, fixed)
check("verkauft zum Festpreis, Übergabe offen", fv["status"] == "sold" and fv["finalPrice"] == 7 and fv["handover"]["status"] == "pending", fv.get("handover"))
buyer_name = fv["buyer"]["name"]
buyer = {b_name: b, d_name: d, op_name: op}[buyer_name]
others = [t for n, t in ((b_name, b), (d_name, d), (op_name, op)) if n != buyer_name and t is not op]
if others:
    check("Unbeteiligte sehen den Käufer nicht", "buyer" not in view(others[0], fixed) and "handover" not in view(others[0], fixed))

# --- Übergabe
st, r = call(f"api/trades/{fixed}/confirm", {"requestId": rid()}, a)
check("Verkäufer bestätigt die Übergabe", st == 200, r)
check("…einer allein reicht nicht", view(a, fixed)["handover"]["status"] == "pending")
st, r = call(f"api/trades/{fixed}/confirm", {"requestId": rid()}, buyer)
check("Käufer bestätigt -> abgeschlossen", st == 200 and view(a, fixed)["handover"]["status"] == "completed", r)
st, r = call(f"api/trades/{fixed}/dispute", {"requestId": rid(), "reason": "Doch nicht bekommen"}, buyer)
check("nach Abschluss kein Streitfall mehr", st == 409, r)

# --- Streitfall
st, r = create(a, kind="fixed", title="Shulkerkiste " + run, category="blocks", itemName="Shulkerkiste", price=3, quantity=2)
disp = r.get("id")
call(f"api/trades/{disp}/buy", {"requestId": rid()}, b)
st, r = call(f"api/trades/{disp}/dispute", {"requestId": rid(), "reason": "x"}, b)
check("Streitfall-Grund zu kurz -> 400", st == 400, r)
check("…Übergabe ist noch offen (nichts halb geändert)", view(b, disp)["handover"]["status"] == "pending")
st, r = call(f"api/trades/{disp}/dispute", {"requestId": rid(), "reason": "Nur eine Kiste bekommen"}, b)
check("Käufer meldet einen Streitfall", st == 200, r)
st, r = call(f"api/trades/{disp}/resolve", {"requestId": rid(), "completed": True, "note": "geklärt"}, a)
check("Verkäufer kann nicht selbst entscheiden", st == 403, r)
st, r = call(f"api/trades/{disp}/resolve", {"requestId": rid(), "completed": True, "note": "Zweite Kiste im Spiel nachgereicht"}, op)
h = view(b, disp)["handover"]
check("Admin klärt den Streitfall", st == 200 and h["status"] == "resolved_completed" and "nachgereicht" in h["resolutionNote"], h)

# --- Tausch / Hilfe (ohne Preis)
st, r = create(a, kind="trade", side="request", title="Suche Hilfe beim Farmbau " + run, category="help_build",
               wants="2 Stacks Eisenblöcke", description="Eisenfarm am Spawn")
swap = r.get("id")
check("A sucht Hilfe (Tausch)", st == 200, r)
st, r = call(f"api/trades/{swap}/offer", {"requestId": rid(), "text": "Ich helfe 2 Stunden"}, b)
off_b = r.get("offerId")
st, r = call(f"api/trades/{swap}/offer", {"requestId": rid(), "text": "Ich baue alles"}, d)
off_d = r.get("offerId")
check("zwei Gegenangebote", off_b and off_d, r)
check("Gegenangebote sind privat (Zähler öffentlich)", view(b, swap)["openOfferCount"] == 2
      and len(view(b, swap)["offers"]) == 1)
st, r = call(f"api/trades/{swap}/offers/{off_d}/accept", {"requestId": rid()}, b)
check("nur der Ersteller nimmt an", st == 403, r)
st, r = call(f"api/trades/{swap}/offers/{off_d}/accept", {"requestId": rid()}, a)
sv = view(d, swap)
check("A nimmt D's Angebot an: vereinbart, ohne Preis", st == 200 and sv["status"] == "sold" and sv["statusLabel"] == "Vereinbart"
      and sv["iWon"] is True and "finalPrice" not in sv, sv.get("statusLabel"))
check("B's Angebot ist abgelehnt", view(b, swap)["offers"][0]["status"] == "declined")

# --- Abbrechen
st, r = call(f"api/trades/{auction}/cancel", {"requestId": rid()}, a)
check("Auktion mit Geboten kann der Verkäufer nicht abbrechen", st == 409, r)
st, r = call(f"api/trades/{auction}/cancel", {"requestId": rid(), "reason": ""}, op)
check("Admin-Abbruch braucht einen Grund", st == 400, r)
st, r = call(f"api/trades/{auction}/cancel", {"requestId": rid(), "reason": "Testende"}, op)
check("Admin bricht ab", st == 200 and view(b, auction)["status"] == "cancelled", r)

# --- Aktivität verrät keine Käufer
texts = [e["text"] for e in call("api/activity?limit=200", token=d)[1]["events"]]
sold = [t for t in texts if "Elytra " + run in t and "verkauft" in t]
check("Aktivität meldet den Verkauf", len(sold) == 1, sold)
check("…ohne den Käufer zu nennen", buyer_name not in sold[0] if sold else False, sold)
check("keine Meldung behauptet eine Item-Übertragung", not any("übertragen" in t.lower() for t in texts))

sys.exit(check.done())
