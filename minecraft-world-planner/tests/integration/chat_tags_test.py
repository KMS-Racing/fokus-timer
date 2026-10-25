"""Test: Team-Kürzel im Spiel (Scoreboard-Teams). Liest dafür die Server-Konsole mit.
Aufruf: TESTSERVER_DIR=/pfad/zum/testserver python3 chat_tags_test.py <code-A> <code-B> <code-D>
A, B und D müssen online sein (Fake-Spieler) und dürfen keine OPs sein. Braucht chatTeamTags=true (Standard).
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, call, cleanup_test_teams, login, suffix  # noqa: E402

TS = os.environ.get("TESTSERVER_DIR")
if len(sys.argv) < 4 or not TS:
    raise SystemExit(__doc__)
LOG = os.path.join(TS, "server.log")
CMDS = os.path.join(TS, "cmds.txt")


def console(command, wait=1.5):
    """Schickt einen Befehl an die Server-Konsole und gibt die neuen Log-Zeilen zurück."""
    with open(LOG, encoding="utf-8", errors="replace") as f:
        before = len(f.readlines())
    with open(CMDS, "a", encoding="utf-8") as f:
        f.write(command + "\n")
    time.sleep(wait)
    with open(LOG, encoding="utf-8", errors="replace") as f:
        return "".join(f.readlines()[before:])


def sb_name(team_id):
    return "wpt_" + team_id.replace("-", "")[:12]


def members(team_id):
    return console(f"team list {sb_name(team_id)}")


check = Checker("Chat-Tags")
a, a_name = login(sys.argv[1])
b, b_name = login(sys.argv[2])
d, d_name = login(sys.argv[3])
cleanup_test_teams([a, b, d])
time.sleep(2)
run = suffix()
tag = "C" + run[:3]

st, meta = call("api/meta", token=a)
check("Server meldet chatTeamTags an", meta.get("chatTeamTags") is True, meta.get("chatTeamTags"))

st, r = call("api/teams", {"name": "Testteam Chat " + run, "tag": tag, "color": "#ff8800", "visibility": "public"}, a)
tid = r["id"]
call(f"api/teams/{tid}/invite", {"player": b_name}, a)
call(f"api/teams/{tid}/accept", {}, b)
time.sleep(2.5)  # der Abgleich läuft jede Sekunde
out = members(tid)
check("Scoreboard-Team mit beiden Mitgliedern", a_name in out and b_name in out and "member" in out, out)
out = console(f"execute as {a_name} run say Hallo {run}")
check("Chat zeigt das Kürzel vor dem Namen", f"[{tag}] {a_name}] Hallo {run}" in out, out)

# Fremdes Scoreboard-Team wird nicht angefasst
console("team add wptestfremd")
console(f"team join wptestfremd {d_name}")
call(f"api/teams/{tid}/invite", {"player": d_name}, a)
call(f"api/teams/{tid}/accept", {}, d)
time.sleep(2.5)
out = console("team list wptestfremd")
check("Spieler in fremdem Scoreboard-Team bleibt dort", d_name in out, out)
check("…und bekommt kein Kürzel", d_name not in members(tid))
console("team remove wptestfremd")

# Privat = kein Kürzel
call(f"api/teams/{tid}/edit", {"visibility": "private"}, a)
time.sleep(2.5)
out = console("team list")
check("Privates Team: kein Scoreboard-Team", sb_name(tid) not in out, out)
call(f"api/teams/{tid}/edit", {"visibility": "public", "tag": tag[:-1] + "Z"}, a)
time.sleep(2.5)
out = console(f"execute as {a_name} run say Neu {run}")
check("Neues Kürzel im Chat", f"[{tag[:-1]}Z] {a_name}] Neu {run}" in out, out)

# Austreten und Auflösen
call(f"api/teams/{tid}/leave", {}, b)
time.sleep(2.5)
out = members(tid)
check("Ausgetretener Spieler verliert das Kürzel", b_name not in out and a_name in out, out)
team = call(f"api/teams/{tid}", token=a)[1]
call(f"api/teams/{tid}/dissolve", {"confirmName": team["name"]}, a)
time.sleep(2.5)
out = console("team list")
check("Aufgelöstes Team: Scoreboard-Team ist weg", sb_name(tid) not in out, out)
out = console(f"execute as {a_name} run say Ohne {run}")
check("Danach wieder ohne Kürzel im Chat", f"[{a_name}] Ohne {run}" in out, out)
sys.exit(check.done())
