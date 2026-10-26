"""Test: Statistik wie bei CraftAttack auf einem echten Server.

Echte Aktionen von Carpet-Fake-Spielern (Blöcke setzen, springen, Diamanterz abbauen, an Fallschaden sterben) müssen als
Minecrafts eigene Zahlen in Ranglisten, Spielerseiten und der Server-Übersicht ankommen. Außerdem: Ausblenden wirkt überall,
/planer statistik und /planer top antworten im Spiel.

Aufruf: TESTSERVER_DIR=/pfad python3 stats_test.py <code-A> <code-B> <code-D>
A, B und D müssen online sein (Fake-Spieler im Überlebensmodus, keine OPs). Dauert 3–4 Minuten: Für Spieler, die online
sind, liest die Mod Minecrafts Statistik einmal pro Minute (und sofort beim Ausloggen), und der Lag-Test wartet zwei Messungen ab.
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, call, login  # noqa: E402
from gamelib import TS, console, mark, platform, remove_platform, respawn, since, wait_for  # noqa: E402

if len(sys.argv) < 4 or not TS:
    raise SystemExit(__doc__)

check = Checker("Statistik")
a, a_name = login(sys.argv[1])
b, b_name = login(sys.argv[2])
d, d_name = login(sys.argv[3])
for tok in (a, b, d):
    call("api/prefs/game", {"key": "rankings", "on": True}, tok)
console("difficulty peaceful")  # sonst stören Monster (nach einem Neustart gilt wieder die Schwierigkeit aus server.properties)
ua, ub, ud = (call("api/me", token=t)[1]["uuid"] for t in (a, b, d))


def page(token, uuid):
    return call(f"api/stats/players/{uuid}", token=token)


def values(uuid):
    st, p = page(a, uuid)
    return p.get("values", {}) if st == 200 else {}


def category(rankings, cid):
    return next(c for c in rankings["categories"] if c["id"] == cid)


def names(cat):
    return [e["name"] for e in cat["entries"]]


# ------------------------------------------------------------------ Grundlagen
st, meta = call("api/meta", token=a)
check("Server bietet die Statistik an", meta.get("gameFeatures", {}).get("statistics") is True, meta.get("gameFeatures"))
ids = [c["id"] for c in meta.get("statCategories", [])]
check("16 Ranglisten mit den Namen von CraftAttack", len(ids) == 16 and {"dauerzocker", "friedhof", "diamantenbank", "abrissbirne", "unsterblich"} <= set(ids), ids)
before = {u: values(u) for u in (ua, ub, ud)}
check("Alle drei haben schon eine Statistik (sie sind online)", all(before.values()), before)


# ------------------------------------------------------------------ Aktiv + AFK passt zur Spielzeit, auch bei Lag
def next_reading(prev, limit):
    """Wartet, bis die Mod Alices Zahlen das nächste Mal liest (jede Minute bzw. alle 1200 Ticks)."""
    end = time.time() + limit
    while time.time() < end:
        v = values(ua)
        if v.get("playTime") != prev.get("playTime"):
            return v
        time.sleep(1)
    return None


r1 = next_reading(values(ua), 75)
try:
    console("tick rate 10")  # wie ein überlasteter Server: halb so viele Ticks pro Sekunde
    time.sleep(20)
finally:
    console("tick rate 20")
r2 = next_reading(r1 or {}, 75) if r1 else None
check("Zwei Messungen mit 20 s Lag dazwischen", r1 is not None and r2 is not None, (r1, r2))
if r1 and r2:
    played = r2["playTime"] - r1["playTime"]
    counted = (r2.get("activeTime", 0) + r2.get("afkTime", 0)) - (r1.get("activeTime", 0) + r1.get("afkTime", 0))
    check("Bei Lag: Aktiv + AFK wächst genau wie Minecrafts Spielzeit (±1,5 s)", played > 30_000 and abs(counted - played) <= 1500,
          {"Spielzeit ms": played, "Aktiv+AFK ms": counted})

# ------------------------------------------------------------------ Echte Aktionen im Spiel
spots = {a_name: (300, 300), b_name: (320, 300), d_name: (340, 300)}
for name, (x, z) in spots.items():
    check(f"Plattform für {name}", platform(name, x, z))
ax, az = spots[a_name]
console(f"item replace entity {a_name} weapon.mainhand with minecraft:dirt 64")
console(f"player {a_name} look at {ax + 0.5} 201 {az + 1.5}")  # auf den Boden direkt vor sich
console(f"player {a_name} use continuous")
console(f"player {b_name} jump continuous")
dx, dz = spots[d_name]
console(f"setblock {dx} 201 {dz + 2} minecraft:diamond_ore")
console(f"item replace entity {d_name} weapon.mainhand with minecraft:diamond_pickaxe")
console(f"player {d_name} look at {dx + 0.5} 201.5 {dz + 2.5}")
console(f"player {d_name} attack continuous")
time.sleep(5)
for name in spots:
    console(f"player {name} stop")
m = mark()
console(f"damage {b_name} 1000 minecraft:fall")
check("Bob stirbt an Fallschaden", wait_for(m, f"{b_name} left the game", 8), since(m)[-300:])
check("…und seine Statistik wird beim Ausloggen gelesen", wait_for(m, f"Statistik beim Ausloggen gelesen: {b_name}", 4), since(m)[-300:])

# Auf die nächste Minute warten, in der die Mod die Online-Spieler liest
end = time.time() + 80
while time.time() < end:
    now = {u: values(u) for u in (ua, ud)}
    if now[ua].get("placed", 0) > before[ua].get("placed", 0) and now[ud].get("mined", 0) > before[ud].get("mined", 0):
        break
    time.sleep(3)
after = {u: values(u) for u in (ua, ub, ud)}
check("Alice: Blöcke platziert zählen", after[ua].get("placed", 0) > before[ua].get("placed", 0), (before[ua].get("placed"), after[ua].get("placed")))
check("Bob: Sprünge zählen (beim Ausloggen gelesen)", after[ub].get("jumps", 0) > before[ub].get("jumps", 0), (before[ub].get("jumps"), after[ub].get("jumps")))
check("Bob: ein Tod mehr", after[ub].get("deaths", 0) == before[ub].get("deaths", 0) + 1, (before[ub].get("deaths"), after[ub].get("deaths")))
check("Dana: Diamanterz zählt als Diamant", after[ud].get("diamonds", 0) == before[ud].get("diamonds", 0) + 1, (before[ud].get("diamonds"), after[ud].get("diamonds")))
st, bob = page(a, ub)
check("Bobs Verlauf: „Gestorben: Fallschaden“", any(e["text"] == "Gestorben: Fallschaden" and e["kind"] == "death" for e in bob.get("events", [])[:3]), bob.get("events", [])[:3])
check("Bobs Todesursachen zählen Fallschaden", any(c["label"] == "Fallschaden" for c in bob.get("causes", [])), bob.get("causes"))
st, dana = page(a, ud)
check("Danas Top-Liste: Diamanterz", any(t["id"] == "minecraft:diamond_ore" and t["name"] == "Diamanterz" for t in dana.get("tops", {}).get("mined", [])), dana.get("tops", {}).get("mined"))

# ------------------------------------------------------------------ Ranglisten
st, week = call("api/stats/rankings?range=week", token=a)
check("7 Tage: Baumeister hat Alice", a_name in names(category(week, "baumeister")), category(week, "baumeister"))
check("7 Tage: Flummi hat Bob", b_name in names(category(week, "flummi")), category(week, "flummi"))
check("7 Tage: Diamantenbank hat Dana", d_name in names(category(week, "diamantenbank")), category(week, "diamantenbank"))
check("7 Tage: Friedhof hat Bob", b_name in names(category(week, "friedhof")), category(week, "friedhof"))
check("„Unsterblich“ gibt es nur gesamt", category(week, "unsterblich")["available"] is False)
st, total = call("api/stats/rankings?range=all", token=a)
mine = category(total, "baumeister").get("me")
check("Gesamt: Alice sieht ihren Platz", mine is not None and mine["place"] >= 1, category(total, "baumeister"))
st, r = call("api/stats/rankings?range=immer", token=a)
check("Unbekannter Zeitraum wird abgelehnt", st == 400, (st, r))

st, server = call("api/stats/server", token=a)
check("Server: Bobs Tod steht im Verlauf (mit Namen)", any(e["name"] == b_name and e["text"] == "Gestorben: Fallschaden" for e in server.get("events", [])), server.get("events", [])[:5])
check("Server: Fallschaden bei den Todesursachen", any(c["label"] == "Fallschaden" for c in server.get("causes", [])), server.get("causes"))
check("Server: Rekord mindestens 3 gleichzeitig", server.get("records", {}).get("maxOnline", {}).get("count", 0) >= 3, server.get("records"))
check("Server: „Wann ist am meisten los“ hat Messwerte", any(v is not None for row in server.get("heat", []) for v in row))
check("Server: Summe platzierter Blöcke enthält Alice", server.get("totals", {}).get("placed", 0) >= after[ua].get("placed", 0), server.get("totals"))

# ------------------------------------------------------------------ Ausblenden
call("api/prefs/game", {"key": "rankings", "on": False}, d)
st, week = call("api/stats/rankings?range=week", token=a)
check("Ausgeblendet: Dana fehlt in der Rangliste", d_name not in names(category(week, "diamantenbank")), category(week, "diamantenbank"))
st, r = page(a, ud)
check("Ausgeblendet: Danas Seite gibt es für Alice nicht", st == 404, (st, r))
st, r = page(d, ud)
check("…aber für Dana selbst, mit Hinweis", st == 200 and r.get("hidden") is True and r.get("places") == {}, (st, r.get("hidden")))
st, plist = call("api/stats/players", token=a)
check("Ausgeblendet: nicht in der Spielerliste", d_name not in [p["name"] for p in plist.get("players", [])])
st, server = call("api/stats/server", token=a)
check("Ausgeblendet: nicht im Verlauf, aber gezählt", server.get("hiddenPlayers", 0) >= 1 and not any(e["name"] == d_name for e in server.get("events", [])),
      (server.get("hiddenPlayers"), [e["name"] for e in server.get("events", [])][:5]))
m = mark()
console(f"execute as {a_name} run planer statistik {d_name}")
check("Im Spiel: /planer statistik Dana sagt „ausgeblendet“", wait_for(m, f"»{d_name}« hat die Statistik ausgeblendet."), since(m)[-300:])
m = mark()
console(f"execute as {d_name} run planer ranglisten an")
check("/planer ranglisten an antwortet", wait_for(m, "In Ranglisten und Statistik sichtbar: an."), since(m)[-300:])
st, r = page(a, ud)
check("…und Dana ist wieder sichtbar", st == 200 and r.get("hidden") is False, st)

# ------------------------------------------------------------------ Befehle im Spiel
m = mark()
console(f"execute as {a_name} run planer statistik")
check("/planer statistik: eigene Zahlen", wait_for(m, f"Statistik von {a_name}") and wait_for(m, "Blöcke platziert:"), since(m)[-500:])
m = mark()
console(f"execute as {a_name} run planer top baumeister")
check("/planer top baumeister: Rangliste", wait_for(m, "Baumeister – Blöcke platziert") and wait_for(m, f". {a_name} "), since(m)[-400:])
m = mark()
console(f"execute as {a_name} run planer top")
check("/planer top: wer führt wo", wait_for(m, "Ranglisten – wer führt?") and "Dauerzocker:" in since(m), since(m)[-600:])
m = mark()
console(f"execute as {a_name} run planer top gibtsnicht")
check("Unbekannte Rangliste: Fehlermeldung", wait_for(m, "Diese Rangliste gibt es nicht."), since(m)[-300:])

# Aufräumen: Bob wieder holen, Plattformen weg
respawn(b_name)
for x, z in spots.values():
    remove_platform(x, z)
sys.exit(check.done())
