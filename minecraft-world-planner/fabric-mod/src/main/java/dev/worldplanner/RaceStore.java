package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Rennstrecken und ihre Zeiten. Eine Strecke besteht aus Toren (Linien quer zur Fahrtrichtung): beim Rundkurs Start/Ziel
 * plus mindestens zwei Zwischentore, beim Sprint Start, Ziel und beliebig viele dazwischen. Die Tore setzt man im Spiel
 * (/rennen tor), weil man dort steht und in Fahrtrichtung schaut.
 *
 * Werden die Tore oder die Fahrzeug-Regel geändert, bekommt die Strecke eine neue Version: Die alten Bestzeiten bleiben
 * im Archiv, zählen aber nicht mehr (sie wären nicht vergleichbar). Jeder darf jede fertige Strecke fahren; ändern und
 * löschen darf nur, wer sie angelegt hat (oder ein Admin).
 */
final class RaceStore extends JsonStore {
    static final Map<String, String> TYPES = ordered("circuit", "Rundkurs", "sprint", "Sprint (von A nach B)");
    static final Map<String, String> VEHICLES = ordered("any", "beliebig", "foot", "zu Fuß", "boat", "Boot", "horse", "Pferd", "elytra", "Elytra");
    static final int MAX_GATES = 30, MIN_WIDTH = 3, MAX_WIDTH = 64, DEFAULT_WIDTH = 12;
    static final int KEEP_RECENT = 100, KEEP_ARCHIVE = 500;
    /** Tore, die näher beieinander stehen, sind fast sicher ein doppelter Befehl. */
    static final double MIN_GATE_DISTANCE = 4;

    /** Ein Tor: Mitte, Fahrtrichtung (Minecraft-Drehung, 0° = Süden) und Breite in Blöcken. */
    record Gate(double x, double y, double z, float yaw, int width) {}

    record Track(String id, String name, String dimension, String type, String vehicle, UUID owner, long created, int version, List<Gate> gates) {
        boolean ready() {
            return gates.size() >= minGates(type);
        }
    }

    /** Eine gezählte Runde. ms und Abschnitte gerundet auf Millisekunden; clean = ohne Server-Ruckler gemessen. */
    record Lap(UUID player, long ms, long[] sectors, long at, int version, String mode, boolean clean) {}

    /** Was eine neue Runde bedeutet – für die Nachricht im Spiel. */
    record Result(boolean counted, boolean personalBest, boolean record, Long previousBest, Lap recordBefore, int place, int of) {}

    private static final class Data {
        Track track;
        List<Gate> draft; // null = wird gerade nicht gebaut
        UUID editor;
        final Map<UUID, Lap> best = new HashMap<>();
        final Map<UUID, long[]> bestSectors = new HashMap<>();
        final ArrayDeque<Lap> recent = new ArrayDeque<>();
        final List<Lap> archive = new ArrayList<>();
        int laps;
    }

    private final PlannerConfig config;
    private final Map<String, Data> tracks = new LinkedHashMap<>();

    RaceStore(Path path, Logger logger, PlannerConfig config) {
        super(path);
        this.config = config;
        JsonElement json = readOrBackup(logger);
        if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("tracks")) return;
        for (JsonElement e : json.getAsJsonObject().getAsJsonArray("tracks")) {
            try {
                Data d = read(e.getAsJsonObject());
                tracks.put(d.track.id(), d);
            } catch (Exception ex) {
                logger.warn("[World Planner] Eine Strecke in {} ist kaputt und wird übersprungen: {}", path.getFileName(), ex.toString());
            }
        }
    }

    static int minGates(String type) {
        return type.equals("sprint") ? 2 : 3;
    }

    // ------------------------------------------------------------------ Anlegen und bauen

    synchronized String create(UUID owner, boolean admin, String rawName, String dimension, String type, String vehicle) {
        String name = Validate.name(rawName, 2, 32, "Name der Strecke");
        Validate.oneOf(type, TYPES, "Art");
        Validate.oneOf(vehicle, VEHICLES, "Fahrzeug");
        if (findIdByName(name) != null) throw PlannerException.conflict("Es gibt schon eine Strecke namens »" + name + "«.");
        long own = tracks.values().stream().filter(d -> d.track.owner().equals(owner)).count();
        if (!admin && own >= config.maxTracksPerPlayer()) {
            throw PlannerException.badRequest("Du hast schon " + own + " Strecken (Maximum " + config.maxTracksPerPlayer() + ").");
        }
        if (editing(owner) != null) throw PlannerException.conflict("Du baust schon an »" + tracks.get(editing(owner)).track.name() + "«. Erst /rennen fertig oder /rennen abbrechen.");
        Data d = new Data();
        d.track = new Track(UUID.randomUUID().toString(), name, dimension, type, vehicle, owner, System.currentTimeMillis(), 1, List.of());
        d.draft = new ArrayList<>();
        d.editor = owner;
        tracks.put(d.track.id(), d);
        changed();
        return d.track.id();
    }

    /** Tore neu setzen. Bis /rennen fertig gilt die alte Strecke weiter. */
    synchronized Track startEdit(UUID player, boolean admin, String id) {
        Data d = editable(player, admin, id);
        if (d.editor != null && !d.editor.equals(player)) throw PlannerException.conflict("An dieser Strecke baut gerade jemand anderes.");
        if (editing(player) != null && !id.equals(editing(player))) throw PlannerException.conflict("Du baust schon an einer anderen Strecke.");
        d.draft = new ArrayList<>();
        d.editor = player;
        changed();
        return d.track;
    }

    /** @return so viele Tore hat die Strecke jetzt */
    synchronized int addGate(UUID player, String id, Gate gate) {
        Data d = drafting(player, id);
        if (d.draft.size() >= MAX_GATES) throw PlannerException.badRequest("Höchstens " + MAX_GATES + " Tore.");
        Validate.range(gate.width(), MIN_WIDTH, MAX_WIDTH, "Breite");
        for (Gate g : d.draft) {
            if (Math.hypot(g.x() - gate.x(), g.z() - gate.z()) < MIN_GATE_DISTANCE && Math.abs(g.y() - gate.y()) < RaceTimer.VERTICAL) {
                throw PlannerException.badRequest("Hier steht schon ein Tor. Geh ein Stück weiter (mindestens " + (int) MIN_GATE_DISTANCE + " Blöcke).");
            }
        }
        d.draft.add(gate);
        changed();
        return d.draft.size();
    }

    synchronized int removeLastGate(UUID player, String id) {
        Data d = drafting(player, id);
        if (d.draft.isEmpty()) throw PlannerException.badRequest("Es gibt noch kein Tor.");
        d.draft.removeLast();
        changed();
        return d.draft.size();
    }

    /** Fertig gebaut: Ab jetzt gelten die neuen Tore. Hatte die alte Version Zeiten, kommen sie ins Archiv. */
    synchronized Track finishEdit(UUID player, String id) {
        Data d = drafting(player, id);
        int min = minGates(d.track.type());
        if (d.draft.size() < min) {
            throw PlannerException.badRequest(d.track.type().equals("sprint") ? "Ein Sprint braucht mindestens Start und Ziel (2 Tore)."
                    : "Ein Rundkurs braucht Start/Ziel und mindestens zwei Tore dazwischen (3 Tore) – sonst könnte man an der Linie hin und her fahren.");
        }
        boolean changedGates = !d.draft.equals(d.track.gates());
        int version = d.track.version();
        if (changedGates && !d.track.gates().isEmpty()) version = archive(d);
        d.track = new Track(d.track.id(), d.track.name(), d.track.dimension(), d.track.type(), d.track.vehicle(), d.track.owner(),
                d.track.created(), version, List.copyOf(d.draft));
        d.draft = null;
        d.editor = null;
        changed();
        return d.track;
    }

    /** Bauen abbrechen. Eine ganz neue Strecke ohne Tore verschwindet dabei wieder. @return true = gelöscht */
    synchronized boolean cancelEdit(UUID player, String id) {
        Data d = drafting(player, id);
        d.draft = null;
        d.editor = null;
        boolean gone = d.track.gates().isEmpty();
        if (gone) tracks.remove(id);
        changed();
        return gone;
    }

    /** An welcher Strecke baut der Spieler gerade? */
    synchronized String editing(UUID player) {
        for (Data d : tracks.values()) if (player.equals(d.editor)) return d.track.id();
        return null;
    }

    synchronized List<Gate> draft(String id) {
        Data d = tracks.get(id);
        return d == null || d.draft == null ? List.of() : List.copyOf(d.draft);
    }

    synchronized void rename(UUID player, boolean admin, String id, String rawName) {
        Data d = editable(player, admin, id);
        String name = Validate.name(rawName, 2, 32, "Name der Strecke");
        String other = findIdByName(name);
        if (other != null && !other.equals(id)) throw PlannerException.conflict("Es gibt schon eine Strecke namens »" + name + "«.");
        d.track = new Track(id, name, d.track.dimension(), d.track.type(), d.track.vehicle(), d.track.owner(), d.track.created(),
                d.track.version(), d.track.gates());
        changed();
    }

    /** Andere Fahrzeug-Regel = andere Zeiten: neue Version, alte Bestzeiten ins Archiv. */
    synchronized void setVehicle(UUID player, boolean admin, String id, String vehicle) {
        Data d = editable(player, admin, id);
        Validate.oneOf(vehicle, VEHICLES, "Fahrzeug");
        if (vehicle.equals(d.track.vehicle())) return;
        int version = archive(d);
        d.track = new Track(id, d.track.name(), d.track.dimension(), d.track.type(), vehicle, d.track.owner(), d.track.created(),
                version, d.track.gates());
        changed();
    }

    synchronized String delete(UUID player, boolean admin, String id) {
        Data d = editable(player, admin, id);
        tracks.remove(id);
        changed();
        return d.track.name();
    }

    private int archive(Data d) {
        for (Lap l : d.best.values()) {
            d.archive.add(l);
            if (d.archive.size() > KEEP_ARCHIVE) d.archive.removeFirst();
        }
        d.best.clear();
        d.bestSectors.clear();
        d.laps = 0;
        return d.track.version() + 1;
    }

    private Data editable(UUID player, boolean admin, String id) {
        Data d = tracks.get(id);
        if (d == null) throw PlannerException.notFound("Diese Strecke gibt es nicht (mehr).");
        if (!admin && !d.track.owner().equals(player)) throw PlannerException.forbidden("Nur wer die Strecke angelegt hat (oder ein Admin), darf sie ändern.");
        return d;
    }

    private Data drafting(UUID player, String id) {
        Data d = tracks.get(id);
        if (d == null) throw PlannerException.notFound("Diese Strecke gibt es nicht (mehr).");
        if (d.draft == null || !player.equals(d.editor)) throw PlannerException.badRequest("Du baust gerade nicht an dieser Strecke.");
        return d;
    }

    // ------------------------------------------------------------------ Zeiten

    /** Neue Runde eintragen. Zählt nur für die aktuelle Version der Strecke; ungenaue (geruckelte) Runden nur im Verlauf. */
    synchronized Result recordLap(String id, Lap lap) {
        Data d = tracks.get(id);
        if (d == null || d.track.version() != lap.version()) return new Result(false, false, false, null, null, 0, 0);
        d.recent.addFirst(lap);
        while (d.recent.size() > KEEP_RECENT) d.recent.removeLast();
        changed();
        if (!lap.clean()) return new Result(false, false, false, null, null, 0, 0);
        d.laps++;
        List<Lap> before = leaderboard(d);
        Lap recordBefore = before.isEmpty() ? null : before.getFirst();
        Lap mine = d.best.get(lap.player());
        boolean pb = mine == null || lap.ms() < mine.ms();
        if (pb) d.best.put(lap.player(), lap);
        long[] sectors = d.bestSectors.get(lap.player());
        if (lap.sectors() != null) {
            if (sectors == null || sectors.length != lap.sectors().length) sectors = lap.sectors().clone();
            else for (int i = 0; i < sectors.length; i++) sectors[i] = Math.min(sectors[i], lap.sectors()[i]);
            d.bestSectors.put(lap.player(), sectors);
        }
        List<Lap> board = leaderboard(d);
        int place = 0;
        for (int i = 0; i < board.size(); i++) if (board.get(i).player().equals(lap.player())) place = rank(board, i);
        boolean record = recordBefore == null || lap.ms() < recordBefore.ms();
        return new Result(true, pb, record, mine == null ? null : mine.ms(), recordBefore, place, board.size());
    }

    /** Beste Runde je Spieler, schnellste zuerst (bei Gleichstand gewinnt, wer es früher geschafft hat). */
    private static List<Lap> leaderboard(Data d) {
        List<Lap> out = new ArrayList<>(d.best.values());
        out.sort(Comparator.comparingLong(Lap::ms).thenComparingLong(Lap::at));
        return out;
    }

    /** Platz mit Gleichstand (gleiche Zeit = gleicher Platz). */
    static int rank(List<Lap> board, int index) {
        int i = index;
        while (i > 0 && board.get(i - 1).ms() == board.get(index).ms()) i--;
        return i + 1;
    }

    synchronized List<Lap> leaderboard(String id) {
        Data d = tracks.get(id);
        return d == null ? List.of() : leaderboard(d);
    }

    synchronized Lap personalBest(String id, UUID player) {
        Data d = tracks.get(id);
        return d == null ? null : d.best.get(player);
    }

    synchronized long[] personalSectors(String id, UUID player) {
        Data d = tracks.get(id);
        long[] s = d == null ? null : d.bestSectors.get(player);
        return s == null ? null : s.clone();
    }

    /** Schnellster Abschnitt je Sektor über alle Spieler (für "lila"). */
    synchronized long[] bestSectors(String id) {
        Data d = tracks.get(id);
        if (d == null) return null;
        long[] out = null;
        for (long[] s : d.bestSectors.values()) {
            if (out == null) out = s.clone();
            else if (out.length == s.length) for (int i = 0; i < s.length; i++) out[i] = Math.min(out[i], s[i]);
        }
        return out;
    }

    // ------------------------------------------------------------------ Abfragen

    synchronized Track track(String id) {
        Data d = tracks.get(id);
        return d == null ? null : d.track;
    }

    synchronized String findIdByName(String name) {
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (Data d : tracks.values()) if (d.track.name().toLowerCase(Locale.ROOT).equals(wanted)) return d.track.id();
        return null;
    }

    synchronized List<String> names(boolean readyOnly) {
        List<String> out = new ArrayList<>();
        for (Data d : tracks.values()) if (!readyOnly || d.track.ready()) out.add(d.track.name());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    synchronized List<String> namesOwnedBy(UUID player) {
        List<String> out = new ArrayList<>();
        for (Data d : tracks.values()) if (d.track.owner().equals(player)) out.add(d.track.name());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    synchronized JsonObject listJson(UUID viewer, Function<UUID, String> names) {
        JsonArray arr = new JsonArray();
        for (Data d : tracks.values()) {
            JsonObject o = summary(d, names);
            Lap mine = d.best.get(viewer);
            if (mine != null) o.addProperty("myBestMs", mine.ms());
            o.addProperty("mine", d.track.owner().equals(viewer));
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("tracks", arr);
        o.add("types", map(TYPES));
        o.add("vehicles", map(VEHICLES));
        return o;
    }

    synchronized JsonObject trackJson(String id, UUID viewer, boolean admin, Function<UUID, String> names) {
        Data d = tracks.get(id);
        if (d == null) throw PlannerException.notFound("Diese Strecke gibt es nicht (mehr).");
        JsonObject o = summary(d, names);
        o.addProperty("mine", d.track.owner().equals(viewer));
        o.addProperty("canEdit", admin || d.track.owner().equals(viewer));
        o.addProperty("created", d.track.created());
        JsonArray gates = new JsonArray();
        for (Gate g : d.track.gates()) {
            JsonObject x = new JsonObject();
            x.addProperty("x", g.x());
            x.addProperty("y", g.y());
            x.addProperty("z", g.z());
            x.addProperty("yaw", g.yaw());
            x.addProperty("width", g.width());
            gates.add(x);
        }
        o.add("gates", gates);
        List<Lap> board = leaderboard(d);
        JsonArray lb = new JsonArray();
        for (int i = 0; i < board.size(); i++) {
            JsonObject x = lapJson(board.get(i), names);
            x.addProperty("place", rank(board, i));
            lb.add(x);
        }
        o.add("leaderboard", lb);
        long[] best = bestSectors(id);
        o.add("bestSectors", best == null ? new JsonArray() : longs(best));
        long[] mine = d.bestSectors.get(viewer);
        if (mine != null) o.add("mySectors", longs(mine));
        JsonArray recent = new JsonArray();
        for (Lap l : d.recent) {
            JsonObject x = lapJson(l, names);
            x.addProperty("current", l.version() == d.track.version());
            recent.add(x);
        }
        o.add("recent", recent);
        o.addProperty("archived", d.archive.size());
        return o;
    }

    private static JsonObject summary(Data d, Function<UUID, String> names) {
        Track t = d.track;
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("name", t.name());
        o.addProperty("dimension", t.dimension());
        o.addProperty("type", t.type());
        o.addProperty("vehicle", t.vehicle());
        o.addProperty("owner", t.owner().toString());
        o.addProperty("ownerName", names.apply(t.owner()));
        o.addProperty("version", t.version());
        o.addProperty("gateCount", t.gates().size());
        o.addProperty("ready", t.ready());
        o.addProperty("building", d.draft != null);
        o.addProperty("laps", d.laps);
        o.addProperty("drivers", d.best.size());
        if (!t.gates().isEmpty()) {
            Gate start = t.gates().getFirst();
            o.addProperty("startX", Math.floor(start.x()));
            o.addProperty("startZ", Math.floor(start.z()));
        }
        List<Lap> board = leaderboard(d);
        if (!board.isEmpty()) o.add("record", lapJson(board.getFirst(), names));
        return o;
    }

    private static JsonObject lapJson(Lap l, Function<UUID, String> names) {
        JsonObject x = new JsonObject();
        x.addProperty("uuid", l.player().toString());
        x.addProperty("name", names.apply(l.player()));
        x.addProperty("ms", l.ms());
        if (l.sectors() != null) x.add("sectors", longs(l.sectors()));
        x.addProperty("at", l.at());
        x.addProperty("mode", l.mode());
        x.addProperty("clean", l.clean());
        return x;
    }

    // ------------------------------------------------------------------ Zeit als Text

    /** 83456 ms → "1:23,46", 9870 → "9,87" (Hundertstel – genauer kann Minecraft nicht messen). */
    static String time(double ms) {
        long cs = Math.round(ms / 10);
        long m = cs / 6000, s = (cs / 100) % 60, h = cs % 100;
        return (m > 0 ? m + ":" + (s < 10 ? "0" : "") + s : String.valueOf(s)) + "," + (h < 10 ? "0" : "") + h;
    }

    /** Abstand: "+0,32" oder "−0,12". */
    static String delta(double ms) {
        return (ms < 0 ? "−" : "+") + time(Math.abs(ms));
    }

    // ------------------------------------------------------------------ Datei

    private static Data read(JsonObject o) {
        Data d = new Data();
        List<Gate> gates = gates(o.getAsJsonArray("gates"));
        d.track = new Track(o.get("id").getAsString(), o.get("name").getAsString(), o.get("dimension").getAsString(), o.get("type").getAsString(),
                o.get("vehicle").getAsString(), UUID.fromString(o.get("owner").getAsString()), o.get("created").getAsLong(),
                o.get("version").getAsInt(), gates);
        if (o.has("draft")) {
            d.draft = new ArrayList<>(gates(o.getAsJsonArray("draft")));
            d.editor = UUID.fromString(o.get("editor").getAsString());
        }
        if (o.has("best")) for (JsonElement e : o.getAsJsonArray("best")) {
            Lap l = lap(e.getAsJsonArray());
            d.best.put(l.player(), l);
        }
        if (o.has("bestSectors")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("bestSectors").entrySet()) {
            d.bestSectors.put(UUID.fromString(e.getKey()), longsOf(e.getValue().getAsJsonArray()));
        }
        if (o.has("recent")) for (JsonElement e : o.getAsJsonArray("recent")) d.recent.addLast(lap(e.getAsJsonArray()));
        if (o.has("archive")) for (JsonElement e : o.getAsJsonArray("archive")) d.archive.add(lap(e.getAsJsonArray()));
        d.laps = o.has("laps") ? o.get("laps").getAsInt() : 0;
        return d;
    }

    private static List<Gate> gates(JsonArray arr) {
        List<Gate> out = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonArray a = e.getAsJsonArray();
            out.add(new Gate(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsFloat(), a.get(4).getAsInt()));
        }
        return List.copyOf(out);
    }

    private static JsonArray gatesJson(List<Gate> gates) {
        JsonArray arr = new JsonArray();
        for (Gate g : gates) {
            JsonArray a = new JsonArray();
            a.add(g.x());
            a.add(g.y());
            a.add(g.z());
            a.add(g.yaw());
            a.add(g.width());
            arr.add(a);
        }
        return arr;
    }

    /** [Spieler, ms, [Abschnitte] oder null, Zeitpunkt, Version, Fahrzeug, ohne Ruckler] */
    private static Lap lap(JsonArray a) {
        return new Lap(UUID.fromString(a.get(0).getAsString()), a.get(1).getAsLong(), a.get(2).isJsonNull() ? null : longsOf(a.get(2).getAsJsonArray()),
                a.get(3).getAsLong(), a.get(4).getAsInt(), a.get(5).getAsString(), a.get(6).getAsBoolean());
    }

    private static JsonArray lapArray(Lap l) {
        JsonArray a = new JsonArray();
        a.add(l.player().toString());
        a.add(l.ms());
        if (l.sectors() == null) a.add(com.google.gson.JsonNull.INSTANCE);
        else a.add(longs(l.sectors()));
        a.add(l.at());
        a.add(l.version());
        a.add(l.mode());
        a.add(l.clean());
        return a;
    }

    private static JsonArray longs(long[] values) {
        JsonArray a = new JsonArray();
        for (long v : values) a.add(v);
        return a;
    }

    private static long[] longsOf(JsonArray a) {
        long[] out = new long[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsLong();
        return out;
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }

    private static JsonObject map(Map<String, String> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        for (Data d : tracks.values()) {
            Track t = d.track;
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id());
            o.addProperty("name", t.name());
            o.addProperty("dimension", t.dimension());
            o.addProperty("type", t.type());
            o.addProperty("vehicle", t.vehicle());
            o.addProperty("owner", t.owner().toString());
            o.addProperty("created", t.created());
            o.addProperty("version", t.version());
            o.add("gates", gatesJson(t.gates()));
            if (d.draft != null) {
                o.add("draft", gatesJson(d.draft));
                o.addProperty("editor", d.editor.toString());
            }
            JsonArray best = new JsonArray();
            d.best.values().forEach(l -> best.add(lapArray(l)));
            o.add("best", best);
            JsonObject sectors = new JsonObject();
            d.bestSectors.forEach((u, s) -> sectors.add(u.toString(), longs(s)));
            o.add("bestSectors", sectors);
            JsonArray recent = new JsonArray();
            d.recent.forEach(l -> recent.add(lapArray(l)));
            o.add("recent", recent);
            JsonArray archive = new JsonArray();
            d.archive.forEach(l -> archive.add(lapArray(l)));
            o.add("archive", archive);
            o.addProperty("laps", d.laps);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("tracks", arr);
        return root;
    }
}
