"""Test: Rennstrecken mit Zeitmessung auf einem echten Server.

Alice baut im Spiel einen Rundkurs (/rennen neu, /rennen tor, /rennen fertig), Bob fährt ihn ab. Gefahren wird mit vielen
kleinen Teleports (1 Block pro Schritt) – so bewegt sich ein Fake-Spieler zuverlässig über die Tore. Geprüft werden
Rundenzeiten, Abschnitte, Bestenliste, ausgelassene Tore, Teleport-Erkennung, Fahrzeug-Regel und die Rechte.

Aufruf: TESTSERVER_DIR=/pfad python3 race_test.py <code-A> <code-B>
A (baut) und B (fährt) müssen online sein (Fake-Spieler im Überlebensmodus, keine OPs). Mit RACE_KEEP=1 bleibt die Strecke stehen.
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, call, login, suffix  # noqa: E402
from gamelib import TS, console, mark, platform, remove_platform, safe, since, teleport, wait_for  # noqa: E402

if len(sys.argv) < 3 or not TS:
    raise SystemExit(__doc__)

check = Checker("Rennen")
a, a_name = login(sys.argv[1])
b, b_name = login(sys.argv[2])
run = suffix()
NAME = "Testkurs " + run
X, Y, Z = 600, 200, 600  # Plattform hoch in der Luft, weit weg von den anderen Tests
console("difficulty peaceful")

# Teststrecken früherer Läufe aufräumen: Jeder darf höchstens 5 Strecken haben, und mit RACE_KEEP=1 bleibt jedes Mal eine stehen.
# Nur Alices eigene Strecken, deren Name mit „Testkurs “ beginnt.
a_uuid = call("api/me", token=a)[1]["uuid"]
for old in call("api/races", token=a)[1].get("tracks", []):
    if old["owner"] == a_uuid and old["name"].startswith("Testkurs "):
        call(f"api/races/{old['id']}/delete", {"confirmName": old["name"]}, a)

# Quadrat, 120 Blöcke pro Runde: Start/Ziel Richtung Osten, dann Süden, Westen, Norden (Minecraft: 0° = Süden, -90° = Osten).
# Die Tore stehen auf der Blockmitte (tp mit ganzen Zahlen = +0,5) – deshalb endet jede Runde bei x = 2, also hinter der Linie.
GATES = [(0, 0, -90), (20, 10, 0), (0, 20, 90), (-20, 10, 180)]
LOOP = [(20, 0), (20, 20), (-20, 20), (-20, 0), (2, 0)]


def drive(name, path, start, step=1.0, pause=0.05):
    """Fährt einen Weg mit kleinen Teleports ab (ein Block pro Schritt). Gibt die Endposition zurück."""
    x, z = start
    for tx, tz in path:
        while abs(tx - x) > 1e-9 or abs(tz - z) > 1e-9:
            dx, dz = tx - x, tz - z
            d = (dx * dx + dz * dz) ** 0.5
            move = min(step, d)
            x, z = x + dx / d * move, z + dz / d * move
            console(f"tp {name} {X + x:.2f} {Y + 1} {Z + z:.2f}")
            time.sleep(pause)
    return x, z


def reply(m, text, timeout=6):
    return wait_for(m, text, timeout)


# ------------------------------------------------------------------ Strecke bauen (Alice)
st, meta = call("api/meta", token=a)
check("Server bietet Rennen an", meta.get("gameFeatures", {}).get("races") is True and "boat" in meta.get("raceVehicles", {}), meta.get("gameFeatures"))
check("Plattform für die Strecke", platform(a_name, X, Z + 10, Y, 26))
console(f"tp {b_name} {X - 24} {Y + 1} {Z}")
safe(b_name)
m = mark()
console(f'execute as {a_name} run rennen neu "{NAME}" runde zu_fuss')
check("/rennen neu legt die Strecke an", reply(m, f"Strecke »{NAME}« angelegt (Rundkurs, Fahrzeug: zu Fuß)"), since(m)[-300:])
for i, (gx, gz, yaw) in enumerate(GATES):
    teleport(a_name, X + gx, Y + 1, Z + gz)
    console(f"tp {a_name} {X + gx} {Y + 1} {Z + gz} {yaw} 0")
    time.sleep(0.4)
    m = mark()
    console(f"execute as {a_name} run rennen tor")
    expected = "Start/Ziel gesetzt" if i == 0 else f"Tor {i} gesetzt"
    check(f"/rennen tor: {expected}", reply(m, expected), since(m)[-300:])
m = mark()
console(f"execute as {a_name} run rennen tor")
check("Doppeltes Tor an derselben Stelle wird abgelehnt", reply(m, "Hier steht schon ein Tor"), since(m)[-300:])
m = mark()
console(f"execute as {a_name} run rennen fertig")
check("/rennen fertig", reply(m, f"»{NAME}« ist fertig (4 Tore)"), since(m)[-300:])
st, races = call("api/races", token=b)
track = next((t for t in races.get("tracks", []) if t["name"] == NAME), None)
check("Strecke steht in /api/races", track is not None and track["ready"] and track["gateCount"] == 4, track)
tid = track["id"] if track else None

# ------------------------------------------------------------------ Fahren (Bob)
m = mark()
console(f'execute as {b_name} run rennen start "{NAME}"')
check("/rennen start: Uhr wartet auf die Startlinie", reply(m, f"Rennen-Nachricht an {b_name}: [Rennen] Zeitmessung für »{NAME}« bereit"), since(m)[-300:])
pos = drive(b_name, [(-5, 0)], (-24, 0), step=5, pause=0.15)
m = mark()
pos = drive(b_name, LOOP + LOOP, pos)
check("Über die Startlinie: Los!", reply(m, f"Rennen-Nachricht an {b_name}: [Rennen] 🏁 Los! »{NAME}«"), since(m)[-300:])
check("Runde 1 ist ein Streckenrekord", reply(m, f"Rennen-Nachricht an {b_name}: [Rennen] Runde 1: ", 10) and "Streckenrekord!" in since(m), since(m)[-500:])
check("Runde 2 wird auch gemessen", reply(m, f"Rennen-Nachricht an {b_name}: [Rennen] Runde 2: ", 10), since(m)[-500:])
st, detail = call(f"api/races/{tid}", token=a)
board = detail.get("leaderboard", [])
check("Bestenliste: Bob vorn", board and board[0]["name"] == b_name, board)
if board:
    lap = board[0]
    check("Rundenzeit plausibel (120 Blöcke in kleinen Schritten)", 3_000 <= lap["ms"] <= 40_000, lap["ms"])
    check("Vier Abschnitte, zusammen die Rundenzeit", len(lap.get("sectors", [])) == 4 and abs(sum(lap["sectors"]) - lap["ms"]) <= 4, lap)
check("Bestenliste hat nur Bobs beste Runde", len(board) == 1, board)
check("Letzte Runden: zwei", len(detail.get("recent", [])) >= 2, detail.get("recent"))

# Abkürzung: Tor 2 auslassen
m = mark()
pos = drive(b_name, [(20, 0), (20, 13), (-20, 13), (-20, 0), (2, 0)], pos)  # z = 13: 7,5 Blöcke an Tor 2 vorbei
check("Ausgelassenes Tor: Runde zählt nicht", reply(m, "Runde 3 zählt nicht: Tor 2 ausgelassen", 10), since(m)[-400:])

# Teleport mitten in der Runde
pos = drive(b_name, [(10, 0)], pos)
m = mark()
console(f"tp {b_name} {X + 10} {Y + 1} {Z + 60}")
check("Teleport erkannt", reply(m, "zählt nicht: Teleport erkannt", 6), since(m)[-300:])
teleport(b_name, X - 5, Y + 1, Z)
pos = (-5, 0)

# Bestenliste im Spiel
m = mark()
console(f'execute as {b_name} run rennen zeiten "{NAME}"')
check("/rennen zeiten: Bestenliste", reply(m, f"Bestenliste »{NAME}«") and reply(m, f"1. {b_name} "), since(m)[-400:])

# ------------------------------------------------------------------ Rechte und Fahrzeug-Regel
m = mark()
console(f'execute as {b_name} run rennen bearbeiten "{NAME}"')
check("Bob darf Alices Strecke nicht umbauen", reply(m, "Nur wer die Strecke angelegt hat"), since(m)[-300:])
st, r = call(f"api/races/{tid}/rename", {"name": "Bobs Kurs"}, b)
check("…auch nicht über die Webseite", st == 403, (st, r))
st, r = call(f"api/races/{tid}/vehicle", {"vehicle": "boat"}, a)
check("Alice stellt auf „nur Boot“ um", st == 200, (st, r))
st, detail = call(f"api/races/{tid}", token=a)
check("Neue Version, alte Zeiten im Archiv", detail.get("version") == 2 and detail.get("leaderboard") == [] and detail.get("archived") == 1,
      (detail.get("version"), detail.get("archived")))
m = mark()
console(f'execute as {b_name} run rennen start "{NAME}"')
pos = drive(b_name, [(-5, 0), (20, 0)], pos)
check("Zu Fuß auf der Boot-Strecke: Warnung beim Start", reply(m, "Achtung: Diese Runde zählt nicht – nur im Boot erlaubt", 6), since(m)[-400:])
m = mark()
console(f"execute as {b_name} run rennen stop")
check("/rennen stop", reply(m, "Zeitmessung beendet: auf eigenen Wunsch"), since(m)[-300:])

# Aufräumen (RACE_KEEP=1 lässt die Strecke stehen – für den Browser-Test danach und Screenshots der Webseite)
if os.environ.get("RACE_KEEP"):
    # Die aktuelle Version soll Zeiten haben, sonst kann der Browser-Test Bestenliste und Abschnitte nicht prüfen
    call(f"api/races/{tid}/vehicle", {"vehicle": "foot"}, a)
    teleport(b_name, X - 5, Y + 1, Z)
    m = mark()
    console(f'execute as {b_name} run rennen start "{NAME}"')
    drive(b_name, LOOP + LOOP, (-5, 0))
    reply(m, f"Rennen-Nachricht an {b_name}: [Rennen] Runde 2: ", 10)
    console(f"execute as {b_name} run rennen stop")
    st, detail = call(f"api/races/{tid}", token=a)
    check("Behalten: Version 3 mit Bobs Bestzeit", detail.get("version") == 3 and len(detail.get("leaderboard", [])) == 1, (detail.get("version"), detail.get("leaderboard")))
    sys.exit(check.done())
st, r = call(f"api/races/{tid}/delete", {"confirmName": "falsch"}, a)
check("Löschen nur mit richtigem Namen", st == 400, (st, r))
st, r = call(f"api/races/{tid}/delete", {"confirmName": NAME}, a)
check("Alice löscht die Strecke", st == 200 and call(f"api/races/{tid}", token=a)[0] == 404, (st, r))
remove_platform(X, Z + 10, Y, 26)
sys.exit(check.done())
