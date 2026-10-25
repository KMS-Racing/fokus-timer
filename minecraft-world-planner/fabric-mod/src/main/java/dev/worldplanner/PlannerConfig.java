package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Einstellungen aus config/world-planner.json.
 * Fehlt die Datei, wird sie mit sicheren Standardwerten angelegt. Fehlende Einträge werden ergänzt,
 * damit neue Optionen nach einem Update in der Datei auftauchen.
 */
record PlannerConfig(
        // Webserver
        String bind,
        int port,
        String publicUrl,
        String allowedOrigin,
        String webOverrideDir,
        int sessionDays,
        // Karten
        String liveMapUrl,
        String seedMapUrl,
        Map<String, String> blueMapIds,
        boolean blueMapIntegration,
        boolean showSeed,
        boolean showOnlinePlayers,
        int discoverRadiusChunks,
        // Länder und Marker
        int maxChunksPerPlayer,
        boolean claimOnlyDiscovered,
        int maxMarkersPerPlayer,
        Map<String, String> extraMarkerTypes,
        // Projekte und Teams
        int maxProjectsPerPlayer,
        int maxTeamsPerPlayer,
        int teamInviteDays,
        /** Kürzel öffentlicher Teams im Spiel vor dem Namen zeigen (Chat, Tab-Liste, über dem Kopf). */
        boolean chatTeamTags,
        // Im Spiel (jeder Spieler kann die ersten drei für sich mit /planer einstellungen abschalten)
        /** Zusammenfassung "Zu erledigen" beim Einloggen und neue Hinweise live im Chat. */
        boolean gameNotifications,
        /** Name des Landes über der Hotbar, wenn man es betritt. */
        boolean landEnterMessages,
        /** Privater Marker "Todespunkt" beim Sterben. */
        boolean deathMarkers,
        /** Team-Chat mit /tc (alle Planer-Teams, auch private). */
        boolean teamChat,
        // Handel (Web-Auktionshaus – überträgt keine Items, siehe README)
        String tradeUnit,
        int tradeMinIncrement,
        int maxActiveListingsPerPlayer,
        int maxListingDays,
        int tradeActionsPerMinute,
        Map<String, String> extraTradeCategories,
        // Aktivitäts-Übersicht
        int activityLimit
) {
    private static final Pattern EXTRA_ID = Pattern.compile("[a-z0-9_]{2,24}");

    static PlannerConfig defaults() {
        return fromJson(new JsonObject());
    }

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
        PlannerConfig config = fromJson(json);
        if (readable) {
            try {
                JsonFiles.write(path, config.toJson(), true);
            } catch (Exception e) {
                logger.warn("[World Planner] Konnte {} nicht schreiben: {}", path, e.getMessage());
            }
        }
        return config;
    }

    /** Liest die Einstellungen aus JSON. Fehlende oder ungültige Werte werden durch sichere Standardwerte ersetzt. */
    static PlannerConfig fromJson(JsonObject json) {
        return new PlannerConfig(
                str(json, "bind", "127.0.0.1"),
                clamp(integer(json, "port", 8765), 1, 65535),
                str(json, "publicUrl", ""),
                str(json, "allowedOrigin", ""),
                str(json, "webOverrideDir", ""),
                clamp(integer(json, "sessionDays", 30), 1, 365),
                str(json, "liveMapUrl", ""),
                str(json, "seedMapUrl", ""),
                // Leer = automatisch: Die Mod fragt BlueMap, welche Karte zu welcher Dimension gehört.
                stringMap(json, "blueMapIds", null),
                bool(json, "blueMapIntegration", true),
                bool(json, "showSeed", true),
                bool(json, "showOnlinePlayers", true),
                clamp(integer(json, "discoverRadiusChunks", 6), 0, 32),
                clamp(integer(json, "maxChunksPerPlayer", 64), 0, 100_000),
                bool(json, "claimOnlyDiscovered", true),
                clamp(integer(json, "maxMarkersPerPlayer", 100), 0, 10_000),
                stringMap(json, "extraMarkerTypes", EXTRA_ID),
                clamp(integer(json, "maxProjectsPerPlayer", 50), 0, 10_000),
                clamp(integer(json, "maxTeamsPerPlayer", 1), 1, 20),
                clamp(integer(json, "teamInviteDays", 7), 1, 90),
                bool(json, "chatTeamTags", true),
                bool(json, "gameNotifications", true),
                bool(json, "landEnterMessages", true),
                bool(json, "deathMarkers", true),
                bool(json, "teamChat", true),
                limitText(str(json, "tradeUnit", "Diamanten"), 24, "Diamanten"),
                clamp(integer(json, "tradeMinIncrement", 1), 1, 1_000_000),
                clamp(integer(json, "maxActiveListingsPerPlayer", 20), 0, 1000),
                clamp(integer(json, "maxListingDays", 14), 1, 90),
                clamp(integer(json, "tradeActionsPerMinute", 30), 1, 1000),
                stringMap(json, "extraTradeCategories", EXTRA_ID),
                clamp(integer(json, "activityLimit", 1000), 50, 100_000)
        );
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("bind", bind);
        o.addProperty("port", port);
        o.addProperty("publicUrl", publicUrl);
        o.addProperty("allowedOrigin", allowedOrigin);
        o.addProperty("webOverrideDir", webOverrideDir);
        o.addProperty("sessionDays", sessionDays);
        o.addProperty("liveMapUrl", liveMapUrl);
        o.addProperty("seedMapUrl", seedMapUrl);
        o.add("blueMapIds", mapJson(blueMapIds));
        o.addProperty("blueMapIntegration", blueMapIntegration);
        o.addProperty("showSeed", showSeed);
        o.addProperty("showOnlinePlayers", showOnlinePlayers);
        o.addProperty("discoverRadiusChunks", discoverRadiusChunks);
        o.addProperty("maxChunksPerPlayer", maxChunksPerPlayer);
        o.addProperty("claimOnlyDiscovered", claimOnlyDiscovered);
        o.addProperty("maxMarkersPerPlayer", maxMarkersPerPlayer);
        o.add("extraMarkerTypes", mapJson(extraMarkerTypes));
        o.addProperty("maxProjectsPerPlayer", maxProjectsPerPlayer);
        o.addProperty("maxTeamsPerPlayer", maxTeamsPerPlayer);
        o.addProperty("teamInviteDays", teamInviteDays);
        o.addProperty("chatTeamTags", chatTeamTags);
        o.addProperty("gameNotifications", gameNotifications);
        o.addProperty("landEnterMessages", landEnterMessages);
        o.addProperty("deathMarkers", deathMarkers);
        o.addProperty("teamChat", teamChat);
        o.addProperty("tradeUnit", tradeUnit);
        o.addProperty("tradeMinIncrement", tradeMinIncrement);
        o.addProperty("maxActiveListingsPerPlayer", maxActiveListingsPerPlayer);
        o.addProperty("maxListingDays", maxListingDays);
        o.addProperty("tradeActionsPerMinute", tradeActionsPerMinute);
        o.add("extraTradeCategories", mapJson(extraTradeCategories));
        o.addProperty("activityLimit", activityLimit);
        return o;
    }

    private static JsonObject mapJson(Map<String, String> map) {
        JsonObject o = new JsonObject();
        map.forEach(o::addProperty);
        return o;
    }

    /** Liest {"id": "Text", ...}. Mit idPattern werden ungültige Schlüssel übersprungen. */
    private static Map<String, String> stringMap(JsonObject json, String key, Pattern idPattern) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json.has(key) && json.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject(key).entrySet()) {
                try {
                    String value = e.getValue().getAsString().trim();
                    if (idPattern != null && (!idPattern.matcher(e.getKey()).matches() || value.isEmpty() || value.length() > 32)) continue;
                    out.put(e.getKey(), value);
                } catch (Exception ignored) {
                    // ungültiger Eintrag
                }
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static String limitText(String value, int max, String fallback) {
        return value.isBlank() || value.length() > max ? fallback : value;
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
