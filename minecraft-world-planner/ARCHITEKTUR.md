# Aufbau, Sicherheit und API

## Überblick

```
Minecraft-Server (Fabric 26.3)
 └─ World Planner Mod
     ├─ Befehle /planer, /land, /tc             PlannerCommands
     ├─ Im Spiel                                GameNotifier (Nachrichten), LandEnterTracker (Actionbar),
     │                                          DeathPoints (Todespunkt), GameText (Texte), Notices ("Zu erledigen")
     ├─ Dienste (alles an einem Ort)            PlannerServices.create(...)
     │    PlayerDirectory, SessionStore, DiscoveryTracker,
     │    LandStore, MarkerStore, TeamStore, ProjectStore, TradeStore,
     │    PrefsStore (Favoriten, Schalter fürs Spiel), ActivityLog, Access (Rechte-Fragen über Bereiche hinweg)
     ├─ Webserver :8765                         ApiServer
     │    ├─ /            → Webseite (web/ aus der Mod-Jar)
     │    └─ /api/...     → JSON-API, verteilt auf LandRoutes, MarkerRoutes, TeamRoutes,
     │                      ProjectRoutes, TradeRoutes, AccountRoutes (+ ImportExport)
     └─ BlueMap-Anbindung (optional)            BlueMapBridge + LandOutline
```

**Threads:** Minecraft-Objekte dürfen nur auf dem *Server-Thread* benutzt werden. Der Webserver (8 Threads) benutzt nur
die Datenspeicher (alle Methoden `synchronized`) und Momentaufnahmen (`AtomicReference<JsonObject>`), die `onTick()` alle
2 Sekunden erstellt (Spieler, Welt, Spawn).

**Reihenfolge der Sperren (gegen Verklemmungen):** Handel/Projekte/Marker → Länder → Teams. Teams fragen niemanden,
Länder fragen nur Teams. `PlannerServices.onTeamDissolved()` ruft die Bereiche nacheinander auf, nie verschachtelt.

**Speichern:**
- Länder, Marker, Teams, Projekte, Favoriten, Aktivität: Änderung setzt „dirty“, ein Timer speichert alle 5 Sekunden im
  Hintergrund (entdeckte Chunks jede Minute), beim Stoppen noch einmal direkt.
- **Handel:** jede Aktion wird **sofort** geschrieben, **bevor** der Server „OK“ sagt. Klappt das nicht (volle Platte),
  wird die Änderung im Speicher zurückgenommen und die Webseite bekommt 503 „es wurde nichts geändert“.
- Jede Datei wird als `.tmp` geschrieben, mit `fsync` auf die Platte gezwungen und dann atomar umbenannt.
  Eine kaputte Datei wird nicht überschrieben, sondern in `…kaputt-<Zeit>` umbenannt.
- Vor einer Umstellung auf ein neues Datenformat wird die alte Datei als `…v<alt>.bak` gesichert (siehe BACKUP.md).

**Timer statt Ticks:** Ein leerer Server pausiert nach 60 Sekunden – dann gibt es keine Ticks mehr. Speichern, BlueMap und
der **Ablauf von Auktionen** laufen deshalb über einen eigenen Timer-Thread (`world-planner-timer`, jede Sekunde für den
Handel). Das BlueMap-Update wird per `server.execute()` auf den Server-Thread gereicht; das arbeitet der Server auch in der
Pause ab.

**Team-Kürzel im Spiel:** `ChatTagSync` gleicht die Planer-Teams mit Minecrafts Scoreboard-Teams ab (Präfix = Kürzel).
Der Timer prüft jede Sekunde, ob sich Teams oder Spielernamen geändert haben (`version()`), und gibt den Abgleich dann per
`server.execute()` auf den Server-Thread. Angefasst werden nur Teams, deren Name mit `wpt_` beginnt.

**Nachrichten im Spiel:** `Notices.collect()` berechnet für einen Spieler die Hinweise **aus dem echten Zustand** der Speicher –
„Zu erledigen“ (dieselben Regeln wie `todoItems()` im Dashboard, ein Browser-Test vergleicht beide Listen wörtlich) und
Neuigkeiten (neues Gebot, Gegenangebot, Streitfall, Mitgliedschaft …). Jeder Hinweis hat eine id, die sich nur ändert, wenn
etwas Neues passiert (z. B. `outbid:<Angebot>:<Gebot>`). `GameNotifier.poll()` läuft alle 2 Sekunden im **Timer-Thread**,
nicht auf dem Server-Thread: Der Handel schreibt unter seiner Sperre auf die Platte, und darauf soll das Spiel nie warten.
Neue ids = neue Nachricht; verschickt wird per `server.execute()`. Beim Einloggen (3 s Pause) kommt die Zusammenfassung,
Neuigkeiten von vorher werden still übernommen. Neuigkeiten derselben Gruppe (Gebote auf ein Angebot) höchstens alle 15 s,
danach nur die neueste; mehr als 3 neue auf einmal → „… und N weitere“. Bei `/planer nachrichten aus` wird weiter mitgezählt,
aber nichts geschickt – so gibt es beim Wiedereinschalten keine Nachholung. Nachrichten an einzelne Spieler schreibt Minecraft
nirgends hin; die Mod loggt sie deshalb auf DEBUG-Stufe (normal aus, für Tests: `tests/integration/log4j2-debug.xml`).

**Land-Anzeige:** `LandEnterTracker` läuft auf dem Server-Thread (zweimal pro Sekunde, braucht die Positionen), fragt
`LandStore.landAt()` aber nur, wenn jemand den Chunk gewechselt hat (Nachschlagen in einer Hash-Tabelle). Team-Kürzel nur
bei öffentlichen Teams. **Todespunkt:** Fabric-Ereignis `ServerLivingEntityEvents.AFTER_DEATH` → `DeathPoints.save()`
legt einen privaten Marker an oder verschiebt den gemerkten (`PrefsStore.Game.deathMarkerId`) – dabei werden nur Dimension
und Koordinaten geändert. `ServerPlayerEvents.AFTER_RESPAWN` (nur nach einem Tod) zeigt Entfernung und Richtung.
**Team-Chat:** `/tc` geht an die Online-Mitglieder von `TeamStore.chatTeamOf()` (höchste Rolle, auch private Teams) und per
`server.sendSystemMessage()` ins Server-Log, wie normaler Chat.

**Keine halben Änderungen:** Bearbeiten prüft erst alle Angaben und übernimmt sie dann (Länder, Teams, Projekte, Handel).
Ein Fehler in einem Feld ändert also auch die anderen Felder nicht. Dafür gibt es eigene Tests.

## Sicherheit

| Thema | Umsetzung |
|---|---|
| Login | Einmal-Code aus dem Spiel (8 Zeichen, 5 Minuten, einmal). Gespeichert wird nur ein Hash des Sitzungs-Tokens. |
| Login-Bremse | 30 Fehlversuche pro Minute **pro Absender** + 300 insgesamt; `X-Forwarded-For` zählt nur von 127.0.0.1 (Reverse-Proxy). |
| Rechte | Jede Regel wird auf dem Server geprüft. Die Webseite bekommt pro Objekt `can: {...}` und zeigt nur erlaubte Knöpfe. |
| Privatsphäre | Private Marker/Projekte und private Teams gibt es für Fremde nicht (**404** statt 403 – die API verrät nicht, dass es sie gibt). Land-Notizen sehen nur Besitzer. Wer geboten/gekauft hat, sieht nur die Verkäufer-Seite. |
| Eingaben | Gemeinsame Prüfregeln (`Validate`): Namen ohne `< > & " '`, Steuer- und unsichtbare Zeichen werden entfernt, Längen, Farben `#rrggbb`, Datum, Koordinaten ±30 Mio. Falsche JSON-Datentypen → 400. Anfragen max. 256 KB (Import 4 MB). |
| Ausgabe | Die Webseite setzt Spielertexte nur mit `textContent` ein; in BlueMap wird HTML escaped. |
| Content-Security-Policy | Nur eigene Skripte und Styles; `connect-src`/`frame-src` nur für die BlueMap-Adressen. Keine Inline-Skripte. |
| Dateien | Die Webseite wird nur aus einer festen Liste von Pfaden ausgeliefert (`[a-z0-9_-]+(/…)*.(html|js|css|svg)`). Keine Dateioperationen mit Benutzereingaben (Export ist ein Download aus dem Speicher, Import liest nur JSON aus dem Request). |
| Handel | Sperre pro Aktion (`synchronized`), `requestId` gegen doppelte Ausführung (auch nach Neustart), Bremse pro Spieler, Ablauf auf dem Server. |
| Secrets | Es gibt keine. Die Config enthält keine Passwörter; nichts wird an fremde Dienste geschickt. |

## Datenmodell

```
Spieler (UUID, Name, OP?) ──┬── besitzt (gleichberechtigt) ──► Land ◄── teamId (optional) ── Team
                            │                                   ▲                               │
                            ├── ist Mitglied (Rolle) ───────────┼───────────────────────────────┘
                            ├── legt an ──► Marker ── landId? / teamId? (für Sichtbarkeit „Land“/„Team“)
                            ├── legt an ──► Projekt ── landId? teamId? location? ── Aufgaben, Updates, Verantwortliche
                            ├── verkauft ──► Angebot (Listing) ── teamId? ── Gebote, Gegenangebote, Übergabe, Verlauf
                            ├── Favoriten (lands, markers, projects, teams, listings)
                            └── Schalter fürs Spiel (Nachrichten, Land-Anzeige, Todespunkt) + id des Todespunkt-Markers
Aktivität: Ereignis (Zeit, Text, Bezug, Publikum "all" | "team:<id>")
```

| Objekt | Wichtige Felder | Gelöscht/aufgelöst, was passiert mit Verknüpfungen? |
|---|---|---|
| **Land** | Name (eindeutig), Dimension, Chunks, Farbe, Beschreibung (öffentlich), Notizen (nur Besitzer), Besitzer, Einladungen, Team | Projekte und Marker bleiben; Marker „nur Land-Besitzer“ werden privat |
| **Marker** | Name, Typ, Dimension, X, Y (optional), Z, Notizen, Sichtbarkeit (privat/Team/Land/alle), Farbe, Symbol. Typ `death` = automatischer Todespunkt (einer pro Spieler) | Todespunkt gelöscht → beim nächsten Tod entsteht ein neuer |
| **Team** | Name, Kürzel (beide eindeutig), Beschreibung, Farbe, Symbol, öffentlich/privat, Beitritt (Einladung/Bewerbung), Mitglieder mit Rolle, Einladungen (mit Ablauf), Bewerbungen, offene Übergabe | Länder bleiben (ohne Team); Projekte und Marker bleiben – „nur Team“ wird zu privat (Ersteller und Beteiligte); Angebote laufen im Namen des Erstellers weiter |
| **Projekt** | Name, Beschreibung, Status, Priorität, Start/Ziel, Sichtbarkeit (alle/Team/Beteiligte), Land, Team, Ort, Verantwortliche, Aufgaben (erledigt von/wann), Updates, Notizen | – |
| **Angebot** | Art (Auktion/Festpreis/Tausch), Biete/Suche, Titel, Kategorie, Item, Menge, Preise, Laufzeit, Team, Status (aktiv/verkauft/nicht verkauft/abgebrochen), Gebote, Gegenangebote, Übergabe (offen/bestätigt/Streit/geklärt), Verlauf | Angebote werden nie automatisch gelöscht |

Alle Dateien haben eine `schemaVersion`. Länder und Marker sind auf Version 2 (0.3), alle neuen Bereiche auf Version 1.

## Dateien im Code

| Datei | Aufgabe |
|---|---|
| `WorldPlannerMod.java` | Einstieg: Start, Timer, Ticks, Speichern, Stoppen |
| `PlannerServices.java` | Baut und verbindet alle Speicher; Aufräumen bei gelöschten Ländern/aufgelösten Teams |
| `PlannerConfig.java` | `config/world-planner.json` mit sicheren Grenzen |
| `PlannerCommands.java` | Befehle im Spiel |
| `ApiServer.java`, `ApiRequest.java`, `Routes.java`, `*Routes.java` | Webserver, Anfrage-Helfer, API-Bereiche |
| `LandStore.java` | Länder: zusammenhängend, Limits, Besitzer, Einladungen, Vorschau, Team-Zuordnung |
| `MarkerStore.java` | Marker mit Sichtbarkeit |
| `TeamStore.java` | Teams, Rollen, Einladungen, Bewerbungen, Übergabe, Auflösen |
| `ProjectStore.java` | Bauprojekte mit Aufgaben und Updates |
| `TradeStore.java` | Web-Auktionshaus (Auktion, Festpreis, Tausch), Übergabe, Streitfall |
| `PrefsStore.java`, `ActivityLog.java`, `ImportExport.java` | Favoriten, Aktivität, Export/Import |
| `Access.java`, `Validate.java`, `RateLimiter.java` | Rechte über Bereiche hinweg, Eingabeprüfung, Bremse |
| `DiscoveryTracker.java`, `SessionStore.java`, `PlayerDirectory.java` | Entdeckte Chunks, Logins, bekannte Spieler |
| `BlueMapBridge.java`, `LandOutline.java` | BlueMap-Ebenen (nur geladen, wenn BlueMap da ist), Umrisse aus Chunks |
| `ChatTagSync.java` | Spiegelt öffentliche Teams als Scoreboard-Teams `wpt_…` → `[KÜRZEL]` vor dem Namen im Spiel |
| `Notices.java` | „Zu erledigen“ und Neuigkeiten eines Spielers, aus dem echten Zustand (auch für `/api/todo`) |
| `GameNotifier.java` | Wer bekommt wann welche Nachricht im Spiel (Zusammenfassung, live, Pause, Abschalten) |
| `GameText.java` | Alle Texte für Chat und Actionbar (Farben, Klick-Links, Richtung) |
| `LandEnterTracker.java`, `DeathPoints.java` | Land-Anzeige beim Betreten, Todespunkt-Marker |
| `JsonStore.java`, `JsonFiles.java`, `Chunks.java`, `PlannerException.java` | Hilfsklassen |

**Webseite** (`web/`, ES-Module ohne Build-Schritt):
`js/main.js` (Start, Navigation, Aktualisieren), `api.js`, `store.js` (Datenstand + Ereignisse), `router.js` (`#/bereich/id`),
`map.js` (Claim-Karte, Messlinie), `bluemap.js` (3D-Abgleich), `coords.js` (Koordinaten-Werkzeuge und Messen, ohne DOM → `node --test`),
`search.js`, `util.js` und `views/*.js` (je ein Bereich). `window.planner` ist ein Einstieg für die Browser-Tests.

**BlueMap-Abgleich:** BlueMap 5.29 liest `#karte:x:y:z:abstand:drehung:winkel:neigung:ortho:modus` beim Laden und bei jedem
`hashchange`. Die Kamera hat 75° Blickwinkel, von oben sieht man bei Abstand d etwa 1,53 × d Blöcke. `distanceForZoom()`
rechnet den Zoom der Karte um (30–2000 Blöcke, BlueMaps Grenze von oben). Die iframes bleiben im Dokument (nur versteckt),
sonst würde BlueMap bei jedem Wechsel neu laden.

## API

Alle Antworten sind JSON. Fehler: `{"error": "deutsche Meldung"}` mit HTTP-Code 400, 401, 403, 404, 409, 413, 429 oder 503.
Außer `info` und `login` brauchen alle Anfragen `Authorization: Bearer <token>`.
Chunk-Listen sind flach: `[x0, z0, x1, z1, …]` (Chunk-Koordinaten = Block / 16, abgerundet).
Handels-Aktionen (alle POST unter `/api/trades`) brauchen eine `requestId` (8–64 Zeichen `A-Z a-z 0-9 -`).

| Methode | Pfad | Body | Hinweis |
|---|---|---|---|
| GET | `/api/info` | – | öffentlich |
| POST | `/api/login` · `/api/logout` | `{code}` / – | |
| GET | `/api/me` | – | Konto, Limits, Teams, Karten |
| GET | `/api/meta` | – | Auswahl-Listen (Typen, Rollen, Kategorien …) aus einer Quelle |
| GET | `/api/world` · `/api/players` · `/api/known-players` · `/api/bluemap-status` | – | |
| GET | `/api/discovered?dimension=…&since=<version>` | – | `{unchanged:true}`, wenn sich nichts geändert hat |
| GET | `/api/people` | – | Spieler mit Rolle, online/zuletzt da, sichtbaren Teams, Ländern |
| GET/POST | `/api/prefs` · `/api/prefs/favorite` | `{kind, id, on}` | Favoriten (lands, markers, projects, teams, listings) |
| POST | `/api/prefs/game` | `{key, on}` | Schalter fürs Spiel: `notify`, `landEnter`, `deathMarker` (`/api/meta` → `gameFeatures`: was der Server anbietet) |
| GET | `/api/todo` | – | „Zu erledigen“ wie im Spiel: `{items: [{id, kind, text, href}]}` |
| GET | `/api/activity?limit=50` | – | nur sichtbare Ereignisse |
| GET/POST | `/api/export` · `/api/import` | `{data, apply}` | `apply:false` = Vorschau |
| GET/POST | `/api/lands` | `{name, dimension, chunks, color?, description?}` | |
| POST | `/api/lands/check` | `{dimension, chunks, landId?}` | Vorschau, ändert nichts |
| POST | `/api/lands/{id}/chunks` | `{add?, remove?}` | `{deleted}` wenn leer |
| POST | `/api/lands/{id}/edit` | `{name?, color?, description?, notes?}` | |
| POST | `/api/lands/{id}/invite` · `uninvite` · `remove-owner` | `{player}` | `remove-owner` nur Admins |
| POST | `/api/lands/{id}/accept` · `decline` · `leave` · `delete` | – | `delete`: nur als einziger Besitzer oder Admin |
| POST | `/api/lands/{id}/team` | `{teamId}` | leer = Zuordnung lösen |
| GET/POST | `/api/markers` | `{name, type, dimension, x, y?, z, notes, visibility, teamId?, landId?, color?, symbol?}` | alte Webseiten: `public` statt `visibility` geht noch |
| POST | `/api/markers/{id}/edit` · `delete` | wie oben / – | |
| GET/POST | `/api/teams` · `/api/teams/{id}` | `{name, tag, description?, color?, symbol?, visibility?, joinPolicy?}` | |
| POST | `/api/teams/{id}/edit` · `invite {player}` · `accept` · `decline` · `apply {message}` · `withdraw-application` | | |
| POST | `/api/teams/{id}/role {player, role}` · `kick {player}` · `leave` | | |
| POST | `/api/teams/{id}/transfer {player, confirmName}` · `transfer-accept` · `transfer-cancel` · `dissolve {confirmName}` | | |
| POST | `/api/teams/{id}/invites/{inv}/withdraw` · `/applications/{app}/accept` · `/decline` | | |
| GET/POST | `/api/projects` · `/api/projects/{id}` | `{name, description, status, priority, startDate, dueDate, teamId, landId, visibility, responsible:[Namen], location, tasks:[…], notes}` | |
| POST | `/api/projects/{id}/edit` · `delete` · `tasks {text}` · `tasks/{t}/edit {text?, done?}` · `tasks/{t}/delete` · `updates {text}` | | |
| GET/POST | `/api/trades` · `/api/trades/{id}` | `{requestId, kind, title, category, itemName, quantity, description, startPrice, minIncrement, buyNowPrice, price, side, wants, durationHours, teamId}` | |
| POST | `/api/trades/{id}/bid {amount}` · `buy` · `offer {text}` · `offers/{o}/withdraw` · `offers/{o}/accept` | jeweils + `requestId` | |
| POST | `/api/trades/{id}/cancel {reason?}` · `confirm` · `dispute {reason}` · `resolve {completed, note}` | jeweils + `requestId` | `resolve` nur Admins |

## Ideen für später

1. **Echter Schutz** (optional): Abbauen/Bauen in fremden Ländern blocken. Ehrlich: Kolben, Explosionen, Lava machen das aufwendig.
2. **Befehle für Handel und Teams im Spiel** (z. B. Einladung annehmen per Klick) – heute gibt es dazu nur Nachrichten.
3. **Gelände in der Claim-Karte**, z. B. aus BlueMaps Flach-Ansicht.
4. **Bestehende Welten:** entdeckte Chunks aus den Region-Dateien (InhabitedTime) übernehmen.
5. **Economy-Anbindung**, falls der Server eine Economy-Mod bekommt – erst dann wären echte Zahlungen möglich.
