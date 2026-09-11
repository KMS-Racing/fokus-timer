# Zeitkonto – Version 1

Eine iPhone-App, die mit der Kamera Liegestütze und Kniebeugen zählt und dir
dafür Handy-Minuten gutschreibt.

> **Wichtig vorweg:** Dieser Code wurde auf einem Linux-Server geschrieben,
> auf dem kein Xcode und kein Swift-Compiler läuft. Er ist also **nicht
> kompiliert getestet**. Rechne damit, dass Xcode beim ersten Build noch
> ein paar Fehler anmeckert – das ist normal und kein Grund zur Panik.
> Schick mir die Fehlermeldung, dann gehen wir sie zusammen durch.

---

## 1. Was die App macht

```
   Kamera  ──►  Vision  ──►  Winkel  ──►  Zähler  ──►  SwiftData
  (Bilder)   (Gelenk-      (Grad-       (oben/       (Kontoauszug)
             punkte)       zahl)        unten)
```

Drei Tabs:

| Tab | Was drin ist |
|-----|--------------|
| **Training** | Kamerabild, Skelett-Overlay, großer Zähler, Start/Pause/Fertig |
| **Konto** | Guthaben, „Minuten ausgeben" mit Countdown |
| **Verlauf** | Alle Buchungen, nach Tagen sortiert |

---

## 2. Xcode-Projekt anlegen

1. Xcode öffnen → **File ▸ New ▸ Project…**
2. **iOS ▸ App** auswählen, **Next**
3. Einstellungen:
   - **Product Name:** `Zeitkonto`
   - **Interface:** SwiftUI
   - **Language:** Swift
   - **Storage:** None *(SwiftData richten wir selbst ein – die Xcode-Vorlage
     würde eine Beispiel-`Item`-Klasse anlegen, die wir nicht brauchen)*
4. Speicherort aussuchen, **Create**
5. Links das blaue Projekt-Symbol anklicken → Target „Zeitkonto" →
   **Minimum Deployments: iOS 17.0** (oder höher).
   Darunter gibt es SwiftData und `@Observable` nicht.

---

## 3. Kamera-Erlaubnis eintragen

**Ohne diesen Schritt stürzt die App beim Start der Kamera sofort ab.**
iOS verlangt, dass du vorher aufschreibst, *wofür* du die Kamera brauchst.

1. Projekt anklicken → Target „Zeitkonto" → Reiter **Info**
2. Mit dem kleinen **+** eine neue Zeile anlegen
3. Key: **Privacy – Camera Usage Description**
   (technischer Name: `NSCameraUsageDescription`)
4. Value: `Die Kamera zählt deine Wiederholungen. Es wird nichts gespeichert oder gesendet.`

Das ist genau der Text, den der Systemdialog später anzeigt.

---

## 4. Die Dateien ins Projekt holen

Die Ordnerstruktur hier entspricht der, die du in Xcode anlegen solltest.
In Xcode: Rechtsklick auf den gelben `Zeitkonto`-Ordner ▸ **New Group** für
jeden Ordner, dann die `.swift`-Dateien per Drag & Drop hineinziehen
(Häkchen bei **Copy items if needed** setzen).

```
Zeitkonto/
├── App/
│   └── ZeitkontoApp.swift      ← ERSETZT die Datei, die Xcode angelegt hat
├── Modelle/
│   ├── Gelenk.swift            Körperpunkte + welche Linien gezeichnet werden
│   ├── Erkennung.swift         Ergebnis eines Kamerabildes + Koordinaten-Umrechnung
│   ├── Uebung.swift            Liegestütz/Kniebeuge: Gelenke + Schwellenwerte
│   ├── Regeln.swift            Umrechnungskurs Wiederholung → Minute
│   └── Eintrag.swift           SwiftData-Modell (der Kontoauszug)
├── Kamera/
│   ├── KameraManager.swift     AVFoundation: Erlaubnis, Session, Bildstrom
│   ├── KoerperErkennung.swift  Vision: Bild → Gelenkpunkte
│   └── KameraVorschau.swift    Live-Bild in SwiftUI anzeigen
├── Logik/
│   ├── Winkel.swift            Die Mathematik
│   ├── WiederholungsZaehler.swift  oben/unten-Zustandsautomat
│   └── TrainingModell.swift    Verbindet alles
└── Ansichten/
    ├── ContentView.swift       ← ERSETZT die Xcode-Vorlage
    ├── TrainingView.swift
    ├── KontoView.swift
    ├── SkelettOverlay.swift
    └── VerlaufView.swift
```

Zwei Dateien der Xcode-Vorlage **löschst** du (Move to Trash):
`ContentView.swift` und `Item.swift`, falls vorhanden. `ZeitkontoApp.swift`
ersetzt du durch meine Version.

---

## 5. Auf dem iPhone testen

Der Simulator hat **keine Kamera** – du siehst dort nur ein schwarzes Bild.
Also:

1. iPhone per Kabel an den Mac mini
2. In Xcode oben das iPhone als Ziel auswählen
3. Target ▸ **Signing & Capabilities** ▸ bei **Team** deine Apple-ID auswählen
   (ein kostenloser Account reicht, die App läuft dann 7 Tage)
4. **▶︎ Run**
5. Auf dem iPhone: *Einstellungen ▸ Allgemein ▸ VPN & Geräteverwaltung* →
   deinem Entwickler-Profil vertrauen

### So stellst du dich hin

- **Kniebeugen:** Handy ca. 2–3 m weg, auf Hüfthöhe, **seitlich** zu dir.
  Vision muss Hüfte, Knie und Knöchel gleichzeitig sehen.
- **Liegestütze:** Handy seitlich neben dir auf den Boden stellen (an eine
  Wand oder ein Buch gelehnt), ca. 1,5 m Abstand.

**Zuerst ohne Zählen prüfen:** Schau dir das grüne Skelett an. Liegen die
Punkte auf deinen echten Gelenken? Wenn nicht, hilft kein Feintuning am
Zähler – dann stimmt Licht, Abstand oder Winkel nicht.

In der Statuszeile oben siehst du live den gemessenen Winkel und die Phase
(`oben` / `unten` / `unbekannt`). Damit kannst du prüfen, ob die
Schwellenwerte für dich passen.

---

## 6. Wie es funktioniert (der interessante Teil)

### 6.1 Die Kamera liefert Bilder

`AVCaptureSession` ist wie ein Rohr: vorne kommt die Kamera rein
(`AVCaptureDeviceInput`), hinten kommen Bilder raus
(`AVCaptureVideoDataOutput`). Etwa 30 Bilder pro Sekunde landen in

```swift
func captureOutput(_ output:, didOutput sampleBuffer:, from connection:)
```

**Wichtig:** Diese Funktion läuft **nicht** auf dem Haupt-Thread. Die
Oberfläche darf man nur vom Haupt-Thread aus anfassen – deshalb steht am Ende
von `KameraManager` das `Task { @MainActor in … }`.

### 6.2 Vision sucht den Körper

```swift
let anfrage = VNDetectHumanBodyPoseRequest()
try handler.perform([anfrage])
let punkte = try anfrage.results?.first?.recognizedPoints(.all)
```

Vision gibt dir für jedes Gelenk einen Punkt **und** einen `confidence`-Wert
zwischen 0 und 1. Punkte mit niedrigem Wert sind geraten – zum Beispiel dein
linker Arm, wenn du seitlich liegst und er vom Körper verdeckt ist.
Deshalb ignorieren wir alles unter `Uebung.mindestVertrauen` (0.3).

### 6.3 Drei Koordinaten-Fallen (hier verliert man Stunden)

1. **Vision rechnet andersherum.** Bei Vision ist (0,0) *unten links* und y
   zeigt nach oben. Bei SwiftUI ist (0,0) *oben links* und y zeigt nach unten.
   → wird in `Erkennung.pixel(_:)` mit `1 - p.y` umgedreht.

2. **Normalisierte Koordinaten verfälschen Winkel.** Vision liefert Werte von
   0 bis 1 für beide Achsen. Bei einem 720×1280-Bild ist „0,1 nach rechts"
   aber nur 72 Pixel, „0,1 nach oben" dagegen 128 Pixel. Würde man so den
   Winkel rechnen, käme Unsinn raus.
   → erst mit der Bildgröße multiplizieren, dann rechnen.

3. **Die Vorschau schneidet ab.** `videoGravity = .resizeAspectFill` vergrößert
   das Bild, bis es den Bildschirm füllt, und schneidet den Rest weg. Das
   Overlay muss genau dieselbe Rechnung machen, sonst schwebt das Skelett
   neben dem Körper.
   → `SkelettOverlay.aufBildschirm(pixel:bild:ansicht:)`

Damit wir die Bilder nicht auch noch drehen müssen, dreht der `KameraManager`
die Kamera-Verbindung gleich auf Hochformat (`videoRotationAngle = 90`).
Deshalb darf Vision mit `orientation: .up` arbeiten.

### 6.4 Vom Punkt zum Winkel

Drei Punkte, der mittlere ist der Scheitelpunkt:

```
Schulter (a)
    \
     \   ← diesen Winkel messen wir
      \
   Ellbogen (b) ────── Handgelenk (c)
```

```swift
let winkel1 = atan2(a.y - b.y, a.x - b.x)   // Richtung b → a
let winkel2 = atan2(c.y - b.y, c.x - b.x)   // Richtung b → c
var grad = (winkel1 - winkel2) * 180 / .pi  // Differenz = gesuchter Winkel
```

`atan2(y, x)` gibt dir die Richtung eines Vektors als Winkel zurück. Die
Differenz zweier Richtungen ist der Winkel zwischen ihnen. Danach klappen wir
das Ergebnis noch auf 0…180°, weil uns die Drehrichtung egal ist.

Arm gestreckt ≈ 170°, unten am Boden ≈ 80°.

### 6.5 Zählen mit einem Zustandsautomaten

Der naive Ansatz „Winkel unter 90 → +1" zählt viel zu oft: Wenn du unten
kurz zitterst, springt der Wert um 90 herum und zählt dreimal.

Deshalb merkt sich die App eine **Phase**:

```
            Winkel ≤ 95°              Winkel ≥ 150°
   ──────────────────────►  UNTEN  ──────────────────────►  OBEN   +1 zählen
```

Gezählt wird nur beim Wechsel **unten → oben**, also wenn die Wiederholung
fertig ist. Zwei Sicherungen:

- **Hysterese:** Zwischen 95° und 150° passiert gar nichts. Der Wert muss
  also wirklich durch den ganzen Bereich wandern.
- **Mindestabstand:** 0,5 Sekunden zwischen zwei Wiederholungen
  (`Regeln.mindestAbstandSekunden`).

Dazu kommt eine Glättung in `Winkel.geglaettet`: Jeder neue Messwert zählt nur
zu 40 %, der alte Wert zu 60 %. Das nennt man Tiefpassfilter – es macht die
Zahl ruhiger, aber auch ein bisschen träge.

---

## 7. Was du zum Ausprobieren drehen kannst

| Datei | Stellschraube | Wirkung |
|-------|---------------|---------|
| `Uebung.swift` | `untenSchwelle` / `obenSchwelle` | Wie tief/hoch du musst, damit es zählt |
| `Uebung.swift` | `mindestVertrauen` | Höher = strenger, zählt seltener |
| `Winkel.swift` | `staerke` bei `geglaettet` | Höher = ruhiger, aber träger |
| `Regeln.swift` | `minuten(fuer:uebung:)` | Der Umrechnungskurs |
| `Regeln.swift` | `mindestAbstandSekunden` | Schutz gegen Doppelzählung |

Wenn die App zu wenig zählt: `untenSchwelle` hochsetzen (z. B. 100) oder
`obenSchwelle` runtersetzen (z. B. 145). Wenn sie zu viel zählt: umgekehrt.

---

## 8. Ehrliche Schwachstellen dieser Version

Du wolltest, dass ich das sage – also:

1. **Der Wechselkurs ist zu billig.** 40 Kniebeugen dauern ungefähr
   2 Minuten und geben dir 40 Minuten Handyzeit. Und Kniebeugen sind viel
   leichter als Liegestütze, zahlen aber gleich viel. Ich habe es so gelassen,
   wie du es geplant hast, aber `Regeln.swift` ist genau die eine Stelle, an
   der du das ändern kannst.

2. **Halbe Wiederholungen sind nur teilweise verhindert.** Die Hysterese
   fängt das Gröbste ab, aber wer schummeln will, schummelt. Das steht ja
   sowieso auf deiner „Später"-Liste.

3. **Die Sperre ist eine Ehrensache.** Ohne Screen Time API hält dich nichts
   davon ab, TikTok trotzdem zu öffnen. Das weißt du – ist aber wichtig,
   bevor du dich über dich selbst ärgerst.

4. **Der Countdown läuft nur, solange du die App offen hast** – zumindest
   sichtbar. Das *Guthaben* wird aber trotzdem korrekt abgebucht, weil wir
   den **Endzeitpunkt** speichern und nicht die Restsekunden. App schließen
   bringt dir also nichts.

5. **Vision kostet Akku.** Körpererkennung auf 30 Bildern pro Sekunde ist
   richtig Arbeit für den Chip. Das Handy wird warm. Deshalb stoppt die
   Kamera, sobald du den Training-Tab verlässt.

6. **Links und rechts können vertauscht sein.** Die Frontkamera spiegelt das
   Bild. Dadurch hält Vision deinen linken Arm für den rechten. Fürs Zählen
   ist das egal (wir nehmen die Seite, die besser zu sehen ist) – aber wundere
   dich nicht, wenn du später mal Statistiken pro Seite bauen willst.

---

## 9. Nächste Schritte, wenn V1 läuft

In dieser Reihenfolge würde ich weitermachen:

1. **Kalibrieren.** Ein paar Sätze machen und die Schwellenwerte auf deinen
   Körper anpassen. Der angezeigte Live-Winkel hilft dir dabei.
2. **Mindest-Tiefe erzwingen** (Anti-Schummeln): zusätzlich merken, wie klein
   der Winkel unten *mindestens* war.
3. **Lokale Mitteilung**, wenn der Countdown abgelaufen ist.
4. **Swift Charts** für die Wochenstatistik – kennst du ja schon vom
   iRacing-Logbuch.
5. Erst ganz zum Schluss: Kurzbefehle und Screen Time API.
