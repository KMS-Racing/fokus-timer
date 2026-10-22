package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private BlueMapBridge blueMap;
    private ExecutorService saver;
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
        });
    }

    private void onStarting(MinecraftServer server) {
        PlannerConfig config = PlannerConfig.load(FabricLoader.getInstance().getConfigDir().resolve("world-planner.json"), LOGGER);
        Path data = server.getWorldPath(LevelResource.ROOT).resolve("world-planner");
        PlayerDirectory players = new PlayerDirectory(data.resolve("players.json"), LOGGER);
        SessionStore sessions = new SessionStore(data.resolve("sessions.json"), LOGGER);
        DiscoveryTracker discovery = new DiscoveryTracker(data.resolve("discovered.json"), LOGGER);
        LandStore lands = new LandStore(data.resolve("lands.json"), LOGGER, discovery, players, config);
        MarkerStore markers = new MarkerStore(data.resolve("markers.json"), LOGGER, players, config);
        services = new PlannerServices(config, players, sessions, discovery, lands, markers,
                new AtomicReference<>(new JsonObject()), new AtomicReference<>(emptyPlayers()), new AtomicReference<>(Map.of()));
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
        s.lands().setKnownDimensions(dimensions);
        s.markers().setKnownDimensions(dimensions);

        // Diese Werte ändern sich während der Laufzeit nicht – einmal reicht.
        JsonObject world = new JsonObject();
        world.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
        world.addProperty("seed", String.valueOf(server.overworld().getSeed())); // als Text: JavaScript kann so große Zahlen nicht exakt
        world.addProperty("worldName", server.getWorldData().getLevelName());
        JsonArray dims = new JsonArray();
        dimensions.forEach(dims::add);
        world.add("dimensions", dims);
        s.worldSnapshot().set(world);

        api = new ApiServer(LOGGER, s);
        try {
            api.start();
        } catch (Exception e) {
            // Kein Absturz des ganzen Servers, nur die Webseite fehlt.
            LOGGER.error("[World Planner] Webserver konnte nicht starten (Port {} schon belegt?): {}", s.config().port(), e.getMessage());
            api = null;
        }

        if (s.config().blueMapIntegration() && FabricLoader.getInstance().isModLoaded("bluemap")) {
            blueMap = new BlueMapBridge(LOGGER, s.lands(), s.markers(), s.players(), s.detectedBlueMapIds());
            LOGGER.info("[World Planner] BlueMap gefunden – Länder und Marker werden auf der 3D-Karte angezeigt.");
        }
    }

    private void onTick(MinecraftServer server) {
        PlannerServices s = services;
        if (s == null) return;
        tick++;

        if (tick % 20 == 0) { // jede Sekunde
            int radius = Math.min(s.config().discoverRadiusChunks(), server.getPlayerList().getViewDistance());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                s.players().seen(player.getUUID(), player.getGameProfile().name(), isAdmin(player));
                s.discovery().markArea(dimensionId(player.level()), player.chunkPosition().x(), player.chunkPosition().z(), radius);
            }
            if (blueMap != null) blueMap.tick(server);
        }
        if (tick % 40 == 0) s.playersSnapshot().set(playersSnapshot(server)); // alle 2 Sekunden
        if (tick % 100 == 0) saveAsync(s, false); // alle 5 Sekunden (nur was sich geändert hat)
        if (tick % 1200 == 0) { // jede Minute
            saveAsync(s, true);
            s.sessions().cleanup();
        }
    }

    private void onStopping(MinecraftServer server) {
        if (api != null) api.stop();
        api = null;
        if (blueMap != null) blueMap.close();
        blueMap = null;
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

    private static JsonObject playersSnapshot(MinecraftServer server) {
        JsonObject players = emptyPlayers();
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

    private static JsonObject emptyPlayers() {
        JsonObject o = new JsonObject();
        o.addProperty("updatedAtEpochMs", System.currentTimeMillis());
        o.add("players", new JsonArray());
        return o;
    }

    private static boolean isAdmin(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    private static String dimensionId(net.minecraft.world.level.Level level) {
        return level.dimension().identifier().toString();
    }
}
