# F1 25 & F1 26 – Live-Dashboard 🏁

Funktioniert mit **F1 25 und F1 26** – das Programm erkennt automatisch,
welches Spiel gerade sendet, und zeigt es oben an ("F1 25" / "F1 26").

Zeigt dir **während des Fahrens** live im Browser an: aktuelle Rundenzeit,
letzte/beste Runde, welche Reifen du hast, die **Abnutzung pro Reifen in %**,
Reifen-Temperaturen, Sprit + Reichweite – und wertet automatisch **Konstanz**
und **Reifen-Abbau** deiner Runden aus.

Läuft komplett lokal auf deinem PC. Es werden **keine Zusatz-Pakete** gebraucht,
nur Node.js.

---

## 1. Voraussetzung: Node.js
Falls noch nicht installiert: [nodejs.org](https://nodejs.org) → die „LTS"-Version
installieren. Kurz prüfen (im Terminal / in der Eingabeaufforderung):

```
node --version
```
Wenn eine Versionsnummer kommt (z.B. `v20.x`), passt alles.

---

## 2. Telemetrie in F1 25 / F1 26 einschalten
Im Spiel:

**Einstellungen → Telemetrie-Einstellungen** und dort:

| Einstellung            | Wert                                    |
|------------------------|-----------------------------------------|
| UDP-Telemetrie         | **AN**                                  |
| UDP-Broadcast-Modus    | AUS                                     |
| UDP-IP-Adresse         | **127.0.0.1**                           |
| UDP-Port               | **20777**                               |
| UDP-Senderate          | 20 oder 30 Hz                           |
| UDP-Format             | **passend zum Spiel** (F1 26 → 2026, F1 25 → 2025) |

> Das Programm versteht beide Formate automatisch. Stell das UDP-Format am
> besten auf dein Spiel-Jahr; falls eine Auswahl fehlt, nimm das nächsthöhere.

> `127.0.0.1` bedeutet „dieser PC" – das Spiel schickt die Daten an dein
> eigenes Gerät, wo dieses Programm läuft.

---

## 3. Starten
Im Ordner `f1-live/` ein Terminal öffnen und:

```
node server.js
```

Du solltest sehen:
```
✅ Höre auf F1-Telemetrie an UDP-Port 20777
✅ Dashboard offen unter:  http://localhost:3000
```

Dann im Browser **http://localhost:3000** öffnen und ein Rennen / eine
Trainingssession in F1 25 starten. Die Werte füllen sich live. 🏎️

---

## 4. Wenn Werte komisch aussehen: Debug-Modus
Weil die genauen Daten-Positionen je F1-Ausgabe leicht anders sein können,
gibt es einen Kontroll-Modus:

```
node server.js --debug
```

Dann zeigt das Terminal alle 2 Sekunden, was ankommt – z.B.:
```
Format 2025 | Reifen Soft (Alter 3) | Abnutzung FL 12.4% FR 11.8% RL 9.2% RR 9.6% | Sprit 78.5kg | letzte Runde 91.234s
```
Sehen die Zahlen plausibel aus (Reifen 0–100 %, Sprit ~0–110 kg, Runde als
sinnvolle Zeit), passt alles. Falls nicht, sag Bescheid – dann justieren wir
eine Kleinigkeit im Parser.

---

## Was ist was?
| Datei              | Aufgabe                                                        |
|--------------------|----------------------------------------------------------------|
| `server.js`        | Empfängt die Telemetrie (UDP) und liefert das Dashboard aus.   |
| `f1parser.js`      | Liest die Werte deines Autos aus den Daten-Paketen heraus.     |
| `public/index.html`| Das Dashboard, das du im Browser siehst.                       |

---

## Nur PC
Telemetrie senden können nur die **PC-Versionen** von F1 25 (und iRacing).
Auf Konsole gibt es das leider nicht.
