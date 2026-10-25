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
