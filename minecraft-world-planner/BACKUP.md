# Backup und Wiederherstellen

Alle Daten des World Planners liegen im Weltordner unter **`world/world-planner/`** (bei einem anderen Weltnamen
entsprechend `<welt>/world-planner/`). Die Einstellungen liegen in **`config/world-planner.json`**.

| Datei | Inhalt | Wichtig? |
|---|---|---|
| `lands.json` | Länder, Besitzer, Einladungen, Notizen, Team-Zuordnung | ja |
| `markers.json` | Marker | ja |
| `teams.json` | Teams, Rollen, Einladungen, Bewerbungen, offene Übergaben | ja |
| `projects.json` | Projekte, Aufgaben, Updates | ja |
| `trades.json` | Handel: Angebote, Gebote, Gegenangebote, Übergaben, Verlauf, zuletzt verarbeitete `requestId`s | ja |
| `players.json` | Bekannte Spieler (Name ↔ UUID, OP-Status, zuletzt gesehen) | ja |
| `discovered.json` | Entdeckte Chunks | ja (sonst ist die Karte wieder dunkel) |
| `events.json` | Events: Zeit, Treffpunkt, Zusagen, Absagen (seit 0.6) | ja |
| `races.json` | Rennstrecken aus 0.5 – seit 0.6 nicht mehr benutzt, die Mod lässt die Datei unangetastet liegen | nur falls du zurück auf 0.5 willst |
| `stats.json` | Statistik: was der Planer selbst misst (Tagesstände, Aktiv/AFK, Sitzungen, Wann online, Verlauf, Rekorde). Minecrafts eigene Zahlen liegen ohnehin in `world/players/stats/` | nett zu haben |
| `prefs.json` | Favoriten und Schalter (Nachrichten, Land-Anzeige, Todespunkt, in Ranglisten sichtbar) | nett zu haben |
| `activity.json` | Aktivitäts-Übersicht (die letzten `activityLimit` Einträge) | nett zu haben |
| `sessions.json` | Web-Logins (nur Hashes und eine grobe Geräte-Beschreibung). Ohne die Datei müssen sich alle neu einloggen. | egal |
| `*.v1.bak` | Automatische Sicherung vor einer Format-Umstellung (z. B. beim Update von 0.2 auf 0.3) | aufheben, bis alles läuft |
| `*.kaputt-<Zeit>` | Eine Datei, die beim Start nicht lesbar war (die Mod überschreibt sie nicht) | ansehen, reparieren |

## Backup machen

**Am sichersten bei gestopptem Server:**
1. Server stoppen (`stop`). Die Mod speichert beim Stoppen alles.
2. Den Ordner `world/world-planner/` und `config/world-planner.json` kopieren (am besten zusammen mit der ganzen Welt).

**Bei laufendem Server** geht es auch: Die Mod schreibt jede Datei erst als `.tmp` und benennt sie dann in einem Schritt um.
Eine kopierte Datei ist also nie halb geschrieben. Zwischen zwei Dateien können aber ein paar Sekunden liegen.
Wer ganz sicher gehen will, nimmt das Backup bei gestopptem Server.

## Wiederherstellen

1. Server stoppen.
2. Die gesicherten Dateien zurück nach `world/world-planner/` kopieren (vorhandene vorher wegschieben, nicht löschen).
3. Server starten und im Log nach `[World Planner]` schauen.

## Update von 0.2 auf 0.3

- Beim ersten Start mit 0.3 werden `lands.json` und `markers.json` auf das neue Format umgestellt. Vorher legt die Mod
  automatisch `lands.json.v1.bak` und `markers.json.v1.bak` an (unverändert). Eine vorhandene Sicherung wird nie überschrieben.
- Marker mit `"public": true` werden „für alle“, mit `false` „nur ich“. Länder, Besitzer und Einladungen bleiben gleich.
- Zurück auf 0.2: Server stoppen, 0.2-Jar einsetzen, `lands.json.v1.bak` → `lands.json` und `markers.json.v1.bak` → `markers.json`
  zurückkopieren. Was in 0.3 neu angelegt wurde (Teams, Projekte, Handel), kennt 0.2 nicht.

## Update von 0.4 auf 0.5

- Nichts wird umgestellt: Alle Dateien bleiben, wie sie sind; neu kommen `stats.json` und `races.json` dazu. In `prefs.json`
  bekommt jeder Spieler beim ersten Ändern den Schalter „in Ranglisten sichtbar“ (fehlt er, gilt „an“).
- Zurück auf 0.4 (nicht ausprobiert): Server stoppen, 0.4-Jar einsetzen. 0.4 kennt `stats.json` und `races.json` nicht und lässt
  sie liegen; den Schalter „in Ranglisten sichtbar“ und die Geräte-Namen der Web-Logins vergisst es beim nächsten Speichern.

## Update von 0.5 auf 0.6

- Rennen gibt es nicht mehr. `races.json` bleibt liegen, wie sie ist (0.6 liest und schreibt sie nicht). Neu kommt `events.json`.
- In `config/world-planner.json` verschwinden beim ersten Start `races` und `maxTracksPerPlayer`; `events` und `maxEventsPerPlayer`
  kommen dazu. Projekte bekommen beim nächsten Abschließen ein Datum (`doneAt`) für den Rückblick.
- Zurück auf 0.5.1 (nicht ausprobiert): Server stoppen, 0.5.1-Jar einsetzen. 0.5.1 liest `races.json` wieder (die Strecken sind
  noch da), kennt `events.json` nicht und lässt sie liegen.

## Export/Import auf der Webseite ist kein Server-Backup

Unter **Einstellungen → Export** lädt jeder Spieler **seine eigenen** Länder, Marker, Projekte und Favoriten als JSON herunter.
Der Import legt daraus nur **neu** an (als der importierende Spieler) und überschreibt nie etwas. Das ist praktisch zum
Umziehen auf einen anderen Server – ersetzt aber kein Backup der Dateien oben.
