package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Alle Länder (Claims) auf dem Server. Ein Land besteht aus zusammenhängenden Chunks
 * in einer Dimension und hat einen oder mehrere gleichberechtigte Besitzer.
 *
 * Regeln:
 * - Jeder Besitzer darf einladen. Der Eingeladene muss annehmen.
 * - Niemand kann einen anderen Besitzer rauswerfen – man kann nur selbst austreten.
 *   Nur Admins können Besitzer entfernen oder Länder löschen.
 * - Tritt der letzte Besitzer aus, wird das Land frei.
 * - Claims sind nur eine Anzeige: Im Spiel wird nichts geschützt.
 */
final class LandStore extends JsonStore {
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _\\-]{2,24}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final String[] PALETTE = {
            "#e57373", "#64b5f6", "#81c784", "#ffb74d", "#ba68c8",
            "#4dd0e1", "#f06292", "#aed581", "#ffd54f", "#90a4ae"
    };
    private static final int MAX_CHUNKS_PER_REQUEST = 512;
    private static final int MAX_PENDING_INVITES = 10;

    private static final class Land {
        final String id;
        String name;
        final String dimension;
        String color;
        String description = "";
        final long createdAt;
        final UUID createdBy;
        final LinkedHashSet<UUID> owners = new LinkedHashSet<>();
        final LinkedHashSet<UUID> invites = new LinkedHashSet<>();
        final LongOpenHashSet chunks = new LongOpenHashSet();

        Land(String id, String name, String dimension, String color, long createdAt, UUID createdBy) {
            this.id = id;
            this.name = name;
            this.dimension = dimension;
            this.color = color;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
        }
    }

    /** Unveränderliche Kopie eines Landes, z. B. für BlueMap. */
    record LandView(String id, String name, String dimension, String color, String description,
                    List<UUID> owners, long[] chunks) {}

    private final Map<String, Land> lands = new LinkedHashMap<>();
    private final Map<String, Long2ObjectOpenHashMap<Land>> chunkIndex = new HashMap<>();
    private final DiscoveryTracker discovery;
    private final PlayerDirectory players;
    private final PlannerConfig config;
    private volatile Set<String> knownDimensions = Set.of();

    LandStore(Path path, Logger logger, DiscoveryTracker discovery, PlayerDirectory players, PlannerConfig config) {
        super(path);
        this.discovery = discovery;
        this.players = players;
        this.config = config;
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("lands")) {
            for (JsonElement el : json.getAsJsonObject().getAsJsonArray("lands")) {
                try {
                    loadLand(el.getAsJsonObject());
                } catch (Exception e) {
                    logger.warn("[World Planner] Ein Land in {} ist kaputt und wird übersprungen: {}", path, e.getMessage());
                }
            }
        }
    }

    private void loadLand(JsonObject o) {
        Land land = new Land(o.get("id").getAsString(), o.get("name").getAsString(), o.get("dimension").getAsString(),
                o.get("color").getAsString(), o.get("createdAt").getAsLong(), UUID.fromString(o.get("createdBy").getAsString()));
        if (o.has("description")) land.description = o.get("description").getAsString();
        o.getAsJsonArray("owners").forEach(u -> land.owners.add(UUID.fromString(u.getAsString())));
        if (o.has("invites")) o.getAsJsonArray("invites").forEach(u -> land.invites.add(UUID.fromString(u.getAsString())));
        JsonArray flat = o.getAsJsonArray("chunks");
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            long key = Chunks.key(flat.get(i).getAsInt(), flat.get(i + 1).getAsInt());
            if (index(land.dimension).containsKey(key)) continue; // doppelt geclaimt? Erster gewinnt.
            land.chunks.add(key);
            index(land.dimension).put(key, land);
        }
        if (!land.owners.isEmpty() && !land.chunks.isEmpty()) lands.put(land.id, land);
    }

    void setKnownDimensions(Set<String> dimensions) {
        knownDimensions = Set.copyOf(dimensions);
    }

    // ------------------------------------------------------------------ Abfragen

    synchronized Optional<String> landNameAt(String dimension, long chunk) {
        Land land = index(dimension).get(chunk);
        return land == null ? Optional.empty() : Optional.of(land.name);
    }

    synchronized String describeChunk(String dimension, long chunk) {
        Land land = index(dimension).get(chunk);
        if (land == null) return null;
        return "»" + land.name + "« (Besitzer: " + ownerNames(land) + ")";
    }

    synchronized Optional<String> findIdByName(String name) {
        Land land = byName(name);
        return land == null ? Optional.empty() : Optional.of(land.id);
    }

    synchronized List<String> namesOwnedBy(UUID uuid) {
        return lands.values().stream().filter(l -> l.owners.contains(uuid)).map(l -> l.name).toList();
    }

    synchronized List<String> namesInviting(UUID uuid) {
        return lands.values().stream().filter(l -> l.invites.contains(uuid)).map(l -> l.name).toList();
    }

    synchronized List<String> allNames() {
        return lands.values().stream().map(l -> l.name).toList();
    }

    synchronized int ownedChunkCount(UUID uuid) {
        int sum = 0;
        for (Land land : lands.values()) if (land.owners.contains(uuid)) sum += land.chunks.size();
        return sum;
    }

    /** Eigene Länder, die direkt an diesen Chunk grenzen (für "/land erweitern" ohne Namen). */
    synchronized List<String> ownLandsNextTo(UUID uuid, String dimension, long chunk) {
        Set<String> names = new LinkedHashSet<>();
        int x = Chunks.x(chunk), z = Chunks.z(chunk);
        for (long n : new long[]{Chunks.key(x + 1, z), Chunks.key(x - 1, z), Chunks.key(x, z + 1), Chunks.key(x, z - 1)}) {
            Land land = index(dimension).get(n);
            if (land != null && land.owners.contains(uuid)) names.add(land.name);
        }
        return List.copyOf(names);
    }

    synchronized String summary(String id) {
        Land land = require(id);
        return "»" + land.name + "« – " + land.chunks.size() + " Chunks, Besitzer: " + ownerNames(land)
                + (land.invites.isEmpty() ? "" : ", eingeladen: " + names(land.invites));
    }

    synchronized List<LandView> views() {
        List<LandView> out = new ArrayList<>(lands.size());
        for (Land land : lands.values()) {
            out.add(new LandView(land.id, land.name, land.dimension, land.color, land.description,
                    List.copyOf(land.owners), land.chunks.toLongArray()));
        }
        return out;
    }

    // ------------------------------------------------------------------ Ändern

    synchronized String create(UUID actor, boolean admin, String rawName, String dimension, long[] chunks) {
        String name = validName(rawName);
        if (byName(name) != null) throw PlannerException.conflict("Es gibt schon ein Land mit dem Namen »" + name + "«.");
        if (!knownDimensions.isEmpty() && !knownDimensions.contains(dimension)) {
            throw PlannerException.badRequest("Unbekannte Dimension: " + dimension);
        }
        LongOpenHashSet wanted = checkClaimable(actor, admin, dimension, chunks);
        if (!isConnected(wanted)) throw PlannerException.badRequest("Die Chunks eines Landes müssen zusammenhängen (Seite an Seite).");

        String color = PALETTE[Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), PALETTE.length)];
        Land land = new Land(UUID.randomUUID().toString(), name, dimension, color, System.currentTimeMillis(), actor);
        land.owners.add(actor);
        for (LongIterator it = wanted.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            land.chunks.add(key);
            index(dimension).put(key, land);
        }
        lands.put(land.id, land);
        changed();
        return land.id;
    }

    synchronized int addChunks(UUID actor, boolean admin, String id, long[] chunks) {
        Land land = require(id);
        requireOwner(land, actor);
        LongOpenHashSet wanted = checkClaimable(actor, admin, land.dimension, chunks);
        LongOpenHashSet union = new LongOpenHashSet(land.chunks);
        union.addAll(wanted);
        if (!isConnected(union)) {
            throw PlannerException.badRequest("Neue Chunks müssen an »" + land.name + "« angrenzen (Seite an Seite).");
        }
        for (LongIterator it = wanted.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            land.chunks.add(key);
            index(land.dimension).put(key, land);
        }
        changed();
        return wanted.size();
    }

    /** Entfernt Chunks. Ist das Land danach leer, wird es gelöscht. Gibt true zurück, wenn das Land gelöscht wurde. */
    synchronized boolean removeChunks(UUID actor, boolean admin, String id, long[] chunks) {
        Land land = require(id);
        if (!admin) requireOwner(land, actor);
        if (chunks.length == 0 || chunks.length > MAX_CHUNKS_PER_REQUEST) throw PlannerException.badRequest("Ungültige Anzahl Chunks.");
        for (long key : chunks) {
            if (!land.chunks.contains(key)) {
                throw PlannerException.badRequest("Chunk " + Chunks.x(key) + "/" + Chunks.z(key) + " gehört nicht zu »" + land.name + "«.");
            }
        }
        for (long key : chunks) {
            land.chunks.remove(key);
            index(land.dimension).remove(key);
        }
        changed();
        if (land.chunks.isEmpty()) {
            lands.remove(land.id);
            return true;
        }
        return false;
    }

    synchronized void edit(UUID actor, boolean admin, String id, String newName, String newColor, String newDescription) {
        Land land = require(id);
        if (!admin) requireOwner(land, actor);
        if (newName != null) {
            String name = validName(newName);
            Land other = byName(name);
            if (other != null && other != land) throw PlannerException.conflict("Es gibt schon ein Land mit dem Namen »" + name + "«.");
            land.name = name;
        }
        if (newColor != null) {
            if (!COLOR.matcher(newColor).matches()) throw PlannerException.badRequest("Farbe muss so aussehen: #a1b2c3");
            land.color = newColor.toLowerCase(Locale.ROOT);
        }
        if (newDescription != null) {
            String d = newDescription.strip();
            if (d.length() > 200) throw PlannerException.badRequest("Beschreibung ist zu lang (max. 200 Zeichen).");
            land.description = d;
        }
        changed();
    }

    synchronized void invite(UUID actor, String id, UUID target) {
        Land land = require(id);
        requireOwner(land, actor);
        if (land.owners.contains(target)) throw PlannerException.conflict(players.name(target) + " besitzt »" + land.name + "« schon.");
        if (land.invites.contains(target)) throw PlannerException.conflict(players.name(target) + " ist schon eingeladen.");
        if (land.invites.size() >= MAX_PENDING_INVITES) throw PlannerException.badRequest("Zu viele offene Einladungen für dieses Land.");
        land.invites.add(target);
        changed();
    }

    synchronized void uninvite(UUID actor, String id, UUID target) {
        Land land = require(id);
        requireOwner(land, actor);
        if (!land.invites.remove(target)) throw PlannerException.notFound(players.name(target) + " ist nicht eingeladen.");
        changed();
    }

    synchronized void accept(UUID actor, boolean admin, String id) {
        Land land = require(id);
        if (!land.invites.contains(actor)) throw PlannerException.notFound("Du hast keine Einladung für »" + land.name + "«.");
        int max = config.maxChunksPerPlayer();
        if (!admin && ownedChunkCount(actor) + land.chunks.size() > max) {
            throw PlannerException.badRequest("Dann hättest du mehr als " + max + " Chunks. Gib erst eigenes Land ab.");
        }
        land.invites.remove(actor);
        land.owners.add(actor);
        changed();
    }

    synchronized void decline(UUID actor, String id) {
        Land land = require(id);
        if (!land.invites.remove(actor)) throw PlannerException.notFound("Du hast keine Einladung für »" + land.name + "«.");
        changed();
    }

    /** Austreten. Gibt true zurück, wenn das Land dadurch frei (gelöscht) wurde. */
    synchronized boolean leave(UUID actor, String id) {
        Land land = require(id);
        requireOwner(land, actor);
        land.owners.remove(actor);
        return deleteIfOwnerless(land);
    }

    synchronized void adminDelete(String id) {
        Land land = require(id);
        land.owners.clear();
        deleteIfOwnerless(land);
    }

    synchronized boolean adminRemoveOwner(String id, UUID target) {
        Land land = require(id);
        if (!land.owners.remove(target)) throw PlannerException.notFound(players.name(target) + " besitzt »" + land.name + "« nicht.");
        return deleteIfOwnerless(land);
    }

    // ------------------------------------------------------------------ Webseite

    synchronized JsonArray toApiJson(UUID viewer, boolean admin) {
        JsonArray arr = new JsonArray();
        for (Land land : lands.values()) {
            boolean mine = land.owners.contains(viewer);
            JsonObject o = new JsonObject();
            o.addProperty("id", land.id);
            o.addProperty("name", land.name);
            o.addProperty("dimension", land.dimension);
            o.addProperty("color", land.color);
            o.addProperty("description", land.description);
            o.addProperty("createdAt", land.createdAt);
            o.add("owners", playersJson(land.owners));
            o.addProperty("chunkCount", land.chunks.size());
            JsonArray flat = new JsonArray(land.chunks.size() * 2);
            for (LongIterator it = land.chunks.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                flat.add(Chunks.x(key));
                flat.add(Chunks.z(key));
            }
            o.add("chunks", flat);
            o.addProperty("mine", mine);
            o.addProperty("invitedMe", land.invites.contains(viewer));
            // Wer eingeladen ist, sehen nur die Besitzer (und Admins).
            if (mine || admin) o.add("invites", playersJson(land.invites));
            arr.add(o);
        }
        return arr;
    }

    // ------------------------------------------------------------------ Hilfsmethoden

    private LongOpenHashSet checkClaimable(UUID actor, boolean admin, String dimension, long[] chunks) {
        if (chunks.length == 0) throw PlannerException.badRequest("Keine Chunks ausgewählt.");
        if (chunks.length > MAX_CHUNKS_PER_REQUEST) throw PlannerException.badRequest("Zu viele Chunks auf einmal (max. " + MAX_CHUNKS_PER_REQUEST + ").");
        LongOpenHashSet wanted = new LongOpenHashSet(chunks);
        for (LongIterator it = wanted.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            Land owner = index(dimension).get(key);
            if (owner != null) {
                throw PlannerException.conflict("Chunk " + Chunks.x(key) + "/" + Chunks.z(key) + " gehört schon zu »" + owner.name + "«.");
            }
            if (config.claimOnlyDiscovered() && !discovery.isDiscovered(dimension, key)) {
                throw PlannerException.badRequest("Chunk " + Chunks.x(key) + "/" + Chunks.z(key) + " ist noch nicht entdeckt.");
            }
        }
        int max = config.maxChunksPerPlayer();
        int used = ownedChunkCount(actor);
        if (!admin && used + wanted.size() > max) {
            throw PlannerException.badRequest("Du darfst höchstens " + max + " Chunks besitzen (du hast schon " + used + ").");
        }
        return wanted;
    }

    /** Hängen alle Chunks über ihre Seiten zusammen? (Breitensuche) */
    private static boolean isConnected(LongOpenHashSet chunks) {
        if (chunks.isEmpty()) return true;
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long start = chunks.iterator().nextLong();
        queue.enqueue(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            long key = queue.dequeueLong();
            int x = Chunks.x(key), z = Chunks.z(key);
            for (long n : new long[]{Chunks.key(x + 1, z), Chunks.key(x - 1, z), Chunks.key(x, z + 1), Chunks.key(x, z - 1)}) {
                if (chunks.contains(n) && seen.add(n)) queue.enqueue(n);
            }
        }
        return seen.size() == chunks.size();
    }

    private boolean deleteIfOwnerless(Land land) {
        changed();
        if (!land.owners.isEmpty()) return false;
        for (LongIterator it = land.chunks.iterator(); it.hasNext(); ) index(land.dimension).remove(it.nextLong());
        lands.remove(land.id);
        return true;
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (!NAME.matcher(name).matches()) {
            throw PlannerException.badRequest("Name muss 2–24 Zeichen lang sein (Buchstaben, Zahlen, Leerzeichen, _ und -).");
        }
        return name;
    }

    private Land byName(String name) {
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (Land land : lands.values()) if (land.name.toLowerCase(Locale.ROOT).equals(wanted)) return land;
        return null;
    }

    private Land require(String id) {
        Land land = lands.get(id);
        if (land == null) throw PlannerException.notFound("Dieses Land gibt es nicht (mehr).");
        return land;
    }

    private void requireOwner(Land land, UUID actor) {
        if (!land.owners.contains(actor)) throw PlannerException.forbidden("Du besitzt »" + land.name + "« nicht.");
    }

    private Long2ObjectOpenHashMap<Land> index(String dimension) {
        return chunkIndex.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>());
    }

    private String ownerNames(Land land) {
        return names(land.owners);
    }

    private String names(Set<UUID> uuids) {
        List<String> names = new ArrayList<>();
        uuids.forEach(u -> names.add(players.name(u)));
        return String.join(", ", names);
    }

    private JsonArray playersJson(Set<UUID> uuids) {
        JsonArray arr = new JsonArray();
        for (UUID u : uuids) {
            JsonObject p = new JsonObject();
            p.addProperty("uuid", u.toString());
            p.addProperty("name", players.name(u));
            arr.add(p);
        }
        return arr;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        for (Land land : lands.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", land.id);
            o.addProperty("name", land.name);
            o.addProperty("dimension", land.dimension);
            o.addProperty("color", land.color);
            o.addProperty("description", land.description);
            o.addProperty("createdAt", land.createdAt);
            o.addProperty("createdBy", land.createdBy.toString());
            JsonArray owners = new JsonArray();
            land.owners.forEach(u -> owners.add(u.toString()));
            o.add("owners", owners);
            JsonArray invites = new JsonArray();
            land.invites.forEach(u -> invites.add(u.toString()));
            o.add("invites", invites);
            JsonArray flat = new JsonArray();
            for (LongIterator it = land.chunks.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                flat.add(Chunks.x(key));
                flat.add(Chunks.z(key));
            }
            o.add("chunks", flat);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("lands", arr);
        return root;
    }
}
