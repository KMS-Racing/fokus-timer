package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Statistik wie bei CraftAttack: pro Spieler Minecrafts eigene Zahlen (Spielzeit, Tode, Blöcke …), dazu Tagesstände
 * für "Heute", "Gestern" und "7 Tage", Sitzungen, Aktiv/AFK, "Wann online" und ein Verlauf (Tode, Meilensteine).
 * Für den Server: Rekorde, "Wann ist am meisten los" und Spieler pro Tag.
 *
 * Minecrafts Zahlen reichen zurück bis zum ersten Login. Was die Mod selbst misst (Aktiv/AFK, Sitzungen, Wann online,
 * Todesursachen, Verlauf, Zuwachs), gibt es erst ab der Installation – "since" sagt, ab wann.
 *
 * Spieler, die sich ausgeblendet haben ("hidden"), fehlen in Ranglisten, im Verlauf und in den Server-Summen;
 * ihre Seite sieht nur der Spieler selbst. In anonymen Zählungen (wie viele online, Wann ist am meisten los) sind sie dabei.
 */
final class StatsStore extends JsonStore {
    static final int KEEP_DAYS = 40;
    static final int KEEP_SERVER_DAYS = 60;
    static final int MAX_EVENTS = 100;
    static final int TOP_ENTRIES = 10;
    static final Set<String> RANGES = Set.of("all", "today", "yesterday", "week");
    /** Kennzahlen für die Summen des Servers ("in Zahlen") und für den Tages-Verlauf auf Spielerseiten. */
    static final List<String> TOTAL_KEYS = List.of("playTime", "deaths", "mined", "placed", "diamonds", "mobKills", "distance");
    static final List<String> HISTORY_KEYS = List.of("playTime", "activeTime", "mined", "placed", "deaths", "mobKills", "distance");

    record Event(long at, String kind, String text) {}

    record Day(int players, int peak, long minutes) {}

    record Entry(UUID player, long value) {}

    private static final class Player {
        Map<String, Long> values = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> tops = new LinkedHashMap<>();
        long firstSeen, updated;
        final TreeMap<String, Map<String, Long>> days = new TreeMap<>();
        int sessions;
        long longestSession, activeMs, afkMs;
        final int[][] heat = new int[7][24]; // Sekunden online je Wochentag (Mo = 0) und Stunde
        final ArrayDeque<Event> events = new ArrayDeque<>();
        final Map<String, Integer> causes = new LinkedHashMap<>(); // Todesursachen seit der Installation
    }

    private long since;
    private final Map<UUID, Player> players = new HashMap<>();
    private int maxOnline;
    private long maxOnlineAt;
    private UUID longestSessionBy;
    private long longestSessionMs, longestSessionAt;
    private final long[][] heatSum = new long[7][24];
    private final int[][] heatSamples = new int[7][24];
    private final TreeMap<String, Day> days = new TreeMap<>();
    private String today = "";
    private final Set<UUID> todayPlayers = new HashSet<>();
    /** Wer gerade online ist, seit wann (nicht gespeichert – nach einem Neustart fängt jede Sitzung neu an). */
    private final Map<UUID, Long> sessionStarts = new ConcurrentHashMap<>();

    StatsStore(Path path, Logger logger) {
        super(path);
        JsonElement json = readOrBackup(logger);
        if (json == null || !json.isJsonObject()) return;
        JsonObject root = json.getAsJsonObject();
        since = root.has("since") ? root.get("since").getAsLong() : 0;
        if (root.has("players")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
                try {
                    players.put(UUID.fromString(e.getKey()), readPlayer(e.getValue().getAsJsonObject()));
                } catch (Exception ignored) {
                    // kaputter Eintrag – Minecrafts Zahlen kommen beim nächsten Login wieder
                }
            }
        }
        if (root.has("server")) {
            try {
                readServer(root.getAsJsonObject("server"));
            } catch (Exception e) {
                logger.warn("[World Planner] Server-Statistik in {} unvollständig: {}", path.getFileName(), e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ Sammeln

    /**
     * Neuer Stand aus Minecrafts Statistik (Online-Spieler jede Minute und beim Ausloggen, Offline-Spieler beim Start).
     * Beim allerersten Stand gibt es keine Meilensteine – sonst bekäme jeder bei der Installation einen Schwall davon.
     *
     * @return erreichte Meilensteine
     */
    synchronized List<String> observe(UUID id, StatValues.Snapshot snap, long now, LocalDate date) {
        if (since == 0) since = now;
        Player p = players.get(id);
        List<String> reached = List.of();
        if (p == null) {
            p = new Player();
            p.firstSeen = now;
            players.put(id, p);
            // Startwert: Alles bis jetzt zählt nicht als Zuwachs von "heute"
            p.days.put(date.minusDays(1).toString(), dayValues(snap.values(), 0));
        } else if (snap.values().getOrDefault("playTime", 0L) < p.values.getOrDefault("playTime", 0L)) {
            // Minecrafts Statistik-Datei wurde gelöscht oder ersetzt: neu anfangen, sonst gäbe es negative Zuwächse
            p.days.clear();
            p.days.put(date.minusDays(1).toString(), dayValues(snap.values(), p.activeMs));
            addEvent(p, new Event(now, "info", "Minecraft-Statistik wurde zurückgesetzt"));
        } else {
            reached = StatValues.milestones(p.values, snap.values());
            for (String text : reached) addEvent(p, new Event(now, "milestone", text));
        }
        p.values = new LinkedHashMap<>(snap.values());
        p.tops = snap.tops();
        p.updated = now;
        p.days.put(date.toString(), dayValues(p.values, p.activeMs));
        prune(p.days, date.minusDays(KEEP_DAYS).toString());
        changed();
        return reached;
    }

    /**
     * Statistik-Datei eines Offline-Spielers übernehmen – nur wenn sie neuer ist als das, was der Planer schon hat, und
     * sich etwas geändert hat (beim Herunterfahren speichert Minecraft die Datei kurz nach dem letzten Stand der Mod).
     */
    synchronized boolean importFile(UUID id, StatValues.Snapshot snap, long fileModified, long now, LocalDate date) {
        Player p = players.get(id);
        if (p != null && (p.updated >= fileModified || p.values.equals(snap.values()))) return false;
        observe(id, snap, now, date);
        return true;
    }

    synchronized boolean has(UUID id) {
        return players.containsKey(id);
    }

    /** Online-Zeit seit dem letzten Aufruf (jede Minute), aufgeteilt in aktiv und AFK. */
    synchronized void addTime(UUID id, long activeMs, long afkMs, ZonedDateTime local) {
        Player p = players.get(id);
        if (p == null || activeMs + afkMs <= 0) return;
        p.activeMs += activeMs;
        p.afkMs += afkMs;
        p.heat[local.getDayOfWeek().getValue() - 1][local.getHour()] += (int) ((activeMs + afkMs) / 1000);
        changed();
    }

    synchronized void sessionStarted(UUID id, long now, int onlineCount) {
        sessionStarts.put(id, now);
        Player p = players.get(id);
        if (p != null) p.sessions++;
        if (onlineCount > maxOnline) {
            maxOnline = onlineCount;
            maxOnlineAt = now;
        }
        changed();
    }

    synchronized void sessionEnded(UUID id, long now) {
        Long start = sessionStarts.remove(id);
        Player p = players.get(id);
        if (start == null || p == null || now <= start) return;
        long ms = now - start;
        p.longestSession = Math.max(p.longestSession, ms);
        if (ms > longestSessionMs) {
            longestSessionMs = ms;
            longestSessionBy = id;
            longestSessionAt = now;
        }
        changed();
    }

    /** Beim Herunterfahren: laufende Sitzungen abschließen. */
    synchronized void endAllSessions(long now) {
        for (UUID id : List.copyOf(sessionStarts.keySet())) sessionEnded(id, now);
    }

    Long onlineSince(UUID id) {
        return sessionStarts.get(id);
    }

    /** @param causeKey "mob:minecraft:zombie", "player" oder "type:<message_id>" (siehe StatNames.cause) */
    synchronized void death(UUID id, String causeKey, String text, long now) {
        Player p = players.get(id);
        if (p == null) return;
        p.causes.merge(causeKey, 1, Integer::sum);
        addEvent(p, new Event(now, "death", text));
        changed();
    }

    /** Jede Minute (auch ohne Spieler): Wer ist online? Für "Wann ist am meisten los" und "Spieler pro Tag". */
    synchronized void sampleServer(Collection<UUID> online, long now, ZonedDateTime local) {
        if (since == 0) since = now;
        int wd = local.getDayOfWeek().getValue() - 1, h = local.getHour();
        heatSum[wd][h] += online.size();
        heatSamples[wd][h]++;
        String date = local.toLocalDate().toString();
        if (!date.equals(today)) {
            today = date;
            todayPlayers.clear();
        }
        todayPlayers.addAll(online);
        Day d = days.getOrDefault(date, new Day(0, 0, 0));
        days.put(date, new Day(todayPlayers.size(), Math.max(d.peak(), online.size()), d.minutes() + online.size()));
        if (online.size() > maxOnline) {
            maxOnline = online.size();
            maxOnlineAt = now;
        }
        while (days.size() > KEEP_SERVER_DAYS) days.pollFirstEntry();
        changed();
    }

    synchronized long since() {
        return since;
    }

    private static void addEvent(Player p, Event e) {
        p.events.addFirst(e);
        while (p.events.size() > MAX_EVENTS) p.events.removeLast();
    }

    private static Map<String, Long> dayValues(Map<String, Long> values, long activeMs) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String key : StatValues.DAY_KEYS) out.put(key, key.equals("activeTime") ? activeMs : values.getOrDefault(key, 0L));
        return out;
    }

    /** Alte Tage löschen – bis auf den neuesten davon: Er ist der Ausgangswert für die Tage danach. */
    private static <V> void prune(TreeMap<String, V> map, String cutoff) {
        String anchor = map.lowerKey(cutoff);
        if (anchor != null) map.headMap(anchor, false).clear();
    }

    // ------------------------------------------------------------------ Werte und Zuwachs

    private static long current(Player p, String key) {
        return key.equals("activeTime") ? p.activeMs : key.equals("afkTime") ? p.afkMs : p.values.getOrDefault(key, 0L);
    }

    /** Stand am Ende eines Tages: der letzte gespeicherte Tag bis dahin (an Tagen ohne Spielen ändert sich nichts). */
    private static long endOf(Player p, LocalDate date, String key) {
        Map.Entry<String, Map<String, Long>> e = p.days.floorEntry(date.toString());
        if (e == null) e = p.days.firstEntry(); // vor der ersten Messung: Zuwachs seit der ersten Messung
        return e == null ? 0 : e.getValue().getOrDefault(key, 0L);
    }

    /** Wert in einem Zeitraum: all = gesamt, today/yesterday/week = Zuwachs. Null = gibt es für diese Kennzahl nicht. */
    private static Long value(Player p, StatValues.Category c, String range, LocalDate today) {
        if (range.equals("all")) return current(p, c.key());
        if (!c.growth()) return null;
        return switch (range) {
            case "today" -> Math.max(0, current(p, c.key()) - endOf(p, today.minusDays(1), c.key()));
            case "yesterday" -> Math.max(0, endOf(p, today.minusDays(1), c.key()) - endOf(p, today.minusDays(2), c.key()));
            case "week" -> Math.max(0, current(p, c.key()) - endOf(p, today.minusDays(7), c.key()));
            default -> null;
        };
    }

    /** Rangliste (größter Wert zuerst, nur Werte über 0, ohne ausgeblendete Spieler). */
    synchronized List<Entry> ranking(StatValues.Category c, String range, LocalDate today, Set<UUID> hidden) {
        List<Entry> out = new ArrayList<>();
        players.forEach((id, p) -> {
            if (hidden.contains(id)) return;
            Long v = value(p, c, range, today);
            if (v != null && v > 0) out.add(new Entry(id, v));
        });
        out.sort(Comparator.comparingLong(Entry::value).reversed().thenComparing(e -> e.player().toString()));
        return out;
    }

    /** Platz mit Gleichstand: 100, 80, 80, 50 → 1, 2, 2, 4. */
    static int place(List<Entry> ranking, int index) {
        int i = index;
        while (i > 0 && ranking.get(i - 1).value() == ranking.get(index).value()) i--;
        return i + 1;
    }

    // ------------------------------------------------------------------ API

    synchronized JsonObject rankingsJson(String range, LocalDate today, UUID viewer, Set<UUID> hidden, Function<UUID, String> names) {
        Validate.oneOf(range, RANGES, "Zeitraum");
        JsonObject o = new JsonObject();
        o.addProperty("range", range);
        o.addProperty("since", since);
        o.addProperty("meHidden", hidden.contains(viewer));
        JsonArray cats = new JsonArray();
        for (StatValues.Category c : StatValues.CATEGORIES) {
            JsonObject co = category(c);
            List<Entry> list = ranking(c, range, today, hidden);
            JsonArray entries = new JsonArray();
            JsonObject me = null;
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                int place = place(list, i);
                if (i < TOP_ENTRIES) entries.add(entry(e, place, names));
                if (e.player().equals(viewer)) {
                    me = new JsonObject();
                    me.addProperty("place", place);
                    me.addProperty("value", e.value());
                }
            }
            co.add("entries", entries);
            co.addProperty("count", list.size());
            co.add("me", me == null ? JsonNull.INSTANCE : me);
            co.addProperty("available", range.equals("all") || c.growth());
            cats.add(co);
        }
        o.add("categories", cats);
        return o;
    }

    /** Liste aller Spieler mit Statistik (für die Auswahl auf der Webseite). */
    synchronized JsonObject playersJson(UUID viewer, Set<UUID> hidden, Set<UUID> online, Function<UUID, String> names) {
        JsonArray arr = new JsonArray();
        players.forEach((id, p) -> {
            if (hidden.contains(id) && !id.equals(viewer)) return;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", id.toString());
            o.addProperty("name", names.apply(id));
            o.addProperty("playTime", p.values.getOrDefault("playTime", 0L));
            o.addProperty("deaths", p.values.getOrDefault("deaths", 0L));
            o.addProperty("updated", p.updated);
            o.addProperty("online", online != null && online.contains(id));
            o.addProperty("hidden", hidden.contains(id));
            arr.add(o);
        });
        JsonObject o = new JsonObject();
        o.add("players", arr);
        o.addProperty("since", since);
        return o;
    }

    /**
     * Server-Übersicht. Summen und Verlauf ohne ausgeblendete Spieler; anonyme Zählungen (online, Rekord, Wann ist am meisten
     * los, Spieler pro Tag) mit allen.
     *
     * @param online wer gerade online ist, oder null, wenn der Server das nicht zeigen soll (showOnlinePlayers)
     */
    synchronized JsonObject serverJson(LocalDate today, Set<UUID> hidden, Set<UUID> online, Function<UUID, String> names) {
        JsonObject o = new JsonObject();
        o.addProperty("since", since);
        int visible = 0;
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String key : TOTAL_KEYS) totals.put(key, 0L);
        Map<String, Integer> causes = new HashMap<>();
        List<JsonObject> events = new ArrayList<>();
        for (Map.Entry<UUID, Player> e : players.entrySet()) {
            if (hidden.contains(e.getKey())) continue;
            visible++;
            Player p = e.getValue();
            for (String key : TOTAL_KEYS) totals.merge(key, current(p, key), Long::sum);
            p.causes.forEach((k, n) -> causes.merge(StatNames.cause(k), n, Integer::sum));
            int n = 0;
            for (Event ev : p.events) {
                if (n++ >= 30) break;
                events.add(event(ev, e.getKey(), names));
            }
        }
        o.addProperty("players", visible);
        o.addProperty("hiddenPlayers", players.size() - visible);
        JsonObject t = new JsonObject();
        totals.forEach(t::addProperty);
        o.add("totals", t);
        if (online != null) o.addProperty("online", online.size());
        Day d = days.get(today.toString());
        JsonObject td = new JsonObject();
        td.addProperty("players", d == null ? 0 : d.players());
        td.addProperty("peak", d == null ? 0 : d.peak());
        o.add("today", td);

        JsonObject records = new JsonObject();
        if (maxOnline > 0) {
            JsonObject r = new JsonObject();
            r.addProperty("count", maxOnline);
            r.addProperty("at", maxOnlineAt);
            records.add("maxOnline", r);
        }
        if (longestSessionBy != null) {
            JsonObject r = new JsonObject();
            boolean show = !hidden.contains(longestSessionBy);
            if (show) {
                r.addProperty("uuid", longestSessionBy.toString());
                r.addProperty("name", names.apply(longestSessionBy));
            }
            r.addProperty("ms", longestSessionMs);
            r.addProperty("at", longestSessionAt);
            records.add("longestSession", r);
        }
        Map.Entry<String, Day> busiest = null, longest = null;
        for (Map.Entry<String, Day> e : days.entrySet()) {
            if (busiest == null || e.getValue().players() > busiest.getValue().players()) busiest = e;
            if (longest == null || e.getValue().minutes() > longest.getValue().minutes()) longest = e;
        }
        if (busiest != null && busiest.getValue().players() > 0) {
            JsonObject r = new JsonObject();
            r.addProperty("date", busiest.getKey());
            r.addProperty("players", busiest.getValue().players());
            records.add("busiestDay", r);
        }
        if (longest != null && longest.getValue().minutes() > 0) {
            JsonObject r = new JsonObject();
            r.addProperty("date", longest.getKey());
            r.addProperty("minutes", longest.getValue().minutes());
            records.add("mostPlayedDay", r);
        }
        o.add("records", records);

        JsonArray heat = new JsonArray();
        for (int wd = 0; wd < 7; wd++) {
            JsonArray row = new JsonArray();
            for (int h = 0; h < 24; h++) {
                if (heatSamples[wd][h] == 0) row.add(JsonNull.INSTANCE);
                else row.add(Math.round(heatSum[wd][h] * 100.0 / heatSamples[wd][h]) / 100.0);
            }
            heat.add(row);
        }
        o.add("heat", heat);

        JsonArray dayArr = new JsonArray();
        String from = today.minusDays(29).toString();
        days.tailMap(from, true).forEach((date, day) -> {
            JsonObject x = new JsonObject();
            x.addProperty("date", date);
            x.addProperty("players", day.players());
            x.addProperty("peak", day.peak());
            x.addProperty("minutes", day.minutes());
            dayArr.add(x);
        });
        o.add("days", dayArr);
        o.add("causes", causeList(causes, 8));
        events.sort(Comparator.comparingLong((JsonObject x) -> x.get("at").getAsLong()).reversed());
        JsonArray evArr = new JsonArray();
        events.stream().limit(25).forEach(evArr::add);
        o.add("events", evArr);
        return o;
    }

    /** Spielerseite. Null, wenn es keine Statistik gibt oder der Spieler sich ausgeblendet hat (außer für sich selbst). */
    synchronized JsonObject playerJson(UUID id, UUID viewer, LocalDate today, Set<UUID> hidden, boolean online, Function<UUID, String> names) {
        Player p = players.get(id);
        if (p == null || (hidden.contains(id) && !id.equals(viewer))) return null;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", id.toString());
        o.addProperty("name", names.apply(id));
        o.addProperty("hidden", hidden.contains(id));
        o.addProperty("since", since);
        o.addProperty("firstSeen", p.firstSeen);
        o.addProperty("updated", p.updated);
        o.addProperty("online", online);
        Long start = sessionStarts.get(id);
        if (online && start != null) o.addProperty("onlineSince", start);

        JsonObject values = new JsonObject();
        p.values.forEach(values::addProperty);
        values.addProperty("activeTime", p.activeMs);
        values.addProperty("afkTime", p.afkMs);
        o.add("values", values);

        JsonObject growth = new JsonObject();
        for (String range : List.of("today", "yesterday", "week")) {
            JsonObject g = new JsonObject();
            for (StatValues.Category c : StatValues.CATEGORIES) {
                Long v = value(p, c, range, today);
                if (v != null) g.addProperty(c.key(), v);
            }
            growth.add(range, g);
        }
        o.add("growth", growth);

        // "Pro Online-Stunde" wie bei CraftAttack – erst ab einer Stunde Spielzeit, vorher sagt das nichts
        long playMs = p.values.getOrDefault("playTime", 0L);
        JsonObject perHour = new JsonObject();
        if (playMs >= 3_600_000L) {
            double hours = playMs / 3_600_000.0;
            for (String key : List.of("mined", "placed", "diamonds", "mobKills", "deaths", "distance", "damageDealt", "eaten", "jumps")) {
                perHour.addProperty(key, Math.round(p.values.getOrDefault(key, 0L) / hours * 10) / 10.0);
            }
        }
        o.add("perHour", perHour);

        JsonObject sessions = new JsonObject();
        sessions.addProperty("count", p.sessions);
        sessions.addProperty("longest", p.longestSession);
        sessions.addProperty("average", p.sessions == 0 ? 0 : (p.activeMs + p.afkMs) / p.sessions);
        o.add("sessions", sessions);

        JsonArray heat = new JsonArray();
        for (int[] row : p.heat) {
            JsonArray r = new JsonArray();
            for (int sec : row) r.add(sec / 60);
            heat.add(r);
        }
        o.add("heat", heat);

        JsonObject tops = new JsonObject();
        p.tops.forEach((kind, counts) -> {
            JsonArray arr = new JsonArray();
            counts.forEach((key, n) -> {
                JsonObject x = new JsonObject();
                x.addProperty("id", key);
                x.addProperty("name", kind.equals("killed") || kind.equals("killedBy") ? StatNames.mob(key) : StatNames.block(key));
                x.addProperty("count", n);
                arr.add(x);
            });
            tops.add(kind, arr);
        });
        o.add("tops", tops);
        Map<String, Integer> causes = new HashMap<>();
        p.causes.forEach((k, n) -> causes.merge(StatNames.cause(k), n, Integer::sum));
        o.add("causes", causeList(causes, 10));

        JsonArray events = new JsonArray();
        int n = 0;
        for (Event ev : p.events) {
            if (n++ >= 30) break;
            events.add(event(ev, id, names));
        }
        o.add("events", events);

        // Platz in jeder Rangliste (gesamt) und vor 7 Tagen – nur unter sichtbaren Spielern
        JsonObject places = new JsonObject();
        if (!hidden.contains(id)) {
            for (StatValues.Category c : StatValues.CATEGORIES) {
                List<Entry> now = ranking(c, "all", today, hidden);
                int idx = indexOf(now, id);
                if (idx < 0) continue;
                JsonObject x = new JsonObject();
                x.addProperty("place", place(now, idx));
                x.addProperty("of", now.size());
                if (c.growth()) {
                    List<Entry> before = rankingAt(c, today.minusDays(7), hidden);
                    int b = indexOf(before, id);
                    if (b >= 0) x.addProperty("weekAgo", place(before, b));
                }
                places.add(c.id(), x);
            }
        }
        o.add("places", places);

        // Zuwachs pro Tag (letzte 30 Tage); null = vor der ersten Messung
        JsonArray history = new JsonArray();
        String first = p.days.isEmpty() ? null : p.days.firstKey();
        for (int i = 29; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            JsonObject x = new JsonObject();
            x.addProperty("date", date.toString());
            for (String key : HISTORY_KEYS) {
                if (first == null || date.toString().compareTo(first) <= 0) x.add(key, JsonNull.INSTANCE);
                else x.addProperty(key, Math.max(0, endOf(p, date, key) - endOf(p, date.minusDays(1), key)));
            }
            history.add(x);
        }
        o.add("history", history);
        return o;
    }

    /** Rangliste mit dem Stand am Ende eines Tages (für "vor 7 Tagen: Platz 5"). */
    private List<Entry> rankingAt(StatValues.Category c, LocalDate date, Set<UUID> hidden) {
        List<Entry> out = new ArrayList<>();
        players.forEach((id, p) -> {
            if (hidden.contains(id) || p.days.isEmpty() || p.days.firstKey().compareTo(date.toString()) > 0) return;
            long v = endOf(p, date, c.key());
            if (v > 0) out.add(new Entry(id, v));
        });
        out.sort(Comparator.comparingLong(Entry::value).reversed().thenComparing(e -> e.player().toString()));
        return out;
    }

    private static int indexOf(List<Entry> list, UUID id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).player().equals(id)) return i;
        return -1;
    }

    /** Für /planer statistik: Werte, Zuwachs heute und Plätze (gesamt). */
    record Summary(Map<String, Long> values, Map<String, Long> today, Map<String, Integer> places, long updated) {}

    /** Null, wenn es für den Spieler (noch) keine Statistik gibt. Plätze nur, wenn er nicht ausgeblendet ist. */
    synchronized Summary summary(UUID id, LocalDate today, Set<UUID> hidden) {
        Player p = players.get(id);
        if (p == null) return null;
        Map<String, Long> growth = new LinkedHashMap<>();
        Map<String, Integer> places = new LinkedHashMap<>();
        for (StatValues.Category c : StatValues.CATEGORIES) {
            Long v = value(p, c, "today", today);
            if (v != null) growth.put(c.key(), v);
            if (hidden.contains(id)) continue;
            List<Entry> list = ranking(c, "all", today, hidden);
            int i = indexOf(list, id);
            if (i >= 0) places.put(c.id(), place(list, i));
        }
        return new Summary(values(id), growth, places, p.updated);
    }

    /** Kennzahlen eines Spielers (mit Aktiv/AFK). Null, wenn es (noch) keine Statistik gibt. */
    synchronized Map<String, Long> values(UUID id) {
        Player p = players.get(id);
        if (p == null) return null;
        Map<String, Long> out = new LinkedHashMap<>(p.values);
        out.put("activeTime", p.activeMs);
        out.put("afkTime", p.afkMs);
        return out;
    }

    static JsonObject category(StatValues.Category c) {
        JsonObject co = new JsonObject();
        co.addProperty("id", c.id());
        co.addProperty("rank", c.rank());
        co.addProperty("label", c.label());
        co.addProperty("key", c.key());
        co.addProperty("unit", c.unit().name().toLowerCase(java.util.Locale.ROOT));
        co.addProperty("growth", c.growth());
        return co;
    }

    private static JsonObject entry(Entry e, int place, Function<UUID, String> names) {
        JsonObject x = new JsonObject();
        x.addProperty("place", place);
        x.addProperty("uuid", e.player().toString());
        x.addProperty("name", names.apply(e.player()));
        x.addProperty("value", e.value());
        return x;
    }

    private static JsonObject event(Event ev, UUID id, Function<UUID, String> names) {
        JsonObject x = new JsonObject();
        x.addProperty("at", ev.at());
        x.addProperty("uuid", id.toString());
        x.addProperty("name", names.apply(id));
        x.addProperty("kind", ev.kind());
        x.addProperty("text", ev.text());
        return x;
    }

    private static JsonArray causeList(Map<String, Integer> causes, int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(causes.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        JsonArray arr = new JsonArray();
        for (int i = 0; i < list.size() && i < limit; i++) {
            JsonObject x = new JsonObject();
            x.addProperty("label", list.get(i).getKey());
            x.addProperty("count", list.get(i).getValue());
            arr.add(x);
        }
        return arr;
    }

    // ------------------------------------------------------------------ Datei

    private static Player readPlayer(JsonObject o) {
        Player p = new Player();
        p.values = longMap(o.getAsJsonObject("values"));
        if (o.has("tops")) {
            for (Map.Entry<String, JsonElement> t : o.getAsJsonObject("tops").entrySet()) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                t.getValue().getAsJsonObject().entrySet().forEach(c -> counts.put(c.getKey(), c.getValue().getAsInt()));
                p.tops.put(t.getKey(), counts);
            }
        }
        p.firstSeen = o.get("firstSeen").getAsLong();
        p.updated = o.get("updated").getAsLong();
        if (o.has("days")) o.getAsJsonObject("days").entrySet().forEach(d -> p.days.put(d.getKey(), longMap(d.getValue().getAsJsonObject())));
        p.sessions = o.has("sessions") ? o.get("sessions").getAsInt() : 0;
        p.longestSession = o.has("longestSession") ? o.get("longestSession").getAsLong() : 0;
        p.activeMs = o.has("activeMs") ? o.get("activeMs").getAsLong() : 0;
        p.afkMs = o.has("afkMs") ? o.get("afkMs").getAsLong() : 0;
        if (o.has("heat")) readGrid(o.getAsJsonArray("heat"), (wd, h, v) -> p.heat[wd][h] = (int) v);
        if (o.has("events")) {
            for (JsonElement e : o.getAsJsonArray("events")) {
                JsonArray a = e.getAsJsonArray();
                if (p.events.size() < MAX_EVENTS) p.events.addLast(new Event(a.get(0).getAsLong(), a.get(1).getAsString(), a.get(2).getAsString()));
            }
        }
        if (o.has("causes")) o.getAsJsonObject("causes").entrySet().forEach(c -> p.causes.put(c.getKey(), c.getValue().getAsInt()));
        return p;
    }

    private void readServer(JsonObject o) {
        maxOnline = o.has("maxOnline") ? o.get("maxOnline").getAsInt() : 0;
        maxOnlineAt = o.has("maxOnlineAt") ? o.get("maxOnlineAt").getAsLong() : 0;
        if (o.has("longestSessionBy")) {
            longestSessionBy = UUID.fromString(o.get("longestSessionBy").getAsString());
            longestSessionMs = o.get("longestSessionMs").getAsLong();
            longestSessionAt = o.get("longestSessionAt").getAsLong();
        }
        if (o.has("heatSum")) readGrid(o.getAsJsonArray("heatSum"), (wd, h, v) -> heatSum[wd][h] = v);
        if (o.has("heatSamples")) readGrid(o.getAsJsonArray("heatSamples"), (wd, h, v) -> heatSamples[wd][h] = (int) v);
        if (o.has("days")) {
            for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("days").entrySet()) {
                JsonArray a = d.getValue().getAsJsonArray();
                days.put(d.getKey(), new Day(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsLong()));
            }
        }
        today = o.has("today") ? o.get("today").getAsString() : "";
        if (o.has("todayPlayers")) o.getAsJsonArray("todayPlayers").forEach(u -> todayPlayers.add(UUID.fromString(u.getAsString())));
    }

    private interface GridSetter {
        void set(int weekday, int hour, long value);
    }

    private static void readGrid(JsonArray rows, GridSetter set) {
        for (int wd = 0; wd < 7 && wd < rows.size(); wd++) {
            JsonArray row = rows.get(wd).getAsJsonArray();
            for (int h = 0; h < 24 && h < row.size(); h++) set.set(wd, h, row.get(h).getAsLong());
        }
    }

    private static Map<String, Long> longMap(JsonObject o) {
        Map<String, Long> m = new LinkedHashMap<>();
        if (o != null) o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsLong()));
        return m;
    }

    private static JsonObject longJson(Map<String, Long> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }

    @Override
    protected JsonElement toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("since", since);
        JsonObject ps = new JsonObject();
        players.forEach((id, p) -> {
            JsonObject o = new JsonObject();
            o.add("values", longJson(p.values));
            JsonObject tops = new JsonObject();
            p.tops.forEach((kind, counts) -> {
                JsonObject c = new JsonObject();
                counts.forEach(c::addProperty);
                tops.add(kind, c);
            });
            o.add("tops", tops);
            o.addProperty("firstSeen", p.firstSeen);
            o.addProperty("updated", p.updated);
            JsonObject d = new JsonObject();
            p.days.forEach((date, v) -> d.add(date, longJson(v)));
            o.add("days", d);
            o.addProperty("sessions", p.sessions);
            o.addProperty("longestSession", p.longestSession);
            o.addProperty("activeMs", p.activeMs);
            o.addProperty("afkMs", p.afkMs);
            JsonArray heat = new JsonArray();
            for (int[] row : p.heat) {
                JsonArray r = new JsonArray();
                for (int v : row) r.add(v);
                heat.add(r);
            }
            o.add("heat", heat);
            JsonArray events = new JsonArray();
            for (Event e : p.events) {
                JsonArray a = new JsonArray();
                a.add(e.at());
                a.add(e.kind());
                a.add(e.text());
                events.add(a);
            }
            o.add("events", events);
            JsonObject causes = new JsonObject();
            p.causes.forEach(causes::addProperty);
            o.add("causes", causes);
            ps.add(id.toString(), o);
        });
        root.add("players", ps);

        JsonObject server = new JsonObject();
        server.addProperty("maxOnline", maxOnline);
        server.addProperty("maxOnlineAt", maxOnlineAt);
        if (longestSessionBy != null) {
            server.addProperty("longestSessionBy", longestSessionBy.toString());
            server.addProperty("longestSessionMs", longestSessionMs);
            server.addProperty("longestSessionAt", longestSessionAt);
        }
        JsonArray sum = new JsonArray(), samples = new JsonArray();
        for (int wd = 0; wd < 7; wd++) {
            JsonArray a = new JsonArray(), b = new JsonArray();
            for (int h = 0; h < 24; h++) {
                a.add(heatSum[wd][h]);
                b.add(heatSamples[wd][h]);
            }
            sum.add(a);
            samples.add(b);
        }
        server.add("heatSum", sum);
        server.add("heatSamples", samples);
        JsonObject d = new JsonObject();
        days.forEach((date, day) -> {
            JsonArray a = new JsonArray();
            a.add(day.players());
            a.add(day.peak());
            a.add(day.minutes());
            d.add(date, a);
        });
        server.add("days", d);
        server.addProperty("today", today);
        JsonArray tp = new JsonArray();
        todayPlayers.forEach(u -> tp.add(u.toString()));
        server.add("todayPlayers", tp);
        root.add("server", server);
        return root;
    }
}
