package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Einstellungen aus config/world-planner.json.
 * Fehlt die Datei, wird sie mit sicheren Standardwerten angelegt. Fehlende Einträge werden ergänzt,
 * damit neue Optionen nach einem Update in der Datei auftauchen.
 */
record PlannerConfig(
        String bind,
        int port,
        String publicUrl,
        String allowedOrigin,
        String liveMapUrl,
        String seedMapUrl,
        Map<String, String> blueMapIds,
        int maxChunksPerPlayer,
        int maxMarkersPerPlayer,
        boolean claimOnlyDiscovered,
        int discoverRadiusChunks,
        int sessionDays,
        boolean showSeed,
        boolean showOnlinePlayers,
        boolean blueMapIntegration,
        String webOverrideDir
) {
    static PlannerConfig load(Path path, Logger logger) {
        JsonObject json = new JsonObject();
        boolean readable = true;
        try {
            JsonElement parsed = JsonFiles.read(path);
            if (parsed != null) json = parsed.getAsJsonObject();
        } catch (Exception e) {
            readable = false;
            logger.error("[World Planner] {} ist kaputt ({}). Ich benutze die Standardwerte, bis du die Datei reparierst.", path, e.getMessage());
        }

        Map<String, String> mapIds = new LinkedHashMap<>();
        if (json.has("blueMapIds") && json.get("blueMapIds").isJsonObject()) {
            json.getAsJsonObject("blueMapIds").entrySet().forEach(e -> mapIds.put(e.getKey(), e.getValue().getAsString()));
        }
        // Leer = automatisch: Die Mod fragt BlueMap, welche Karte zu welcher Dimension gehört.

        PlannerConfig config = new PlannerConfig(
                str(json, "bind", "127.0.0.1"),
                clamp(integer(json, "port", 8765), 1, 65535),
                str(json, "publicUrl", ""),
                str(json, "allowedOrigin", ""),
                str(json, "liveMapUrl", ""),
                str(json, "seedMapUrl", ""),
                Map.copyOf(mapIds),
                clamp(integer(json, "maxChunksPerPlayer", 64), 0, 100_000),
                clamp(integer(json, "maxMarkersPerPlayer", 100), 0, 10_000),
                bool(json, "claimOnlyDiscovered", true),
                clamp(integer(json, "discoverRadiusChunks", 6), 0, 32),
                clamp(integer(json, "sessionDays", 30), 1, 365),
                bool(json, "showSeed", true),
                bool(json, "showOnlinePlayers", true),
                bool(json, "blueMapIntegration", true),
                str(json, "webOverrideDir", "")
        );

        if (readable) {
            try {
                JsonFiles.write(path, config.toJson(mapIds), true);
            } catch (Exception e) {
                logger.warn("[World Planner] Konnte {} nicht schreiben: {}", path, e.getMessage());
            }
        }
        return config;
    }

    private JsonObject toJson(Map<String, String> orderedMapIds) {
        JsonObject o = new JsonObject();
        o.addProperty("bind", bind);
        o.addProperty("port", port);
        o.addProperty("publicUrl", publicUrl);
        o.addProperty("allowedOrigin", allowedOrigin);
        o.addProperty("liveMapUrl", liveMapUrl);
        o.addProperty("seedMapUrl", seedMapUrl);
        JsonObject ids = new JsonObject();
        orderedMapIds.forEach(ids::addProperty);
        o.add("blueMapIds", ids);
        o.addProperty("maxChunksPerPlayer", maxChunksPerPlayer);
        o.addProperty("maxMarkersPerPlayer", maxMarkersPerPlayer);
        o.addProperty("claimOnlyDiscovered", claimOnlyDiscovered);
        o.addProperty("discoverRadiusChunks", discoverRadiusChunks);
        o.addProperty("sessionDays", sessionDays);
        o.addProperty("showSeed", showSeed);
        o.addProperty("showOnlinePlayers", showOnlinePlayers);
        o.addProperty("blueMapIntegration", blueMapIntegration);
        o.addProperty("webOverrideDir", webOverrideDir);
        return o;
    }

    private static String str(JsonObject json, String key, String fallback) {
        try {
            return json.has(key) ? json.get(key).getAsString().trim() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int integer(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) ? json.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) ? json.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
