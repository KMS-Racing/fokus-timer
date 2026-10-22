# Minecraft World Planner

Ein Planungs-Tool für **alle Spieler auf deinem Minecraft-Server** (Java Edition, Fabric 26.3).

- 🗺️ **Claim-Karte**: zeigt nur die Gebiete, die schon entdeckt wurden (wo ein Spieler in der Nähe war)
- 🏠 **Land claimen**: Chunks anklicken → „Neues Land“, oder im Spiel `/land neu <Name>`
- 🤝 **Land teilen**: Mitbesitzer einladen. Alle Besitzer sind gleichberechtigt, niemand kann einen anderen rauswerfen.
- 👀 **Für alle sichtbar**: Jeder sieht alle Länder, auf der Webseite und als Ebene in **BlueMap** (3D-Karte)
- 📍 **Bau-Planer**: Marker mit Koordinaten, Nether↔Oberwelt-Umrechnung, TP-Befehl kopieren, öffentlich oder privat
- 🔐 **Login ohne Passwort**: im Spiel `/planer login` tippen, Code auf der Webseite eingeben
- 🌱 **Seed-Vorschau** (optional): zweite BlueMap von einem extra Server mit demselben Seed

> **Wichtig, ehrlich gesagt:** Claims sind **nur eine Anzeige**. Im Spiel wird nichts geschützt. Jemand kann trotzdem in einem fremden Land abbauen.
> Das Tool zeigt, wem was gehört, damit ihr euch absprechen könnt. Wenn ihr echten Schutz gegen Griefer braucht,
> gibt es fertige Mods wie *Open Parties and Claims* (läuft auf 26.3).

---

## Was du brauchst

| Was | Version | Wo |
|---|---|---|
| Minecraft-**Server** (Java) mit Fabric | **26.3** | https://fabricmc.net/use/server/ |
| Fabric API | 0.164.0+26.3 (oder neuer für 26.3) | https://modrinth.com/mod/fabric-api |
| BlueMap (optional, für 3D-Karte) | 5.29 oder neuer | https://modrinth.com/plugin/bluemap |
| Java (JDK) zum Bauen und für den Server | **25** | https://adoptium.net/ (Temurin 25) |

Die Mod läuft **nur auf einem richtigen Server** (`"environment": "server"`), nicht in einer Einzelspieler- oder LAN-Welt.

---

## 1. Mod bauen

```bash
cd fabric-mod
./gradlew build          # Windows: gradlew.bat build
```

Beim ersten Mal lädt Gradle viel herunter, das dauert ein paar Minuten.
Fertig ist die Datei `fabric-mod/build/libs/world-planner-0.2.0.jar`.
(Die `...-sources.jar` daneben brauchst du nicht.)

## 2. Auf dem Server installieren

1. **Backup** von Server und Welt machen, dann Server stoppen.
2. `world-planner-0.2.0.jar` und die passende **Fabric API** in den Ordner `mods/` legen. Für die 3D-Karte auch **BlueMap**.
3. Server starten. Im Log sollte stehen:
   ```
   [World Planner] Webseite und API laufen auf http://127.0.0.1:8765/
   ```
4. Die Mod hat `config/world-planner.json` angelegt (siehe [Einstellungen](#einstellungen)).

## 3. Webseite öffnen und einloggen

- Auf dem **Server-PC selbst**: http://127.0.0.1:8765/ öffnen.
- Damit **andere** die Seite erreichen, siehe [Für Freunde erreichbar machen](#für-freunde-erreichbar-machen).

Einloggen:
1. Auf dem Server `/planer login` tippen → du bekommst einen Code wie `K7QM-2XPA` (5 Minuten gültig, nur einmal benutzbar). Klick auf den Code kopiert ihn.
2. Code auf der Webseite eingeben → fertig. Der Browser bleibt 30 Tage eingeloggt.

Ist in der Config `publicUrl` eingetragen, bekommst du im Chat zusätzlich einen anklickbaren Link, der dich direkt einloggt.

---

## Befehle im Spiel

| Befehl | Was er macht |
|---|---|
| `/planer login` | Login-Code für die Webseite |
| `/planer abmelden` | Meldet dich auf **allen** Geräten von der Webseite ab (z. B. wenn dein Handy weg ist) |
| `/land` oder `/land info` | Wem gehört der Chunk, in dem ich stehe? |
| `/land neu <Name>` | Neues Land aus dem Chunk, in dem du stehst |
| `/land erweitern [Land]` | Chunk zu deinem Land hinzufügen (muss angrenzen). Ohne Namen wird das angrenzende Land genommen. |
| `/land abgeben` | Chunk aus deinem Land entfernen |
| `/land liste` | Deine Länder, Chunk-Verbrauch und Einladungen |
| `/land einladen <Land> <Spieler>` | Mitbesitzer einladen (geht auch, wenn der Spieler offline ist) |
| `/land annehmen <Land>` / `/land ablehnen <Land>` | Einladung annehmen / ablehnen |
| `/land verlassen <Land>` | Aus einem Land austreten. Als letzter Besitzer wird das Land frei. |
| `/land admin loeschen <Land>` | **Nur OPs:** Land löschen |
| `/land admin entfernen <Land> <Spieler>` | **Nur OPs:** Besitzer entfernen (z. B. bei Streit) |

Namen mit Leerzeichen in Anführungszeichen: `/land neu "Kims Base"`. Mit **Tab** werden Namen vervollständigt.

### Regeln für Länder

- Ein Land besteht aus **zusammenhängenden Chunks** (Seite an Seite) in einer Dimension.
- Man kann nur **entdeckte** Chunks claimen (abschaltbar mit `claimOnlyDiscovered`).
- Jeder Spieler darf höchstens `maxChunksPerPlayer` Chunks besitzen (Standard 64). Geteilte Länder zählen bei jedem Besitzer mit.
- **Gleichberechtigte Besitzer:** Jeder Besitzer darf einladen, erweitern, verkleinern, umbenennen. **Niemand kann einen anderen Besitzer rauswerfen**, man kann nur selbst austreten. Nur Admins können Besitzer entfernen.
- Nachteil dieser Regel: Wenn du den Falschen einlädst, kannst du ihn nicht selbst wieder loswerden. Lad also nur Leute ein, denen du vertraust. Sonst muss ein Admin helfen.
- Wer **OP** ist (Level 2+), ist auf der Webseite automatisch Admin.

---

## Einstellungen

Datei: `config/world-planner.json` (Server neu starten nach Änderungen)

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `bind` | `"127.0.0.1"` | Auf welcher Netzwerk-Adresse der Webserver lauscht. `127.0.0.1` = nur dieser PC. `0.0.0.0` = alle (siehe Sicherheit!). |
| `port` | `8765` | Port der Webseite |
| `publicUrl` | `""` | Adresse, unter der Spieler die Seite öffnen (z. B. `https://planer.example.de/`). Dann gibt es im Chat einen Login-Link. |
| `allowedOrigin` | `""` | Nur nötig, wenn die Webseite auf einem **anderen** Server liegt als die Mod (CORS). Normalerweise leer lassen. |
| `liveMapUrl` | `""` | BlueMap-Adresse der echten Welt. Leer + BlueMap installiert → die Seite probiert automatisch Port 8100 auf demselben Rechner. |
| `seedMapUrl` | `""` | BlueMap-Adresse des Seed-Vorschau-Servers (optional) |
| `blueMapIds` | `{}` | Leer = automatisch von BlueMap erkannt. Nur ausfüllen, wenn „In 3D zeigen“ die falsche Karte öffnet, z. B. `{"minecraft:overworld": "world"}` |
| `maxChunksPerPlayer` | `64` | Höchstzahl Chunks pro Spieler (Admins sind ausgenommen) |
| `maxMarkersPerPlayer` | `100` | Höchstzahl Bau-Marker pro Spieler |
| `claimOnlyDiscovered` | `true` | Nur entdeckte Chunks dürfen geclaimt werden |
| `discoverRadiusChunks` | `6` | Wie viele Chunks rund um jeden Spieler als „entdeckt“ gelten (höchstens die Sichtweite des Servers) |
| `sessionDays` | `30` | Wie lange ein Web-Login gültig bleibt |
| `showSeed` | `true` | Seed auf der Webseite anzeigen. Auf `false` stellen, wenn der Seed geheim sein soll! |
| `showOnlinePlayers` | `true` | Positionen der Online-Spieler auf der Webseite anzeigen |
| `blueMapIntegration` | `true` | Länder und öffentliche Marker in BlueMap einzeichnen |

---

## BlueMap einrichten (3D-Karte)

1. BlueMap in `mods/` legen, Server starten.
2. In `config/bluemap/core.conf` `accept-download: true` setzen. Damit erlaubst du BlueMap, die Minecraft-Texturen zu laden (das ist Teil von Mojangs EULA, lies den Kommentar in der Datei).
3. **Nur entdeckte Gebiete zeigen:** In `config/bluemap/maps/world.conf` (und den anderen Karten) z. B.
   ```
   min-inhabited-time: 200
   ```
   setzen. Dann rendert BlueMap nur Chunks, in deren Nähe Spieler insgesamt mindestens 200 Ticks (10 Sekunden) waren.
   Ehrlicher Hinweis: Das zählt für den **ganzen Server**. „Jeder sieht nur, wo er selbst war“ kann BlueMap nicht.
4. `/bluemap reload`. Die Karte ist dann unter `http://<server>:8100/` erreichbar.
5. Länder erscheinen in BlueMap als Ebene **„Länder“**, öffentliche Marker als **„Bau-Planer“** (oben links im Menü ein-/ausblendbar).

> Achtung: BlueMaps eigener Webserver ist standardmäßig **von allen Rechnern im Netzwerk** auf Port 8100 erreichbar und zeigt alles, was gerendert wurde.

## Seed-Vorschau (optional)

Die Seed-Vorschau zeigt die Welt **so, wie Minecraft sie aus dem Seed erzeugt**: auch Gebiete, die noch niemand entdeckt hat, aber ohne eure Bauten.

1. Zweiten, getrennten Server-Ordner anlegen (z. B. `world-planner-vorschau`) mit **derselben** Minecraft-, Fabric- und BlueMap-Version.
2. In `server.properties` denselben `level-seed` und dieselben Welt-Einstellungen eintragen. **Nicht** die Welt-Dateien vom echten Server kopieren!
3. Mit einem Chunk-Pregenerator ein Gebiet erzeugen lassen (nicht zu groß, das kostet viel Zeit und Platz). BlueMap rendert es dann.
4. Anderen Port nehmen (`server-port` und BlueMap-`port`, z. B. 8101), dann in der Planer-Config `seedMapUrl` eintragen.

Ehrlich: Die Vorschau stimmt nur, wenn Seed, Version, Datapacks und Welt-Einstellungen **exakt** gleich sind. Und sie verrät allen, wo z. B. Dörfer liegen, auch in unentdeckten Gebieten.

---

## Für Freunde erreichbar machen

Standardmäßig ist die Seite **nur auf dem Server-PC** erreichbar (`bind: 127.0.0.1`). Das ist Absicht und sicher.

**Im selben WLAN / LAN:** `bind` auf `"0.0.0.0"` setzen. Freunde öffnen dann `http://<IP-des-Server-PCs>:8765/`.
Das ist okay für zu Hause, aber ohne HTTPS könnte jemand im selben Netz Login-Codes mitlesen.

**Übers Internet:** Bitte **nicht** einfach Port 8765 im Router freigeben! Ohne HTTPS reisen Login-Codes und Sitzungen unverschlüsselt durchs Internet. Stattdessen:
- einen **Reverse-Proxy mit HTTPS** nutzen (Beispiel: `deploy/nginx-example.conf`, Zertifikat z. B. von Let's Encrypt), `bind` bleibt `127.0.0.1`,
- dann `publicUrl` auf die HTTPS-Adresse setzen.

Lass dir beim ersten Mal von jemandem helfen, der sich mit Servern auskennt. Ein falsch eingerichteter Server im Internet ist ein echtes Risiko.

---

## Wo die Daten liegen (Backup!)

Alles liegt im Weltordner unter `world/world-planner/`:

| Datei | Inhalt |
|---|---|
| `lands.json` | Alle Länder, Besitzer, Einladungen |
| `markers.json` | Bau-Planer-Marker |
| `discovered.json` | Entdeckte Chunks |
| `players.json` | Bekannte Spieler (Name ↔ UUID, OP-Status) |
| `sessions.json` | Web-Logins (nur als Hash gespeichert, nicht das echte Token) |

Gespeichert wird alle paar Sekunden im Hintergrund und beim Stoppen. Ist eine Datei kaputt, benennt die Mod sie in `...kaputt-<Zeit>` um, statt sie zu überschreiben.

---

## Was (noch) nicht geht – ehrlich

- **Kein Schutz im Spiel.** Claims sind nur eine Anzeige (bewusste Entscheidung).
- **„Entdeckt“ gilt für den ganzen Server**, nicht pro Spieler.
- **Bestehende Welten:** Gezählt wird erst ab Installation der Mod. Alte Gebiete gelten erst als entdeckt, wenn wieder jemand dort ist.
- **Die Claim-Karte zeigt kein Gelände**, nur entdeckt/unentdeckt, Länder, Marker und Spieler. Das echte Gelände siehst du im Tab „Live-Welt 3D“ (BlueMap).
- **Nur Minecraft 26.3.** Für 26.2/26.4 müssen Versionen in `gradle.properties` angepasst und evtl. kleine Code-Stellen geändert werden.
- Kein Einzelspieler/LAN-Welt-Support (nur dedizierter Server).

## Fehlerbehebung

| Problem | Lösung |
|---|---|
| Webseite lädt nicht | Im Server-Log nach `[World Planner]` suchen. „Port schon belegt?“ → anderen `port` nehmen. |
| „Server nicht erreichbar“ | Läuft der Server? Stimmt die Adresse? Von einem anderen PC aus muss `bind` `0.0.0.0` sein. |
| „Code falsch oder abgelaufen“ | Codes gelten 5 Minuten und nur einmal. Neu `/planer login` tippen. |
| 3D-Tab bleibt leer / „Karte blockiert“ | `liveMapUrl` eintragen. Läuft die Planer-Seite über `https://`, muss BlueMap auch über `https://` laufen. |
| „In 3D zeigen“ öffnet falsche Karte | In `blueMapIds` die richtige Karten-ID eintragen (Dateiname in `config/bluemap/maps/` ohne `.conf`). |

Mehr Technik (Aufbau, API): siehe `ARCHITEKTUR.md`. Was am Original verbessert wurde: `VERBESSERUNGEN.md`.
