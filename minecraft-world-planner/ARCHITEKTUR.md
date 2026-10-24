# Aufbau und API

## Überblick

```
Minecraft-Server (Fabric 26.3)
 └─ World Planner Mod
     ├─ Befehle /planer, /land          (PlannerCommands)
     ├─ Datenspeicher (JSON im Weltordner)
     │    PlayerDirectory, SessionStore, DiscoveryTracker, LandStore, MarkerStore
     ├─ Webserver :8765                  (ApiServer)
     │    ├─ /            → Webseite (index.html, app.js, style.css aus der Mod-Jar)
     │    └─ /api/...     → JSON-API
     └─ BlueMap-Anbindung (optional)     (BlueMapBridge + LandOutline)
```

**Threads:** Minecraft-Objekte (Spieler, Welten) dürfen nur auf dem *Server-Thread* benutzt werden.
Der Webserver läuft in eigenen Threads und benutzt deshalb nur:
- die Datenspeicher (alle Methoden `synchronized`) und
- Momentaufnahmen (`AtomicReference<JsonObject>`), die `WorldPlannerMod.onTick()` alle 2 Sekunden erstellt.

**Speichern:** Jede Änderung setzt ein „dirty“-Flag. Alle 5 Sekunden (entdeckte Chunks: jede Minute) wird
im Hintergrund-Thread gespeichert, beim Stoppen noch einmal direkt. Dateien werden erst als `.tmp` geschrieben und
dann umbenannt, damit bei einem Absturz keine halbe Datei übrig bleibt.

**Timer statt Ticks:** Ein leerer Server pausiert nach 60 Sekunden. Dann gibt es keine Ticks mehr. Deshalb laufen
Speichern und BlueMap-Updates über einen eigenen Timer-Thread (`world-planner-timer`). Das BlueMap-Update liest
Minecraft-Welten und wird darum per `server.execute()` auf den Server-Thread gereicht. Das arbeitet der Server auch in
der Pause ab (getestet: neues Land war nach 2 s in BlueMap und nach 4 s auf der Festplatte). Über Ticks laufen nur noch
Dinge, die Spieler brauchen (entdeckte Chunks, Spielerpositionen). Ohne Spieler sind die ohnehin egal.

**BlueMap-Abgleich (Webseite):** BlueMap 5.29 liest die Adresse `#karte:x:y:z:abstand:drehung:winkel:neigung:ortho:modus`
beim Laden und bei jedem `hashchange`. Die Kamera hat 75° Blickwinkel, von oben sieht man bei Abstand d also etwa
1,53 × d Blöcke. Darüber rechnet `distanceForZoom()` den Zoom der Claim-Karte um (begrenzt auf 30–2000, BlueMaps Grenze
von oben). Der Test `tests/integration/bluemap_sync_test.js` misst die echte Kamera im iframe nach.

**Sicherheitsregel (CSP):** Die Webseite darf nur eigene Skripte laden. Als iframe und für die Erreichbarkeits-Prüfung
sind nur die BlueMap-Adressen erlaubt (`liveMapUrl`, `seedMapUrl` oder automatisch `http://<host>:8100`).

## Dateien im Code

| Datei | Aufgabe |
|---|---|
| `WorldPlannerMod.java` | Einstieg: lädt alles, Tick-Schleife, Speichern |
| `PlannerConfig.java` | `config/world-planner.json` |
| `PlannerCommands.java` | Befehle im Spiel |
| `ApiServer.java` | Webserver und API |
| `LandStore.java` | Länder und alle Regeln (zusammenhängend, Limits, Besitzer, Einladungen) |
| `MarkerStore.java` | Bau-Marker |
| `DiscoveryTracker.java` | Entdeckte Chunks |
| `SessionStore.java` | Login-Codes und Sitzungen |
| `PlayerDirectory.java` | Bekannte Spieler, OP-Status |
| `BlueMapBridge.java` | Zeichnet Länder/Marker in BlueMap (wird nur geladen, wenn BlueMap da ist) |
| `LandOutline.java` | Macht aus Chunks einen Umriss (Polygon mit Löchern) |
| `JsonStore.java`, `JsonFiles.java`, `Chunks.java`, `PlannerException.java` | Hilfsklassen |

## API

Alle Antworten sind JSON. Fehler: `{"error": "deutsche Meldung"}` mit passendem HTTP-Code (400, 401, 403, 404, 409, 413, 429).
Außer `info` und `login` brauchen alle Anfragen `Authorization: Bearer <token>`.
Chunk-Listen sind flach: `[x0, z0, x1, z1, ...]` (Chunk-Koordinaten, also Block / 16).

| Methode | Pfad | Body | Antwort |
|---|---|---|---|
| GET | `/api/info` | – | Name der App |
| POST | `/api/login` | `{code}` | `{token, uuid, name}` |
| POST | `/api/logout` | – | `{ok}` |
| GET | `/api/me` | – | Name, Admin, Chunk-Verbrauch, Limits, Karten-URLs |
| GET | `/api/world` | – | Version, Seed (Text), Weltname, Dimensionen, Spawn `{dimension, x, y, z}` |
| GET | `/api/players` | – | Online-Spieler mit Position |
| GET | `/api/discovered?dimension=…` | – | `{dimension, chunks:[…]}` |
| GET | `/api/lands` | – | alle Länder (Einladungslisten nur für Besitzer/Admins) |
| POST | `/api/lands` | `{name, dimension, chunks}` | `{ok, id}` |
| POST | `/api/lands/{id}/chunks` | `{add?, remove?}` | `{ok, deleted?}` |
| POST | `/api/lands/{id}/edit` | `{name?, color?, description?}` | `{ok}` |
| POST | `/api/lands/{id}/invite` · `/uninvite` | `{player}` | `{ok}` |
| POST | `/api/lands/{id}/accept` · `/decline` · `/leave` | – | `{ok, deleted?}` |
| POST | `/api/lands/{id}/delete` | – | Admin: Land löschen |
| POST | `/api/lands/{id}/remove-owner` | `{player}` | Admin: Besitzer entfernen |
| GET | `/api/markers` | – | öffentliche + eigene private Marker |
| POST | `/api/markers` | `{name, type, dimension, x, y, z, notes, public}` | `{ok, id}` |
| POST | `/api/markers/{id}/edit` · `/delete` | wie oben / – | `{ok}` |
| GET | `/api/known-players` | – | Namen für die Einladen-Vorschläge |
| GET | `/api/bluemap-status` | – | `{available, renderQueue, renderThreads, rendering, paused}` von BlueMaps Render-Schnittstelle |

Marker-Typen: `city`, `base`, `road`, `farm`, `resource`, `portal`, `other`.

## Ideen für später

1. **Echter Schutz** (optional einschaltbar): Abbauen/Bauen/Kisten in fremden Ländern blocken (Fabric-Events `PlayerBlockBreakEvents`, `UseBlockCallback`, …). Ehrlich: Kolben, Explosionen, Lava usw. machen das aufwendig.
2. **Gelände in der Claim-Karte**, z. B. aus BlueMaps Flach-Ansicht.
3. **Welt-Check**: Vergleich von Seed/Version zwischen Live- und Vorschau-Server, damit die Vorschau sicher stimmt.
4. **Bestehende Welten**: entdeckte Chunks aus den Region-Dateien (InhabitedTime) übernehmen.
5. **Benachrichtigung im Spiel**, wenn dich jemand online über die Webseite einlädt.
