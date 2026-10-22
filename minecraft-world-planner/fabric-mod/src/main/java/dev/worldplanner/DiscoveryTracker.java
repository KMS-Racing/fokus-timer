package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Merkt sich, welche Chunks schon entdeckt wurden – also wo irgendein Spieler schon in der Nähe war.
 * Das gilt für den ganzen Server (nicht pro Spieler). Nur entdeckte Chunks werden auf der
 * Claim-Karte gezeichnet, und (wenn eingestellt) kann man nur dort Land claimen.
 */
final class DiscoveryTracker extends JsonStore {
    private final Map<String, LongOpenHashSet> chunksByDimension = new HashMap<>();

    DiscoveryTracker(Path path, Logger logger) {
        super(path);
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
                JsonArray flat = e.getValue().getAsJsonArray();
                LongOpenHashSet set = new LongOpenHashSet(flat.size() / 2);
                for (int i = 0; i + 1 < flat.size(); i += 2) {
                    set.add(Chunks.key(flat.get(i).getAsInt(), flat.get(i + 1).getAsInt()));
                }
                chunksByDimension.put(e.getKey(), set);
            }
        }
    }

    /** Markiert ein Quadrat aus Chunks rund um (centerX, centerZ) als entdeckt. */
    synchronized void markArea(String dimension, int centerX, int centerZ, int radius) {
        LongOpenHashSet set = chunksByDimension.computeIfAbsent(dimension, d -> new LongOpenHashSet());
        boolean added = false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                added |= set.add(Chunks.key(centerX + dx, centerZ + dz));
            }
        }
        if (added) changed();
    }

    synchronized boolean isDiscovered(String dimension, long chunkKey) {
        LongOpenHashSet set = chunksByDimension.get(dimension);
        return set != null && set.contains(chunkKey);
    }

    synchronized int count(String dimension) {
        LongOpenHashSet set = chunksByDimension.get(dimension);
        return set == null ? 0 : set.size();
    }

    /** Alle entdeckten Chunks als flache Liste [x0, z0, x1, z1, ...] – spart Platz in der Antwort. */
    synchronized int[] export(String dimension) {
        LongOpenHashSet set = chunksByDimension.get(dimension);
        if (set == null) return new int[0];
        int[] out = new int[set.size() * 2];
        int i = 0;
        for (LongIterator it = set.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            out[i++] = Chunks.x(key);
            out[i++] = Chunks.z(key);
        }
        return out;
    }

    @Override
    protected JsonElement toJson() {
        JsonObject root = new JsonObject();
        chunksByDimension.forEach((dimension, set) -> {
            JsonArray flat = new JsonArray(set.size() * 2);
            for (LongIterator it = set.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                flat.add(Chunks.x(key));
                flat.add(Chunks.z(key));
            }
            root.add(dimension, flat);
        });
        return root;
    }
}
