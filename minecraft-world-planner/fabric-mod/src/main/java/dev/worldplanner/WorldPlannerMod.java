package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Einstiegspunkt der Mod. Hier wird alles verbunden:
 * Datenspeicher (im Weltordner unter world-planner/), Befehle, Webserver und BlueMap.
 *
 * Wichtig: Minecraft-Objekte (Spieler, Welten) dürfen nur auf dem Server-Thread benutzt werden.
 * Deshalb erstellt onTick() regelmäßig "Momentaufnahmen", die der Webserver gefahrlos lesen kann.
 */
public final class WorldPlannerMod implements ModInitializer {
    public static final String MOD_ID = "world_planner";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private volatile PlannerServices services;
    private ApiServer api;
    private volatile BlueMapBridge blueMap;
    private ExecutorService saver;
    private ScheduledExecutorService timer;
    private final AtomicBoolean blueMapUpdateQueued = new AtomicBoolean();
    private volatile ChatTagSync chatTags;
    private final AtomicBoolean chatTagsQueued = new AtomicBoolean();
    private volatile long syncedTeamsVersion = -1;
    private volatile long syncedPlayersVersion = -1;
    private volatile GameNotifier notifier;
    private final AtomicBoolean noticesQueued = new AtomicBoolean();
    private LandEnterTracker landEnter; // nur Server-Thread
    private final Set<UUID> diedWithPoint = new HashSet<>(); // nur Server-Thread: Todespunkt gespeichert, Hinweis nach dem Wiederbeleben
    private long tick;

    @Override
    public void onInitialize() {
        PlannerCommands commands = new PlannerCommands(() -> services);
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> commands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(this::onStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlannerServices s = services;
            ServerPlayer player = handler.getPlayer();
            if (s != null) s.players().seen(player.getUUID(), player.getGameProfile().name(), isAdmin(player));
            GameNotifier n = notifier;
            if (n != null) n.joined(player.getUUID());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            GameNotifier n = notifier;
            if (n != null) n.left(uuid);
            if (landEnter != null) landEnter.left(uuid);
            diedWithPoint.remove(uuid);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) onPlayerDeath(player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) onPlayerRespawn(newPlayer);
        });
    }

    private void onStarting(MinecraftServer server) {
        PlannerConfig config = PlannerConfig.load(FabricLoader.getInstance().getConfigDir().resolve("world-planner.json"), LOGGER);
        Path data = server.getWorldPath(LevelResource.ROOT).resolve("world-planner");
        services = PlannerServices.create(data, config, LOGGER);
        saver = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "world-planner-save");
            t.setDaemon(true);
            return t;
        });
        tick = 0;
    }

    private void onStarted(MinecraftServer server) {
        PlannerServices s = services;
        Set<String> dimensions = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) dimensions.add(dimensionId(level));
        s.setKnownDimensions(dimensions);
        s.worldSnapshot().set(worldSnapshot(server));

        if (!s.config().webOverrideDir().isBlank()) {
            LOGGER.warn("[World Planner] Webseite kommt aus dem Ordner {} statt aus der Mod (webOverrideDir).", s.config().webOverrideDir());
        }
        api = new ApiServer(LOGGER, s);
        try {
            api.start();
        } catch (Exception e) {
            // Kein Absturz des ganzen Servers, nur die Webseite fehlt.
            LOGGER.error("[World Planner] Webserver konnte nicht starten (Port {} schon belegt?): {}", s.config().port(), e.getMessage());
            api = null;
        }

        // Team-Kürzel im Chat über Minecrafts Scoreboard-Teams (wir sind hier auf dem Server-Thread)
        if (s.config().chatTeamTags()) {
            chatTags = new ChatTagSync(LOGGER, s.teams(), s.players());
            syncedTeamsVersion = s.teams().version();
            syncedPlayersVersion = s.players().version();
            chatTags.sync(server.getScoreboard());
        } else {
            int removed = ChatTagSync.removeAll(server.getScoreboard());
            if (removed > 0) LOGGER.info("[World Planner] chatTeamTags ist aus – {} Team-Kürzel aus dem Scoreboard entfernt.", removed);
        }

        notifier = s.config().gameNotifications() ? new GameNotifier(s) : null;
        landEnter = s.config().landEnterMessages() ? new LandEnterTracker() : null;

        if (s.config().blueMapIntegration() && FabricLoader.getInstance().isModLoaded("bluemap")) {
            blueMap = new BlueMapBridge(LOGGER, s);
            LOGGER.info("[World Planner] BlueMap gefunden – Länder und Marker werden auf der 3D-Karte angezeigt.");
        }

        // Ein leerer Server pausiert nach 60 Sekunden – dann gibt es keine Ticks mehr. Speichern und BlueMap
        // dürfen davon nicht abhängen, sonst würden nachts über die Webseite geänderte Länder weder gespeichert
        // noch in BlueMap gezeigt. Deshalb ein eigener Timer.
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "world-planner-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> safely(() -> saveAsync(s, false)), 5, 5, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> safely(() -> {
            saveAsync(s, true);
            s.sessions().cleanup();
        }), 60, 60, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> safely(() -> queueBlueMapUpdate(server)), 1, 1, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> safely(() -> queueChatTags(server)), 1, 1, TimeUnit.SECONDS);
        // Abgelaufene Auktionen/Angebote beendet der Server selbst – auch wenn niemand die Webseite offen hat.
        timer.scheduleWithFixedDelay(() -> safely(() -> s.trades().settleExpired()), 1, 1, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> safely(() -> queueNotices(server)), 2, 2, TimeUnit.SECONDS);
    }

    /**
     * Hinweise im Spiel: Berechnet wird hier im Timer-Thread (die Speicher können kurz gesperrt sein – das Spiel soll
     * nicht warten), verschickt auf dem Server-Thread. Es steht höchstens ein Auftrag gleichzeitig in der Schlange.
     */
    private void queueNotices(MinecraftServer server) {
        GameNotifier n = notifier;
        if (n == null || noticesQueued.get()) return; // der letzte Auftrag ist noch nicht verschickt – nächstes Mal
        List<GameNotifier.Outgoing> out = n.poll();
        if (out.isEmpty()) return;
        noticesQueued.set(true);
        server.execute(() -> {
            try {
                for (GameNotifier.Outgoing o : out) {
                    ServerPlayer player = server.getPlayerList().getPlayer(o.player());
                    if (player == null) continue; // inzwischen ausgeloggt
                    player.sendSystemMessage(o.message());
                    LOGGER.debug("[World Planner] Nachricht an {}: {}", player.getGameProfile().name(), o.message().getString());
                }
            } finally {
                noticesQueued.set(false);
            }
        });
    }

    /** Todespunkt als privaten Marker speichern (Server-Thread). */
    private void onPlayerDeath(ServerPlayer player) {
        PlannerServices s = services;
        if (s == null || !s.config().deathMarkers() || !s.prefs().game(player.getUUID()).deathMarker()) return;
        DeathPoints.Point p = new DeathPoints.Point(dimensionId(player.level()), player.getBlockX(), player.getBlockY(), player.getBlockZ());
        String problem = DeathPoints.save(s, player.getUUID(), isAdmin(player), p);
        if (problem == null) diedWithPoint.add(player.getUUID());
        Component msg = problem == null ? GameText.deathSaved(p, s.config().publicUrl()) : GameText.deathNotSaved(problem);
        player.sendSystemMessage(msg);
        LOGGER.debug("[World Planner] Nachricht an {}: {}", player.getGameProfile().name(), msg.getString());
    }

    /** Nach dem Wiederbeleben: Wo liegt der Todespunkt von hier aus? (Server-Thread) */
    private void onPlayerRespawn(ServerPlayer player) {
        PlannerServices s = services;
        if (s == null || !diedWithPoint.remove(player.getUUID())) return;
        DeathPoints.Point p = DeathPoints.load(s, player.getUUID());
        if (p == null) return;
        Component msg = GameText.deathWhere(p, dimensionId(player.level()), player.getX(), player.getZ(), s.config().publicUrl());
        player.sendSystemMessage(msg);
        LOGGER.debug("[World Planner] Nachricht an {}: {}", player.getGameProfile().name(), msg.getString());
    }

    /**
     * BlueMap-Update auf dem Server-Thread ausführen (es liest Minecraft-Welten). server.execute() wird auch
     * im Pause-Zustand abgearbeitet. Es steht höchstens ein Auftrag gleichzeitig in der Schlange.
     */
    private void queueBlueMapUpdate(MinecraftServer server) {
        if (blueMap == null || !blueMapUpdateQueued.compareAndSet(false, true)) return;
        server.execute(() -> {
            try {
                BlueMapBridge bridge = blueMap;
                if (bridge != null) bridge.tick(server);
            } finally {
                blueMapUpdateQueued.set(false);
            }
        });
    }

    /**
     * Team-Kürzel nachziehen, wenn sich Teams oder Spielernamen geändert haben. Das Scoreboard ist Minecraft-Zustand,
     * deshalb läuft der Abgleich per server.execute() auf dem Server-Thread (auch in der Pause).
     */
    private void queueChatTags(MinecraftServer server) {
        ChatTagSync sync = chatTags;
        PlannerServices s = services;
        if (sync == null || s == null) return;
        long teamsVersion = s.teams().version();
        long playersVersion = s.players().version();
        if (teamsVersion == syncedTeamsVersion && playersVersion == syncedPlayersVersion) return;
        if (!chatTagsQueued.compareAndSet(false, true)) return;
        server.execute(() -> {
            try {
                sync.sync(server.getScoreboard());
                syncedTeamsVersion = teamsVersion;
                syncedPlayersVersion = playersVersion;
            } finally {
                chatTagsQueued.set(false);
            }
        });
    }

    private static void safely(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            LOGGER.warn("[World Planner] Hintergrund-Aufgabe fehlgeschlagen: {}", e.toString());
        }
    }

    private void onTick(MinecraftServer server) {
        PlannerServices s = services;
        if (s == null) return;
        tick++;

        // Nur was Spieler braucht, läuft über Ticks (ohne Spieler pausiert der Server ohnehin).
        if (tick % 20 == 0) { // jede Sekunde
            int radius = Math.min(s.config().discoverRadiusChunks(), server.getPlayerList().getViewDistance());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                s.players().seen(player.getUUID(), player.getGameProfile().name(), isAdmin(player));
                s.discovery().markArea(dimensionId(player.level()), player.chunkPosition().x(), player.chunkPosition().z(), radius);
            }
        }
        LandEnterTracker enter = landEnter;
        if (enter != null && tick % 10 == 0) { // zweimal pro Sekunde – fragt nur nach, wenn jemand den Chunk gewechselt hat
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Component msg = enter.update(s, player.getUUID(), dimensionId(player.level()),
                        Chunks.key(player.chunkPosition().x(), player.chunkPosition().z()));
                if (msg == null) continue;
                player.sendOverlayMessage(msg);
                LOGGER.debug("[World Planner] Land-Anzeige für {}: {}", player.getGameProfile().name(), msg.getString());
            }
        }
        if (tick % 40 == 0) { // alle 2 Sekunden
            s.playersSnapshot().set(playersSnapshot(server));
            s.worldSnapshot().set(worldSnapshot(server)); // der Spawn kann sich mit /setworldspawn ändern
        }
    }

    private void onStopping(MinecraftServer server) {
        if (timer != null) {
            timer.shutdownNow();
            try {
                timer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            timer = null;
        }
        if (api != null) api.stop();
        api = null;
        if (blueMap != null) blueMap.close();
        blueMap = null;
        chatTags = null; // die Scoreboard-Teams speichert Minecraft selbst; beim nächsten Start wird abgeglichen
        notifier = null;
        landEnter = null;
        diedWithPoint.clear();
        PlannerServices s = services;
        services = null;
        if (saver != null) {
            saver.shutdown();
            try {
                saver.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (s != null) {
            for (JsonStore store : s.stores()) {
                JsonElement snapshot = store.takeDirtySnapshot();
                if (snapshot != null) write(store, snapshot);
            }
            LOGGER.info("[World Planner] Daten gespeichert.");
        }
    }

    /** Speichert geänderte Daten im Hintergrund, damit das Spiel nicht ruckelt. */
    private void saveAsync(PlannerServices s, boolean includeDiscovery) {
        for (JsonStore store : s.stores()) {
            if (store == s.discovery() && !includeDiscovery) continue;
            JsonElement snapshot = store.takeDirtySnapshot();
            if (snapshot != null) saver.execute(() -> write(store, snapshot));
        }
    }

    private static void write(JsonStore store, JsonElement snapshot) {
        try {
            JsonFiles.write(store.path, snapshot, !(store instanceof DiscoveryTracker));
        } catch (Exception e) {
            LOGGER.error("[World Planner] Konnte {} nicht speichern: {}", store.path, e.getMessage());
            store.markDirty(); // beim nächsten Mal nochmal versuchen
        }
    }

    private static JsonObject worldSnapshot(MinecraftServer server) {
        JsonObject world = new JsonObject();
        world.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
        world.addProperty("seed", String.valueOf(server.overworld().getSeed())); // als Text: JavaScript kann so große Zahlen nicht exakt
        world.addProperty("worldName", server.getWorldData().getLevelName());
        JsonArray dims = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) dims.add(dimensionId(level));
        world.add("dimensions", dims);
        // Startpunkt für die Karten, wenn man selbst nicht online ist
        LevelData.RespawnData respawn = server.overworld().getRespawnData();
        JsonObject spawn = new JsonObject();
        spawn.addProperty("dimension", respawn.dimension().identifier().toString());
        spawn.addProperty("x", respawn.pos().getX());
        spawn.addProperty("y", respawn.pos().getY());
        spawn.addProperty("z", respawn.pos().getZ());
        world.add("spawn", spawn);
        return world;
    }

    private static JsonObject playersSnapshot(MinecraftServer server) {
        JsonObject players = PlannerServices.emptyPlayers();
        JsonArray rows = players.getAsJsonArray("players");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getGameProfile().name());
            p.addProperty("uuid", player.getUUID().toString());
            p.addProperty("dimension", dimensionId(player.level()));
            p.addProperty("x", Math.round(player.getX() * 10) / 10.0);
            p.addProperty("y", Math.round(player.getY() * 10) / 10.0);
            p.addProperty("z", Math.round(player.getZ() * 10) / 10.0);
            rows.add(p);
        }
        return players;
    }

    private static boolean isAdmin(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    private static String dimensionId(net.minecraft.world.level.Level level) {
        return level.dimension().identifier().toString();
    }
}
