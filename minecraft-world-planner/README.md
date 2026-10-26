# Minecraft World Planner

Eine SMP-Plattform im Browser für **alle Spieler auf deinem Minecraft-Server** (Java Edition, Fabric 26.3).
Die Mod bringt ihre Webseite selbst mit – kein extra Webhosting nötig.

| Bereich | Was er kann |
|---|---|
| 🏠 **Dashboard** | „Zu erledigen“ (das Dringendste zuerst, 6 sichtbar, der Rest zum Aufklappen: Events gleich, Streitfälle, Einladungen, überboten, Übergaben, überfällige Projekte, Bewerbungen), deine Zahlen, Server-Status, **nächste Events**, **Rückblick auf die letzten 7 Tage**, was zuletzt passiert ist |
| 🗺️ **Karte** | Nur **entdeckte** Gebiete, Länder, Marker, Projekte, Spieler, Spawn · Gehe zu Koordinaten · Ebenen · Legende · Maßstab · Regionen · **Messen** (Entfernung, Richtung, Strecke durch den Nether) |
| 🧊 **Live-Welt** | BlueMap (3D) an derselben Stelle wie die Karte · optional Seed-Vorschau |
| ⚑ **Claims** | Chunks anklicken → **Vorschau** (frei? entdeckt? zusammenhängend? Limit?) → Land erstellen · Mitbesitzer · Notizen · Team-Zuordnung |
| ◆ **Marker** | Orte mit Typ, Symbol, Farbe, optionaler Höhe · Sichtbarkeit: nur ich / Team / Land-Besitzer / alle · Nether-Umrechnung · TP-Befehl |
| ⚔ **Teams** | Rollen Owner / Admin / Moderator / Mitglied / Anwärter · Einladung oder Bewerbung (**nie ohne Zustimmung**) · bestätigte Übergabe · Auflösen ohne Datenverlust · **Kürzel im Spiel**: `[KMS] Alice` im Chat, in der Tab-Liste und über dem Kopf |
| ⚖ **Handel** | Auktion, Sofortkauf, Festpreis, Tausch und **Hilfe** (Bauen, Kämpfen …) · Gebote, Gegenangebote, Übergabe-Bestätigung, Streitfall |
| ⚒ **Projekte** | Bauprojekte mit Aufgaben, Fortschritt, Verantwortlichen, Updates, Land/Team/Ort |
| ◷ **Events** | Bauwettbewerb, Treffen, Party … mit **Kalender** (Liste und Monat), Treffpunkt (Land oder Koordinaten), Zusagen „Komme / Vielleicht / Komme nicht“ · Einladung im Spiel-Chat mit [Komme], **Erinnerung 10 Minuten vorher** und zum Start · Absagen und Verschieben mit Nachricht · auch nur fürs Team · **📅 in den Handy-Kalender** (.ics, mit Erinnerung) · **🔁 nochmal nächste Woche** · „Event hier planen“ bei Ländern, Markern und Projekten |
| ☺ **Spieler** | Wer ist online, wer war zuletzt da, Teams, Länder |
| ★ **Statistik** | Wie bei CraftAttack: der Server in Zahlen, „Wann ist am meisten los?“, Rekorde, Verlauf mit Toden und Meilensteinen, eine Seite pro Spieler |
| ♛ **Ranglisten** | **16 Ranglisten** wie bei CraftAttack (Dauerzocker, Friedhof, Diamantenbank, Abrissbirne …) gesamt / 7 Tage / gestern / heute – **alle Spieler, für alle sichtbar**, nur auf der Webseite |
| ⚙ **Einstellungen** | Hell/Dunkel, Schalter fürs Spiel, **angemeldete Geräte**, Favoriten, **Export/Import** mit Vorschau, Server-Infos |
| 🎮 **Im Spiel** | Kurz nach dem Einloggen „Zu erledigen“ im Chat, danach Neues sofort (Einladung, überboten, verkauft …) · Land-Name über der Hotbar beim Betreten · **Wegweiser** zu Markern, Ländern und zum Todespunkt · **Land-Grenzen** als Partikel · **Todespunkt** als privater Marker · **Team-Chat** `/tc` · **Team-Einladungen annehmen** per Klick (`/planer team annehmen`) · Nachricht bei neuem Web-Login · alles einzeln abschaltbar |

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
Fertig ist die Datei `fabric-mod/build/libs/world-planner-0.5.0.jar` (die `...-sources.jar` daneben brauchst du nicht).

## 2. Auf dem Server installieren (oder von 0.2/0.3/0.4 updaten)

1. **Backup** machen (siehe [BACKUP.md](BACKUP.md)), dann Server stoppen.
2. Alte `world-planner-*.jar` aus `mods/` entfernen, `world-planner-0.5.0.jar` und die passende **Fabric API** hineinlegen. Für 3D auch **BlueMap**.
3. Server starten. Im Log sollte stehen:
   ```
   [World Planner] Webseite und API laufen auf http://127.0.0.1:8765/
   ```
   Beim Update von 0.2 zusätzlich: `lands.json wird auf Version 2 umgestellt. Sicherung der alten Datei: lands.json.v1.bak`
   (genauso für `markers.json`). Alle Länder, Besitzer und Marker bleiben erhalten – getestet mit echten 0.2-Daten.
4. Die Mod ergänzt `config/world-planner.json` um die neuen Einstellungen (siehe [Einstellungen](#einstellungen)).
   Beim Update von 0.3 oder 0.4 ändert sich an den Daten nichts – getestet: Länder, Marker, Teams, Projekte, Angebote,
   Favoriten, Spiel-Schalter, Aktivität und Web-Logins waren nach dem Update von 0.4 auf 0.5 genau gleich.
   Neu kommen `stats.json` und `races.json` dazu. Beim ersten Start übernimmt 0.5 Minecrafts eigene Statistik-Dateien
   (`world/players/stats/`) der Spieler, die der Planer schon kennt – Log: `Statistik von 4 Spieler(n) … übernommen`.
   **Update auf 0.6:** Rennen gibt es nicht mehr. `races.json` bleibt unangetastet liegen (0.6 liest sie nicht) – du kannst
   sie löschen oder aufheben. Neu ist `events.json`. In der Config verschwinden `races` und `maxTracksPerPlayer`, dazu kommen
   `events` und `maxEventsPerPlayer`. Alles andere bleibt gleich (getestet mit einem Update von 0.5.1 auf 0.6.0).
   **Von 0.6.0 auf 0.6.1 oder 0.6.2:** nur die Jar tauschen – an Dateien und Config ändert sich nichts.

## 3. Webseite öffnen und einloggen

- Auf dem **Server-PC selbst**: http://127.0.0.1:8765/
- Damit **andere** die Seite erreichen: [Für Freunde erreichbar machen](#für-freunde-erreichbar-machen).

Einloggen: Im Spiel `/planer login` tippen → Code wie `K7QM-2XPA` (5 Minuten gültig, nur einmal) → auf der Webseite eingeben.
Ist `publicUrl` eingetragen, gibt es im Chat einen Link, der direkt einloggt. Wer **OP** ist (Level 2+), ist auf der Webseite Admin.

---

## Befehle im Spiel

| Befehl | Was er macht |
|---|---|
| `/planer` | Was gerade **zu erledigen** ist – dieselbe Liste wie auf dem Dashboard |
| `/planer hilfe` | Alle Befehle (anklicken fügt sie in den Chat ein) |
| `/planer login` | Login-Code für die Webseite |
| `/planer abmelden` | Meldet dich auf **allen** Geräten von der Webseite ab |
| `/planer einstellungen` | Deine Schalter fürs Spiel mit Klick zum An-/Ausschalten |
| `/planer nachrichten an\|aus` · `/planer landanzeige an\|aus` · `/planer todespunkt an\|aus` | Einzelne Schalter (gelten auch für die Webseite) |
| `/planer todespunkt` | Wo du zuletzt gestorben bist – mit Entfernung und Richtung von hier |
| `/planer marker <Name>` | Privater Marker an deiner Position (teilen und bearbeiten auf der Webseite) |
| `/planer weg <Ziel>` | **Wegweiser**: Pfeil über der Hotbar zu einem Marker, Land oder `todespunkt` – gedreht nach deiner Blickrichtung, mit Entfernung (kein Teleport). `/planer weg aus` beendet ihn |
| `/planer statistik [Spieler]` | Spielzeit, Tode, Blöcke … (die Ranglisten gibt es nur auf der Webseite) |
| `/planer events` | Die nächsten 5 Events mit Zeit, wer kommt, deiner Antwort und Knöpfen [Komme] [Vielleicht] [Nein] (Knopf anklicken, dann Enter) |
| `/planer team annehmen <Kürzel>` / `ablehnen <Kürzel>` | Eine Team-Einladung annehmen oder ablehnen – oder ein Team übernehmen, das dir übergeben werden soll. Nur, was dir selbst angeboten wurde; die Nachricht im Chat hat dafür einen Knopf [Annehmen] bzw. [Übernehmen]. (Nicht `/team`: das ist Minecrafts eigener OP-Befehl.) |
| `/tc <Nachricht>` (lang: `/teamchat`) | **Team-Chat** – nur Mitglieder deines Teams lesen mit, auch bei privaten Teams |
| `/land` oder `/land info` | Wem gehört der Chunk, in dem ich stehe? |
| `/land neu <Name>` | Neues Land aus dem Chunk, in dem du stehst |
| `/land erweitern [Land]` | Chunk zu deinem Land hinzufügen (muss angrenzen) |
| `/land abgeben` | Chunk aus deinem Land entfernen |
| `/land liste` | Deine Länder, Chunk-Verbrauch und Einladungen |
| `/land grenzen [Land]` | Rand des Landes 20 Sekunden lang als Partikel (nur du siehst sie) – auch kurz nach `/land neu` und `/land erweitern` |
| `/land einladen <Land> <Spieler>` | Mitbesitzer einladen (geht auch, wenn der Spieler offline ist) |
| `/land annehmen <Land>` / `ablehnen <Land>` | Einladung annehmen / ablehnen |
| `/land verlassen <Land>` | Austreten. Als letzter Besitzer wird das Land frei. |
| `/land admin loeschen <Land>` · `/land admin entfernen <Land> <Spieler>` | **Nur OPs** |

Namen mit Leerzeichen in Anführungszeichen: `/land neu "Kims Base"`. Mit **Tab** werden Namen vervollständigt.
Teams gründen, Handel und Projekte gehen nur auf der Webseite – im Spiel bekommst du dazu aber Nachrichten (siehe Regeln).

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

**Im Spiel** (alles serverseitig – im Spiel braucht niemand eine Mod)
- **Nachrichten** (`gameNotifications`): Etwa 3 Sekunden nach dem Einloggen kommt „Zu erledigen“ – dieselbe Liste wie auf dem
  Dashboard, in derselben Reihenfolge: das Dringendste zuerst (ein Event, das gleich beginnt, Streitfälle, Einladungen, dann
  Warnungen wie Übergaben, zuletzt Infos). Höchstens 6 Zeilen, `/planer` zeigt bis zu 12. Danach kommt **live**, was neu ist: Einladungen, Bewerbungen,
  „überboten“, verkauft/gekauft/gewonnen, Gegenangebote, neue Gebote auf dein Angebot, Streitfälle. Viele Gebote hintereinander
  fasst der Planer zusammen (höchstens eine Nachricht alle 15 Sekunden pro Angebot – „überboten“ kommt aber immer sofort).
  Es gibt **keine Nachholung**: Wer offline war, sieht beim Einloggen, was jetzt noch offen ist – keine Liste alter Ereignisse.
  Nachrichten sehen nur, was du auch auf der Webseite sehen darfst (z. B. erfährt ein Bieter nicht, wer ihn überboten hat).
- **Land-Anzeige** (`landEnterMessages`): Über der Hotbar steht „▶ Kims Base [KMS] · von Kim“, wenn du ein Land betrittst,
  und „Wildnis“, wenn du es verlässt. Das ist **nur eine Anzeige** – sie schützt nichts.
- **Todespunkt** (`deathMarkers`): Stirbst du, speichert der Planer die Stelle als **privaten** Marker „Todespunkt“ ☠
  (nur du siehst ihn, auch nicht in BlueMap). Jeder hat genau einen; beim nächsten Tod wird er verschoben. Was du auf der Webseite
  daran änderst (Name, Sichtbarkeit), bleibt. Nach dem Wiederbeleben steht im Chat, wie weit und in welcher Richtung er liegt.
  Er zählt zu deinen Markern (`maxMarkersPerPlayer`); ist das Limit voll, sagt der Planer das ehrlich. **Die Items schützt das nicht** –
  sie verschwinden wie immer nach 5 Minuten. Beim Import (Einstellungen) wird ein Todespunkt nicht übernommen – er gehört zu der
  Welt, in der du gestorben bist.
- **Team-Chat** (`teamChat`): `/tc Hallo` geht an alle Mitglieder deines Teams, die online sind – auch bei **privaten** Teams.
  Bist du in mehreren Teams, zählt das mit der höchsten Rolle. Wie normaler Chat steht der Team-Chat im **Server-Log**
  (Admins können ihn also lesen). Es sind Server-Nachrichten wie bei Plugins üblich – Minecrafts „Chat melden“ gilt dafür nicht.
  Öffentliche Teams mit Kürzel sind zugleich Minecraft-Scoreboard-Teams – dort sollte auch Minecrafts eigenes `/teammsg`
  funktionieren. Das ist Minecraft-Standard; mit Fake-Spielern ließ es sich hier nicht prüfen.
- Jeder Spieler kann Nachrichten, Land-Anzeige und Todespunkt **für sich** abschalten: im Spiel mit `/planer einstellungen`
  oder auf der Webseite unter Einstellungen → „Im Spiel“.
- **Wegweiser** (`/planer weg`, auf der Webseite „🧭 Im Spiel hinführen“ bei Markern, Ländern und Events mit Treffpunkt): ein Pfeil über
  der Hotbar, gedreht nach deiner Blickrichtung, mit Entfernung und Höhe. Liegt das Ziel in der Oberwelt und du bist im Nether
  (oder umgekehrt), führt der Pfeil zur passenden Stelle für ein Portal (Nether = Oberwelt ÷ 8). Am Ziel endet er von selbst.
  Er teleportiert nicht und zeigt nur, was du auch auf der Webseite sehen darfst (private Marker anderer nicht).
- **Land-Grenzen** (`/land grenzen`): Partikel in der Landfarbe entlang des Randes, 20 Sekunden lang, nur für dich und nur
  im Umkreis von 40 Blöcken. Auch das ist nur eine Anzeige.
- **Neuer Web-Login:** Meldet sich jemand mit deinem Konto auf der Webseite an, kommt im Spiel sofort
  „⚠ Neuer Web-Login auf „Firefox · Windows““ mit Knopf zum Abmelden – auch wenn du Nachrichten ausgeschaltet hast.
  Unter Einstellungen → „Angemeldete Geräte“ siehst du jedes Gerät (nur eine grobe Beschreibung, keine IP) und kannst
  einzelne oder alle anderen abmelden.

**Statistik** (wie bei CraftAttack – `statistics`, Standard an)
- Die Zahlen kommen aus **Minecrafts eigener Statistik** (dieselben wie unter Esc → Statistiken): Spielzeit, Tode, abgebaute und
  platzierte Blöcke, Diamanten, Mobs, Strecke, Schaden, Gegessenes, Fische, gezüchtete Tiere, Handel, Hergestelltes, Sprünge.
  Sie reichen zurück bis zum ersten Login – auch vor der Mod. Die Mod liest sie jede Minute für Online-Spieler und beim Ausloggen.
- Selbst gemessen (erst **ab der Installation von 0.5**): Aktiv/AFK, Sitzungen, „Wann online“, Todesursachen, Verlauf,
  Zuwachs pro Tag. **AFK** = `afkMinutes` (Standard 5) Minuten ohne Bewegung und ohne Umschauen; Bewegung zählt immer –
  auch im Boot oder vom Wasser geschoben. Aktiv und AFK werden in Spiel-Ticks gezählt wie Minecrafts Spielzeit – so ist
  „Aktiv“ nie größer als die Spielzeit, auch wenn der Server ruckelt (ob jemand AFK ist, entscheidet die echte Uhr).
  „Heute“ und „Gestern“ richten sich nach `timeZone` (Standard Europe/Berlin).
- **Nach einem Absturz** kann Minecrafts eigene Zahl ein paar Minuten zurückspringen (Minecraft speichert seine Statistik nur
  alle paar Minuten). „Gesamt“ zeigt dann Minecrafts Zahl, Verlauf und Zuwachs bleiben, und auf der Spielerseite steht
  „Minecrafts Statistik ist zurückgesprungen …“. Dasselbe passiert, wenn ein Admin eine Statistik-Datei löscht.
- **Ranglisten** mit den Namen von CraftAttack: Dauerzocker, Fleißig, Friedhof, Diamantenbank, Abrissbirne, Baumeister,
  Monsterjäger, Weltenbummler, Unsterblich, Kampfmaschine, Vielfraß, Angelprofi, Tierfreund, Händler, Handwerker, Flummi –
  jeweils gesamt, letzte 7 Tage, gestern und heute (Zuwachs zählt ab der Installation). Gleiche Werte = gleicher Platz.
- **Für alle sichtbar:** Wie bei CraftAttack stehen alle Spieler in den Ranglisten, und jeder eingeloggte Spieler sieht jede
  Spielerseite (Spielzeit, AFK, Tode, Sitzungen …). Ausblenden gibt es nicht
  (nur 0.5.0 hatte das; ein dort gespeichertes „aus“ wird ab 0.5.1 überlesen). Sag das deinen Mitspielern, bevor du updatest.
  Die Ranglisten stehen nur auf der Webseite (Reiter **Ranglisten**), im Spiel gibt es dafür keinen Befehl mehr.
- Platziert = benutzte Block-Items, Gegessen = benutzte Essen-Items plus Kuchenstücke. Karotten, Kartoffeln und Beeren zählen
  bei keinem von beiden – Minecraft zählt dort Pflanzen und Essen in **einer** Zahl, die Mod kann sie nicht trennen.

**Events** (`events`, Standard an)
- Auf der Webseite unter **Events**: anlegen mit Titel, Art (Bauwettbewerb, Treffen, Party, Markt, Gemeinsam bauen, Sonstiges),
  Tag, Uhrzeit, Dauer (15 Minuten bis 24 Stunden), optional Treffpunkt (ein Land oder Koordinaten) und Beschreibung.
  „Nur Team“ geht für Mitglieder eines Teams – dann sieht es nur das Team. Ansicht als **Liste** (nach Tagen) oder **Kalender**.
- **Alle Zeiten gelten in der Zeitzone des Servers** (`timeZone`), auf der Webseite genauso wie im Chat – damit alle dieselbe
  Uhrzeit sehen. Wer woanders wohnt, muss umrechnen (die Webseite schreibt die Zeitzone dazu).
- **Zusagen:** „Komme“, „Vielleicht“ oder „Komme nicht“ – auf der Webseite oder im Spiel (`/planer events`). Jeder nur für sich.
  Wer einlädt, kommt automatisch (kann es ändern).
- **Im Spiel** (wie alle Nachrichten mit `/planer nachrichten aus` abschaltbar): Ein neues Event kommt als Nachricht mit [Komme]
  und steht bei „Zu erledigen“, bis du antwortest (ab 7 Tagen vorher). Wer „Komme“ oder „Vielleicht“ gesagt hat, bekommt
  **10 Minuten vorher** eine Erinnerung (bei einem Land als Treffpunkt mit [Hinführen] zum Wegweiser) und **zum Start** eine
  Nachricht. Absagen und Verschieben bekommen nur die mit „Komme“ oder „Vielleicht“ mit.
- Ändern, Absagen und Löschen darf nur, wer eingeladen hat (oder ein Admin). Absagen ist meist besser als Löschen: Das Event
  bleibt als „abgesagt“ sichtbar. Höchstens `maxEventsPerPlayer` anstehende Events pro Spieler.
- **📅 In meinen Kalender:** lädt das Event als `.ics`-Datei herunter (öffnen → Kalender-App übernimmt es) – mit Erinnerung
  10 Minuten vorher, **auch wenn du nicht im Spiel bist**. Die Datei entsteht im Browser; nichts geht an Google, Apple oder sonst
  wohin. Wird das Event danach verschoben, ändert sich dein Kalender **nicht** von selbst – lade die Datei neu (manche
  Kalender-Apps legen es dann doppelt an; das alte musst du dann selbst löschen).
- **🔁 Nochmal planen** (bei eigenen Events): öffnet das Formular mit demselben Event zur selben Uhrzeit eine Woche später (auch
  über die Zeitumstellung richtig). Antworten werden nicht übernommen – alle werden neu eingeladen.
- **📅 Event hier planen** auf der Seite eines Landes, bei einem Marker und **📅 Bauabend planen** bei einem Projekt: Das
  Formular ist dann schon mit Treffpunkt (und bei Projekten mit „Gemeinsam bauen“) ausgefüllt.
- Die Liste zeigt alles, was noch kommt, und vergangene Events der letzten 60 Tage. Ältere bleiben gespeichert und sind über
  ihren Link erreichbar.
- Wird ein Team aufgelöst, werden seine anstehenden Team-Events abgesagt – und für die früheren Mitglieder verschwinden sie
  (das Team gibt es ja nicht mehr; nur wer eingeladen hat, sieht sie noch). Wird ein Land gelöscht, bleibt der Treffpunkt als
  Koordinate stehen.

**Rückblick auf dem Dashboard** – die letzten 7 Tage, nur aus echten Daten: Spielzeit und aktive Spieler (derselbe Zuwachs wie die
Rangliste „7 Tage“), höchstens gleichzeitig online, die Spitze von sechs Ranglisten, Meilensteine, neue Länder, abgeschlossene
Projekte und erledigte Aufgaben, vergangene Events (mit „N hatten zugesagt“ – wer wirklich kam, weiß der Planer nicht) und wie
viele in den nächsten 7 Tagen anstehen. Projekte, die schon vor 0.6 abgeschlossen waren, zählen nicht (das Datum fehlt) – ebenso importierte fertige Projekte und Aufgaben.

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
| `gameNotifications` | `true` | Nachrichten im Spiel: „Zu erledigen“ beim Einloggen und Neues live (jeder kann sie für sich abschalten) |
| `landEnterMessages` | `true` | Land-Name über der Hotbar beim Betreten eines Landes |
| `deathMarkers` | `true` | Privater Marker „Todespunkt“ beim Sterben |
| `teamChat` | `true` | Team-Chat mit `/tc` für alle Planer-Teams |
| `tradeUnit` | `"Diamanten"` | Einheit für Preise im Handel (nur ein Wort für die Anzeige – es wird nichts abgebucht) |
| `tradeMinIncrement` | `1` | Standard-Mindestschritt bei Auktionen |
| `maxActiveListingsPerPlayer` | `20` | Aktive Angebote pro Spieler |
| `maxListingDays` | `14` | Längste Laufzeit eines Angebots |
| `tradeActionsPerMinute` | `30` | Handels-Aktionen pro Spieler und Minute |
| `extraTradeCategories` | `{}` | Eigene Kategorien, z. B. `{"potions": "Tränke"}` |
| `activityLimit` | `1000` | So viele Einträge behält die Aktivitäts-Übersicht |
| `statistics` | `true` | Statistik und Ranglisten (für alle sichtbar; `false` = für alle aus) |
| `timeZone` | `"Europe/Berlin"` | Zeitzone für „Heute“, „Gestern“ und „Wann ist am meisten los“ |
| `afkMinutes` | `5` | So viele Minuten ohne Bewegung und ohne Umschauen = AFK (1–60) |
| `events` | `true` | Events mit Kalender, Erinnerungen im Spiel und `/planer events` |
| `maxEventsPerPlayer` | `5` | So viele anstehende Events darf ein Spieler gleichzeitig angelegt haben (Admins ausgenommen) |
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
- **Nachrichten im Spiel nur, solange du online bist.** Es gibt keine Nachholung alter Ereignisse – beim Einloggen kommt nur,
  was noch zu erledigen ist. Auf der Webseite steht unter „Was ist passiert?“ der Verlauf.
- **Teams, Handel und Projekte** bedienst du auf der Webseite. Im Spiel gibt es dazu Nachrichten, aber keine Befehle zum Bieten,
  Kaufen oder Annehmen von Team-Einladungen.
- **Getestet mit Fake-Spielern, nicht mit einem echten Minecraft-Fenster:** Dass Nachrichten beim richtigen Spieler ankommen, ist
  auf einem echten Server geprüft. Wie Farben, Klick-Links, Partikel und die Anzeige über der Hotbar in deinem Spiel **aussehen**,
  konnte ich ohne echten Minecraft-Client nicht ansehen. Auch der Hinweis **nach dem Wiederbeleben** ist nur per Unit-Test geprüft
  (Fake-Spieler werden beim Tod entfernt statt wiederbelebt).
- **Events-Erinnerungen im Spiel** kommen nur, wenn du gerade online bist (keine Nachrichten auf Vorrat). Loggst du dich in den
  10 Minuten vor dem Start ein, steht die Erinnerung bei „Zu erledigen“. Für eine Erinnerung ohne Spiel: „📅 In meinen Kalender“
  (ob deine Kalender-App die Datei richtig übernimmt, ließ sich hier nicht prüfen – geprüft ist die Datei selbst). Ob jemand wirklich dabei war, weiß der Planer nicht –
  im Rückblick steht deshalb „hatten zugesagt“. Events gehen nicht in den persönlichen Export (sie sind an Zeit und Server gebunden).
- **Statistik:** Minecrafts Zahlen gehen bis zum ersten Login zurück, alles Selbstgemessene (AFK, Wann online, Zuwachs, Verlauf)
  erst ab 0.5. Spieler, die seit der Installation des Planers nie online waren, erscheinen erst nach ihrem nächsten Login.
  Blöcke und Mobs haben deutsche Namen, soweit sie in der Mod stehen; seltene erscheinen mit ihrem englischen Namen aus der ID
  (der Server kennt nur Minecrafts englische Texte).
- **Logout bei Fake-Spielern:** Fabrics Abmelde-Ereignis kommt bei Carpet-Fake-Spielern nicht. Die Mod merkt deshalb jede Sekunde
  selbst, wer fehlt – so ist auch das geprüft. Bei echten Spielern kommt das Ereignis normalerweise sofort.
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
