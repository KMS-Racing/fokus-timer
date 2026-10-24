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
 * zurzeit die Favoriten. Ansicht, Ebenen und Sortierung speichert der Browser selbst (localStorage).
 * Jeder Spieler sieht und ändert nur seine eigenen Einträge.
 */
final class PrefsStore extends JsonStore {
    static final Set<String> KINDS = Set.of("lands", "markers", "projects", "teams", "listings");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int MAX_PER_KIND = 200;

    private final Map<UUID, Map<String, LinkedHashSet<String>>> favorites = new HashMap<>();

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
        return o;
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
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("favorites", fav);
        return root;
    }
}
