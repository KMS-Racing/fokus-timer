# Minecraft World Planner

Eine SMP-Plattform im Browser für **alle Spieler auf deinem Minecraft-Server** (Java Edition, Fabric 26.3).
Die Mod bringt ihre Webseite selbst mit – kein extra Webhosting nötig.

| Bereich | Was er kann |
|---|---|
| 🏠 **Dashboard** | „Zu erledigen“ (Einladungen, überboten, Übergaben, Bewerbungen, überfällige Projekte), deine Zahlen, Server-Status, was zuletzt passiert ist |
| 🗺️ **Karte** | Nur **entdeckte** Gebiete, Länder, Marker, Projekte, Spieler, Spawn · Gehe zu Koordinaten · Ebenen · Legende · Maßstab · Regionen |
| 🧊 **Live-Welt** | BlueMap (3D) an derselben Stelle wie die Karte · optional Seed-Vorschau |
| ⚑ **Claims** | Chunks anklicken → **Vorschau** (frei? entdeckt? zusammenhängend? Limit?) → Land erstellen · Mitbesitzer · Notizen · Team-Zuordnung |
| ◆ **Marker** | Orte mit Typ, Symbol, Farbe, optionaler Höhe · Sichtbarkeit: nur ich / Team / Land-Besitzer / alle · Nether-Umrechnung · TP-Befehl |
| ⚔ **Teams** | Rollen Owner / Admin / Moderator / Mitglied / Anwärter · Einladung oder Bewerbung (**nie ohne Zustimmung**) · bestätigte Übergabe · Auflösen ohne Datenverlust · **Kürzel im Spiel**: `[KMS] Alice` im Chat, in der Tab-Liste und über dem Kopf |
| ⚖ **Handel** | Auktion, Sofortkauf, Festpreis, Tausch und **Hilfe** (Bauen, Kämpfen …) · Gebote, Gegenangebote, Übergabe-Bestätigung, Streitfall |
| ⚒ **Projekte** | Bauprojekte mit Aufgaben, Fortschritt, Verantwortlichen, Updates, Land/Team/Ort |
| ☺ **Spieler** | Wer ist online, wer war zuletzt da, Teams, Länder |
| ⚙ **Einstellungen** | Hell/Dunkel, Favoriten, **Export/Import** mit Vorschau, Server-Infos |

Dazu eine **Suche oben** (Länder, Marker, Projekte, Teams, Spieler, Angebote – und Koordinaten wie `100 64 -200`; Taste `/`).

> **Wichtig, ehrlich gesagt:**
> - **Claims sind nur eine Anzeige.** Im Spiel wird nichts geschützt. Jemand kann trotzdem in einem fremden Land abbauen.
>   Das Tool zeigt, wem was gehört, damit ihr euch absprechen könnt. Für echten Schutz gibt es Mods wie *Open Parties and Claims*.
> - **Der Handel überträgt keine Items und kein Geld.** Er hält fest, was ihr abmacht (Gebote, Käufe, Tausch). Übergeben wird **im Spiel**;
>   danach bestätigen beide Seiten auf der Webseite. Bei Problemen gibt es einen Streitfall, den ein Admin klärt.

---

## Was du brauchst

| Was | Version | Wo |
|---|---|---|
| Minecraft-**Server** (Java) mit Fabric | **26.3** | https://fabricmc.net/use/server/ |
| Fabric API | 0.164.0+26.3 (oder neuer für 26.3) | https://modrinth.com/mod/fabric-api |
| BlueMap (optional, für 3D) | 5.29 oder neuer | https://modrinth.com/plugin/bluemap |
| Java (JDK) zum Bauen und für den Server | **25** | https://adoptium.net/ (Temurin 25) |

Die Mod läuft **nur auf einem richtigen Server** (`"environment": "server"`), nicht in einer Einzelspieler- oder LAN-Welt.

---

## 1. Mod bauen

```bash
cd fabric-mod
./gradlew build          # Windows: gradlew.bat build
```

Beim ersten Mal lädt Gradle viel herunter, das dauert ein paar Minuten. `build` führt auch die Unit-Tests aus.
Fertig ist die Datei `fabric-mod/build/libs/world-planner-0.3.1.jar` (die `...-sources.jar` daneben brauchst du nicht).

## 2. Auf dem Server installieren (oder von 0.2 updaten)

1. **Backup** machen (siehe [BACKUP.md](BACKUP.md)), dann Server stoppen.
2. Alte `world-planner-*.jar` aus `mods/` entfernen, `world-planner-0.3.1.jar` und die passende **Fabric API** hineinlegen. Für 3D auch **BlueMap**.
3. Server starten. Im Log sollte stehen:
   ```
   [World Planner] Webseite und API laufen auf http://127.0.0.1:8765/
   ```
   Beim Update von 0.2 zusätzlich: `lands.json wird auf Version 2 umgestellt. Sicherung der alten Datei: lands.json.v1.bak`
   (genauso für `markers.json`). Alle Länder, Besitzer und Marker bleiben erhalten – getestet mit echten 0.2-Daten.
4. Die Mod ergänzt `config/world-planner.json` um die neuen Einstellungen (siehe [Einstellungen](#einstellungen)).

## 3. Webseite öffnen und einloggen

- Auf dem **Server-PC selbst**: http://127.0.0.1:8765/
- Damit **andere** die Seite erreichen: [Für Freunde erreichbar machen](#für-freunde-erreichbar-machen).

Einloggen: Im Spiel `/planer login` tippen → Code wie `K7QM-2XPA` (5 Minuten gültig, nur einmal) → auf der Webseite eingeben.
Ist `publicUrl` eingetragen, gibt es im Chat einen Link, der direkt einloggt. Wer **OP** ist (Level 2+), ist auf der Webseite Admin.

---

## Befehle im Spiel

| Befehl | Was er macht |
|---|---|
| `/planer login` | Login-Code für die Webseite |
| `/planer abmelden` | Meldet dich auf **allen** Geräten von der Webseite ab |
| `/land` oder `/land info` | Wem gehört der Chunk, in dem ich stehe? |
| `/land neu <Name>` | Neues Land aus dem Chunk, in dem du stehst |
| `/land erweitern [Land]` | Chunk zu deinem Land hinzufügen (muss angrenzen) |
| `/land abgeben` | Chunk aus deinem Land entfernen |
| `/land liste` | Deine Länder, Chunk-Verbrauch und Einladungen |
| `/land einladen <Land> <Spieler>` | Mitbesitzer einladen (geht auch, wenn der Spieler offline ist) |
| `/land annehmen <Land>` / `ablehnen <Land>` | Einladung annehmen / ablehnen |
| `/land verlassen <Land>` | Austreten. Als letzter Besitzer wird das Land frei. |
| `/land admin loeschen <Land>` · `/land admin entfernen <Land> <Spieler>` | **Nur OPs** |

Namen mit Leerzeichen in Anführungszeichen: `/land neu "Kims Base"`. Mit **Tab** werden Namen vervollständigt.
Teams, Handel und Projekte gibt es (noch) nur auf der Webseite.

## Regeln

**Länder**
- Ein Land besteht aus **zusammenhängenden Chunks** (Seite an Seite) in einer Dimension. Keine Überschneidungen.
- Nur **entdeckte** Chunks (abschaltbar mit `claimOnlyDiscovered`). Höchstens `maxChunksPerPlayer` Chunks pro Spieler.
- **Gleichberechtigte Besitzer:** Jeder Besitzer darf einladen, erweitern, verkleinern, umbenennen. Niemand kann einen anderen
  rauswerfen – nur Admins. **Löschen** geht nur als einziger Besitzer; mit Mitbesitzern kann man nur austreten.
- Ein Land kann einem **Team** zugeordnet werden (von einem Besitzer, der im Team ist). Die Besitzer bleiben die Besitzer.
- Wird ein Land aufgelöst, bleiben verknüpfte Projekte und Marker erhalten (nur die Verknüpfung fällt weg).

**Teams**
- Rollen: Owner > Admin > Moderator > Mitglied > Anwärter. Man verwaltet nur Leute **unter** dem eigenen Rang.
- Beitritt nur per **Einladung** (muss angenommen werden, läuft nach `teamInviteDays` ab) oder **Bewerbung** (muss ein Moderator+ annehmen).
- Der Owner kann nicht einfach austreten: **Übergabe** (Teamname eintippen, Empfänger nimmt an) oder **Auflösen** (Teamname eintippen).
- Auflösen löscht keine Daten: Länder, Projekte, Marker und Angebote bleiben; „nur Team“ wird zu „privat“.
- Private Teams sehen nur Mitglieder, Eingeladene und Server-Admins (auch nicht in BlueMap).
- **Kürzel im Spiel** (`chatTeamTags`, Standard an): Mitglieder öffentlicher Teams bekommen `[KÜRZEL]` in der Teamfarbe vor den Namen –
  im Chat, in der Tab-Liste und über dem Kopf. Dafür nutzt die Mod Minecrafts eigene Scoreboard-Teams (`wpt_…`, wie `/team`),
  also braucht im Spiel niemand eine Mod. Ist man in mehreren Teams, zählt das mit der höchsten Rolle.
  Private Teams bekommen kein Kürzel (sonst wüsste jeder, wer drin ist). Wer schon in einem anderen Scoreboard-Team ist
  (z. B. von einem Minispiel), wird nicht herausgenommen. Am Spiel ändert sich fast nichts: Unsichtbare Teammitglieder bleiben
  unsichtbar, Schaden untereinander bleibt an – nur Schwert-Schwungangriffe (Sweep) treffen Teammitglieder nicht mehr,
  weil Minecraft sie als Verbündete zählt.

**Handel (Web-Modus)**
- **Auktion:** Mindestgebot = Höchstgebot + Mindestschritt; man kann sich nicht selbst überbieten; ein Gebot in den letzten
  2 Minuten **verlängert** die Auktion um 2 Minuten. Ab dem Sofortkaufpreis nimmt man den Sofortkauf.
- **Festpreis:** der erste Kauf gewinnt – auch wenn mehrere gleichzeitig klicken (getestet mit 16 gleichzeitigen Käufen).
- **Tausch / Hilfe:** Gegenangebote als Text; der Ersteller nimmt eins an. Für „Biete“ und „Suche“.
- Ablauf auf dem **Server** (jede Sekunde), nicht im Browser. Wer geboten hat, sieht nur die Verkäufer-Seite; Käufer sind privat.
- Nach dem Verkauf: Übergabe im Spiel → **beide bestätigen**. Problem → **Streitfall** → ein Admin klärt (nur Status und Notiz).
- Schutz vor Missbrauch: höchstens `tradeActionsPerMinute` Aktionen pro Minute, `maxActiveListingsPerPlayer` aktive Angebote,
  jede Aktion trägt eine `requestId` (ein Doppelklick führt nichts doppelt aus), Speichern **vor** der Bestätigung.
- Team-Angebote dürfen Team-Admins und der Owner einstellen; Teammitglieder können beim eigenen Team nicht kaufen.

---

## Einstellungen

Datei: `config/world-planner.json` (Server neu starten nach Änderungen). Unsinnige Werte werden auf sichere Grenzen gesetzt.

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `bind` | `"127.0.0.1"` | Netzwerk-Adresse des Webservers. `127.0.0.1` = nur dieser PC, `0.0.0.0` = alle (siehe Sicherheit!) |
| `port` | `8765` | Port der Webseite |
| `publicUrl` | `""` | Adresse für Spieler (z. B. `https://planer.example.de/`) → Login-Link im Chat |
| `allowedOrigin` | `""` | Nur wenn die Webseite auf einem **anderen** Server liegt (CORS). Normalerweise leer |
| `liveMapUrl` | `""` | BlueMap-Adresse. Leer + BlueMap installiert → automatisch Port 8100 auf demselben Rechner |
| `seedMapUrl` | `""` | BlueMap-Adresse des Seed-Vorschau-Servers (optional) |
| `blueMapIds` | `{}` | Leer = automatisch erkannt. Nur bei falscher Karte ausfüllen, z. B. `{"minecraft:overworld": "world"}` |
| `blueMapIntegration` | `true` | Länder und öffentliche Marker in BlueMap einzeichnen |
| `maxChunksPerPlayer` | `64` | Höchstzahl Chunks pro Spieler (Admins ausgenommen) |
| `claimOnlyDiscovered` | `true` | Nur entdeckte Chunks dürfen geclaimt werden |
| `discoverRadiusChunks` | `6` | Chunks rund um jeden Spieler, die als „entdeckt“ gelten (höchstens die Sichtweite) |
| `maxMarkersPerPlayer` | `100` | Höchstzahl Marker pro Spieler |
| `extraMarkerTypes` | `{}` | Eigene Marker-Arten, z. B. `{"shop": "Laden"}` (Schlüssel: Kleinbuchstaben/Ziffern/_) |
| `maxProjectsPerPlayer` | `50` | Höchstzahl Projekte, die ein Spieler anlegt |
| `maxTeamsPerPlayer` | `1` | In wie vielen Teams man gleichzeitig sein darf (1–20) |
| `teamInviteDays` | `7` | Nach wie vielen Tagen eine Team-Einladung abläuft |
| `chatTeamTags` | `true` | Team-Kürzel im Spiel vor den Namen (Chat, Tab-Liste, über dem Kopf). Auf `false` stellen und neu starten entfernt alle Kürzel wieder. |
| `tradeUnit` | `"Diamanten"` | Einheit für Preise im Handel (nur ein Wort für die Anzeige – es wird nichts abgebucht) |
| `tradeMinIncrement` | `1` | Standard-Mindestschritt bei Auktionen |
| `maxActiveListingsPerPlayer` | `20` | Aktive Angebote pro Spieler |
| `maxListingDays` | `14` | Längste Laufzeit eines Angebots |
| `tradeActionsPerMinute` | `30` | Handels-Aktionen pro Spieler und Minute |
| `extraTradeCategories` | `{}` | Eigene Kategorien, z. B. `{"potions": "Tränke"}` |
| `activityLimit` | `1000` | So viele Einträge behält die Aktivitäts-Übersicht |
| `sessionDays` | `30` | Wie lange ein Web-Login gültig bleibt |
| `showSeed` | `true` | Seed anzeigen. Auf `false`, wenn der Seed geheim sein soll! |
| `showOnlinePlayers` | `true` | Online-Status und Positionen der Spieler anzeigen |
| `webOverrideDir` | `""` | Nur für Entwickler: Webseite aus diesem Ordner statt aus der Mod laden. Leer lassen! |

---

## BlueMap einrichten (3D)

1. BlueMap in `mods/` legen, Server starten.
2. In `config/bluemap/core.conf` `accept-download: true` setzen (damit erlaubst du BlueMap, die Minecraft-Texturen zu laden – Teil von Mojangs EULA).
3. **Nur entdeckte Gebiete zeigen:** in `config/bluemap/maps/world.conf` (und den anderen Karten) z. B. `min-inhabited-time: 200`.
   Ehrlich: Das zählt für den **ganzen Server**. „Jeder sieht nur, wo er selbst war“ kann BlueMap nicht.
4. `/bluemap reload`. Die Karte ist dann unter `http://<server>:8100/` erreichbar.
5. In BlueMap gibt es die Ebenen **„Länder“** und **„Bau-Planer“** (nur **öffentliche** Marker; private Teams erscheinen dort nicht).

**So arbeiten Karte und BlueMap zusammen** (im Code von BlueMap 5.29 nachgeprüft und mit der echten Kamera getestet):
- „Live-Welt“ öffnet BlueMap **an der Stelle und mit dem Ausschnitt der Karte**. Hast du dich in BlueMap bewegt und die Karte nicht angefasst, bleibt BlueMap, wo du warst – ohne Neuladen.
- „In 3D zeigen“ fährt nah an den Punkt (150 Blöcke Abstand).
- Eine Hinweis-Leiste erklärt schwarze Flächen (noch nicht entdeckt, BlueMap rendert gerade, noch nicht gerendert).
- Ist BlueMap nicht erreichbar, steht das groß da (mit „Erneut versuchen“). Alles andere funktioniert trotzdem.

> Achtung: BlueMaps eigener Webserver ist standardmäßig **von allen Rechnern im Netzwerk** auf Port 8100 erreichbar.

## Seed-Vorschau (optional)

Zeigt die Welt **so, wie Minecraft sie aus dem Seed erzeugt** – auch Unentdecktes, aber ohne eure Bauten.
1. Zweiten, getrennten Server-Ordner mit **derselben** Minecraft-, Fabric- und BlueMap-Version anlegen.
2. In `server.properties` denselben `level-seed` und dieselben Welt-Einstellungen. **Nicht** die Welt-Dateien kopieren!
3. Gebiet mit einem Chunk-Pregenerator erzeugen (nicht zu groß), BlueMap rendert es.
4. Andere Ports (z. B. BlueMap 8101), dann `seedMapUrl` eintragen.

Ehrlich: Die Vorschau stimmt nur bei **exakt** gleichen Einstellungen, und sie verrät allen, wo z. B. Dörfer liegen.

---

## Für Freunde erreichbar machen

Standardmäßig ist die Seite **nur auf dem Server-PC** erreichbar (`bind: 127.0.0.1`). Das ist Absicht.

- **Im selben WLAN:** `bind` auf `"0.0.0.0"`, Freunde öffnen `http://<IP-des-Server-PCs>:8765/`. Ohne HTTPS könnte jemand im selben Netz mitlesen.
- **Übers Internet:** **nicht** einfach Port 8765 freigeben! Nimm einen **Reverse-Proxy mit HTTPS** (Beispiel: `deploy/nginx-example.conf`),
  `bind` bleibt `127.0.0.1`, dann `publicUrl` auf die HTTPS-Adresse setzen. Lass dir beim ersten Mal von jemandem helfen, der sich mit Servern auskennt.

## Wo die Daten liegen

Alles liegt im Weltordner unter `world/world-planner/` – Details und Backup-Anleitung in **[BACKUP.md](BACKUP.md)**.
Export/Import auf der Webseite (Einstellungen) ist für **deine eigenen** Daten; ein Server-Backup sind die Dateien selbst.

---

## Was (noch) nicht geht – ehrlich

- **Kein Schutz im Spiel.** Claims sind nur eine Anzeige (bewusste Entscheidung).
- **Keine echten Transaktionen.** Es gibt keine Economy-Mod-Anbindung und keinen Team-Kontostand: Ein Kontostand, der nicht mit echten
  Items oder einer Economy verbunden ist, würde etwas vortäuschen. Darum ist der Handel bewusst ein „Absprache-Buch“.
- **„Entdeckt“ gilt für den ganzen Server**, nicht pro Spieler, und erst ab Installation der Mod.
- **Die Karte zeigt kein Gelände** – dafür gibt es die Live-Welt (BlueMap).
- **Keine Benachrichtigung im Spiel** bei Geboten oder Team-Einladungen; das Dashboard zeigt alles beim nächsten Öffnen.
- **Teams, Handel und Projekte** gibt es nur auf der Webseite, nicht als Befehle.
- **Kürzel nach dem Entfernen der Mod:** Minecraft speichert Scoreboard-Teams selbst. Wer die Mod löscht, sollte vorher
  `chatTeamTags` auf `false` stellen und einmal starten – sonst bleiben die `wpt_…`-Teams, bis man sie mit `/team remove` löscht.
- **Nur Minecraft 26.3**, nur dedizierter Server.

## Fehlerbehebung

| Problem | Lösung |
|---|---|
| Webseite lädt nicht | Im Server-Log nach `[World Planner]` suchen. „Port schon belegt?“ → anderen `port` nehmen. |
| „Server nicht erreichbar“ | Läuft der Server? Stimmt die Adresse? Von einem anderen PC aus muss `bind` `0.0.0.0` sein. |
| „Code falsch oder abgelaufen“ | Codes gelten 5 Minuten und nur einmal. Neu `/planer login` tippen. |
| „Zu viele Handels-Aktionen“ | Die Bremse greift (`tradeActionsPerMinute`). Eine Minute warten. |
| „Speichern fehlgeschlagen – es wurde nichts geändert“ | Die Festplatte ist voll oder schreibgeschützt. Der Handel nimmt die Aktion dann zurück, statt sie halb zu speichern. |
| 3D bleibt leer / „Karte blockiert“ | `liveMapUrl` eintragen. Läuft die Seite über `https://`, muss BlueMap auch über `https://` laufen. |
| „In 3D zeigen“ öffnet die falsche Karte | In `blueMapIds` die richtige Karten-ID eintragen (Dateiname in `config/bluemap/maps/` ohne `.conf`). |

Mehr Technik (Aufbau, API, Sicherheit): [ARCHITEKTUR.md](ARCHITEKTUR.md) · Tests: [tests/README.md](tests/README.md) ·
Backup: [BACKUP.md](BACKUP.md) · Was verbessert wurde: [VERBESSERUNGEN.md](VERBESSERUNGEN.md)
