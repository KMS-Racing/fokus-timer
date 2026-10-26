package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Persönliche Einstellungen, die auf dem Server liegen (damit sie auf jedem Gerät gleich sind):
 * Favoriten und die Schalter fürs Spiel (Nachrichten, Land-Anzeige, Todespunkt, in Ranglisten sichtbar). Ansicht, Ebenen und Sortierung
 * speichert der Browser selbst (localStorage). Jeder Spieler sieht und ändert nur seine eigenen Einträge.
 */
final class PrefsStore extends JsonStore {
    static final Set<String> KINDS = Set.of("lands", "markers", "projects", "teams", "listings");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int MAX_PER_KIND = 200;

    /** Schalter fürs Spiel – Standard: alles an. */
    static final Map<String, String> GAME_KEYS = gameKeys();

    /**
     * rankings: in Ranglisten und Statistik für andere sichtbar.
     * deathMarkerId: der automatische Todespunkt-Marker dieses Spielers (wird bei jedem Tod verschoben).
     */
    record Game(boolean notices, boolean landEnter, boolean deathMarker, boolean rankings, String deathMarkerId) {
        static final Game DEFAULT = new Game(true, true, true, true, null);

        boolean get(String key) {
            return switch (key) {
                case "notify" -> notices;
                case "landEnter" -> landEnter;
                case "rankings" -> rankings;
                default -> deathMarker;
            };
        }

        Game with(String key, boolean on) {
            return new Game(key.equals("notify") ? on : notices, key.equals("landEnter") ? on : landEnter,
                    key.equals("deathMarker") ? on : deathMarker, key.equals("rankings") ? on : rankings, deathMarkerId);
        }
    }

    private final Map<UUID, Map<String, LinkedHashSet<String>>> favorites = new HashMap<>();
    private final Map<UUID, Game> game = new HashMap<>();

    PrefsStore(Path path, Logger logger) {
        super(path);
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("favorites")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().getAsJsonObject("favorites").entrySet()) {
                try {
                    Map<String, LinkedHashSet<String>> byKind = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> k : e.getValue().getAsJsonObject().entrySet()) {
                        if (!KINDS.contains(k.getKey())) continue;
                        LinkedHashSet<String> ids = new LinkedHashSet<>();
                        k.getValue().getAsJsonArray().forEach(id -> ids.add(id.getAsString()));
                        byKind.put(k.getKey(), ids);
                    }
                    favorites.put(UUID.fromString(e.getKey()), byKind);
                } catch (Exception ignored) {
                    // kaputter Eintrag
                }
            }
        }
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("game")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().getAsJsonObject("game").entrySet()) {
                try {
                    JsonObject o = e.getValue().getAsJsonObject();
                    game.put(UUID.fromString(e.getKey()), new Game(flag(o, "notify"), flag(o, "landEnter"), flag(o, "deathMarker"),
                            flag(o, "rankings"), o.has("deathMarkerId") ? o.get("deathMarkerId").getAsString() : null));
                } catch (Exception ignored) {
                    // kaputter Eintrag
                }
            }
        }
    }

    private static Map<String, String> gameKeys() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("notify", "Nachrichten im Spiel");
        m.put("landEnter", "Land-Anzeige");
        m.put("deathMarker", "Todespunkt-Marker");
        m.put("rankings", "In Ranglisten und Statistik sichtbar");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static boolean flag(JsonObject o, String key) {
        return !o.has(key) || o.get(key).getAsBoolean();
    }

    synchronized JsonObject get(UUID user) {
        JsonObject fav = new JsonObject();
        Map<String, LinkedHashSet<String>> mine = favorites.getOrDefault(user, Map.of());
        for (String kind : KINDS) {
            JsonArray arr = new JsonArray();
            mine.getOrDefault(kind, new LinkedHashSet<>()).forEach(arr::add);
            fav.add(kind, arr);
        }
        JsonObject o = new JsonObject();
        o.add("favorites", fav);
        Game g = game(user);
        JsonObject go = new JsonObject();
        for (String key : GAME_KEYS.keySet()) go.addProperty(key, g.get(key));
        o.add("game", go);
        return o;
    }

    synchronized Game game(UUID user) {
        return game.getOrDefault(user, Game.DEFAULT);
    }

    synchronized void setGame(UUID user, String key, boolean on) {
        Validate.oneOf(key, GAME_KEYS.keySet(), "Einstellung");
        game.put(user, game(user).with(key, on));
        changed();
    }

    synchronized void setDeathMarkerId(UUID user, String markerId) {
        Game g = game(user);
        game.put(user, new Game(g.notices, g.landEnter, g.deathMarker, g.rankings, markerId));
        changed();
    }

    /** Spieler, die in Ranglisten und Statistik nicht auftauchen wollen. */
    synchronized Set<UUID> statsHidden() {
        Set<UUID> out = new java.util.HashSet<>();
        game.forEach((user, g) -> {
            if (!g.rankings) out.add(user);
        });
        return out;
    }

    synchronized void setFavorite(UUID user, String kind, String id, boolean on) {
        Validate.oneOf(kind, KINDS, "Art");
        if (id == null || !ID.matcher(id).matches()) throw PlannerException.badRequest("Ungültige ID.");
        LinkedHashSet<String> ids = favorites.computeIfAbsent(user, u -> new LinkedHashMap<>()).computeIfAbsent(kind, k -> new LinkedHashSet<>());
        if (on) {
            if (!ids.contains(id) && ids.size() >= MAX_PER_KIND) throw PlannerException.badRequest("Höchstens " + MAX_PER_KIND + " Favoriten pro Bereich.");
            ids.add(id);
        } else {
            ids.remove(id);
        }
        changed();
    }

    @Override
    protected JsonElement toJson() {
        JsonObject fav = new JsonObject();
        favorites.forEach((user, byKind) -> {
            JsonObject k = new JsonObject();
            byKind.forEach((kind, ids) -> {
                JsonArray arr = new JsonArray();
                ids.forEach(arr::add);
                k.add(kind, arr);
            });
            fav.add(user.toString(), k);
        });
        JsonObject gameJson = new JsonObject();
        game.forEach((user, g) -> {
            JsonObject o = new JsonObject();
            o.addProperty("notify", g.notices);
            o.addProperty("landEnter", g.landEnter);
            o.addProperty("deathMarker", g.deathMarker);
            o.addProperty("rankings", g.rankings);
            if (g.deathMarkerId != null) o.addProperty("deathMarkerId", g.deathMarkerId);
            gameJson.add(user.toString(), o);
        });
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("favorites", fav);
        root.add("game", gameJson);
        return root;
    }
}
