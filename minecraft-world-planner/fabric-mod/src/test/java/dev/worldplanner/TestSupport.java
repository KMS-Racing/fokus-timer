package dev.worldplanner;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

/** Hilfen für die Unit-Tests: Einstellungen, Logger und eine komplette Daten-Umgebung im Temp-Ordner. */
final class TestSupport {
    static final Logger LOG = LoggerFactory.getLogger("world-planner-test");
    static final String OVERWORLD = "minecraft:overworld";

    private TestSupport() {}

    /** Standard-Einstellungen, einzelne Werte überschreibbar: config("maxChunksPerPlayer", 5) */
    static PlannerConfig config(Object... keyValues) {
        JsonObject json = new JsonObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value instanceof Number n) json.addProperty(key, n);
            else if (value instanceof Boolean b) json.addProperty(key, b);
            else json.addProperty(key, String.valueOf(value));
        }
        return PlannerConfig.fromJson(json);
    }

    static UUID uuid(int n) {
        return new UUID(0x1234L, n);
    }

    /** Alle Speicher zusammen in einem Ordner – wie im echten Weltordner (gleiche Verdrahtung wie die Mod). */
    static final class World {
        final Path dir;
        final PlannerConfig config;
        final PlannerServices services;
        final PlayerDirectory players;
        final DiscoveryTracker discovery;
        final LandStore lands;
        final MarkerStore markers;
        final TeamStore teams;
        final ProjectStore projects;
        final TradeStore trades;
        final PrefsStore prefs;
        final ActivityLog activity;

        World(Path dir, PlannerConfig config) {
            this.dir = dir;
            this.config = config;
            this.services = PlannerServices.create(dir, config, LOG);
            this.players = services.players();
            this.discovery = services.discovery();
            this.lands = services.lands();
            this.markers = services.markers();
            this.teams = services.teams();
            this.projects = services.projects();
            this.trades = services.trades();
            this.prefs = services.prefs();
            this.activity = services.activity();
            services.setKnownDimensions(java.util.Set.of(OVERWORLD, "minecraft:the_nether", "minecraft:the_end"));
        }

        /** Speichert alle geänderten Daten wie der Mod-Timer (der Handel speichert jede Aktion selbst). */
        void save() throws Exception {
            for (JsonStore store : services.stores()) {
                var snapshot = store.takeDirtySnapshot();
                if (snapshot != null) JsonFiles.write(store.path, snapshot, true);
            }
        }

        /** Eine neue Umgebung aus denselben Dateien – wie nach einem Server-Neustart. */
        World restart() throws Exception {
            save();
            return new World(dir, config);
        }

        UUID player(int n, String name) {
            UUID u = uuid(n);
            players.seen(u, name, false);
            return u;
        }

        UUID admin(int n, String name) {
            UUID u = uuid(n);
            players.seen(u, name, true);
            return u;
        }

        /** Markiert ein Rechteck von Chunks als entdeckt. */
        void discover(int x1, int z1, int x2, int z2) {
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) discovery.markArea(OVERWORLD, x, z, 0);
        }
    }

    static long[] chunks(int... xz) {
        long[] out = new long[xz.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = Chunks.key(xz[i * 2], xz[i * 2 + 1]);
        return out;
    }
}
