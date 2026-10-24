# Tests

| Art | Wo | Braucht | Aufruf |
|---|---|---|---|
| **Java-Unit-Tests** (Regeln, Umrechnungen, Speichern, Login-Bremse) | `fabric-mod/src/test/java` | nur Java 25 | `cd fabric-mod && ./gradlew test` |
| **Integrationstests** (echter Server, echte API, echter Browser) | `tests/integration` | Testserver + Python 3 + Playwright | siehe unten |

Die Integrationstests prüfen nicht nur, ob Knöpfe da sind. Sie schauen auch nach, was der Server wirklich gespeichert hat und wo die BlueMap-Kamera wirklich steht.

## Integrationstests vorbereiten

1. Einen **eigenen** Testserver-Ordner anlegen. Nimm nie deinen echten Server!
   - Fabric-Server 26.3, Fabric API, `world-planner-*.jar`, optional BlueMap 5.29
   - **Carpet** (`fabric-carpet` für 26.3) für Fake-Spieler
   - `eula.txt` mit `eula=true` (damit akzeptierst du Mojangs EULA), `online-mode=false`
2. Server einmal starten, damit eine Welt entsteht, dann Fake-Spieler holen:
   ```bash
   export TESTSERVER_DIR=/pfad/zum/testserver JAVA=/pfad/zu/java25
   tests/integration/testserver.sh start
   tests/integration/testserver.sh cmd "player Alice spawn"
   tests/integration/testserver.sh cmd "player Bob spawn"
   tests/integration/testserver.sh cmd "player Chef spawn"
   tests/integration/testserver.sh cmd "op Chef"
   ```
3. Login-Codes holen und Tests starten (jeder Code gilt nur einmal):
   ```bash
   T=tests/integration/testserver.sh
   python3 tests/integration/api_core_test.py $($T code Alice) $($T code Bob) $($T code Chef)
   node tests/integration/browser_core_test.js $($T code Alice) $($T code Bob) Bob
   node tests/integration/bluemap_sync_test.js $($T code Alice)   # braucht BlueMap mit gerenderter Welt
   ```

Umgebungsvariablen: `PLANNER_URL` (Standard `http://127.0.0.1:8765/`), `CHROMIUM` (Pfad zu Chromium, sonst der von Playwright), `PLAYWRIGHT_MODULE`, `SHOT_DIR` (Ordner für Screenshots).

Die Tests legen Daten mit zufälligen Namen an (z. B. „Base QWER“) und suchen sich freie Chunks selbst. Sie funktionieren also auch, wenn schon Daten auf dem Testserver liegen.
