"""Neustart-Test: Bleiben Teams, Projekte, Handel, Favoriten, Spiel-Schalter, Aktivität, Länder, Marker und Logins erhalten?

Ablauf:
  1. python3 restart_check.py save <datei> <code-A> <code-B>   (legt Testdaten an und merkt sich den Stand)
  2. Server stoppen und wieder starten
  3. python3 restart_check.py compare <datei>                 (vergleicht; benutzt dieselben Logins – sie müssen den Neustart überleben)

Die Datei enthält Sitzungs-Tokens des Testservers – nicht weitergeben, nicht committen.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, call, cleanup_test_teams, login, rid, suffix  # noqa: E402

AREAS = ["lands", "markers", "teams", "projects", "trades", "prefs", "activity"]


def snapshot(token):
    out = {}
    for area in AREAS:
        st, r = call("api/" + area + ("?limit=200" if area == "activity" else ""), token=token)
        if st != 200:
            raise SystemExit(f"{area}: {st} {r}")
        out[area] = r
    return out


def normalize(snap):
    """Nur, was gespeichert sein muss – keine Zeitwerte, die sich bei jeder Anfrage ändern."""
    n = {}
    n["lands"] = sorted((l["id"], l["name"], tuple(l["chunks"]), tuple(o["uuid"] for o in l["owners"]), l.get("teamId"), l.get("notes")) for l in snap["lands"]["lands"])
    n["markers"] = sorted((m["id"], m["name"], m["x"], m["z"], m["visibility"]) for m in snap["markers"]["markers"])
    n["teams"] = sorted((t["id"], t["name"], t["tag"], tuple(sorted((m["uuid"], m["role"]) for m in t["members"]))) for t in snap["teams"]["teams"])
    n["projects"] = sorted((p["id"], p["name"], p["status"], tuple((t["text"], t["done"]) for t in p["tasks"]), len(p["updates"])) for p in snap["projects"]["projects"])
    n["trades"] = sorted((l["id"], l["title"], l["status"], l["bidCount"], l.get("highestBid")) for l in snap["trades"]["listings"])
    n["prefs"] = {k: sorted(v) for k, v in snap["prefs"]["favorites"].items()}
    n["game"] = snap["prefs"].get("game")  # Schalter fürs Spiel (seit 0.4)
    n["activity"] = [e["id"] for e in snap["activity"]["events"]]
    return n


if len(sys.argv) < 3:
    raise SystemExit(__doc__)
mode, path = sys.argv[1], sys.argv[2]

if mode == "save":
    a, a_name = login(sys.argv[3])
    b, b_name = login(sys.argv[4])
    cleanup_test_teams([a, b])
    run = suffix()
    st, r = call("api/teams", {"name": "Testteam Neustart " + run, "tag": "N" + run[:3]}, a)
    team = r["id"]
    call(f"api/teams/{team}/invite", {"player": b_name}, a)
    call(f"api/teams/{team}/accept", {}, b)
    call(f"api/teams/{team}/role", {"player": b_name, "role": "moderator"}, a)
    st, r = call("api/projects", {"name": "Neustart-Projekt " + run, "teamId": team, "visibility": "team", "tasks": ["Eins", "Zwei"]}, a)
    project = r["id"]
    p = call(f"api/projects/{project}", token=a)[1]
    call(f"api/projects/{project}/tasks/{p['tasks'][0]['id']}/edit", {"done": True}, a)
    call(f"api/projects/{project}/updates", {"text": "Vor dem Neustart"}, a)
    call("api/prefs/favorite", {"kind": "projects", "id": project, "on": True}, a)
    st, r = call("api/trades", {"requestId": rid(), "kind": "auction", "title": "Neustart-Auktion " + run, "category": "tools",
                                "itemName": "Angel", "startPrice": 2, "durationHours": 24}, a)
    listing = r["id"]
    bid_request = rid()
    call(f"api/trades/{listing}/bid", {"requestId": bid_request, "amount": 2}, b)
    json.dump({"a": a, "b": b, "bidRequest": bid_request, "listing": listing, "team": team, "project": project,
               "before": {"a": normalize(snapshot(a)), "b": normalize(snapshot(b))}}, open(path, "w"))
    print(f"Gespeichert in {path}. Jetzt den Server neu starten und 'compare' aufrufen.")
    sys.exit(0)

if mode == "compare":
    saved = json.load(open(path))
    check = Checker("Neustart")
    for who in ("a", "b"):
        st, me = call("api/me", token=saved[who])
        check(f"Login von Spieler {who.upper()} gilt nach dem Neustart noch", st == 200, me)
        if st != 200:
            continue
        after = normalize(snapshot(saved[who]))
        before = saved["before"][who]
        for area in AREAS:
            if area == "activity":
                # Nach dem Neustart können neue Einträge dazukommen – die alten müssen alle noch da sein
                missing = [e for e in before[area] if e not in after[area]]
                check(f"{who.upper()}: Aktivität vollständig", not missing, missing[:3])
                continue
            same = json.loads(json.dumps(before[area])) == json.loads(json.dumps(after[area]))
            check(f"{who.upper()}: {area} unverändert", same, f"vorher {before[area]} nachher {after[area]}" if not same else "")
        # Nach einem Update können neue Schalter dazukommen – die alten müssen gleich bleiben, neue stehen auf "an" (Standard)
        bg, ag = before.get("game"), after.get("game") or {}
        kept = bg is not None and all(ag.get(k) == v for k, v in bg.items())
        new_default = all(v is True for k, v in ag.items() if k not in (bg or {}))
        check(f"{who.upper()}: Spiel-Schalter unverändert", kept and new_default, f"vorher {bg} nachher {ag}")
    # Die requestId des Gebots kennt der Server noch: derselbe Klick wird nicht noch einmal ausgeführt
    st, r = call(f"api/trades/{saved['listing']}/bid", {"requestId": saved["bidRequest"], "amount": 2}, saved["b"])
    check("Gleiche requestId nach dem Neustart: kein zweites Gebot", st == 200 and r.get("repeated") is True, r)
    # Aufräumen
    team = call(f"api/teams/{saved['team']}", token=saved["a"])[1]
    call(f"api/projects/{saved['project']}/delete", {}, saved["a"])
    if "name" in team:
        call(f"api/teams/{saved['team']}/dissolve", {"confirmName": team["name"]}, saved["a"])
    sys.exit(check.done())

raise SystemExit(__doc__)
