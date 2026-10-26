"""Absturz-Test: Der Server stirbt (kill -9), bevor Minecraft gespeichert hat – bleibt der Statistik-Verlauf erhalten?

Minecraft speichert seine Statistik-Dateien nur alle paar Minuten, der Planer öfter. Nach einem Absturz ist Minecrafts
Spielzeit deshalb kleiner als der letzte Stand des Planers. Früher hat der Planer das für „Statistik gelöscht“ gehalten und
den ganzen Tagesverlauf weggeworfen.

Ablauf:
  1. python3 crash_check.py wait <datei> <code-A>   (wartet, bis der Planer neuer gespeichert hat als Minecraft, und merkt sich den Stand)
  2. SOFORT danach: kill -9 <PID des Servers>, Server wieder starten, Spieler A wieder einloggen
  3. python3 crash_check.py compare <datei>

Die Datei enthält ein Sitzungs-Token des Testservers – nicht weitergeben, nicht committen.
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, call, login  # noqa: E402
from gamelib import TS  # noqa: E402

if len(sys.argv) < 3 or not TS or sys.argv[1] not in ("wait", "compare") or (sys.argv[1] == "wait" and len(sys.argv) < 4):
    raise SystemExit(__doc__)
mode, path = sys.argv[1], sys.argv[2]


def minecraft_play_time(uuid):
    """Was Minecraft zuletzt in world/players/stats/<uuid>.json gespeichert hat (Millisekunden)."""
    with open(os.path.join(TS, "world", "players", "stats", uuid + ".json"), encoding="utf-8") as f:
        return json.load(f)["stats"]["minecraft:custom"].get("minecraft:play_time", 0) * 50


def planner_play_time(uuid):
    """Was der Planer zuletzt in world/world-planner/stats.json gespeichert hat (Millisekunden)."""
    try:
        with open(os.path.join(TS, "world", "world-planner", "stats.json"), encoding="utf-8") as f:
            return json.load(f)["players"].get(uuid, {}).get("values", {}).get("playTime", 0)
    except (OSError, ValueError):
        return 0  # wird gerade geschrieben


def page(token, uuid):
    st, p = call(f"api/stats/players/{uuid}", token=token)
    if st != 200:
        raise SystemExit(f"Spielerseite: {st} {p}")
    return p


if mode == "wait":
    token, name = login(sys.argv[3])
    uuid = call("api/me", token=token)[1]["uuid"]
    end = time.time() + 420
    while time.time() < end:
        ahead = planner_play_time(uuid) - minecraft_play_time(uuid)
        if ahead >= 30_000:
            break
        time.sleep(1)
    else:
        raise SystemExit("Der Planer war nie 30 s weiter als Minecraft – kein Absturz-Fall herstellbar")
    p = page(token, uuid)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"token": token, "uuid": uuid, "name": name, "ahead": ahead, "before": p}, f)
    print(f"BEREIT: Der Planer ist {ahead / 1000:.0f} s weiter als Minecraft – jetzt kill -9")
    sys.exit(0)

with open(path, encoding="utf-8") as f:
    saved = json.load(f)
check = Checker("Absturz")
before, uuid = saved["before"], saved["uuid"]
after = page(saved["token"], uuid)
events = [e["text"] for e in after.get("events", [])]
check("Rücksprung erkannt (Minecraft hatte wirklich noch nicht gespeichert)", any("zurückgesprungen" in t for t in events), events[:3])
check("Gesamt = Minecrafts Zahl (kleiner als vor dem Absturz)", after["values"]["playTime"] < before["values"]["playTime"],
      (before["values"]["playTime"], after["values"]["playTime"]))
check("Zuwachs gestern unverändert", after["growth"]["yesterday"] == before["growth"]["yesterday"], (before["growth"]["yesterday"], after["growth"]["yesterday"]))
old_days = before["history"][:-1]  # alle Tage außer heute
new_days = [h for h in after["history"] if h["date"] in {d["date"] for d in old_days}]
check("Verlauf der Vortage unverändert", len(old_days) == 29 and new_days == old_days, [(x, y) for x, y in zip(old_days, new_days) if x != y][:2])
check("Spielzeit heute bleibt (inklusive der Minuten, die Minecraft vergessen hat)",
      after["growth"]["today"]["playTime"] >= before["growth"]["today"]["playTime"], (before["growth"]["today"], after["growth"]["today"]))
check("Aktiv heute ist nicht größer als die Spielzeit heute",
      after["growth"]["today"]["activeTime"] <= after["growth"]["today"]["playTime"] + 1500, after["growth"]["today"])
sys.exit(check.done())
