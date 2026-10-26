# Tests

| Art | Wo | Braucht | Aufruf |
|---|---|---|---|
| **Java-Unit-Tests** (Regeln, Rechte, Handel inkl. gleichzeitiger Zugriffe, Speichern, Umstellung von 0.2) | `fabric-mod/src/test/java` | Java 25 | `cd fabric-mod && ./gradlew test` |
| **Web-Unit-Tests** (Koordinaten, Messen, Statistik-Zahlen und -Tage, Event-Zeiten in Server-Zeitzone, Kalender, Event-Hinweise) | `tests/web` | Node.js 22 | `node --test tests/web/*.test.mjs` |
| **Integrationstests** (echter Server, echte API, echter Browser) | `tests/integration` | Testserver + Python 3 + Playwright | siehe unten |

Die Integrationstests prüfen nicht nur, ob Knöpfe da sind. Sie schauen nach, was der Server wirklich gespeichert hat,
schicken gleichzeitige Anfragen (Gebote, Käufe) und messen, wo die BlueMap-Kamera wirklich steht.

| Datei | Prüft |
|---|---|
| `api_core_test.py` | Login, Länder, Marker, Rechte, kaputte Anfragen |
| `smp_api_test.py` | Teams (Zustimmung, Rollen, Übergabe, Auflösen ohne Datenverlust), Team-Länder/-Projekte/-Marker, Projekte, Aktivität, Favoriten, Export/Import |
| `trade_api_test.py` | Handel: Gebotsregeln, gleichzeitige Gebote und Käufe über HTTP, `requestId`, Privatsphäre, Übergabe, Streitfall, Tausch/Hilfe |
| `browser_core_test.js` | Login per Link, Land per Karte claimen mit Vorschau, einladen/annehmen, Marker, Suche, Handy |
| `smp_browser_test.js` | Zwei Spieler gleichzeitig: Team gründen/einladen/Rolle, Team-Projekt, Kauf mit Übergabe, Auktion, Team auflösen |
| `bluemap_sync_test.js` | BlueMap übernimmt Position und Zoom der Karte wirklich (misst die Kamera im iframe) |
| `chat_tags_test.py` | Team-Kürzel im Spiel: liest die Server-Konsole mit (`/team list`, `/say`) – braucht `TESTSERVER_DIR` |
| `game_integration_test.py` | Spiel-Integration: Nachrichten (live, beim Einloggen, abgeschaltet), Land-Anzeige, Todespunkt, `/planer`, `/tc`, „überboten“ – liest die DEBUG-Zeilen der Mod im Server-Log |
| `game_web_test.js` | Schalter „Im Spiel“ in den Einstellungen, Dashboard-Liste = Liste im Spiel (`/api/todo`, wörtlich), Mess-Werkzeug, Handy |
| `stats_test.py` | Statistik mit echten Aktionen von Fake-Spielern (Blöcke setzen, springen, Diamanterz abbauen, an Fallschaden sterben): Zahlen, Ranglisten, Todesursache, alle für alle sichtbar (Ausblenden → 400), `/planer statistik` ohne Ränge, `/planer top` und `/planer ranglisten` gibt es nicht mehr. Außerdem 20 s künstlicher Lag (`/tick rate 10`): Aktiv + AFK muss genauso wachsen wie Minecrafts Spielzeit |
| `events_test.py` | Events auf dem Server: Einladung im Chat mit [Komme], `/planer events`, `/planer event <id> ja`, Verschieben und Absagen (nur wer zugesagt hat), Erinnerung („In 2 Minuten“) und Start („Jetzt“) – ein Event beginnt dafür in gut 2 Minuten. Außerdem: `/api/races` und `/rennen` gibt es nicht mehr |
| `stats_web_test.js` | Statistik- und Ranglisten-Seiten zeigen dasselbe wie die API (Kacheln, Wärmekarte mit leeren Feldern ohne Messung, Pfeiltasten, Tabellen-Ansicht, Ranglisten als eigener Reiter, alter Link leitet um, kein „Mich ausblenden“, Einstellungen mit Hinweis statt Schalter), Handy |
| `events_web_test.js` | Events im Browser: Formular (Uhrzeit = Server-Zeit), Liste, Kalender, Zusagen umschalten, Bob sieht auf dem Dashboard wörtlich denselben Text wie `/api/todo` im Spiel, Verschieben, Absagen, Löschen; Rückblick-Zahlen = API; Handy |
| `restart_check.py` | Alle Daten vor und nach einem Server-Neustart vergleichen (`save`, Server neu starten, `compare`) – ab 0.6 auch ein Team-Event mit Treffpunkt und Zusage. Taugt auch für Updates: Kennt der alte Server noch keine Events, fehlt nur dieser Teil |
| `crash_check.py` | Absturz: wartet, bis der Planer neuer gespeichert hat als Minecraft (`wait`), dann `kill -9`, Neustart, `compare` – Statistik-Verlauf, Zuwachs gestern und Aktiv ≤ Spielzeit müssen stimmen |

## Integrationstests vorbereiten

1. Einen **eigenen** Testserver-Ordner anlegen. Nimm nie deinen echten Server!
   - Fabric-Server 26.3, Fabric API, `world-planner-*.jar`, optional BlueMap 5.29
   - **Carpet** (`fabric-carpet` für 26.3) für Fake-Spieler
   - `eula.txt` mit `eula=true` (damit akzeptierst du Mojangs EULA), `online-mode=false`
2. Server einmal starten, damit eine Welt entsteht, dann Fake-Spieler holen:
   ```bash
   export TESTSERVER_DIR=/pfad/zum/testserver JAVA=/pfad/zu/java25
   export LOG_CONFIG=tests/integration/log4j2-debug.xml   # nur für game_integration_test.py nötig
   T=tests/integration/testserver.sh
   $T start
   $T cmd "difficulty peaceful"            # sonst töten Monster die Fake-Spieler (Carpet entfernt sie dann)
   for p in Alice Bob Dana Chef; do $T cmd "player $p spawn in survival"; done
   $T cmd "op Chef"
   ```
   Ein paar Sekunden nach „Done“ warten, sonst meldet Carpet „An unexpected error occurred“.
3. Login-Codes holen und Tests starten (jeder Code gilt nur einmal; Alice, Bob und Dana dürfen keine OPs sein):
   ```bash
   python3 tests/integration/api_core_test.py  $($T code Alice) $($T code Bob) $($T code Chef)
   python3 tests/integration/smp_api_test.py   $($T code Alice) $($T code Bob) $($T code Dana) $($T code Chef)
   python3 tests/integration/trade_api_test.py $($T code Alice) $($T code Bob) $($T code Dana) $($T code Chef)
   node tests/integration/browser_core_test.js $($T code Alice) $($T code Bob) Bob
   node tests/integration/smp_browser_test.js  $($T code Alice) $($T code Bob) Bob
   node tests/integration/bluemap_sync_test.js $($T code Alice)   # braucht BlueMap mit gerenderter Welt
   python3 tests/integration/chat_tags_test.py $($T code Alice) $($T code Bob) $($T code Dana)   # liest server.log in TESTSERVER_DIR
   python3 tests/integration/game_integration_test.py $($T code Alice) $($T code Bob) $($T code Dana) $($T code Chef)
   node tests/integration/game_web_test.js     $($T code Alice) $($T code Bob)
   python3 tests/integration/stats_test.py     $($T code Alice) $($T code Bob) $($T code Dana)   # dauert 3–4 Minuten
   node tests/integration/stats_web_test.js    $($T code Alice)
   python3 tests/integration/events_test.py    $($T code Alice) $($T code Bob) $($T code Dana)   # dauert gut 2 Minuten
   node tests/integration/events_web_test.js   $($T code Alice) $($T code Bob)
   ```

Umgebungsvariablen: `PLANNER_URL` (Standard `http://127.0.0.1:8765/`), `CHROMIUM` (Pfad zu Chromium, sonst der von Playwright),
`PLAYWRIGHT_MODULE`, `SHOT_DIR` (Ordner für Screenshots).

Die Tests legen Daten mit zufälligen Namen an (z. B. „Base QWER“), suchen sich freie Chunks selbst und räumen Teams früherer,
abgebrochener Läufe auf (nur Teams, die mit „Testteam “ beginnen, Alices Länder mit „Testland “ ohne Mitbesitzer und Alices Events mit „Test-“). Sie funktionieren also auch, wenn schon Daten auf dem
Testserver liegen. Der Handels-Test hält sich an die Bremse von 30 Aktionen pro Minute – zwei Läufe direkt hintereinander gehen.

**Warum DEBUG-Zeilen?** Minecraft schreibt Nachrichten an einzelne Spieler (Chat, Actionbar) nirgends hin. Mit
`LOG_CONFIG=tests/integration/log4j2-debug.xml` schreibt die Mod jede solche Nachricht zusätzlich als DEBUG-Zeile ins Log
(„Nachricht an Bob: …“, „Land-Anzeige für Bob: …“, „Team-Chat an Bob: …“). Auf einem echten Server bleibt DEBUG aus –
die Zeilen enthalten private Dinge (z. B. wer geboten hat).

**Was die Tests nicht können:**
- Wie Farben, Klick-Links und die Actionbar in einem **echten Minecraft-Fenster** aussehen – die Tests prüfen Inhalt und
  Empfänger, nicht die Darstellung.
- Den Hinweis **nach dem Wiederbeleben**: Carpet entfernt Fake-Spieler, wenn sie sterben, statt sie wiederzubeleben.
  Der Text ist per Unit-Test geprüft, das Ereignis selbst nicht auf dem Server.
- Minecrafts eigenes `/teammsg`: Über `execute as` von der Konsole aus gibt es weder Ausgabe noch Fehlermeldung.
- **Event-Erinnerung 10 Minuten vorher** mit einem Event, das wirklich in 10 Minuten beginnt: Der Test nimmt ein Event in gut
  2 Minuten (dieselbe Regel „höchstens 10 Minuten vorher“), damit er nicht 10 Minuten warten muss. Die genaue Grenze prüfen die
  Unit-Tests mit einer festen Uhr.
- **Abmelden echter Spieler:** Bei Carpet-Fake-Spielern kommt Fabrics Abmelde-Ereignis nicht; die Mod bemerkt den Logout dann eine
  Sekunde später selbst (das prüft `stats_test.py`). Dass das Ereignis bei echten Spielern sofort kommt, ließ sich hier nicht prüfen.
- Den Ablauf einer Auktion vorspulen (kürzeste Laufzeit 1 Stunde). Er ist in den Java-Unit-Tests mit einer austauschbaren
  Uhr geprüft; auf dem echten Server kann man ihn prüfen, indem man eine 1-Stunden-Auktion anlegt und nach Ablauf in
  `trades.json` nachsieht (ohne die Seite zu öffnen) – dann muss der Timer sie beendet haben.
