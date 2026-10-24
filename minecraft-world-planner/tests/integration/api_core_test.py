"""API-Test: Login, Länder, Marker, Rechte, kaputte Anfragen.
Aufruf: python3 api_core_test.py <code-spielerA> <code-spielerB> [<code-admin>]
Spieler A und B dürfen KEINE OPs sein, der dritte Code (optional) muss von einem OP stammen.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apilib import Checker, call, flat, free_chunks, login, suffix  # noqa: E402

if len(sys.argv) < 3:
    raise SystemExit(__doc__)
check = Checker("API-Kern")
alice, alice_name = login(sys.argv[1])
bob, bob_name = login(sys.argv[2])
admin = login(sys.argv[3])[0] if len(sys.argv) > 3 else None
run = suffix()

# --- Ohne Login geht nichts (außer info/login)
for path in ["api/me", "api/world", "api/lands", "api/markers", "api/players", "api/discovered", "api/known-players", "api/bluemap-status"]:
    st, _ = call(path)
    check(f"ohne Login: {path} -> 401", st == 401, st)
st, _ = call("api/lands", {"name": "X", "dimension": "minecraft:overworld", "chunks": [0, 0]})
check("ohne Login: Land anlegen -> 401", st == 401, st)
st, r = call("api/info")
check("api/info ist öffentlich und verrät nichts Privates", st == 200 and set(r) <= {"app", "loginHint"}, r)
check("Spieler A ist kein Admin", call("api/me", token=alice)[1]["admin"] is False)

# --- Welt-Daten
st, w = call("api/world", token=alice)
sp = w.get("spawn", {})
check("Welt: Seed als Text", isinstance(w.get("seed"), str), w)
check("Welt: Spawn mit ganzzahligen Koordinaten", all(isinstance(sp.get(k), int) for k in "xyz") and sp.get("dimension", "").startswith("minecraft:"), sp)
st, bm = call("api/bluemap-status", token=alice)
check("BlueMap-Status hat 'available'", st == 200 and isinstance(bm.get("available"), bool), bm)
if bm.get("available"):
    check("BlueMap-Status: Warteschlange ist eine Zahl >= 0", isinstance(bm.get("renderQueue"), int) and bm["renderQueue"] >= 0, bm)

# --- Land anlegen und Regeln
c = free_chunks(alice, 3)
base_name = "Base " + run
st, r = call("api/lands", {"name": base_name, "dimension": "minecraft:overworld", "chunks": flat(c[:1])}, alice)
check("A legt ein Land an", st == 200, r)
lid = r.get("id")
st, r = call(f"api/lands/{lid}/chunks", {"add": flat(c[1:2])}, alice)
check("angrenzenden Chunk hinzufügen", st == 200, r)
far = (c[0][0] + 5, c[0][1] + 5)
st, r = call(f"api/lands/{lid}/chunks", {"add": [far[0], far[1]]}, alice)
check("nicht angrenzender Chunk wird abgelehnt", st == 400, r)
st, r = call(f"api/lands/{lid}/chunks", {"add": [900000, 900000]}, alice)
check("unentdeckter Chunk wird abgelehnt", st == 400 and "entdeckt" in r.get("error", ""), r)
st, r = call(f"api/lands/{lid}/chunks", {"add": flat(c[2:3])}, bob)
check("B (kein Besitzer) darf nicht erweitern", st == 403, r)
st, r = call("api/lands", {"name": base_name.lower(), "dimension": "minecraft:overworld", "chunks": flat(c[2:3])}, bob)
check("doppelter Name (Groß/klein egal) -> 409", st == 409, r)
st, r = call("api/lands", {"name": "Klau " + run, "dimension": "minecraft:overworld", "chunks": flat(c[:1])}, bob)
check("fremden Chunk claimen -> 409", st == 409, r)
st, r = call("api/lands", {"name": "<script>", "dimension": "minecraft:overworld", "chunks": flat(c[2:3])}, bob)
check("HTML im Landnamen -> 400", st == 400, r)
st, r = call("api/lands", {"name": "Fake " + run, "dimension": "minecraft:fake", "chunks": flat(c[2:3])}, bob)
check("unbekannte Dimension -> 400", st == 400, r)
st, r = call("api/lands", {"name": "Insel " + run, "dimension": "minecraft:overworld", "chunks": [c[2][0], c[2][1], c[2][0] + 3, c[2][1] + 3]}, bob)
check("nicht zusammenhängend -> 400", st == 400, r)

# --- Einladen / Annehmen / Rechte
st, r = call(f"api/lands/{lid}/invite", {"player": "GibtEsNicht" + run}, alice)
check("unbekannten Spieler einladen -> 404", st == 404, r)
st, r = call(f"api/lands/{lid}/invite", {"player": bob_name.upper()}, alice)
check("A lädt B ein (Groß/klein egal)", st == 200, r)
lands = {l["id"]: l for l in call("api/lands", token=bob)[1]["lands"]}
check("B sieht invitedMe", lands[lid]["invitedMe"] is True, lands[lid])
check("B sieht die Einladungsliste nicht (nur Besitzer)", "invites" not in lands[lid], lands[lid])
st, r = call(f"api/lands/{lid}/accept", {}, bob)
check("B nimmt an", st == 200, r)
st, r = call(f"api/lands/{lid}/chunks", {"add": flat(c[2:3])}, bob)
check("B darf als Mitbesitzer erweitern", st == 200, r)
st, r = call(f"api/lands/{lid}/remove-owner", {"player": bob_name}, alice)
check("A kann B NICHT rauswerfen (nur Admins)", st == 403, r)
st, r = call(f"api/lands/{lid}/delete", {}, alice)
check("Besitzer darf geteiltes Land nicht löschen (409: erst austreten lassen)", st == 409 and "austreten" in r.get("error", ""), r)
st, r = call(f"api/lands/{lid}/delete", {}, bob)
check("auch der Mitbesitzer nicht", st == 409, r)
st, r = call(f"api/lands/{lid}/edit", {"color": "red; background:url(x)"}, alice)
check("ungültige Farbe -> 400", st == 400, r)
st, r = call(f"api/lands/{lid}/edit", {"color": "#123abc", "description": "Test & mehr"}, bob)
check("Mitbesitzer ändert Farbe/Beschreibung", st == 200, r)
st, r = call(f"api/lands/{lid}/leave", {}, bob)
check("B tritt aus", st == 200 and r.get("deleted") is False, r)
owners = [o["name"] for o in {l["id"]: l for l in call("api/lands", token=alice)[1]["lands"]}[lid]["owners"]]
check("danach gehört das Land nur A", owners == [alice_name], owners)

# --- Marker
st, r = call("api/markers", {"name": "<img src=x onerror=alert(1)> " + run, "type": "portal", "dimension": "minecraft:overworld",
                             "x": 100, "y": 64, "z": -50, "notes": "<b>fett</b>", "public": True}, alice)
check("Marker mit HTML im Namen wird gespeichert (die Seite zeigt ihn als Text)", st == 200, r)
pub = r.get("id")
st, r = call("api/markers", {"name": "Geheim " + run, "type": "base", "dimension": "minecraft:overworld", "x": 1, "y": 2, "z": 3, "public": False}, alice)
priv = r.get("id")
check("privater Marker", st == 200, r)
bob_markers = {m["id"] for m in call("api/markers", token=bob)[1]["markers"]}
check("B sieht den öffentlichen Marker", pub in bob_markers)
check("B sieht den privaten Marker NICHT", priv not in bob_markers)
st, r = call(f"api/markers/{pub}/delete", {}, bob)
check("B darf A's Marker nicht löschen", st == 403, r)
st, r = call(f"api/markers/{priv}/edit", {"name": "gekapert", "type": "base", "x": 1, "y": 1, "z": 1}, bob)
check("B kann A's privaten Marker nicht bearbeiten – er existiert für B gar nicht (404)", st == 404, r)
st, r = call(f"api/markers/{pub}/edit", {"name": "gekapert", "type": "base", "x": 1, "y": 1, "z": 1}, bob)
check("B darf A's öffentlichen Marker nicht bearbeiten (403)", st == 403, r)
st, r = call("api/markers", {"name": "X", "type": "hack", "x": 1, "y": 1, "z": 1}, alice)
check("unbekannter Marker-Typ -> 400", st == 400, r)
st, r = call("api/markers", {"name": "X", "type": "base", "x": 1e12, "y": 1, "z": 1}, alice)
check("riesige Koordinate -> 400", st == 400, r)
st, r = call("api/markers", {"name": "", "type": "base", "x": 1, "y": 1, "z": 1}, alice)
check("leerer Name -> 400", st == 400, r)

# --- Kaputte Anfragen
st, r = call("api/lands", raw=b"{kaputt", token=alice)
check("kaputtes JSON -> 400", st == 400, r)
st, r = call("api/lands", raw=b"x" * 300000, token=alice)
check("zu große Anfrage -> 413", st == 413, r)
st, r = call("api/lands", {"name": "Riesig " + run, "dimension": "minecraft:overworld", "chunks": [0, 0] * 600}, alice)
check("zu viele Chunks auf einmal -> 400", st == 400, r)
st, r = call("api/lands/gibtesnicht/edit", {"name": "x"}, alice)
check("unbekannte Land-ID -> 404", st == 404, r)
st, r = call("api/me", token="falsch")
check("falsches Token -> 401", st == 401, r)
st, r = call("api/gibtesnicht", token=alice)
check("unbekannte Adresse -> 404", st == 404, r)

# --- Admin
if admin:
    check("Admin-Code gehört einem Admin", call("api/me", token=admin)[1]["admin"] is True)
    st, r = call(f"api/markers/{pub}/delete", {}, admin)
    check("Admin darf fremden öffentlichen Marker löschen", st == 200, r)
    st, r = call(f"api/lands/{lid}/delete", {}, admin)
    check("Admin darf Land löschen", st == 200, r)
else:
    print("(Admin-Tests übersprungen: kein Admin-Code angegeben)")
    call(f"api/markers/{pub}/delete", {}, alice)

# --- Aufräumen + Abmelden
call(f"api/markers/{priv}/delete", {}, alice)
if not admin:
    call(f"api/lands/{lid}/leave", {}, alice)
st, r = call("api/logout", {}, bob)
check("B meldet sich ab", st == 200, r)
st, r = call("api/me", token=bob)
check("B's Token ist danach ungültig", st == 401, r)

sys.exit(check.done())
