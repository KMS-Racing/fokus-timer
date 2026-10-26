package dev.worldplanner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Merkt sich alle Spieler, die schon mal auf dem Server waren (UUID ↔ Name),
 * damit man auch Offline-Spieler in ein Land einladen kann.
 * Ob jemand Admin (OP) ist, wird jede Sekunde für Online-Spieler aktualisiert.
 */
final class PlayerDirectory extends JsonStore {
    private static final long LAST_SEEN_SAVE_INTERVAL_MS = 10 * 60 * 1000;

    record Entry(UUID uuid, String name, boolean admin, long lastSeen) {}

    private final Map<UUID, Entry> byId = new HashMap<>();

    PlayerDirectory(Path path, Logger logger) {
        super(path);
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
                try {
                    JsonObject o = e.getValue().getAsJsonObject();
                    UUID uuid = UUID.fromString(e.getKey());
                    byId.put(uuid, new Entry(uuid, o.get("name").getAsString(),
                            o.has("admin") && o.get("admin").getAsBoolean(),
                            o.has("lastSeen") ? o.get("lastSeen").getAsLong() : 0));
                } catch (Exception ignored) {
                    // einzelne kaputte Einträge überspringen
                }
            }
        }
    }

    synchronized void seen(UUID uuid, String name, boolean admin) {
        long now = System.currentTimeMillis();
        Entry old = byId.get(uuid);
        if (old != null && old.name.equals(name) && old.admin == admin && now - old.lastSeen < LAST_SEEN_SAVE_INTERVAL_MS) {
            return;
        }
        byId.put(uuid, new Entry(uuid, name, admin, now));
        changed();
    }

    synchronized String name(UUID uuid) {
        Entry e = byId.get(uuid);
        return e != null ? e.name : uuid.toString().substring(0, 8);
    }

    synchronized boolean isAdmin(UUID uuid) {
        Entry e = byId.get(uuid);
        return e != null && e.admin;
    }

    synchronized Optional<UUID> findByName(String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return byId.values().stream()
                .filter(e -> e.name.toLowerCase(Locale.ROOT).equals(wanted))
                .map(Entry::uuid)
                .findFirst();
    }

    synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Zuletzt gesehen (höchstens alle 10 Minuten gespeichert, also ungefähr) – 0 = unbekannt. */
    synchronized long lastSeen(UUID uuid) {
        Entry e = byId.get(uuid);
        return e == null ? 0 : e.lastSeen;
    }

    synchronized boolean known(UUID uuid) {
        return byId.containsKey(uuid);
    }

    synchronized List<String> names() {
        List<String> names = new ArrayList<>();
        byId.values().forEach(e -> names.add(e.name));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    @Override
    protected JsonElement toJson() {
        JsonObject root = new JsonObject();
        byId.values().forEach(e -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.name);
            o.addProperty("admin", e.admin);
            o.addProperty("lastSeen", e.lastSeen);
            root.add(e.uuid.toString(), o);
        });
        return root;
    }
}
