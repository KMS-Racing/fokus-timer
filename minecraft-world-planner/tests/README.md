# Tests

| Art | Wo | Braucht | Aufruf |
|---|---|---|---|
| **Java-Unit-Tests** (Regeln, Rechte, Handel inkl. gleichzeitiger Zugriffe, Speichern, Umstellung von 0.2) | `fabric-mod/src/test/java` | Java 25 | `cd fabric-mod && ./gradlew test` |
| **Web-Unit-Tests** (Koordinaten: Chunk/Region, Nether, Richtung, Koordinaten lesen) | `tests/web` | Node.js 22 | `node --test tests/web/*.test.mjs` |
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
| `restart_check.py` | Alle Daten vor und nach einem Server-Neustart vergleichen (`save`, Server neu starten, `compare`) |

## Integrationstests vorbereiten

1. Einen **eigenen** Testserver-Ordner anlegen. Nimm nie deinen echten Server!
   - Fabric-Server 26.3, Fabric API, `world-planner-*.jar`, optional BlueMap 5.29
   - **Carpet** (`fabric-carpet` für 26.3) für Fake-Spieler
   - `eula.txt` mit `eula=true` (damit akzeptierst du Mojangs EULA), `online-mode=false`
2. Server einmal starten, damit eine Welt entsteht, dann Fake-Spieler holen:
   ```bash
   export TESTSERVER_DIR=/pfad/zum/testserver JAVA=/pfad/zu/java25
   T=tests/integration/testserver.sh
   $T start
   for p in Alice Bob Dana Chef; do $T cmd "player $p spawn"; done
   $T cmd "op Chef"
   ```
3. Login-Codes holen und Tests starten (jeder Code gilt nur einmal; Alice, Bob und Dana dürfen keine OPs sein):
   ```bash
   python3 tests/integration/api_core_test.py  $($T code Alice) $($T code Bob) $($T code Chef)
   python3 tests/integration/smp_api_test.py   $($T code Alice) $($T code Bob) $($T code Dana) $($T code Chef)
   python3 tests/integration/trade_api_test.py $($T code Alice) $($T code Bob) $($T code Dana) $($T code Chef)
   node tests/integration/browser_core_test.js $($T code Alice) $($T code Bob) Bob
   node tests/integration/smp_browser_test.js  $($T code Alice) $($T code Bob) Bob
   node tests/integration/bluemap_sync_test.js $($T code Alice)   # braucht BlueMap mit gerenderter Welt
   python3 tests/integration/chat_tags_test.py $($T code Alice) $($T code Bob) $($T code Dana)   # liest server.log in TESTSERVER_DIR
   ```

Umgebungsvariablen: `PLANNER_URL` (Standard `http://127.0.0.1:8765/`), `CHROMIUM` (Pfad zu Chromium, sonst der von Playwright),
`PLAYWRIGHT_MODULE`, `SHOT_DIR` (Ordner für Screenshots).

Die Tests legen Daten mit zufälligen Namen an (z. B. „Base QWER“), suchen sich freie Chunks selbst und räumen Teams früherer,
abgebrochener Läufe auf (nur Teams, die mit „Testteam “ beginnen). Sie funktionieren also auch, wenn schon Daten auf dem
Testserver liegen. Der Handels-Test hält sich an die Bremse von 30 Aktionen pro Minute – zwei Läufe direkt hintereinander gehen.

**Was die Tests nicht können:** Der Ablauf einer Auktion lässt sich über die API nicht vorspulen (kürzeste Laufzeit 1 Stunde).
Er ist in den Java-Unit-Tests mit einer austauschbaren Uhr geprüft; auf dem echten Server kann man ihn prüfen, indem man eine
1-Stunden-Auktion anlegt und nach Ablauf in `trades.json` nachsieht (ohne die Seite zu öffnen) – dann muss der Timer sie beendet haben.
