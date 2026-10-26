"""Test: Events mit Kalender auf einem echten Server – Einladung im Chat, Zusage per Befehl, Erinnerung, Absage, Verschiebung.

Ein Event beginnt schon in 2 Minuten: So kommen "In 2 Minuten" und "Jetzt: … beginnt" in einem Lauf (dauert gut 2 Minuten).
Nachrichten an einzelne Spieler stehen als DEBUG-Zeile im Log (LOG_CONFIG=tests/integration/log4j2-debug.xml).

Aufruf: TESTSERVER_DIR=/pfad python3 events_test.py <code-A> <code-B> <code-D>
A, B und D müssen online sein (Fake-Spieler im Überlebensmodus, keine OPs).
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, call, login, suffix  # noqa: E402
from gamelib import TS, console, mark, since, wait_for  # noqa: E402

if len(sys.argv) < 4 or not TS:
    raise SystemExit(__doc__)

check = Checker("Events")
a, a_name = login(sys.argv[1])
b, b_name = login(sys.argv[2])
d, d_name = login(sys.argv[3])
run = suffix()
MIN = 60_000
for tok in (a, b, d):
    call("api/prefs/game", {"key": "notify", "on": True}, tok)

# Aufräumen: eigene Test-Events früherer Läufe (Name beginnt mit „Test-“), damit die Grenze von 5 nicht voll wird
for e in call("api/events", token=a)[1].get("events", []):
    if e["mine"] and e["title"].startswith("Test-"):
        call(f"api/events/{e['id']}/delete", {}, a)


def message_to(m, player, text, timeout=8):
    return wait_for(m, f"Nachricht an {player}: [Planer] ", timeout) and wait_for(m, text, timeout)


st, meta = call("api/meta", token=a)
check("Server bietet Events an", meta.get("gameFeatures", {}).get("events") is True and "build" in meta.get("eventKinds", {}), meta.get("gameFeatures"))
check("Rennen gibt es nicht mehr: /api/races", call("api/races", token=a)[0] == 404)
m = mark()
console(f"execute as {a_name} run rennen liste")
check("…und /rennen im Spiel auch nicht", wait_for(m, "Unknown or incomplete command") or wait_for(m, "Incorrect argument for command"), since(m)[-300:])

# ------------------------------------------------------------------ Einladung, Zusage im Spiel
now = int(time.time() * 1000)
later = "Test-Bauabend " + run
m = mark()
st, r = call("api/events", {"title": later, "kind": "build", "start": now + 3 * 3600_000, "minutes": 90,
                            "place": {"dimension": "minecraft:overworld", "x": 10, "y": 70, "z": -20}}, a)
check("Event anlegen", st == 200 and r.get("id"), (st, r))
later_id = r.get("id")
check("Bob bekommt die Einladung im Chat mit [Komme]",
      message_to(m, b_name, f"Event: »{later}« am ") and f"– kommst du? [Komme]" in since(m), since(m)[-600:])
check("…Dana auch", f"Nachricht an {d_name}: [Planer] ✉ Event: »{later}«" in since(m), since(m)[-600:])
check("…aber Alice nicht (sie lädt ein)", f"Nachricht an {a_name}: [Planer] ✉ Event: »{later}«" not in since(m))
st, todo = call("api/todo", token=b)
check("/api/todo: dieselbe Einladung", any(i["text"].startswith(f"Event: »{later}« am ") and i["kind"] == "invite" for i in todo.get("items", [])), todo)

m = mark()
console(f"execute as {d_name} run planer events")
check("/planer events listet das Event mit Knöpfen", wait_for(m, "Nächste Events") and wait_for(m, f"»{later}«") and "[Komme] [Vielleicht] [Nein]" in since(m),
      since(m)[-500:])
m = mark()
console(f"execute as {d_name} run planer event {later_id} ja")
check("/planer event <id> ja: gespeichert", wait_for(m, f"Gespeichert: »{later}« – Komme."), since(m)[-300:])
st, e = call(f"api/events/{later_id}", token=d)
check("…und die Webseite weiß es", e.get("myAnswer") == "yes" and e["counts"]["yes"] == 2, e.get("counts"))
m = mark()
console(f"execute as {d_name} run planer event gibtsnicht ja")
check("Unbekanntes Event: Fehlermeldung", wait_for(m, "Dieses Event gibt es nicht (mehr)."), since(m)[-300:])

# ------------------------------------------------------------------ Verschieben und Absagen
m = mark()
st, r = call(f"api/events/{later_id}/edit", {"start": now + 4 * 3600_000}, a)
check("Alice verschiebt um eine Stunde", st == 200, (st, r))
check("Dana (hat zugesagt) erfährt es", message_to(m, d_name, f"Verschoben: »{later}« jetzt am "), since(m)[-400:])
check("…Bob (hat nicht geantwortet) nicht", f"Nachricht an {b_name}: [Planer] ⚠ Verschoben" not in since(m))
st, r = call(f"api/events/{later_id}/edit", {"title": "Bobs Abend"}, b)
check("Bob darf Alices Event nicht ändern", st == 403, (st, r))
m = mark()
st, r = call(f"api/events/{later_id}/cancel", {"reason": "Server-Update"}, a)
check("Alice sagt ab", st == 200, (st, r))
check("Dana bekommt die Absage", message_to(m, d_name, f"Abgesagt: »{later}« (") and "– Server-Update" in since(m), since(m)[-400:])
st, r = call(f"api/events/{later_id}/answer", {"answer": "yes"}, b)
check("Abgesagt: zusagen geht nicht mehr", st == 409, (st, r))

# ------------------------------------------------------------------ Erinnerung und Start
soon = "Test-Treffen " + run
start = int(time.time() * 1000) + 2 * MIN + 30_000
st, r = call("api/events", {"title": soon, "kind": "meet", "start": start, "minutes": 30,
                            "place": {"dimension": "minecraft:overworld", "x": 0, "z": 0}}, a)
soon_id = r.get("id")
check("Event in gut 2 Minuten", st == 200, (st, r))
m = mark()
call(f"api/events/{soon_id}/answer", {"answer": "maybe"}, b)
check("Bob sagt „Vielleicht“ – Erinnerung kommt gleich", message_to(m, b_name, f"In 3 Minuten: »{soon}« – Treffpunkt: 0 ~ 0", 10)
      or message_to(m, b_name, f"In 2 Minuten: »{soon}« – Treffpunkt: 0 ~ 0", 10), since(m)[-500:])
time.sleep(5)  # der Notifier fragt alle 2 Sekunden – käme sie doppelt, wäre sie jetzt da
check("…und nur einmal", since(m).count(f"Nachricht an {b_name}: [Planer] ⚠ In ") == 1, since(m)[-500:])
check("Dana hat nicht geantwortet: keine Erinnerung", f"Nachricht an {d_name}: [Planer] ⚠ In " not in since(m))
st, r = call(f"api/events/{soon_id}/navigate", {}, b)
check("Im Spiel hinführen (Bob ist online)", st == 200, (st, r))
check("…der Wegweiser startet", message_to(m, b_name, f"Wegweiser zu »{soon}« (von der Webseite)"), since(m)[-400:])
wait = (start - int(time.time() * 1000)) / 1000 + 10
m = mark()
check("Zum Start: „Jetzt: … beginnt“", message_to(m, b_name, f"Jetzt: »{soon}« beginnt – Treffpunkt: 0 ~ 0", wait), since(m)[-500:])
st, e = call(f"api/events/{soon_id}", token=b)
check("Die Webseite sagt „läuft gerade“", e.get("state") == "running", e.get("state"))

# ------------------------------------------------------------------ Rückblick und Aufräumen
st, recap = call("api/recap", token=a)
check("Rückblick: das gestartete Event steht drin", any(x["id"] == soon_id for x in recap.get("events", {}).get("past", [])), recap.get("events"))
for eid in (later_id, soon_id):
    st, r = call(f"api/events/{eid}/delete", {}, a)
    check("Aufräumen: löschen", st == 200, (st, r))
sys.exit(check.done())
