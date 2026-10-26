# Was am Original verbessert wurde

Das Original (Version 0.1.0) war eine gute Idee mit sinnvollem Aufbau: BlueMap statt einer selbst gebauten 3D-Karte,
und an Sicherheit wurde gedacht. **Aber es wurde nie ausprobiert**, und es hatte Fehler.

## 1. Die Mod ließ sich nicht bauen (5 Fehler)

Beim echten Bauen gegen Minecraft 26.3 kamen nacheinander diese Fehler:

| # | Fehler | Warum | Lösung |
|---|---|---|---|
| 1 | `Cannot use Mojang mappings in a non-obfuscated environment` | Seit 26.1 ist Minecrafts Code nicht mehr verschleiert. „Mappings“ (Übersetzungstabellen für Namen) gibt es nicht mehr. | Zeile `mappings loom.officialMojangMappings()` entfernt |
| 2 | `Could not find method modImplementation()` | Das neue Loom-Plugin kennt diesen Befehl nicht mehr | `modImplementation` → `implementation` |
| 3–4 | `cannot find symbol: method location()` (2×) | Mojang hat `ResourceLocation` in `Identifier` umbenannt | `.location()` → `.identifier()` |
| 5 | `cannot find symbol: method getName()` | `GameProfile` ist jetzt ein *Record* | `.getName()` → `.name()` |

**Lehre daraus:** Code, den eine KI (oder ein Mensch) schreibt, ist erst fertig, wenn er wirklich gebaut und getestet wurde.
Die README des Originals sagte sogar selbst: „has not been compiled“.

## 2. Bugs in der Mod

- **Server-Threads ohne Daemon-Flag:** Der Thread-Pool des Webservers wurde nie beendet. Solche Threads können verhindern, dass sich Java beim Stoppen beendet. Jetzt: Daemon-Threads plus sauberes `shutdownNow()`. Getestet: Der Server beendet sich in 2 Sekunden.
- **Kaputte Config ließ den ganzen Server abstürzen** (`RuntimeException` beim Start). Jetzt: Fehlermeldung im Log, sichere Standardwerte, der Server läuft weiter.
- **Seed als Zahl in JSON:** JavaScript kann Zahlen über 2^53 nicht exakt darstellen, große Seeds wurden also *falsch* angezeigt. Jetzt als Text.
- **`fabric.mod.json` behauptete `minecraft >=26.1`**, obwohl nur gegen 26.3 gebaut. Jetzt `~26.3`.
- **Die ersten 2 Sekunden nach dem Start** lieferte `/api/world` ein leeres Objekt. Jetzt sofort gefüllt.

## 3. Bugs in der Webseite

- **Der „Close“-Button im Einstellungsfenster hat trotzdem gespeichert.** Er war ein Submit-Button im selben Formular.
- **„Import JSON“ hat ohne Nachfrage alle bestehenden Marker gelöscht.** Jetzt wird ergänzt, mit Rückfrage.
- **Kopier-Buttons** gaben keine Rückmeldung und funktionierten ohne HTTPS gar nicht. Jetzt mit Meldung und Ersatzweg.
- **Marker hatten keine Dimension**, obwohl es den Typ „Portal“ gab. Der TP-Befehl war im Nether also falsch. Jetzt mit Dimension, passendem `/execute in …`-Befehl und Nether↔Oberwelt-Umrechnung.
- **„Test API“ hat die Einstellungen im Speicher verändert**, ohne zu speichern.
- **Export:** `URL.revokeObjectURL` direkt nach `click()` kann in manchen Browsern den Download abbrechen.
- Beim ersten Öffnen kam sofort eine rote Fehlermeldung.

## 4. Sicherheit

- **Vorher:** ein einziges geheimes Token für alle. Wer es kennt, ist „Admin“. Für ein Tool für *alle* Spieler unbrauchbar.
- **Jetzt:** Login per Einmal-Code aus dem Spiel. Damit ist bewiesen, dass du wirklich dieser Spieler bist. Gespeichert wird nur ein Hash des Tokens. Falsche Codes werden gebremst. `/planer abmelden` beendet alle Sitzungen.
- **Strenge Content-Security-Policy:** Die Seite darf keinen fremden Code ausführen. Deshalb sind HTML, CSS und JS jetzt getrennte Dateien.
- **Alle Spieler-Texte** (Landnamen, Marker, Notizen) werden auf der Webseite als Text eingesetzt und in BlueMap HTML-escaped. Getestet mit `<img onerror=…>` als Markername.
- **Rechte werden auf dem Server geprüft**, nicht nur auf der Webseite. 40 automatische Tests versuchen z. B. fremdes Land zu klauen, andere rauszuwerfen oder riesige Anfragen zu schicken.

## 5. Neue Funktionen (deine Wünsche)

- Für alle Spieler offen (eigene Logins, OP = Admin)
- Claim-Karte, die nur entdeckte Chunks zeigt
- Länder claimen (Webseite **und** Befehle), für alle sichtbar, in BlueMap eingezeichnet
- Gleichberechtigte Mitbesitzer mit Einladen/Annehmen
- Bau-Marker auf dem Server statt nur im Browser, öffentlich oder privat, in BlueMap
- „In 3D zeigen“ springt in BlueMap genau zur Stelle
- Die Mod liefert die Webseite selbst aus (kein extra Webhosting nötig)
- Alles auf Deutsch, funktioniert auch auf dem Handy

## So wurde getestet

Auf einem echten Minecraft-26.3-Server mit Fabric API, BlueMap 5.29 und zwei Fake-Spielern (Carpet-Mod):
- Befehle im Spiel
- **40/40** API-Tests (Rechte, Regeln, kaputte Eingaben)
- **26/26** Browser-Tests mit Chromium (Login per Link, Chunks auswählen, Land erstellen, einladen, annehmen, Marker, BlueMap-Tab, Handy-Ansicht)
- Neustart-Test: Logins, Länder und Marker bleiben erhalten
- BlueMap zeigt Länder als Flächen und Marker als Punkte

---

# Version 0.3: SMP-Plattform

## Neu

- **Neue Oberfläche** mit Hauptnavigation (Dashboard, Karte, Live-Welt, Claims, Marker, Teams, Handel, Projekte, Spieler,
  Einstellungen), Adressen zum Teilen (`#/teams/<id>`), globaler Suche (auch Koordinaten), hellem und dunklem Design.
  Die Webseite besteht jetzt aus kleinen Modulen statt einer 1300-Zeilen-Datei.
- **Teams** mit Rollen, Einladung/Bewerbung (nie ohne Zustimmung), bestätigter Übergabe und Auflösen ohne Datenverlust.
- **Handel (Web-Modus):** Auktion, Sofortkauf, Festpreis, Tausch und Hilfe (Bauen, Kämpfen …). Gebote unter Sperre,
  Speichern vor der Bestätigung, `requestId` gegen Doppelklicks, Ablauf auf dem Server, Verlängerung bei späten Geboten,
  Übergabe-Bestätigung und Streitfall. **Es werden keine Items oder Zahlungen vorgetäuscht.**
- **Projekte** mit Aufgaben, Fortschritt, Verantwortlichen, Updates, Land/Team/Ort.
- **Claims 2.0:** Vorschau vor dem Claimen, Notizen (nur Besitzer), Team-Zuordnung, Löschen nur als einziger Besitzer.
- **Marker 2.0:** Sichtbarkeit nur ich / Team / Land-Besitzer / alle, Symbol, Farbe, Höhe optional, eigene Arten per Config.
- **Karte:** Gehe zu Koordinaten, Zu mir, Spawn, Zurücksetzen, Ebenen, Legende, Maßstab, Regionen, Projekt-Fahnen.
- **Dashboard** mit „Zu erledigen“ aus echten Daten, **Aktivität**, **Favoriten**, **Export/Import** mit Vorschau.
- Alte Daten (0.2) werden übernommen; vorher sichert die Mod sie als `*.v1.bak` (getestet mit echten 0.2-Daten: 9/9 Länder, 5/5 Marker).

## Fehler, die beim Bauen und Testen von 0.3 gefunden und behoben wurden

Ehrlich aufgeschrieben, weil Tests genau dafür da sind:

| Fehler | Gefunden durch | Behoben |
|---|---|---|
| Bearbeiten mit einem ungültigen Feld hat die gültigen Felder davor trotzdem übernommen (Projekte, Länder, Teams) | Code-Durchsicht, dann Unit-Tests | Erst alles prüfen, dann übernehmen |
| Ein Streitfall mit zu kurzem Grund wurde abgelehnt, stand intern aber schon auf „Streitfall“ (genauso bei der Admin-Klärung) | Unit-Test `handoverAndDispute` | Erst prüfen, dann ändern |
| Bei der Team-Übergabe wurde der alte Owner herabgestuft, bevor geprüft war, ob der neue noch im Team ist | Code-Durchsicht | Reihenfolge getauscht |
| Falsche JSON-Datentypen (z. B. Liste statt Text) gaben „Interner Fehler“ (500) | Code-Durchsicht | jetzt 400 „Ungültige Daten“ |
| Die Karte zeigte Länder erst nach 10 Sekunden (sie verpasste das erste Laden) | Screenshot | Karte übernimmt vorhandene Daten sofort |
| Nach dem Abhaken einer Aufgabe stand der Fortschritt erst nach einem Klick woanders richtig da | Browser-Test | nur echte Textfelder verzögern das Neuzeichnen |
| Handy: Menü schloss nicht sofort; nach dem Abmelden landete der nächste Spieler auf der alten Seite | Browser-Test | behoben |
| Aktiver Filter-Knopf im hellen Design unlesbar, 3D-Hinweis über BlueMaps Leiste, Beschriftungen übereinander | Screenshots | behoben |

## So wurde 0.3 getestet

Auf einem echten Minecraft-26.3-Server mit Fabric API, BlueMap 5.29 und vier Fake-Spielern (Carpet), einer davon OP:
- **95** Java-Unit-Tests (u. a. 8 Bieter × 50 gleichzeitige Gebote, 16 gleichzeitige Sofortkäufe, volle Festplatte, Neustart)
- **7** Web-Unit-Tests (Koordinaten)
- API: **59** Kern + **85** SMP + **46** Handel (inkl. gleichzeitiger Gebote/Käufe über HTTP)
- Browser (Chromium): **44** Kern + **32** SMP mit zwei Spielern + **22** BlueMap-Abgleich (echte Kamera)
- Umstieg von echten 0.2-Daten auf 0.3, Neustart, Ablauf einer Auktion durch den Server-Timer

# Version 0.3.1: Team-Kürzel im Spiel

- Mitglieder öffentlicher Teams bekommen `[KÜRZEL]` vor den Namen – im Chat, in der Tab-Liste und über dem Kopf.
  Umgesetzt mit Minecrafts eigenen Scoreboard-Teams, also ohne Mod im Spiel der Spieler.
- Sicher eingebaut: nur eigene `wpt_…`-Teams werden angefasst, fremde Scoreboard-Teams (Minispiele, `/team`) bleiben, private
  Teams bekommen kein Kürzel, „Unsichtbare halb sehen“ ist aus, damit sich am Spiel nichts ändert. Abschaltbar mit `chatTeamTags`.
- Getestet: 6 Unit-Tests mit Minecrafts echtem Scoreboard, 10 Prüfungen auf dem echten Server (`/team list`, `/say` zeigt
  `[[KÜRZEL] Alice] Hallo`), Neustart, Ab- und Wiedereinschalten.

# Version 0.4: Spiel-Integration

Bis 0.3 passierte alles auf der Webseite – im Spiel merkte man nichts davon. Jetzt kommt der Planer ins Spiel, und zwar nur
mit dem, was der Server ohnehin kann (niemand braucht eine Mod im Spiel):

- **Nachrichten im Chat:** Kurz nach dem Einloggen „Zu erledigen“ (dieselbe Liste wie auf dem Dashboard), danach live, was neu ist:
  Einladungen, Bewerbungen, überboten, verkauft/gekauft/gewonnen, Gegenangebote, neue Gebote, Streitfälle. Alles wird aus dem echten
  Zustand berechnet – es gibt keine erfundenen Ereignisse und keine Nachholung alter Meldungen. Viele Gebote hintereinander werden
  zusammengefasst; „überboten“ kommt immer sofort. Niemand erfährt im Spiel mehr als auf der Webseite.
- **Land-Anzeige:** Name des Landes (mit Team-Kürzel, wenn öffentlich) über der Hotbar beim Betreten, „Wildnis“ beim Verlassen.
- **Todespunkt:** Beim Sterben ein privater Marker ☠ (einer pro Spieler, wird verschoben), nach dem Wiederbeleben Entfernung und
  Richtung im Chat. Schützt keine Items – das steht auch so dabei.
- **Befehle:** `/planer` (Liste), `/planer hilfe`, `/planer einstellungen` mit Klick-Schaltern, `/planer todespunkt`,
  `/planer marker <Name>` und **Team-Chat** `/tc` – auch für private Teams.
- **Webseite:** Schalter „Im Spiel“ in den Einstellungen, **Mess-Werkzeug** auf der Karte (Entfernung, Richtung, Strecke durch den
  Nether, ungefähre Laufzeit), `/api/todo`.
- Jeder Spieler kann Nachrichten, Land-Anzeige und Todespunkt für sich abschalten; der Admin für alle (`gameNotifications`,
  `landEnterMessages`, `deathMarkers`, `teamChat`).

## Fehler, die beim Bauen und Testen von 0.4 gefunden und behoben wurden

| Fehler | Gefunden durch | Behoben |
|---|---|---|
| Das Dashboard zeigte Server-Admins Bewerbungen als „zu erledigen“, obwohl sie darüber nicht entscheiden dürfen | Vergleich mit der neuen Liste im Spiel | Dashboard und Spiel nutzen dieselbe Regel (Moderator und höher) |
| Die Liste im Spiel hätte bei Preisen die *aktuelle* Handels-Einheit aus der Config benutzt statt der Einheit des Angebots | Code-Durchsicht | Einheit des Angebots |
| Beim Import wurde ein fremder Todespunkt als normaler Marker übernommen (Alices Todespunkt landete bei Dana) | Integrationstest: Dana hatte plötzlich zwei Todespunkte | Todespunkte werden nicht importiert; die Vorschau nennt den Grund |
| Ein Record-Feld hieß `notify` – das gibt es in Java schon (`Object.notify()`), der Bau schlug fehl | Compiler | umbenannt |
| Neue CSS-Klasse `top` gab es schon (Kopfzeile, „klebt“ beim Scrollen) – die Schalter in den Einstellungen sahen falsch aus | Screenshot | eigener Klassenname |
| Spielernamen auf der Karte konnten unter Marker-Namen verschwinden (z. B. „Todespunkt“ neben dem Spieler) | Screenshot | Spielernamen haben Vorrang |
| `/planer` zeigte wie die Zusammenfassung nur 6 Punkte – ohne Webseiten-Link kam man im Spiel nicht an den Rest | Code-Durchsicht | `/planer` zeigt bis zu 12 und sagt, wo der Rest steht |
| Test-Fehler: Der Browser-Test „Kopieren gibt Rückmeldung“ las manchmal noch die vorige Meldung („Marker angelegt“) | zweiter Testlauf | Der Test wartet gezielt auf die Kopier-Meldung (genauso streng, ohne Zufall) |
| Test-Anpassung: Ein Browser-Test erwartete den alten Dashboard-Text „Übergabe bestätigen“ | Absicht (neuer, genauerer Text) | prüft jetzt „Du hast »…« gekauft“ |

Und ehrlich: Beim Testen auf dem echten Server sind die Fake-Spieler mehrfach gestorben – durch Fallschaden nach einem Teleport,
durch Monster in der Nacht und einmal ertrunken (das Test-Land liegt in einem See). Das waren Fehler im **Testaufbau**, nicht in der
Mod; jeder dieser Tode hat aber nebenbei gezeigt, dass der Todespunkt auch bei „echten“ Toden gespeichert wird.

## So wurde 0.4 getestet

Auf demselben echten Minecraft-26.3-Server (Fabric API, BlueMap 5.29, Carpet, vier Fake-Spieler, einer OP) – zum Schluss alles
noch einmal mit genau der Jar, die ausgeliefert wird:
- **128** Java-Unit-Tests (27 neu: Hinweise, Nachrichten-Logik mit austauschbarer Uhr, Todespunkt, Land-Anzeige, Schalter, Import)
- **8** Web-Unit-Tests (neu: Messen)
- API: **59** Kern + **85** SMP + **46** Handel, Chat-Kürzel **10**
- **36** Prüfungen „Spiel-Integration“ auf dem Server: Einladung kommt live und nur beim Richtigen, abgeschaltet = nichts und keine
  Nachholung, Zusammenfassung nach dem Einloggen, Land-Anzeige rein/raus/aus, Todespunkt (privat, einer, wird verschoben,
  abschaltbar), `/planer`, `/planer hilfe`, `/planer marker`, `/tc` (auch privates Team, nur Mitglieder), „überboten“ live ohne Namen
- Browser: **48** Kern + **33** SMP + **22** BlueMap + **20** neue (Schalter, Dashboard = Liste im Spiel wörtlich, Messen, Handy)
- Neustart: **19** (inkl. Spiel-Schalter), Update von 0.3.1: alle Länder, Marker, Projekte, Angebote und Favoriten gleich
- Server-Log des letzten Laufs: 0 Fehler

**Nicht geprüft** (ehrlich): wie es in einem echten Minecraft-Fenster aussieht (Farben, Klick-Links, Actionbar), der Hinweis
nach dem Wiederbeleben auf dem Server (Fake-Spieler werden beim Tod entfernt) und Minecrafts eigenes `/teammsg`.

# Version 0.5: Wegweiser, Statistik wie bei CraftAttack, Rennen

- **Wegweiser** (`/planer weg <Marker|Land|todespunkt>`, auf der Webseite „🧭 Im Spiel hinführen“): Pfeil über der Hotbar, gedreht
  nach der Blickrichtung, mit Entfernung und Höhe; im Nether zur passenden Portal-Stelle. Kein Teleport.
- **Land-Grenzen** als Partikel (`/land grenzen`, auch kurz nach `/land neu` und `/land erweitern`), nur für den, der fragt.
- **Sicherheit:** Nachricht im Spiel bei jedem neuen Web-Login (auch bei „Nachrichten aus“) mit Knopf „Überall abmelden“,
  Liste der angemeldeten Geräte in den Einstellungen (nur „Firefox · Windows“, keine IP), einzelne Geräte abmelden.
- **Statistik wie bei CraftAttack:** Server in Zahlen, 16 Ranglisten mit den Namen von CraftAttack (gesamt, 7 Tage, gestern, heute),
  „Wann ist am meisten los?“, Spieler und Spielstunden pro Tag, Rekorde, Verlauf mit Toden und Meilensteinen, eine Seite pro Spieler
  (Zuwachs, pro Online-Stunde, Plätze mit Veränderung zur Vorwoche, Wann online, Top-Listen, Todesursachen), `/planer statistik`
  und `/planer top`. Die Zahlen kommen aus Minecrafts eigener Statistik – nichts wird geschätzt. Jeder kann sich ausblenden.
- **Rennen:** Strecken im Spiel bauen (Tore quer zur Fahrtrichtung), Zeitmessung mit Abschnitten in F1-Farben, Bestenliste,
  Fahrzeug-Regel, Versionen (alte Zeiten ins Archiv), Webseite mit Streckenplan. Die Hundertstel sind zwischen zwei Ticks
  geschätzt – das steht überall dabei; geruckelte Runden heißen „ungenau“ und zählen nicht.

## Fehler, die beim Bauen und Testen von 0.5 gefunden und behoben wurden

| Fehler | Gefunden durch | Behoben |
|---|---|---|
| Fabrics Abmelde-Ereignis kommt bei Carpet-Fake-Spielern nie – die Statistik wurde beim Ausloggen nicht gelesen (ein Tod kurz vor dem Logout fehlte bis zum nächsten Login) | Integrationstest: „Bob: ein Tod mehr“ schlug fehl | Die Mod bemerkt jede Sekunde selbst, wer fehlt, und meldet ihn genau einmal ab (auch für Wegweiser, Land-Anzeige und Zeitmessung) |
| Nach jedem Neustart wurden die Statistik-Dateien erneut „übernommen“ (Minecraft speichert sie kurz nach dem letzten Stand der Mod) | Server-Log | Nur übernehmen, wenn sich die Zahlen wirklich geändert haben |
| Wer im Ranglisten-Schalter „aus“ gewählt hatte, stand in der Bestenliste der Rennen trotzdem mit Namen | Code-Durchsicht | Zeit bleibt (sonst wäre die Bestenliste falsch), der Name heißt für andere „ausgeblendet“ – auch im Chat und in der Aktivität |
| Zeitmessung und Wegweiser hätten sich über der Hotbar abgewechselt (beide schreiben regelmäßig neu) | Code-Durchsicht | Die Rennanzeige hält ihren Platz eine halbe Sekunde |
| Nach „/planer nachrichten aus“ konnten schon wartende Nachrichten noch kommen | Unit-Test | Warteschlange wird beim Abschalten geleert |
| Statistik-Pfad: In 26.3 liegen Minecrafts Statistik-Dateien in `world/players/stats`, nicht mehr in `world/stats` | Nachgesehen in der echten Welt | Pfad von Minecraft selbst (`LevelResource.PLAYER_STATS_DIR`) |
| Handy: Die Diagramme wurden verkleinert, die Schrift war nur noch ~6 px groß | Screenshot | Zeichnungen richten sich nach der Breite, die Schrift bleibt 11 px |
| „Zuwachs“ und „Pro Online-Stunde“ waren lange Listen voller Nullen; Achse „0 / 1,5 / 3“ für Spielerzahlen | Screenshots | Nur Zeilen mit Werten; ganze Zahlen auf der Achse |
| **Nach einem Absturz war der ganze Statistik-Verlauf eines Spielers weg.** Minecraft speichert seine Statistik nur alle paar Minuten, der Planer öfter. Stirbt der Server dazwischen (hier: die Testumgebung wurde nachts hart beendet), ist Minecrafts Spielzeit danach kleiner als der letzte Stand des Planers – der hielt das für „Statistik gelöscht“ und warf alle Tagesstände weg (Zuwachs heute und gestern: 0). Außerdem wären Meilensteine doppelt gekommen | Screenshot: „Minecraft-Statistik wurde zurückgesetzt“, obwohl niemand etwas gelöscht hatte; im Log fehlte das Herunterfahren | Tagesstände werden um genau den Rücksprung verschoben – jeder Tageszuwachs bleibt, nichts wird negativ, „Gesamt“ bleibt Minecrafts Zahl. Meilensteine zählen ab dem höchsten je gesehenen Wert. Neu: 2 Unit-Tests und `crash_check.py` mit echtem `kill -9` (alte Jar: Spielzeit heute 32 min → 0, durchgefallen; neue Jar: 6/6) |
| „Aktiv (ohne AFK)“ konnte größer sein als die Spielzeit (Screenshot: 10 min gegen 9 min). Minecraft zählt Spielzeit in Ticks, der Planer zählte Aktiv/AFK mit der echten Uhr – bei Ruckeln zählt Minecraft weniger. Hier waren es nur 2 Sekunden (plus Abrunden), auf einem Server mit 18 statt 20 Ticks pro Sekunde wären es 10 % | Screenshot nach dem Commit, dann die genauen Millisekunden nachgesehen | Aktiv/AFK zählen jetzt dieselben Ticks; die AFK-Grenze (5 Minuten) bleibt echte Zeit. Neuer Unit-Test mit einem Server auf 10 Ticks pro Sekunde |
| Handy: In der Rennen-Bestenliste war die Spalte S4 unsichtbar – man konnte zwar seitlich wischen, aber nichts zeigte das an | Screenshot nach dem Commit | Engere Zellen auf schmalen Bildschirmen (4 Abschnitte passen ab 390 px) und ein Schatten am Rand jeder Tabelle, solange es seitlich weitergeht. Bei sehr vielen Toren muss man weiterhin wischen |
| Test-Fehler: Der Browser-Test prüfte die Rennen-Seiten nur, weil von Hand angelegte Demo-Zeiten herumlagen – die vom Renn-Test behaltene Strecke hatte keine aktuellen Zeiten (der Test baut sie am Ende um). Und nach 5 behaltenen Strecken lief der Renn-Test in die Grenze „höchstens 5 Strecken“ | voller Testlauf (Renn-Test 2 von 26) | Der Renn-Test räumt Alices alte „Testkurs …“-Strecken auf und fährt im Behalte-Modus zum Schluss zwei gültige Runden – der Browser-Test hängt nicht mehr an Zufallsdaten |
| Test-Fehler: Ein Browser-Test wartete nach „Details“ auf irgendeine Überschrift – die Liste hat auch eine; manchmal prüfte er zu früh | voller Testlauf (1 von 48 rot) | wartet jetzt auf die Überschrift mit dem Landnamen (strenger, ohne Zufall) |

Und ehrlich zum **Testaufbau**: Die Fake-Spieler sind wieder gestorben – sie wurden auf eine Plattform teleportiert, die es noch
nicht gab (erst bauen, dann hinstellen, behoben). Carpets `look` nimmt die Winkel anders herum als gedacht (jetzt `look at`).
Und `tp` mit ganzen Zahlen setzt auf die Blockmitte: Die Startlinie lag bei x = 600,5, der Test hielt bei 600,0 an – die Runde
zählte erst bei der nächsten Bewegung. Die Mod hatte recht; der Testweg endet jetzt hinter der Linie.

## So wurde 0.5 getestet

Auf demselben echten Minecraft-26.3-Server (Fabric API, BlueMap 5.29, Carpet, vier Fake-Spieler, einer OP) – zum Schluss alles
noch einmal mit genau der Jar, die ausgeliefert wird:
- **165** Java-Unit-Tests (37 neu: Wegweiser, Grenzen, Actionbar, Geräte, Login-Nachricht; Statistik-Kennzahlen, Zuwachs pro Tag,
  Meilensteine, Ranglisten mit Gleichstand, Ausblenden, AFK (auch bei Lag), Neustart, Absturz; Zeitmessung zwischen den Ticks, ausgelassene Tore,
  Teleport, Ruckler, Fahrzeug-Regeln, Sprint, Bestenliste, Versionen, Texte, API)
- **15** Web-Unit-Tests (neu: Statistik-Zahlen, Tage über die Zeitumstellung, Rundenzeiten, Streckenplan)
- Alte Tests, alle grün: API **59** Kern + **85** SMP + **46** Handel, Chat-Kürzel **10**, Spiel-Integration **36** + **20** Webseite,
  Browser **48** Kern + **33** SMP + **22** BlueMap
- Neu auf dem Server: **41** Statistik (echte Aktionen: Blöcke setzen, springen, Diamanterz abbauen, an Fallschaden sterben; 20 s Lag mit `/tick rate 10`),
  **30** Rennen (bauen, Runden, Abschnitte, ausgelassenes Tor, Teleport, Fahrzeug-Regel, Rechte), **26** Browser (Seite = API,
  Wärmekarte ohne erfundene Nullen, Pfeiltasten, Tabellen-Ansicht, lila Abschnitte, Handy)
- Absturz mit `kill -9`, bevor Minecraft gespeichert hat: **6/6** (Verlauf, Zuwachs, Aktiv ≤ Spielzeit)
- Update von 0.4.0 auf 0.5: **19/19** – Länder, Marker, Teams, Projekte, Angebote, Favoriten, Spiel-Schalter, Aktivität und Logins gleich

**Nicht geprüft** (ehrlich): wie Pfeil, Partikel und Rennanzeige in einem echten Minecraft-Fenster aussehen; Rennen mit echtem
Boot, Pferd oder Elytra (Fake-Spieler können nicht lenken – die Fahrzeug-Erkennung ist nur per Unit-Test geprüft); dass Fabrics
Abmelde-Ereignis bei echten Spielern sofort kommt (die Mod kommt aber auch ohne aus).

## 0.5.1: Ranglisten für alle sichtbar, nur auf der Webseite

Dein Wunsch: „mach die Ranglisten immer für alle sichtbar, einfach als Tab auf der Website, nicht in game“.

- **Eigener Reiter ♛ Ranglisten** in der Navigation (`#/ranglisten`). Die Statistik hat nur noch „Server“ und „Spieler“.
  Alte Links (`#/statistik/ranglisten`, z. B. aus Chat-Nachrichten von 0.5.0) führen auf die neue Seite.
- **Für alle sichtbar:** Ausblenden gibt es nicht mehr – kein Schalter in den Einstellungen (dort steht jetzt ein Hinweis),
  kein „Mich ausblenden“, `/api/prefs/game` mit `rankings` → 400. Alle Spieler stehen in Ranglisten, Server-Summen, Verlauf
  und Renn-Bestenlisten, jede Spielerseite ist für alle eingeloggten Spieler offen.
- **Nicht im Spiel:** `/planer top` und `/planer ranglisten an|aus` gibt es nicht mehr; `/planer statistik` zeigt deine Zahlen
  ohne „Ränge“ und verlinkt auf die Ranglisten der Webseite. `/rennen zeiten` (Bestenliste einer Strecke) bleibt – die gehört
  zum Fahren.
- **Bestehende Daten:** Wer sich in 0.5.0 ausgeblendet hatte, steht ab 0.5.1 wieder drin. Der alte Eintrag in `prefs.json` wird
  überlesen und beim nächsten Speichern weggelassen; alle anderen Schalter bleiben.

**Ehrlich:** Damit sieht jeder eingeloggte Spieler Spielzeit, AFK-Zeit, Tode und Sitzungen aller anderen – so wie bei
CraftAttack. Auf einem Server unter Freunden passt das; sag es deinen Mitspielern trotzdem vorher. Wer das gar nicht will:
`statistics: false` schaltet Statistik und Ranglisten für alle ab.

| Fehler | Gefunden durch | Behoben |
|---|---|---|
| Test-Fehler: Der Browser-Test ließ bei jedem Lauf ein Land mit 3 Chunks stehen – nach vielen Läufen war Alices Grenze von 64 Chunks voll, und zwei Tests brachen ab | voller Testlauf (Spiel-Integration und Browser-Kern abgebrochen: „höchstens 64 Chunks“) | Der Test heißt sein Land „Testland …“ und räumt alte eigene Testländer am Anfang auf (nur ohne Mitbesitzer) |
| Test-Fehler: Der BlueMap-Test setzte einen künstlichen Status und las ihn erst danach – holte die Seite (alle 5 s) dazwischen den echten Status, war der Test rot | voller Testlauf (1 von 22 rot) | Setzen und Lesen in einem Schritt im Browser – kein Zeitfenster mehr |

# Version 0.6: Events mit Kalender, Wochen-Rückblick – und keine Rennen mehr

Deine Wünsche: „Events mit Kalender, Wochen-Rückblick“ und „Gar nichts mit Rennen, nur Minecraft-Sachen“.

- **Rennen sind komplett raus:** kein `/rennen`, keine Strecken-Seite, kein `/api/races`, keine Rennanzeige über der Hotbar.
  `races.json` bleibt unangetastet liegen (0.6 liest und schreibt sie nicht) – nichts von deinen alten Daten wird gelöscht.
- **Events (◷ Events):** Bauwettbewerb, Treffen, Party, Markt, Gemeinsam bauen oder Sonstiges, mit Datum, Uhrzeit, Dauer,
  Treffpunkt (ein Land oder Koordinaten) und optional nur für ein Team. Liste nach Tagen und Kalender-Monatsansicht, Zusagen
  „Komme / Vielleicht / Komme nicht“ mit Namen, Absagen mit Grund, Verschieben.
- **Im Spiel:** Einladung im Chat mit Knopf [Komme] (höchstens 7 Tage vorher, einmal), `/planer events` und
  `/planer event <id> ja|vielleicht|nein`, Erinnerung 10 Minuten vorher und zum Start für alle, die zugesagt oder „Vielleicht“
  gesagt haben (ist der Treffpunkt ein Land, mit [Hinführen]). Verschieben und Absagen erfahren nur die, die „Komme“ oder
  „Vielleicht“ gesagt haben – wer „Komme nicht“ gesagt hat, wird nicht mehr gestört.
- **Wochen-Rückblick** auf dem Dashboard: Spielzeit, aktive Spieler und Spitze online der letzten 7 Tage, Wochen-Spitzenreiter
  (dieselben Zahlen wie „7 Tage“ in den Ranglisten), Meilensteine, neue Länder, abgeschlossene Projekte und Aufgaben, Events.
  Daneben „Nächste Events“. Alles aus echten Daten; Leeres wird weggelassen statt mit Nullen aufgefüllt.

**Ehrlich – was Events nicht können:**
- Erinnerungen kommen nur, wenn du **online** bist. Wer offline ist, bekommt nichts (kein Handy, keine Mail) – nur auf dem
  Dashboard steht es beim nächsten Öffnen.
- Alle Zeiten sind in der **Zeitzone des Servers** (Config `timeZone`), im Spiel und auf der Webseite gleich. Wer woanders wohnt,
  sieht trotzdem Server-Zeit – das steht im Formular dabei.
- „Zugesagt“ heißt nicht „war da“. Der Rückblick schreibt deshalb „N hatten zugesagt“, nicht „N kamen“ – die Mod prüft nicht,
  wer wirklich am Treffpunkt war.
- Keine wiederkehrenden Events, keine Erinnerung zu einer selbst gewählten Zeit.
- Events sind (noch) nicht im Export der Webseite und kommen auch nicht in einen Import.
- Projekte, die **vor** 0.6 abgeschlossen wurden, haben kein Datum – sie fehlen im Rückblick der ersten Woche.
- Wird ein Team aufgelöst, verschwinden seine Team-Events für die früheren Mitglieder ganz – auch die Absage sehen sie nicht
  (nur wer eingeladen hat). Beim Durchsehen gefunden; ich habe es so gelassen, weil sich ohne Team nicht mehr sagen lässt, wer
  das private Event sehen darf.

## Fehler, die beim Bauen und Testen von 0.6 gefunden und behoben wurden

| Fehler | Gefunden durch | Behoben |
|---|---|---|
| Ein laufendes Event ließ sich nicht bearbeiten: Die Seite zeigte „Bearbeiten“, aber Speichern scheiterte immer mit „Der Beginn muss in der Zukunft liegen“ – auch wenn man nur die Beschreibung änderte (das Formular schickt den alten Beginn mit) | Code-Durchsicht nach dem vollen Testlauf | Bleibt der Beginn gleich, wird er nicht mehr geprüft (Server und Webseite). Ein neuer Beginn in der Vergangenheit geht weiterhin nicht. Neuer Unit-Test |
| Die Erinnerung zeigte zwei Symbole: „⚠ ⏰ In 10 Minuten“ | Chat-Zeile im Server-Log | Das Symbol kommt nur noch von der Art des Hinweises (⚠) |
| Wegweiser zu einem Event hieß „Wegweiser zu »Event »Bauabend««“ – doppelte Anführungszeichen | Integrationstest | Das Ziel heißt einfach wie das Event |
| Auf der Detailseite klebten Texte zusammen: „Alicelädt ein“, „2 kommendu: Komme“ | Screenshot | Leerzeichen zwischen Name und Markierung |
| Test-Fehler: Ein Kalender-Unit-Test nahm an, der Februar 2026 beginne an einem Montag (es ist ein Sonntag) – der Code war richtig | Unit-Test rot | Test korrigiert (5 Wochen), dazu Februar 2027, der wirklich genau 4 Wochen hat |
| Test-Fehler: Ein Unit-Test erwartete „nicht online“ (409) für ein Event ohne Treffpunkt – richtig ist „kein Treffpunkt“ (400) | Unit-Test rot | Erwartung korrigiert, dazu ein Event **mit** Treffpunkt für den 409-Fall |
| Test-Fehler: Der Browser-Kern-Test räumt das Testland vom letzten Lauf weg (3 Chunks), lud danach aber den Chunk-Zähler nicht neu – er erwartete 17, die Seite zeigte richtig 14 | voller Testlauf (2 von 48 rot) | Der Test lädt nach dem Aufräumen auch den Zähler neu, so wie die Seite selbst nach dem Löschen |
| Test-Fehler: Der Browser-Test prüfte das Löschen mit einem Abruf des gelöschten Events – der erzeugte selbst den Konsolen-Fehler 404, den der Test dann als Fehler meldete | Browser-Test rot | Prüft das Löschen über die Liste |

## So wurde 0.6 getestet

Auf demselben echten Minecraft-26.3-Server (Fabric API, BlueMap 5.29, Carpet, vier Fake-Spieler, einer OP):
- **167** Java-Unit-Tests: 13 neu (Events: Regeln, Rechte, Team-Events, Zusagen, Verschieben, Erinnerungen nur für „Komme“ und
  „Vielleicht“, Einladung, Land als Treffpunkt, Neustart, Server-Zeitzone, API; Rückblick: nur echte Daten der letzten 7 Tage und
  nur Sichtbares; Erinnerung im Chat). Die 12 Rennen-Tests sind mit den Rennen gelöscht – sie testeten Code, den es nicht mehr gibt.
- **17** Web-Unit-Tests (neu: Event-Zeiten in Server-Zeitzone auch über die Zeitumstellung, Kalender-Wochen, Event-Hinweise wie
  im Spiel; die 2 Rennen-Tests sind weg)
- Voller Testlauf, alle grün: API **59** Kern + **85** SMP + **46** Handel, Chat-Kürzel **10**, Spiel-Integration **36** + **20**
  Webseite, Statistik **41** + **23** Browser (vorher 26, der Rennen-Teil ist weg), Browser **48** Kern + **33** SMP + **22** BlueMap
- Neu auf dem Server: **30** Events (Einladung im Chat, `/planer events`, Zusage per Befehl, Verschieben, Absagen, Erinnerung und
  Start mit einem Event in gut 2 Minuten, Wegweiser, Rückblick, `/rennen` und `/api/races` gibt es nicht mehr), **31** Browser
  (Formular, Liste, Kalender, Zusagen, Dashboard-Text = Spiel-Text, Verschieben, Absagen, Löschen, Handy)
- Im ersten vollen Lauf waren 2 von 48 Browser-Kern-Prüfungen rot (Test-Fehler, siehe oben). Nach den beiden Korrekturen liefen
  Events, Events im Browser und Browser-Kern mit der neuen Jar noch einmal: 30/30, 31/31, 48/48. Das laufende Event habe ich
  zusätzlich von Hand im Browser bearbeitet.
- Update von 0.5.1 auf 0.6.0: **19/19** – Länder, Marker, Teams, Projekte, Angebote, Favoriten, Spiel-Schalter, Aktivität und
  Logins gleich. `races.json` danach Byte für Byte gleich (gleiche Prüfsumme, gleiche Änderungszeit), Config umgeschrieben
  (`races` weg, `events` da), Spielzeit keines Spielers kleiner, `/api/races` → 404.
- Neustart mit 0.6.0: **21/21**, jetzt auch mit einem Team-Event (Treffpunkt, zwei Zusagen)

**Nicht geprüft** (ehrlich): wie Einladung und Knöpfe in einem echten Minecraft-Fenster aussehen (Fake-Spieler haben keinen
Bildschirm – geprüft ist der Text im Server-Log); eine Erinnerung genau 10 Minuten vorher mit echter Wartezeit (der Test nimmt
2 Minuten, die Grenze prüft ein Unit-Test mit fester Uhr); der Rückblick über eine echte Woche mit vielen Spielern; zurück von
0.6 auf 0.5.1.

## 0.6.1: Kürzere „Zu erledigen“-Liste, Kalender-Datei, „Nochmal planen“ – und eine gründliche Durchsicht

Deine Wünsche: „kürz die Zu-erledigen-Liste“ und „füge noch Sachen hinzu, die du magst, und guck am Ende alles nach Fehlern durch“.

- **„Zu erledigen“ zeigt 6 Einträge**, darunter „… und N weitere anzeigen“ (aufklappbar), in der Überschrift steht die Zahl.
  **Neu sortiert, das Dringendste zuerst** – im Spiel und auf der Webseite gleich: ein Event, das gleich beginnt oder gerade
  begonnen hat, Streitfälle, Einladungen, Warnungen (Übergaben, überboten, überfällig, abgesagt), zuletzt Infos (Bewerbungen).
  Ohne die neue Reihenfolge hätte das Kürzen geschadet: Vorher standen Events ganz unten – „In 10 Minuten: Bauabend“ wäre hinter
  29 Übergaben verschwunden. Dasselbe galt schon vorher für die Zusammenfassung im Spiel (höchstens 6 Zeilen).
- **📅 In meinen Kalender:** Ein Event als `.ics`-Datei für Handy oder PC, mit Erinnerung 10 Minuten vorher – damit gibt es
  endlich eine Erinnerung, auch wenn man nicht im Spiel ist. Die Datei entsteht im Browser, nichts geht an einen fremden Dienst.
- **🔁 Nochmal planen:** dasselbe Event eine Woche später, zur selben Uhrzeit – auch über die Zeitumstellung (einfach 7 × 24
  Stunden dazuzählen hätte aus 20:00 Uhr 19:00 Uhr gemacht). Erst das Formular, dann wird eingeladen.
- **📅 Event hier planen** auf der Seite eines Landes und bei Markern, **📅 Bauabend planen** bei Projekten: Treffpunkt (und
  bei Projekten „Gemeinsam bauen“) ist schon ausgefüllt.

**Ehrlich – was die Kalender-Datei nicht kann:** Wird das Event verschoben oder abgesagt, merkt dein Kalender das nicht von
selbst – die Datei ist eine Momentaufnahme. Neu laden hilft; manche Kalender-Apps legen es dann doppelt an. Ob Google-, Apple-
oder Outlook-Kalender die Datei richtig übernehmen, konnte ich hier nicht ausprobieren (kein Handy). Geprüft habe ich die Datei
mit meinem eigenen Test **und** mit einem unabhängigen Kalender-Programm (Python-Bibliothek `icalendar`): Titel mit Komma,
Semikolon, Backslash und Emoji kommen exakt zurück, Zeiten in UTC, Erinnerung −10 Minuten, keine Fehler.

## Fehler, die bei der Durchsicht von 0.6.1 gefunden und behoben wurden

| Fehler | Gefunden durch | Behoben |
|---|---|---|
| **Importierte Projekte hätten im Rückblick als „diese Woche fertig“ gestanden** – wer Projekte über Einstellungen → Import wieder einspielt, dessen fertige Projekte und erledigte Aufgaben bekamen „jetzt“ als Datum. Eine vorgetäuschte Zahl | Code-Durchsicht | Beim Import ist das Datum unbekannt (0) – zählt nicht. Unit-Test, und mit absichtlich wieder eingebautem Fehler geprüft, dass der Test ihn findet |
| Marker-Namen dürfen Zeichen haben, die in Event-Titeln verboten sind (z. B. „Bob's Haus“) – „Event hier“ hätte einen Titel vorgeschlagen, den der Server ablehnt | Code-Durchsicht | Der Vorschlag lässt verbotene Zeichen weg (dieselbe Regel wie der Server), Unit-Test |
| Vergangene Events wären für immer alle 20 Sekunden an jeden offenen Browser geschickt worden – nach einem Jahr Hunderte | Code-Durchsicht | Die Liste enthält Vergangenes nur 60 Tage. Gespeichert bleibt alles; ältere Events holt die Detailseite einzeln (von Hand mit einem 70 Tage alten Event auf dem Server geprüft) |
| Meine eigene neue Doku versprach „ältere Events per Link erreichbar“ – die Detailseite suchte aber nur in der Liste | beim Gegenlesen der eigenen Änderung | siehe oben: einzeln holen. Wurde ein Event gelöscht, während man es ansieht, wird nicht nachgefragt |
| Test-Fehler: Der SMP-Browser-Test suchte einen neuen Kauf auf dem Dashboard – seit dem Kürzen steht der (bei Bob mit vielen offenen Übergaben auf dem Testserver) im zugeklappten Teil. Der Test brach ab | voller Testlauf | Alle Browser-Tests klappen die Liste über eine gemeinsame Hilfsfunktion auf und prüfen weiter genau denselben Text |
| Test-Fehler: Mein Test zu „nächste Woche“ rechnete 20 Tage weiter und erwartete den 21.11. – richtig ist der 14.11. (der nächste Samstag) | Unit-Test rot | Erwartung korrigiert, der Code war richtig |

## So wurde 0.6.1 getestet

Auf demselben echten Minecraft-26.3-Server, zum Schluss mit genau der Jar, die ausgeliefert wird:
- **169** Java-Unit-Tests (neu: Reihenfolge von „Zu erledigen“ im Spiel und in `/api/todo`, 60-Tage-Liste, Bearbeiten eines
  laufenden Events, importierte Projekte im Rückblick)
- **20** Web-Unit-Tests (neu: Kalender-Datei – gültig, ≤ 75 Bytes pro Zeile, Sonderzeichen, UTC, Erinnerung; „nächste Woche“
  über beide Zeitumstellungen; Titel-Vorschläge)
- Voller Testlauf, alle grün: API **59** Kern + **85** SMP + **46** Handel, Chat-Kürzel **10**, Spiel-Integration **36** + **22**
  Webseite (neu: zugeklappt nur 6, Rest per Knopf; Reihenfolge = Spiel), Statistik **41** + **23** Browser, Events **30** + **39**
  Browser (neu: Kalender-Datei wirklich herunterladen und prüfen, „Nochmal planen“, „Event hier planen“ bei Land, Marker und
  Projekt), Browser **48** Kern + **33** SMP + **22** BlueMap
- Im vollen Lauf brach der SMP-Browser-Test ab – siehe Tabelle; nach der Korrektur 33/33 (mit derselben Jar)
- Update von 0.6.0 auf 0.6.1: **21/21** (mit Team-Event), Config Byte für Byte gleich
- Von Hand: ein 70 Tage altes Event direkt in `events.json` eingetragen – nicht in der Liste, per Link mit Detailseite da;
  ein unbekannter Link zeigt „gibt es nicht (mehr)“

**Nicht geprüft** (ehrlich): die Kalender-Datei in einer echten Kalender-App auf einem Handy; wie die neue Reihenfolge im
echten Minecraft-Fenster aussieht (geprüft ist der Text).

## 0.6.2: Team-Einladungen im Spiel annehmen – und eine Zeitbombe in den Tests

- **`/planer team annehmen <Kürzel>`** und **`/planer team ablehnen <Kürzel>`**: eine Team-Einladung annehmen oder ablehnen,
  oder ein Team übernehmen, das dir übergeben werden soll. Die Nachricht im Chat hat dafür einen Knopf ([Annehmen] bei der
  Einladung, [Übernehmen] bei der Übergabe). Es geht nur, was dir selbst angeboten wurde – niemand kommt ohne eigene Zustimmung
  in ein Team. Das Kürzel darf klein geschrieben sein, Tab ergänzt nur Kürzel, die dir etwas anbieten.
  Nicht `/team`, weil Minecraft selbst schon einen `/team`-Befehl (für OPs) hat.
- Einladung und Übergabe kann es für dasselbe Team nie gleichzeitig geben (eingeladen wird, wer nicht Mitglied ist, übergeben
  nur an Mitglieder) – deshalb reicht ein Befehlspaar für beides.

| Fehler | Gefunden durch | Behoben |
|---|---|---|
| **Zeitbombe in einem Unit-Test (seit 0.6.0):** Der Test „Team aufgelöst = Events abgesagt“ rechnete mit einer festen Uhrzeit (26.10.2026, 17 Uhr UTC), das Auflösen nahm aber die echte Uhr. Bis 19 Uhr UTC am 26.10. grün – danach für immer rot. Wer die Mod später selbst gebaut hätte, hätte einen kaputten Build bekommen. Die Mod selbst war richtig | Build am Abend plötzlich rot, ohne Änderung an Events | Das Auflösen bekommt die Uhrzeit als Wert, der Test übergibt seine feste Uhr. Danach **alle** Java- und Web-Tests mit vorgestellter Uhr laufen lassen (`libfaketime`: morgen, in 40 und 155 Tagen, Sommer 2027, Ende 2027), um weitere Zeitbomben zu finden – Ergebnis siehe unten |
| Wackeliger Test im Handel: „8 gleichzeitige Bieter“ verlangte mehr als 8 angenommene Gebote – das klappt nur, wenn der Rechner die Threads abwechselt. Liefen sie nacheinander, kam jeder genau einmal durch (danach ist man Höchstbietender und darf sich nicht selbst überbieten): 8, rot. Der Handel selbst war richtig – die wichtigen Prüfungen (Gebote steigen streng, niemand überbietet sich selbst, jedes bestätigte Gebot ist gespeichert) waren grün | bei der Zeitreise-Prüfung 2× rot (mit vorgestellter Uhr ist der Rechner langsamer); einzeln nicht nachzustellen | Strenger statt schwächer: Vor jeder der 50 Runden warten alle 8 aufeinander und bieten dann gleichzeitig. Damit ist echter Wettbewerb garantiert und der Test verlangt jetzt **mindestens 50** angenommene Gebote (vorher: mehr als 8) |

## So wurde 0.6.2 getestet

Auf demselben echten Minecraft-26.3-Server, mit genau der Jar, die ausgeliefert wird – diesmal alles in **einem** Durchgang grün:
- **169** Java-Unit-Tests (neu: Knöpfe der Team-Hinweise), **20** Web-Unit-Tests
- Voller Testlauf: API **59** Kern + **85** SMP + **46** Handel, Chat-Kürzel **10**, Spiel-Integration **46** (neu: 10 Prüfungen
  für `/planer team` – Einladung mit Knopf im Chat, annehmen auch klein geschrieben, zweimal annehmen, fremdes Kürzel, Bob kann
  eine Übergabe an Dana nicht annehmen, Übergabe ablehnen, übernehmen) + **22** Webseite, Statistik **41** + **23** Browser,
  Events **30** + **39** Browser, Browser **48** Kern + **33** SMP + **22** BlueMap
- Update von 0.6.1 auf 0.6.2: **21/21**, Config Byte für Byte gleich
- **Zeitreise** (alle Unit-Tests mit vorgestellter Uhr, `libfaketime`): Java 169/169 morgen, in 40 Tagen und in 400 Tagen
  (Nov. 2027); in 155 und 230 Tagen (Sommerzeit 2027) 168/169 – rot war nur der Handels-Test mit 8 Threads, und das liegt am
  Werkzeug: Mit `libfaketime`, aber **ohne** Zeitverschiebung, scheitert er genauso (60 s statt 0,9 s). Web 20/20 an allen Daten.

**Nicht geprüft** (ehrlich): die Knöpfe in einem echten Minecraft-Fenster (geprüft ist der Text im Server-Log).
