package dev.worldplanner;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Alles, was Befehle und Webseite brauchen, an einem Ort. Wird beim Serverstart erzeugt.
 * Die "on..."-Methoden räumen Verknüpfungen zwischen den Bereichen auf, damit z. B. beim Auflösen
 * eines Teams keine Länder, Projekte oder Marker verloren gehen.
 */
record PlannerServices(
        PlannerConfig config,
        PlayerDirectory players,
        SessionStore sessions,
        DiscoveryTracker discovery,
        LandStore lands,
        MarkerStore markers,
        TeamStore teams,
        ProjectStore projects,
        TradeStore trades,
        PrefsStore prefs,
        ActivityLog activity,
        StatsStore stats,
        RaceStore races,
        Access access,
        AtomicReference<JsonObject> worldSnapshot,
        AtomicReference<JsonObject> playersSnapshot,
        AtomicReference<Map<String, String>> detectedBlueMapIds,
        AtomicReference<JsonObject> blueMapStatus,
        /** Von der Webseite gestartete Wegweiser; der Server-Thread holt sie ab (Minecraft-Zustand nur dort). */
        java.util.Queue<Waypoints.Request> waypointRequests
) {
    /** Baut alle Speicher in einem Ordner und verbindet sie (auch für Unit-Tests). */
    static PlannerServices create(java.nio.file.Path data, PlannerConfig config, org.slf4j.Logger logger) {
        PlayerDirectory players = new PlayerDirectory(data.resolve("players.json"), logger);
        SessionStore sessions = new SessionStore(data.resolve("sessions.json"), logger);
        DiscoveryTracker discovery = new DiscoveryTracker(data.resolve("discovered.json"), logger);
        LandStore lands = new LandStore(data.resolve("lands.json"), logger, discovery, players, config);
        MarkerStore markers = new MarkerStore(data.resolve("markers.json"), logger, players, config);
        TeamStore teams = new TeamStore(data.resolve("teams.json"), logger, players, config);
        ProjectStore projects = new ProjectStore(data.resolve("projects.json"), logger, players, config);
        TradeStore trades = new TradeStore(data.resolve("trades.json"), logger, players, config);
        PrefsStore prefs = new PrefsStore(data.resolve("prefs.json"), logger);
        ActivityLog activity = new ActivityLog(data.resolve("activity.json"), logger, config.activityLimit());
        StatsStore stats = new StatsStore(data.resolve("stats.json"), logger);
        RaceStore races = new RaceStore(data.resolve("races.json"), logger, config);
        Access access = new Access(lands, teams);
        lands.setTeams(teams);
        markers.setAccess(access);
        projects.setAccess(access);
        trades.setAccess(access);
        return new PlannerServices(config, players, sessions, discovery, lands, markers, teams, projects, trades, prefs, activity,
                stats, races, access, new AtomicReference<>(new JsonObject()), new AtomicReference<>(emptyPlayers()),
                new AtomicReference<>(Map.of()), new AtomicReference<>(blueMapUnavailable()),
                new java.util.concurrent.ConcurrentLinkedQueue<>());
    }

    void setKnownDimensions(java.util.Set<String> dimensions) {
        lands.setKnownDimensions(dimensions);
        markers.setKnownDimensions(dimensions);
        projects.setKnownDimensions(dimensions);
    }

    /**
     * Speicher, die der Timer alle paar Sekunden sichert (Entdeckt und Statistik nur jede Minute – die ändern sich ständig
     * und sind am größten). Der Handel fehlt hier absichtlich: Er speichert jede Aktion sofort selbst (bevor der Server "OK" sagt).
     */
    JsonStore[] stores() {
        return new JsonStore[]{players, sessions, discovery, lands, markers, teams, projects, prefs, activity, stats, races};
    }

    boolean savedEveryMinute(JsonStore store) {
        return store == discovery || store == stats;
    }

    /** UUIDs der Spieler laut letzter Momentaufnahme (höchstens 2 Sekunden alt). */
    java.util.Set<java.util.UUID> onlinePlayers() {
        java.util.Set<java.util.UUID> out = new java.util.HashSet<>();
        for (com.google.gson.JsonElement e : playersSnapshot.get().getAsJsonArray("players")) {
            out.add(java.util.UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));
        }
        return out;
    }

    /** Ein Land wurde gelöscht: Projekte und Marker behalten, nur die Verknüpfung lösen. */
    void onLandDeleted(String landId) {
        projects.detachLand(landId);
        markers.detachLand(landId);
    }

    /** Ein Team wurde aufgelöst: Länder, Projekte, Marker und Angebote bleiben erhalten. */
    void onTeamDissolved(TeamStore.Dissolved team) {
        lands.dropTeam(team.id());
        projects.detachTeam(team.id());
        markers.detachTeam(team.id());
        trades.detachTeam(team.id());
    }

    /** Jemand hat ein Team verlassen oder wurde entfernt: Team-Zuordnung von Ländern neu prüfen. */
    void onTeamMembershipChanged(String teamId) {
        lands.recheckTeam(teamId);
    }

    /** Ist der Spieler laut letzter Momentaufnahme (höchstens 2 Sekunden alt) im Spiel? */
    boolean isOnline(java.util.UUID player) {
        return onlinePlayers().contains(player);
    }

    /** Wegweiser von der Webseite aus starten – nur für dich selbst und nur, wenn du gerade im Spiel bist. */
    void requestWaypoint(java.util.UUID player, Waypoints.Target target) {
        if (!isOnline(player)) throw PlannerException.conflict("Du bist gerade nicht im Spiel. Der Wegweiser erscheint im Spiel über der Hotbar.");
        if (waypointRequests.size() > 200) throw new PlannerException(429, "Zu viele Anfragen – einen Moment warten.");
        waypointRequests.add(new Waypoints.Request(player, target));
    }

    static JsonObject emptyPlayers() {
        JsonObject o = new JsonObject();
        o.addProperty("updatedAtEpochMs", System.currentTimeMillis());
        o.add("players", new com.google.gson.JsonArray());
        return o;
    }

    /** Steht hier und nicht in BlueMapBridge, damit ohne installiertes BlueMap keine BlueMap-Klasse geladen wird. */
    static JsonObject blueMapUnavailable() {
        JsonObject o = new JsonObject();
        o.addProperty("available", false);
        return o;
    }
}
