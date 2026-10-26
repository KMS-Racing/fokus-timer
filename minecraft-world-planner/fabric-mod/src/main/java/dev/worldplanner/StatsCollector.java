package dev.worldplanner;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sammelt die Statistik im Spiel: beim Login und Logout, jede Minute, beim Tod und beim Herunterfahren.
 * Nur Server-Thread – außer importFiles, das im Hintergrund die Dateien der Offline-Spieler liest.
 * Gerechnet und gespeichert wird in StatsStore.
 */
final class StatsCollector {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldPlannerMod.MOD_ID);
    private final PlannerServices s;
    private final StatReader reader = new StatReader();
    private final ActivityTracker activity;
    private final ZoneId zone;

    StatsCollector(PlannerServices s) {
        this.s = s;
        this.activity = new ActivityTracker(s.config().afkMinutes() * 60_000L);
        this.zone = s.config().zone();
    }

    void joined(ServerPlayer player, int onlineCount) {
        long now = System.currentTimeMillis();
        s.stats().observe(player.getUUID(), reader.read(player), now, LocalDate.now(zone));
        s.stats().sessionStarted(player.getUUID(), now, onlineCount);
        activity.check(player.getUUID(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), now);
    }

    void everySecond(List<ServerPlayer> players) {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : players) activity.check(p.getUUID(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), now);
    }

    void everyMinute(List<ServerPlayer> players) {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : players) flush(p, now);
    }

    /** Logout: ein letztes Mal lesen (das Spieler-Objekt hat seine Statistik noch). */
    void left(ServerPlayer player) {
        long now = System.currentTimeMillis();
        flush(player, now);
        s.stats().sessionEnded(player.getUUID(), now);
        activity.left(player.getUUID());
        LOGGER.debug("[World Planner] Statistik beim Ausloggen gelesen: {}", player.getGameProfile().name());
    }

    void died(ServerPlayer player, DamageSource source) {
        String[] cause = StatReader.deathCause(source, player);
        s.stats().death(player.getUUID(), cause[0], cause[1], System.currentTimeMillis());
    }

    void stopping(List<ServerPlayer> players) {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : players) flush(p, now);
        s.stats().endAllSessions(now);
        activity.clear();
    }

    boolean isAfk(UUID player) {
        return activity.isAfk(player, System.currentTimeMillis());
    }

    private void flush(ServerPlayer p, long now) {
        long[] time = activity.drain(p.getUUID());
        s.stats().addTime(p.getUUID(), time[0], time[1], ZonedDateTime.now(zone));
        s.stats().observe(p.getUUID(), reader.read(p), now, LocalDate.now(zone));
    }

    /**
     * Beim Start: Minecrafts Statistik-Dateien (world/players/stats/<uuid>.json) für Spieler, die gerade nicht online sind –
     * damit die Ranglisten gleich vollständig sind. Nur Spieler, die der Planer schon kennt (sonst fehlt der Name);
     * alle anderen kommen beim nächsten Login dazu. Liest nur, schreibt nichts in den Ordner.
     *
     * @return so viele Spieler übernommen
     */
    int importFiles(Path dir) throws java.io.IOException {
        if (!Files.isDirectory(dir)) return 0;
        long now = System.currentTimeMillis();
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                UUID id;
                try {
                    id = UUID.fromString(name.substring(0, name.length() - ".json".length()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (!s.players().known(id) || !Files.isRegularFile(file)) continue;
                StatValues.Raw raw;
                try {
                    raw = StatValues.fromStatsFile(JsonFiles.read(file));
                } catch (Exception e) {
                    continue; // kaputte oder halb geschriebene Datei – beim nächsten Login kommen die Zahlen aus dem Spiel
                }
                if (raw == null) continue;
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (s.stats().importFile(id, reader.compute(raw), modified, now, LocalDate.now(zone))) count++;
            }
        }
        return count;
    }
}
