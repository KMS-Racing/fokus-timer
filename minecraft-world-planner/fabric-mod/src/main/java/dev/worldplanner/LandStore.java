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

/**
 * Alle Länder (Claims) auf dem Server. Ein Land besteht aus zusammenhängenden Chunks
 * in einer Dimension und hat einen oder mehrere gleichberechtigte Besitzer.
 *
 * Regeln:
 * - Jeder Besitzer darf einladen. Der Eingeladene muss annehmen.
 * - Niemand kann einen anderen Besitzer rauswerfen – man kann nur selbst austreten.
 *   Löschen darf ein Besitzer nur, wenn er der einzige ist. Nur Admins können Besitzer entfernen.
 * - Tritt der letzte Besitzer aus, wird das Land frei.
 * - Ein Land kann einem Team zugeordnet werden (von einem Besitzer, der im Team ist). Die Besitzer bleiben
 *   die einzigen, die das Land ändern dürfen – das Team zeigt es nur auf seiner Seite und kann Projekte verknüpfen.
 * - Überschneidungen gibt es nicht: Jeder Chunk gehört höchstens einem Land (wird hier erzwungen).
 * - Claims sind nur eine Anzeige: Im Spiel wird nichts geschützt.
 */
final class LandStore extends JsonStore {
    private static final String[] PALETTE = {
            "#e57373", "#64b5f6", "#81c784", "#ffb74d", "#ba68c8",
            "#4dd0e1", "#f06292", "#aed581", "#ffd54f", "#90a4ae"
    };
    private static final int MAX_CHUNKS_PER_REQUEST = 512;
    private static final int MAX_PENDING_INVITES = 10;
    private static final int SCHEMA = 2;

    private static final class Land {
        final String id;
        String name;
        final String dimension;
        String color;
        String description = "";
        String notes = "";
        String teamId;
        final long createdAt;
        long updatedAt; // 0 = unbekannt (Länder aus Version 1)
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
                    List<UUID> owners, long[] chunks, String teamId) {}

    private final Map<String, Land> lands = new LinkedHashMap<>();
    private final Map<String, Long2ObjectOpenHashMap<Land>> chunkIndex = new HashMap<>();
    private final DiscoveryTracker discovery;
    private final PlayerDirectory players;
    private final PlannerConfig config;
    private TeamStore teams;
    private volatile Set<String> knownDimensions = Set.of();

    LandStore(Path path, Logger logger, DiscoveryTracker discovery, PlayerDirectory players, PlannerConfig config) {
        super(path);
        this.discovery = discovery;
        this.players = players;
        this.config = config;
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("lands")) {
            backupBeforeMigration(logger, json.getAsJsonObject(), SCHEMA);
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
        if (o.has("notes")) land.notes = o.get("notes").getAsString();
        if (o.has("teamId") && !o.get("teamId").isJsonNull()) land.teamId = o.get("teamId").getAsString();
        land.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0;
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

    /** Für die Team-Zuordnung. Reihenfolge der Sperren: Land -> Team (Teams fragen nie hier nach). */
    void setTeams(TeamStore teams) {
        this.teams = teams;
    }

    // ------------------------------------------------------------------ Abfragen

    synchronized boolean exists(String id) {
        return lands.containsKey(id);
    }

    synchronized boolean isOwner(UUID player, String id) {
        Land land = lands.get(id);
        return land != null && land.owners.contains(player);
    }

    synchronized String teamOf(String id) {
        Land land = lands.get(id);
        return land == null ? null : land.teamId;
    }

    synchronized String nameOf(String id) {
        Land land = lands.get(id);
        return land == null ? null : land.name;
    }

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

    synchronized int ownedLandCount(UUID uuid) {
        return (int) lands.values().stream().filter(l -> l.owners.contains(uuid)).count();
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
        return "»" + land.name + "« – " + land.chunks.size() + (land.chunks.size() == 1 ? " Chunk" : " Chunks")
                + ", Besitzer: " + ownerNames(land) + (land.invites.isEmpty() ? "" : ", eingeladen: " + names(land.invites));
    }

    synchronized List<LandView> views() {
        List<LandView> out = new ArrayList<>(lands.size());
        for (Land land : lands.values()) {
            out.add(new LandView(land.id, land.name, land.dimension, land.color, land.description,
                    List.copyOf(land.owners), land.chunks.toLongArray(), land.teamId));
        }
        return out;
    }

    // ------------------------------------------------------------------ Ändern

    synchronized String create(UUID actor, boolean admin, String rawName, String dimension, long[] chunks) {
        return create(actor, admin, rawName, dimension, chunks, null, null);
    }

    synchronized String create(UUID actor, boolean admin, String rawName, String dimension, long[] chunks, String color, String description) {
        String name = Validate.name(rawName, 2, 24, "Name");
        if (byName(name) != null) throw PlannerException.conflict("Es gibt schon ein Land mit dem Namen »" + name + "«.");
        if (!knownDimensions.isEmpty() && !knownDimensions.contains(dimension)) {
            throw PlannerException.badRequest("Unbekannte Dimension: " + dimension);
        }
        String chosenColor = color == null || color.isBlank()
                ? PALETTE[Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), PALETTE.length)]
                : Validate.color(color);
        String desc = Validate.text(description, 200, "Beschreibung");
        LongOpenHashSet wanted = checkClaimable(actor, admin, dimension, chunks);
        if (!isConnected(wanted)) throw PlannerException.badRequest("Die Chunks eines Landes müssen zusammenhängen (Seite an Seite).");

        long now = System.currentTimeMillis();
        Land land = new Land(UUID.randomUUID().toString(), name, dimension, chosenColor, now, actor);
        land.description = desc;
        land.updatedAt = now;
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
        touch(land);
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
        touch(land);
        if (land.chunks.isEmpty()) {
            lands.remove(land.id);
            return true;
        }
        return false;
    }

    synchronized void edit(UUID actor, boolean admin, String id, String newName, String newColor, String newDescription) {
        edit(actor, admin, id, newName, newColor, newDescription, null);
    }

    synchronized void edit(UUID actor, boolean admin, String id, String newName, String newColor, String newDescription, String newNotes) {
        Land land = require(id);
        if (!admin) requireOwner(land, actor);
        // Erst alles prüfen, dann übernehmen – ein Fehler soll nichts halb ändern
        String name = newName == null ? land.name : Validate.name(newName, 2, 24, "Name");
        Land other = byName(name);
        if (other != null && other != land) throw PlannerException.conflict("Es gibt schon ein Land mit dem Namen »" + name + "«.");
        String color = newColor == null ? land.color : Validate.color(newColor);
        String description = newDescription == null ? land.description : Validate.text(newDescription, 200, "Beschreibung");
        String notes = newNotes == null ? land.notes : Validate.text(newNotes, 1000, "Notizen");
        land.name = name;
        land.color = color;
        land.description = description;
        land.notes = notes;
        touch(land);
    }

    /** Löschen geht nur als einziger Besitzer (mit Mitbesitzern: austreten). Admins dürfen immer. */
    synchronized void delete(UUID actor, boolean admin, String id) {
        Land land = require(id);
        if (!admin) {
            requireOwner(land, actor);
            if (land.owners.size() > 1) {
                throw PlannerException.conflict("»" + land.name + "« hat noch andere Besitzer. Du kannst nur austreten – löschen geht erst, wenn du allein bist.");
            }
        }
        land.owners.clear();
        deleteIfOwnerless(land);
    }

    synchronized void invite(UUID actor, String id, UUID target) {
        Land land = require(id);
        requireOwner(land, actor);
        if (land.owners.contains(target)) throw PlannerException.conflict(players.name(target) + " besitzt »" + land.name + "« schon.");
        if (land.invites.contains(target)) throw PlannerException.conflict(players.name(target) + " ist schon eingeladen.");
        if (land.invites.size() >= MAX_PENDING_INVITES) throw PlannerException.badRequest("Zu viele offene Einladungen für dieses Land.");
        land.invites.add(target);
        touch(land);
    }

    synchronized void uninvite(UUID actor, String id, UUID target) {
        Land land = require(id);
        requireOwner(land, actor);
        if (!land.invites.remove(target)) throw PlannerException.notFound(players.name(target) + " ist nicht eingeladen.");
        touch(land);
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
        touch(land);
    }

    synchronized void decline(UUID actor, String id) {
        Land land = require(id);
        if (!land.invites.remove(actor)) throw PlannerException.notFound("Du hast keine Einladung für »" + land.name + "«.");
        touch(land);
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

    /**
     * Land einem Team zuordnen (teamId) oder die Zuordnung lösen (null).
     * Zuordnen: ein Besitzer, der im Team mindestens Mitglied ist. Lösen: ein Besitzer, ein Team-Admin oder ein Server-Admin.
     */
    synchronized void setTeam(UUID actor, boolean admin, String id, String teamId) {
        Land land = require(id);
        if (teamId == null || teamId.isEmpty()) {
            boolean teamAdmin = land.teamId != null && teams.role(actor, land.teamId) != null
                    && teams.role(actor, land.teamId).rank >= TeamStore.Role.ADMIN.rank;
            if (!admin && !teamAdmin) requireOwner(land, actor);
            land.teamId = null;
        } else {
            requireOwner(land, actor);
            TeamStore.Role role = teams.role(actor, teamId);
            if (role == null || role.rank < TeamStore.Role.MEMBER.rank) {
                throw PlannerException.forbidden("Du kannst ein Land nur einem Team zuordnen, in dem du mindestens Mitglied bist.");
            }
            land.teamId = teamId;
        }
        touch(land);
    }

    /** Team aufgelöst: Zuordnungen lösen, die Länder selbst bleiben. */
    synchronized void dropTeam(String teamId) {
        for (Land land : lands.values()) {
            if (teamId.equals(land.teamId)) {
                land.teamId = null;
                touch(land);
            }
        }
    }

    /** Nach Austritt/Rauswurf: Zuordnung lösen, wenn kein Besitzer mehr im Team ist. */
    synchronized void recheckTeam(String teamId) {
        for (Land land : lands.values()) {
            if (teamId.equals(land.teamId) && land.owners.stream().noneMatch(o -> teams.role(o, teamId) != null)) {
                land.teamId = null;
                touch(land);
            }
        }
    }

    /**
     * Vorschau vor dem Speichern (ändert nichts): Welche Chunks gehören schon jemandem, welche sind unentdeckt,
     * hängt alles zusammen, wie viele Chunks hätte man danach? landId = bestehendes Land, das erweitert werden soll.
     */
    synchronized JsonObject check(UUID actor, boolean admin, String landId, String dimension, long[] chunks) {
        Land target = landId == null || landId.isEmpty() ? null : require(landId);
        if (target != null) dimension = target.dimension;
        LongOpenHashSet wanted = new LongOpenHashSet(chunks);
        JsonArray conflicts = new JsonArray();
        JsonArray undiscovered = new JsonArray();
        Map<String, Integer> perLand = new LinkedHashMap<>();
        for (LongIterator it = wanted.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            Land owner = index(dimension).get(key);
            if (owner != null && owner != target) {
                JsonObject c = new JsonObject();
                c.addProperty("x", Chunks.x(key));
                c.addProperty("z", Chunks.z(key));
                c.addProperty("land", owner.name);
                conflicts.add(c);
                perLand.merge(owner.name, 1, Integer::sum);
            } else if (owner == null && config.claimOnlyDiscovered() && !discovery.isDiscovered(dimension, key)) {
                JsonObject c = new JsonObject();
                c.addProperty("x", Chunks.x(key));
                c.addProperty("z", Chunks.z(key));
                undiscovered.add(c);
            }
        }
        LongOpenHashSet union = new LongOpenHashSet(wanted);
        if (target != null) union.addAll(target.chunks);
        boolean connected = isConnected(union);
        int used = ownedChunkCount(actor);
        int added = target == null ? wanted.size() : (int) wanted.longStream().filter(k -> !target.chunks.contains(k)).count();
        JsonObject o = new JsonObject();
        o.addProperty("chunkCount", wanted.size());
        o.add("conflicts", conflicts);
        JsonObject byLand = new JsonObject();
        perLand.forEach(byLand::addProperty);
        o.add("conflictsByLand", byLand);
        o.add("undiscovered", undiscovered);
        o.addProperty("connected", connected);
        o.addProperty("usedChunks", used);
        o.addProperty("chunksAfter", used + added);
        o.addProperty("maxChunks", config.maxChunksPerPlayer());
        o.addProperty("overLimit", !admin && used + added > config.maxChunksPerPlayer());
        o.addProperty("tooMany", wanted.size() > MAX_CHUNKS_PER_REQUEST);
        boolean fine = wanted.size() > 0 && conflicts.isEmpty() && undiscovered.isEmpty() && connected
                && !o.get("overLimit").getAsBoolean() && wanted.size() <= MAX_CHUNKS_PER_REQUEST;
        o.addProperty("ok", fine);
        if (!wanted.isEmpty()) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (LongIterator it = wanted.iterator(); it.hasNext(); ) {
                long k = it.nextLong();
                minX = Math.min(minX, Chunks.x(k));
                maxX = Math.max(maxX, Chunks.x(k));
                minZ = Math.min(minZ, Chunks.z(k));
                maxZ = Math.max(maxZ, Chunks.z(k));
            }
            JsonObject bounds = new JsonObject();
            bounds.addProperty("minX", minX * 16);
            bounds.addProperty("maxX", maxX * 16 + 15);
            bounds.addProperty("minZ", minZ * 16);
            bounds.addProperty("maxZ", maxZ * 16 + 15);
            o.add("bounds", bounds);
        }
        return o;
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
            o.addProperty("updatedAt", land.updatedAt); // 0 = unbekannt
            o.add("createdBy", playerJson(land.createdBy));
            if (land.teamId != null) o.addProperty("teamId", land.teamId);
            o.add("owners", playersJson(land.owners));
            o.addProperty("chunkCount", land.chunks.size());
            JsonArray flat = new JsonArray(land.chunks.size() * 2);
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (LongIterator it = land.chunks.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                int x = Chunks.x(key), z = Chunks.z(key);
                flat.add(x);
                flat.add(z);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            o.add("chunks", flat);
            // Grenzen in Blockkoordinaten (einschließlich)
            JsonObject bounds = new JsonObject();
            bounds.addProperty("minX", minX * 16);
            bounds.addProperty("maxX", maxX * 16 + 15);
            bounds.addProperty("minZ", minZ * 16);
            bounds.addProperty("maxZ", maxZ * 16 + 15);
            o.add("bounds", bounds);
            o.addProperty("mine", mine);
            o.addProperty("invitedMe", land.invites.contains(viewer));
            // Wer eingeladen ist und die Notizen sehen nur die Besitzer (und Admins).
            if (mine || admin) {
                o.add("invites", playersJson(land.invites));
                o.addProperty("notes", land.notes);
            }
            JsonObject can = new JsonObject();
            can.addProperty("edit", mine || admin);
            can.addProperty("delete", (mine && land.owners.size() == 1) || admin);
            can.addProperty("leave", mine);
            o.add("can", can);
            arr.add(o);
        }
        return arr;
    }

    /** Eigene Länder für den Export (Notizen inklusive). */
    synchronized JsonArray exportOwn(UUID owner) {
        JsonArray arr = new JsonArray();
        for (Land land : lands.values()) if (land.owners.contains(owner)) arr.add(storageJson(land));
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
        touch(land);
        if (!land.owners.isEmpty()) return false;
        for (LongIterator it = land.chunks.iterator(); it.hasNext(); ) index(land.dimension).remove(it.nextLong());
        lands.remove(land.id);
        return true;
    }

    private Land byName(String name) {
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (Land land : lands.values()) if (land.name.toLowerCase(Locale.ROOT).equals(wanted)) return land;
        return null;
    }

    private Land require(String id) {
        Land land = id == null ? null : lands.get(id);
        if (land == null) throw PlannerException.notFound("Dieses Land gibt es nicht (mehr).");
        return land;
    }

    private void requireOwner(Land land, UUID actor) {
        if (!land.owners.contains(actor)) throw PlannerException.forbidden("Du besitzt »" + land.name + "« nicht.");
    }

    private void touch(Land land) {
        land.updatedAt = System.currentTimeMillis();
        changed();
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

    private JsonObject playerJson(UUID u) {
        JsonObject p = new JsonObject();
        p.addProperty("uuid", u.toString());
        p.addProperty("name", players.name(u));
        return p;
    }

    private JsonArray playersJson(Set<UUID> uuids) {
        JsonArray arr = new JsonArray();
        for (UUID u : uuids) arr.add(playerJson(u));
        return arr;
    }

    private JsonObject storageJson(Land land) {
        JsonObject o = new JsonObject();
        o.addProperty("id", land.id);
        o.addProperty("name", land.name);
        o.addProperty("dimension", land.dimension);
        o.addProperty("color", land.color);
        o.addProperty("description", land.description);
        o.addProperty("notes", land.notes);
        if (land.teamId != null) o.addProperty("teamId", land.teamId);
        o.addProperty("createdAt", land.createdAt);
        o.addProperty("updatedAt", land.updatedAt);
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
        return o;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        for (Land land : lands.values()) arr.add(storageJson(land));
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA);
        root.add("lands", arr);
        return root;
    }
}
