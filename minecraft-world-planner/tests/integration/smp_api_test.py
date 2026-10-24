"""API-Test für die SMP-Plattform: Teams, Team-Länder/-Projekte/-Marker, Projekte, Konto, Aktivität, Export/Import.
Aufruf: python3 smp_api_test.py <code-A> <code-B> <code-D> <code-admin>
A, B und D dürfen keine OPs sein, der vierte Code muss von einem OP stammen. Alle vier müssen online gewesen sein.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, by_id, call, cleanup_test_teams, flat, free_chunks, login, suffix  # noqa: E402

if len(sys.argv) < 5:
    raise SystemExit(__doc__)
check = Checker("SMP-API")
a, a_name = login(sys.argv[1])
b, b_name = login(sys.argv[2])
d, d_name = login(sys.argv[3])
op, _ = login(sys.argv[4])
run = suffix()
cleanup_test_teams([a, b, d])

# --- Konto und Auswahl-Listen
st, me = call("api/me", token=a)
check("me: Teams, Einladungen, Karten, Handelseinheit", st == 200 and me["teams"] == [] and "pendingTeamInvites" in me
      and "mapIds" in me["maps"] and me["tradeUnit"], me)
st, meta = call("api/meta", token=a)
check("meta: Marker-Typen, Team-Rollen, Handels-Kategorien inkl. Hilfe", st == 200 and "spawner" in meta["markerTypes"]
      and meta["teamRoles"]["recruit"] == "Anwärter" and "help_build" in meta["tradeCategories"], meta)
st, people = call("api/people", token=a)
names = {p["name"]: p for p in people.get("players", [])}
check("people: alle bekannten Spieler mit Rolle", st == 200 and a_name in names and names[a_name]["role"] == "player", people)
check("people: Admin ist als Admin markiert", any(p["role"] == "admin" for p in people["players"]))

# --- Team gründen und Mitglieder nur mit Zustimmung
team_name = "Testteam " + run
tag = "T" + run[:3]
st, r = call("api/teams", {"name": team_name, "tag": tag.lower(), "description": "Wir bauen <b>Burgen</b>", "joinPolicy": "invite"}, a)
check("A gründet ein Team", st == 200, r)
tid = r.get("id")
st, t = call(f"api/teams/{tid}", token=a)
check("Tag wird groß geschrieben, A ist Owner", t["tag"] == tag and t["myRole"] == "owner", t)
st, r = call("api/teams", {"name": "Zweit " + run, "tag": "Z" + run[:3]}, a)
check("nur ein Team pro Spieler (Standard)", st == 409, r)
st, r = call("api/teams", {"name": team_name.upper(), "tag": "X" + run[:3]}, b)
check("Teamname schon vergeben (Groß/klein egal)", st == 409, r)

st, r = call(f"api/teams/{tid}/invite", {"player": b_name}, a)
check("A lädt B ein", st == 200 and r.get("inviteId"), r)
st, t = call(f"api/teams/{tid}", token=b)
check("B ist noch KEIN Mitglied, sieht aber die Einladung", "myRole" not in t and "myInvite" in t, t)
check("B: Einladung im Konto gezählt", call("api/me", token=b)[1]["pendingTeamInvites"] == 1)
st, r = call(f"api/teams/{tid}/accept", {}, b)
check("B nimmt an", st == 200, r)
st, r = call(f"api/teams/{tid}/invite", {"player": d_name}, b)
check("Mitglied B darf nicht einladen", st == 403, r)
st, r = call(f"api/teams/{tid}/role", {"player": b_name, "role": "moderator"}, a)
check("A macht B zum Moderator", st == 200, r)
st, r = call(f"api/teams/{tid}/role", {"player": b_name, "role": "owner"}, a)
check("Owner wird man nicht per Rolle", st == 400, r)
st, r = call(f"api/teams/{tid}/invite", {"player": d_name}, b)
check("Moderator B lädt D ein", st == 200, r)
inv = r.get("inviteId")
st, r = call(f"api/teams/{tid}/decline", {}, d)
check("D lehnt ab", st == 200, r)
st, r = call(f"api/teams/{tid}/accept", {}, d)
check("abgelehnte Einladung lässt sich nicht annehmen", st == 404, r)

# --- Privat / Bewerbung
st, r = call(f"api/teams/{tid}/edit", {"visibility": "private"}, a)
check("A stellt das Team auf privat", st == 200, r)
st, r = call(f"api/teams/{tid}", token=d)
check("privates Team existiert für Fremde nicht", st == 404, r)
check("…und fehlt in der Liste", by_id(call("api/teams", token=d)[1]["teams"], tid) is None)
check("Admin sieht private Teams", call(f"api/teams/{tid}", token=op)[0] == 200)
st, r = call(f"api/teams/{tid}/edit", {"visibility": "public", "joinPolicy": "apply"}, b)
check("Moderator darf Team-Einstellungen nicht ändern", st == 403, r)
st, r = call(f"api/teams/{tid}/edit", {"visibility": "public", "joinPolicy": "apply", "tag": "!"}, a)
check("ungültiger Tag -> 400", st == 400, r)
st, t = call(f"api/teams/{tid}", token=a)
check("…und nichts wurde halb geändert", t["visibility"] == "private" and t["joinPolicy"] == "invite", t)
call(f"api/teams/{tid}/edit", {"visibility": "public", "joinPolicy": "apply"}, a)
st, r = call(f"api/teams/{tid}/apply", {"message": "Ich kann gut <script>bauen</script>"}, d)
check("D bewirbt sich", st == 200 and r.get("applicationId"), r)
app = r.get("applicationId")
st, t = call(f"api/teams/{tid}", token=d)
check("D ist noch kein Mitglied", "myRole" not in t and t.get("myApplication") == app, t)
st, t = call(f"api/teams/{tid}", token=b)
check("Moderator sieht die Bewerbung mit Nachricht", any(x["id"] == app and "<script>" in x["message"] for x in t["applications"]), t.get("applications"))
st, r = call(f"api/teams/{tid}/applications/{app}/accept", {}, b)
check("Moderator nimmt die Bewerbung an", st == 200, r)
st, t = call(f"api/teams/{tid}", token=d)
check("D startet als Anwärter", t.get("myRole") == "recruit", t)
st, r = call(f"api/teams/{tid}/role", {"player": d_name, "role": "moderator"}, b)
check("Moderator kann niemanden zum Moderator machen", st == 403, r)
st, r = call(f"api/teams/{tid}/kick", {"player": b_name}, d)
check("Anwärter wirft niemanden raus", st == 403, r)

# --- Team-Land, Team-Projekt, Team-Marker
c = free_chunks(b, 2)
st, r = call("api/lands", {"name": "Teamland " + run, "dimension": "minecraft:overworld", "chunks": flat(c)}, b)
land = r.get("id")
check("B legt ein Land an", st == 200, r)
st, r = call(f"api/lands/{land}/team", {"teamId": tid}, b)
check("B ordnet das Land dem Team zu", st == 200, r)
check("Land zeigt die Team-Zuordnung", by_id(call("api/lands", token=d)[1]["lands"], land).get("teamId") == tid)
st, r = call(f"api/lands/{land}/team", {"teamId": ""}, d)
check("Anwärter (kein Besitzer) löst die Zuordnung nicht", st == 403, r)
st, r = call("api/projects", {"name": "Teamburg " + run, "teamId": tid, "visibility": "team", "tasks": ["Mauern", "Turm"]}, b)
project = r.get("id")
check("B legt ein Team-Projekt an", st == 200, r)
st, p = call(f"api/projects/{project}", token=d)
check("Anwärter sieht das Team-Projekt, darf es aber nicht bearbeiten", st == 200 and p["can"]["edit"] is False, p)
st, r = call(f"api/projects/{project}/tasks", {"text": "Hack"}, d)
check("…Aufgabe hinzufügen -> 403", st == 403, r)
st, r = call(f"api/projects/{project}", token=op)
check("Admin sieht Nur-Team-Projekte nicht (nicht im Team)", st == 404, r)
st, r = call("api/markers", {"name": "Teamlager " + run, "x": c[0][0] * 16 + 8, "z": c[0][1] * 16 + 8, "visibility": "team", "teamId": tid}, b)
marker = r.get("id")
check("B legt einen Team-Marker an", st == 200, r)
check("A (Owner) sieht den Team-Marker", by_id(call("api/markers", token=a)[1]["markers"], marker) is not None)

# --- Übergabe und Austritt
st, r = call(f"api/teams/{tid}/leave", {}, a)
check("Owner kann nicht einfach austreten", st == 409, r)
st, r = call(f"api/teams/{tid}/transfer", {"player": b_name, "confirmName": "falsch"}, a)
check("Übergabe nur mit richtigem Teamnamen", st == 400, r)
st, r = call(f"api/teams/{tid}/transfer", {"player": b_name, "confirmName": team_name}, a)
check("A bietet B das Team an", st == 200, r)
check("B ist noch nicht Owner", call(f"api/teams/{tid}", token=b)[1]["myRole"] == "moderator")
st, r = call(f"api/teams/{tid}/transfer-accept", {}, d)
check("nur der Empfänger kann annehmen", st == 404, r)
st, r = call(f"api/teams/{tid}/transfer-accept", {}, b)
check("B nimmt die Übergabe an", st == 200, r)
check("B ist Owner, A ist Admin", call(f"api/teams/{tid}", token=b)[1]["myRole"] == "owner"
      and call(f"api/teams/{tid}", token=a)[1]["myRole"] == "admin")
st, r = call(f"api/teams/{tid}/leave", {}, a)
check("A tritt aus", st == 200, r)

# --- Aktivität: öffentlich vs. nur Team
st, act = call("api/activity?limit=100", token=a)
texts = [e["text"] for e in act.get("events", [])]
check("Aktivität: Gründung ist öffentlich sichtbar", any(team_name in t and "gegründet" in t for t in texts), texts[:5])
check("Aktivität: Rollenwechsel sieht nur das Team (A ist raus)", not any("ist jetzt Moderator" in t and tag in t for t in texts))
st, act_b = call("api/activity?limit=100", token=b)
check("…das Team sieht ihn", any("ist jetzt Moderator" in t and tag in t for t in [e["text"] for e in act_b["events"]]))
check("Aktivität: limit wird eingehalten", len(call("api/activity?limit=2", token=a)[1]["events"]) <= 2)

# --- Auflösen ohne Datenverlust
st, r = call(f"api/teams/{tid}/dissolve", {"confirmName": "nein"}, b)
check("Auflösen nur mit richtigem Namen", st == 400, r)
st, r = call(f"api/teams/{tid}/dissolve", {"confirmName": team_name}, d)
check("Anwärter kann nicht auflösen", st == 403, r)
st, r = call(f"api/teams/{tid}/dissolve", {"confirmName": team_name}, b)
check("Owner B löst das Team auf", st == 200, r)
check("Team ist weg", call(f"api/teams/{tid}", token=b)[0] == 404)
l = by_id(call("api/lands", token=b)[1]["lands"], land)
check("Land bleibt, nur ohne Team", l is not None and "teamId" not in l, l)
st, p = call(f"api/projects/{project}", token=b)
check("Projekt bleibt (jetzt Nur-Beteiligte)", st == 200 and p["visibility"] == "private" and len(p["tasks"]) == 2, p)
m = by_id(call("api/markers", token=b)[1]["markers"], marker)
check("Team-Marker bleibt als privater Marker", m is not None and m["visibility"] == "private", m)
check("D sieht das Projekt nicht mehr", call(f"api/projects/{project}", token=d)[0] == 404)

# --- Projekte allgemein
st, r = call("api/projects", {"name": "Leuchtturm " + run, "description": "am Meer", "priority": "high", "status": "planned",
                              "startDate": "2026-11-01", "dueDate": "2026-12-24", "tasks": ["Fundament", "Turm", "Licht"],
                              "location": {"dimension": "minecraft:overworld", "x": -120, "z": 340}}, a)
pub = r.get("id")
check("A legt ein öffentliches Projekt an", st == 200, r)
st, p = call(f"api/projects/{pub}", token=a)
task = p["tasks"][0]["id"]
st, r = call(f"api/projects/{pub}/tasks/{task}/edit", {"done": True}, a)
p = call(f"api/projects/{pub}", token=d)[1]
check("Fortschritt 1/3, für alle sichtbar", p["progress"] == {"done": 1, "total": 3}, p.get("progress"))
check("D darf das Projekt nicht bearbeiten", call(f"api/projects/{pub}/edit", {"status": "done"}, d)[0] == 403)
st, r = call(f"api/projects/{pub}/edit", {"name": "Neuer Name " + run, "dueDate": "2026-10-01"}, a)
check("Zieldatum vor Startdatum -> 400", st == 400, r)
check("…Name wurde nicht halb geändert", call(f"api/projects/{pub}", token=a)[1]["name"] == "Leuchtturm " + run)
st, r = call(f"api/projects/{pub}/edit", {"status": "in_progress"}, a)
check("Status ändern", st == 200, r)
st, r = call(f"api/projects/{pub}/updates", {"text": "Fundament steht!"}, a)
check("Projekt-Update schreiben", st == 200, r)
st, r = call(f"api/projects/{pub}/edit", {"responsible": ["GibtEsNicht" + run]}, a)
check("unbekannter Verantwortlicher -> 404", st == 404, r)
st, r = call("api/projects", {"name": ["kein", "Text"]}, a)
check("falscher Datentyp -> 400", st == 400, r)
st, r = call("api/projects", {"name": "Geheim " + run, "visibility": "private"}, a)
secret = r.get("id")
check("privates Projekt ist für andere unsichtbar", call(f"api/projects/{secret}", token=d)[0] == 404
      and by_id(call("api/projects", token=d)[1]["projects"], secret) is None)
check("…auch beim Löschversuch (404 statt 403)", call(f"api/projects/{secret}/delete", {}, d)[0] == 404)

# --- Favoriten
st, r = call("api/prefs/favorite", {"kind": "projects", "id": pub, "on": True}, a)
check("Favorit setzen", st == 200 and pub in r["favorites"]["projects"], r)
check("Favoriten sind pro Spieler", pub not in call("api/prefs", token=d)[1]["favorites"]["projects"])
st, r = call("api/prefs/favorite", {"kind": "../../etc", "id": "x", "on": True}, a)
check("ungültige Favoriten-Art -> 400", st == 400, r)

# --- Export / Import
st, exp = call("api/export", token=a)
check("Export: Format und Version", st == 200 and exp["format"] == "world-planner-export" and exp["version"] == 1, exp.get("format"))
check("Export enthält nur eigene Projekte", all(p["createdBy"] == me["uuid"] for p in exp["projects"]) and len(exp["projects"]) >= 2)
before = call("api/projects", token=d)[1]["projects"]
st, prev = call("api/import", {"data": exp, "apply": False}, d)
check("Import-Vorschau", st == 200 and prev["applied"] is False and prev["projects"]["create"] >= 2, prev.get("projects"))
check("Vorschau legt nichts an", len(call("api/projects", token=d)[1]["projects"]) == len(before))
st, res = call("api/import", {"data": exp, "apply": True}, d)
check("Import legt an – als D", st == 200 and res["applied"] is True and res["projects"]["create"] >= 2, res.get("projects"))
check("Länder von A werden nicht übernommen (Chunks belegt)", res["lands"]["create"] == 0 and len(res["lands"]["skipped"]) == len(exp["lands"]))
mine = [p for p in call("api/projects", token=d)[1]["projects"] if p["createdBy"]["name"] == d_name and run in p["name"]]
check("importierte Projekte gehören D", len(mine) >= 2, [p["name"] for p in mine])
st, r = call("api/import", {"data": {"format": "etwas"}, "apply": False}, d)
check("fremdes Dateiformat -> 400", st == 400, r)

# --- Aufräumen
for p in mine:
    call(f"api/projects/{p['id']}/delete", {}, d)
for m in call("api/markers", token=d)[1]["markers"]:
    if m["ownerName"] == d_name and run in m["name"]:
        call(f"api/markers/{m['id']}/delete", {}, d)
call(f"api/projects/{pub}/delete", {}, a)
call(f"api/projects/{secret}/delete", {}, a)
call(f"api/projects/{project}/delete", {}, b)
call(f"api/markers/{marker}/delete", {}, b)
st, r = call(f"api/lands/{land}/delete", {}, b)
check("Aufräumen: B löscht sein Land (alleiniger Besitzer)", st == 200, r)
sys.exit(check.done())
