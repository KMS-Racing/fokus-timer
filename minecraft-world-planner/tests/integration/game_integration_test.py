"""Test: Spiel-Integration auf einem echten Server – Nachrichten, Land-Anzeige, Todespunkt, /planer und /tc.

Minecraft schreibt Nachrichten an einzelne Spieler nirgends hin. Deshalb muss der Testserver mit
LOG_CONFIG=tests/integration/log4j2-debug.xml gestartet sein: Dann schreibt die Mod jede Nachricht als
DEBUG-Zeile ins Log ("Nachricht an Bob: ..."), und dieser Test liest mit.

Aufruf: TESTSERVER_DIR=/pfad python3 game_integration_test.py <code-A> <code-B> <code-D> <code-OP>
A, B und D müssen online sein (Carpet-Fake-Spieler im Überlebensmodus: "player Bob spawn in survival")
und dürfen keine OPs sein. OP räumt am Ende die Test-Auktion ab.
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, call, cleanup_test_teams, flat, free_chunks, login, rid, suffix  # noqa: E402

TS = os.environ.get("TESTSERVER_DIR")
if len(sys.argv) < 5 or not TS:
    raise SystemExit(__doc__)
LOG = os.path.join(TS, "server.log")
CMDS = os.path.join(TS, "cmds.txt")
OW = "minecraft:overworld"


def lines():
    with open(LOG, encoding="utf-8", errors="replace") as f:
        return f.readlines()


def mark():
    return len(lines())


def since(m):
    return "".join(lines()[m:])


def wait_for(m, needle, timeout=8.0):
    end = time.time() + timeout
    while time.time() < end:
        if needle in since(m):
            return True
        time.sleep(0.3)
    return False


def console(command):
    with open(CMDS, "a", encoding="utf-8") as f:
        f.write(command + "\n")


def safe(name):
    """Sanftfall: Wer hoch oben ankommt, schwebt herunter, statt an Fallschaden zu sterben (Überlebensmodus)."""
    console(f"effect give {name} minecraft:slow_falling infinite 0 true")


def respawn(name):
    """Carpet entfernt Fake-Spieler, wenn sie sterben – also wieder holen. Danach schützt Minecraft neu
    gespawnte Spieler 3 Sekunden lang vor Schaden, deshalb kurz warten."""
    m = mark()
    console(f"player {name} spawn in survival")
    ok = wait_for(m, f"{name}[local] logged in", 10)
    safe(name)
    time.sleep(4)
    return ok


def place(name, bx, bz):
    """Teleport hoch über den Boden (dort steht sicher kein Berg im Weg). Gibt zurück, ob er wirklich passiert ist."""
    m = mark()
    console(f"tp {name} {bx} 260 {bz}")
    # Minecraft setzt ganze Zahlen auf die Blockmitte: -72 wird zu -71.5
    return wait_for(m, f"Teleported {name} to {bx + 0.5:.6f}, 260.000000, {bz + 0.5:.6f}", 5)


check = Checker("Spiel-Integration")
a, a_name = login(sys.argv[1])
b, b_name = login(sys.argv[2])
d, d_name = login(sys.argv[3])
op, op_name = login(sys.argv[4])
cleanup_test_teams([a, b, d])
run = suffix()
for who in (b_name, d_name):
    safe(who)

# ------------------------------------------------------------------ Grundlagen
st, meta = call("api/meta", token=a)
check("Server bietet die Spiel-Funktionen an", meta.get("gameFeatures") == {"notify": True, "landEnter": True, "deathMarker": True, "teamChat": True,
                                                                         "statistics": True, "events": True},
      meta.get("gameFeatures"))
for tok in (a, b, d):
    for key in ("notify", "landEnter", "deathMarker"):
        call("api/prefs/game", {"key": key, "on": True}, tok)
st, prefs = call("api/prefs", token=b)
check("Schalter stehen in /api/prefs", prefs.get("game") == {"notify": True, "landEnter": True, "deathMarker": True}, prefs)
st, r = call("api/prefs/game", {"key": "alles", "on": False}, b)
check("Unbekannter Schalter wird abgelehnt", st == 400, (st, r))
m = mark()
console(f"execute as {b_name} run planer einstellungen")
if not wait_for(m, "Deine Einstellungen im Spiel"):
    raise SystemExit("Keine Antwort vom Server – läuft er, und sind die Fake-Spieler online?")

# ------------------------------------------------------------------ Live-Nachricht: Einladung zu einem Land
chunks = free_chunks(a, 2)
land_name = "Spiel " + run
m = mark()
st, r = call("api/lands", {"name": land_name, "dimension": OW, "chunks": flat(chunks)}, a)
land = r["id"]
call(f"api/lands/{land}/invite", {"player": b_name}, a)
check("Einladung kommt sofort im Spiel an",
      wait_for(m, f"Nachricht an {b_name}: [Planer] ✉ Einladung: Land »{land_name}« mitbesitzen [Annehmen]"),
      since(m)[-600:])
if "DEBUG" not in since(m):
    raise SystemExit("Keine DEBUG-Zeilen im Log – den Testserver mit LOG_CONFIG=tests/integration/log4j2-debug.xml starten.")
check("…und nur beim Eingeladenen", not any(land_name in line and f"an {d_name}:" in line for line in lines()[m:]))
st, todo = call("api/todo", token=b)
check("/api/todo zeigt dieselbe Einladung", any(i["text"] == f"Einladung: Land »{land_name}« mitbesitzen" and i["kind"] == "invite"
                                                for i in todo.get("items", [])), todo)

# Ausgeschaltet: nichts – und nach dem Einschalten keine Nachholung
call("api/prefs/game", {"key": "notify", "on": False}, b)
quiet_name = "Stille " + run
m = mark()
st, r = call("api/lands", {"name": quiet_name, "dimension": OW, "chunks": flat(free_chunks(a, 1))}, a)
quiet = r["id"]
call(f"api/lands/{quiet}/invite", {"player": b_name}, a)
time.sleep(5)
check("Ausgeschaltet: keine Nachricht", quiet_name not in since(m), since(m)[-400:])
call("api/prefs/game", {"key": "notify", "on": True}, b)
time.sleep(4)
check("Wieder an: keine Nachholung", quiet_name not in since(m))

# Zusammenfassung beim Einloggen (Carpet: "player X kill" trennt nur die Verbindung)
m = mark()
console(f"player {b_name} kill")
time.sleep(2)
respawn(b_name)
check("Nach dem Einloggen: Zusammenfassung „Zu erledigen“", wait_for(m, f"Nachricht an {b_name}: [Planer] Zu erledigen", 10), since(m)[-600:])
check("…mit der offenen Einladung", land_name in since(m) and quiet_name in since(m))

# ------------------------------------------------------------------ Land-Anzeige
x, z = chunks[0][0] * 16 + 8, chunks[0][1] * 16 + 8
m = mark()
check("Teleport ins Land", place(b_name, x, z))
check("Betreten: Land-Name über der Hotbar", wait_for(m, f"Land-Anzeige für {b_name}: ▶ {land_name} · von {a_name}"), since(m)[-400:])
m = mark()
check("Teleport in die Wildnis", place(b_name, x + 1600, z))
check("Verlassen: „Wildnis“", wait_for(m, f"Land-Anzeige für {b_name}: Wildnis"), since(m)[-400:])
m = mark()
console(f"execute as {b_name} run planer landanzeige aus")
check("/planer landanzeige aus antwortet", wait_for(m, "Land-Anzeige: aus."))
st, prefs = call("api/prefs", token=b)
check("…und die Webseite kennt den Schalter", prefs["game"]["landEnter"] is False, prefs)
m = mark()
moved = place(b_name, x, z)
time.sleep(3)
check("Ausgeschaltet: keine Land-Anzeige (Bob ist wirklich im Land)", moved and f"Land-Anzeige für {b_name}" not in since(m), since(m)[-300:])
console(f"execute as {b_name} run planer landanzeige an")

# ------------------------------------------------------------------ Todespunkt
# Feste Ausgangslage: Todespunkte aus früheren Läufen weg – danach muss es genau einer sein.
for x_ in call("api/markers", token=d)[1]["markers"]:
    if x_["type"] == "death" and x_["mine"]:
        call(f"api/markers/{x_['id']}/delete", {}, d)
m = mark()
console(f"damage {d_name} 1000 minecraft:generic")
check("Tod: Todespunkt gespeichert", wait_for(m, f"Nachricht an {d_name}: [Planer] ☠ Todespunkt gespeichert:"), since(m)[-400:])
st, mk = call("api/markers", token=d)
deaths = [x_ for x_ in mk["markers"] if x_["type"] == "death" and x_["mine"]]
check("…als genau ein privater Marker ☠", len(deaths) == 1 and deaths[0]["visibility"] == "private" and deaths[0]["symbol"] == "☠", deaths)
death_id = deaths[0]["id"] if deaths else None
st, mk_b = call("api/markers", token=b)
check("Andere sehen ihn nicht", not any(x_["id"] == death_id for x_ in mk_b["markers"]))
respawn(d_name)
m = mark()
check("Teleport für den zweiten Tod", place(d_name, x + 64, z))
time.sleep(1)
console(f"damage {d_name} 1000 minecraft:generic")
wait_for(m, f"Nachricht an {d_name}: [Planer] ☠ Todespunkt gespeichert:")
st, mk = call("api/markers", token=d)
deaths = [x_ for x_ in mk["markers"] if x_["type"] == "death" and x_["mine"]]
check("Zweiter Tod: derselbe Marker, verschoben", len(deaths) == 1 and deaths[0]["id"] == death_id and abs(deaths[0]["x"] - (x + 64)) <= 1, deaths)
death_x = deaths[0]["x"] if deaths else None
respawn(d_name)
m = mark()
console(f"execute as {d_name} run planer todespunkt")
check("/planer todespunkt zeigt Stelle und Richtung", wait_for(m, f"☠ Dein Todespunkt: {death_x} /") and "Blöcke Richtung" in since(m), since(m)[-400:])
console(f"execute as {d_name} run planer todespunkt aus")
time.sleep(1)
m = mark()
console(f"damage {d_name} 1000 minecraft:generic")
time.sleep(3)
check("Ausgeschaltet: kein neuer Todespunkt", f"{d_name} died" in since(m) and "Todespunkt gespeichert" not in since(m), since(m)[-400:])
respawn(d_name)
call("api/prefs/game", {"key": "deathMarker", "on": True}, d)

# ------------------------------------------------------------------ /planer
m = mark()
console(f"execute as {b_name} run planer")
check("/planer: Liste „Zu erledigen“", wait_for(m, "Alle Befehle: /planer hilfe") and "Zu erledigen" in since(m), since(m)[-400:])
m = mark()
console(f"execute as {b_name} run planer hilfe")
check("/planer hilfe nennt alle Befehle", wait_for(m, "/planer marker") and "/tc" in since(m) and "/planer todespunkt" in since(m))
m = mark()
console(f"execute as {b_name} run planer marker Testmarker {run}")
check("/planer marker setzt einen Marker", wait_for(m, f"Marker »Testmarker {run}« gesetzt bei"), since(m)[-300:])
st, mk_b = call("api/markers", token=b)
own = [x_ for x_ in mk_b["markers"] if x_["name"] == f"Testmarker {run}"]
check("…privat, an Bobs Position", len(own) == 1 and own[0]["visibility"] == "private" and abs(own[0]["x"] - x) <= 1, own)

# ------------------------------------------------------------------ Team-Chat
tag = "S" + run[:3]
st, r = call("api/teams", {"name": "Testteam Spiel " + run, "tag": tag, "color": "#33aa55", "visibility": "private"}, a)
team = r["id"]
call(f"api/teams/{team}/invite", {"player": b_name}, a)
call(f"api/teams/{team}/accept", {}, b)
m = mark()
console(f"execute as {a_name} run tc Hallo {run}")
line = f"[Team {tag}] {a_name}: Hallo {run}"
check("/tc erreicht Teammitglieder (auch im privaten Team)", wait_for(m, f"Team-Chat an {b_name}: {line}"), since(m)[-400:])
check("…der Absender sieht seine Nachricht", f"Team-Chat an {a_name}: {line}" in since(m))
check("…andere nicht", f"Team-Chat an {d_name}" not in since(m))
check("…und sie steht im Server-Log wie normaler Chat", f"[Server thread/INFO]: System chat: {line}" in since(m))
m = mark()
console(f"execute as {d_name} run tc Hallo")
check("Ohne Team: freundliche Fehlermeldung", wait_for(m, "Du bist in keinem Team"), since(m)[-300:])

# ------------------------------------------------------------------ Handel: überboten (live)
st, r = call("api/trades", {"requestId": rid(), "kind": "auction", "title": "Spieltest " + run, "category": "tools", "itemName": "Spitzhacke",
                            "startPrice": 2, "durationHours": 1}, a)
auction = r["id"]
m = mark()
call(f"api/trades/{auction}/bid", {"requestId": rid(), "amount": 2}, b)
check("Verkäufer: neues Gebot im Spiel", wait_for(m, f"Nachricht an {a_name}: [Planer] • Neues Gebot auf »Spieltest {run}«: 2 Diamanten von {b_name}"),
      since(m)[-400:])
m = mark()
call(f"api/trades/{auction}/bid", {"requestId": rid(), "amount": 5}, d)
check("Bieter: „Überboten“ im Spiel", wait_for(m, f"Nachricht an {b_name}: [Planer] ⚠ Überboten: »Spieltest {run}« (jetzt 5 Diamanten)"),
      since(m)[-400:])
check("…ohne zu verraten, wer", f"Überboten: »Spieltest {run}« (jetzt 5 Diamanten) von" not in since(m))

# ------------------------------------------------------------------ Aufräumen
call(f"api/trades/{auction}/cancel", {"requestId": rid(), "reason": "Test fertig"}, op)
call(f"api/teams/{team}/dissolve", {"confirmName": "Testteam Spiel " + run}, a)
for lid in (land, quiet):
    call(f"api/lands/{lid}/delete", {}, a)
for mid in [x_["id"] for x_ in own]:
    call(f"api/markers/{mid}/delete", {}, b)
if death_id:
    call(f"api/markers/{death_id}/delete", {}, d)
sys.exit(check.done())
