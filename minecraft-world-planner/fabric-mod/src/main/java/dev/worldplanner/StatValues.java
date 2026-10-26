package dev.worldplanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Statistik-Kennzahlen aus Minecrafts eigenen Statistiken (dieselben Zahlen wie im Spiel unter Esc → Statistiken).
 * Nichts davon wird geschätzt: Spielzeit, Tode, abgebaute Blöcke usw. zählt Minecraft selbst – auch schon aus der Zeit
 * vor der Mod. Nur "Aktiv (ohne AFK)" misst die Mod selbst, und zwar erst ab Installation von Version 0.5.
 */
final class StatValues {
    /** Minecrafts Rohwerte, Schlüssel wie in der Statistik-Datei ("minecraft:play_time", "minecraft:stone" …). */
    record Raw(Map<String, Integer> custom, Map<String, Integer> mined, Map<String, Integer> used, Map<String, Integer> crafted,
               Map<String, Integer> killed, Map<String, Integer> killedBy) {}

    /** Kennzahlen eines Spielers plus Top-Listen (meist abgebaute Blöcke usw.). */
    record Snapshot(Map<String, Long> values, Map<String, Map<String, Integer>> tops) {}

    /** Einheit für die Anzeige: Zeit in ms, Strecke in cm, Schaden in Zehntel-Lebenspunkten, sonst Anzahl. */
    enum Unit { TIME, DISTANCE, DAMAGE, COUNT }

    /** Eine Rangliste. growth = false: Momentwert (z. B. "seit dem letzten Tod"), dafür gibt es kein "heute"/"7 Tage". */
    record Category(String id, String rank, String label, String key, Unit unit, boolean growth) {}

    static final List<Category> CATEGORIES = List.of(
            new Category("dauerzocker", "Dauerzocker", "Spielzeit", "playTime", Unit.TIME, true),
            new Category("fleissig", "Fleißig", "Aktiv (ohne AFK)", "activeTime", Unit.TIME, true),
            new Category("friedhof", "Friedhof", "Tode", "deaths", Unit.COUNT, true),
            new Category("diamantenbank", "Diamantenbank", "Diamanten abgebaut", "diamonds", Unit.COUNT, true),
            new Category("abrissbirne", "Abrissbirne", "Blöcke abgebaut", "mined", Unit.COUNT, true),
            new Category("baumeister", "Baumeister", "Blöcke platziert", "placed", Unit.COUNT, true),
            new Category("monsterjaeger", "Monsterjäger", "Mobs besiegt", "mobKills", Unit.COUNT, true),
            new Category("weltenbummler", "Weltenbummler", "Strecke", "distance", Unit.DISTANCE, true),
            new Category("unsterblich", "Unsterblich", "Spielzeit seit dem letzten Tod", "sinceDeath", Unit.TIME, false),
            new Category("kampfmaschine", "Kampfmaschine", "Schaden ausgeteilt", "damageDealt", Unit.DAMAGE, true),
            new Category("vielfrass", "Vielfraß", "Gegessen", "eaten", Unit.COUNT, true),
            new Category("angelprofi", "Angelprofi", "Fische gefangen", "fish", Unit.COUNT, true),
            new Category("tierfreund", "Tierfreund", "Tiere gezüchtet", "bred", Unit.COUNT, true),
            new Category("haendler", "Händler", "Handel mit Dorfbewohnern", "trades", Unit.COUNT, true),
            new Category("handwerker", "Handwerker", "Gegenstände hergestellt", "crafted", Unit.COUNT, true),
            new Category("flummi", "Flummi", "Sprünge", "jumps", Unit.COUNT, true));

    /** Kennzahlen, deren Tagesstand gespeichert wird (für "Heute", "Gestern", "7 Tage" und den Verlauf). */
    static final List<String> DAY_KEYS = CATEGORIES.stream().filter(Category::growth).map(Category::key).toList();

    static Category category(String id) {
        for (Category c : CATEGORIES) if (c.id().equals(id)) return c;
        return null;
    }

    /** Alle Kennzahlen mit Beschriftung (für Spielerseiten, auch die ohne eigene Rangliste). */
    static final Map<String, String> LABELS = labels();
    static final Map<String, Unit> UNITS = units();

    /** Fortbewegung, die als Strecke zählt (ohne Fallen und Klettern – das ist senkrecht). */
    private static final List<String> TRAVEL = List.of("walk_one_cm", "crouch_one_cm", "sprint_one_cm", "walk_on_water_one_cm",
            "walk_under_water_one_cm", "swim_one_cm", "boat_one_cm", "horse_one_cm", "minecart_one_cm", "pig_one_cm",
            "strider_one_cm", "aviate_one_cm", "happy_ghast_one_cm", "nautilus_one_cm", "fly_one_cm");
    private static final int TOP = 15;

    private StatValues() {
    }

    /**
     * @param isBlockItem ist dieses Item ein Block? (Benutzen = Platzieren)
     * @param isFood      ist dieses Item Essen? (Benutzen = Essen)
     */
    static Snapshot compute(Raw raw, Predicate<String> isBlockItem, Predicate<String> isFood) {
        Map<String, Long> v = new LinkedHashMap<>();
        v.put("playTime", custom(raw, "play_time") * 50); // Ticks → Millisekunden
        v.put("deaths", custom(raw, "deaths"));
        v.put("diamonds", (long) raw.mined().getOrDefault("minecraft:diamond_ore", 0) + raw.mined().getOrDefault("minecraft:deepslate_diamond_ore", 0));
        v.put("mined", sum(raw.mined()));
        long placed = 0, eaten = custom(raw, "eat_cake_slice");
        for (Map.Entry<String, Integer> e : raw.used().entrySet()) {
            if (placedBlock(e.getKey(), isBlockItem, isFood)) placed += e.getValue();
            if (isFood.test(e.getKey()) && !isBlockItem.test(e.getKey())) eaten += e.getValue();
        }
        v.put("placed", placed);
        v.put("eaten", eaten);
        v.put("mobKills", custom(raw, "mob_kills"));
        v.put("playerKills", custom(raw, "player_kills"));
        long travel = 0;
        for (String key : TRAVEL) travel += custom(raw, key);
        v.put("distance", travel);
        v.put("elytra", custom(raw, "aviate_one_cm"));
        v.put("jumps", custom(raw, "jump"));
        v.put("fish", custom(raw, "fish_caught"));
        v.put("bred", custom(raw, "animals_bred"));
        v.put("trades", custom(raw, "traded_with_villager"));
        v.put("crafted", sum(raw.crafted()));
        v.put("damageDealt", custom(raw, "damage_dealt"));
        v.put("damageTaken", custom(raw, "damage_taken"));
        v.put("totems", (long) raw.used().getOrDefault("minecraft:totem_of_undying", 0));
        v.put("chests", custom(raw, "open_chest"));
        v.put("enchanted", custom(raw, "enchant_item"));
        v.put("raids", custom(raw, "raid_win"));
        v.put("sinceDeath", custom(raw, "time_since_death") * 50);

        Map<String, Map<String, Integer>> tops = new LinkedHashMap<>();
        tops.put("mined", top(raw.mined()));
        Map<String, Integer> placedBlocks = new LinkedHashMap<>();
        raw.used().forEach((k, n) -> {
            if (placedBlock(k, isBlockItem, isFood)) placedBlocks.put(k, n);
        });
        tops.put("placed", top(placedBlocks));
        tops.put("killed", top(raw.killed()));
        tops.put("killedBy", top(raw.killedBy()));
        return new Snapshot(v, tops);
    }

    /**
     * Minecraft zählt bei Block-Items "benutzt" = platziert. Karotten, Kartoffeln und Beeren sind beides (Pflanze und Essen),
     * und Minecraft zählt Pflanzen und Essen dort in einer Zahl – die zählen deshalb weder als platziert noch als gegessen.
     */
    private static boolean placedBlock(String key, Predicate<String> isBlockItem, Predicate<String> isFood) {
        return isBlockItem.test(key) && !isFood.test(key);
    }

    /**
     * Liest eine Statistik-Datei von Minecraft (world/players/stats/<uuid>.json) – für Spieler, die gerade nicht online sind.
     * Gibt null zurück, wenn die Datei nicht im heutigen Format ist (sehr alte Welten); dann kommen die Zahlen beim nächsten Login.
     */
    static Raw fromStatsFile(com.google.gson.JsonElement json) {
        if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("stats")) return null;
        com.google.gson.JsonObject stats = json.getAsJsonObject().getAsJsonObject("stats");
        Map<String, Integer> custom = group(stats, "minecraft:custom");
        Integer old = custom.remove("minecraft:play_one_minute"); // vor Minecraft 1.17 hieß die Spielzeit so
        if (old != null) custom.merge("minecraft:play_time", old, Math::max);
        return new Raw(custom, group(stats, "minecraft:mined"), group(stats, "minecraft:used"), group(stats, "minecraft:crafted"),
                group(stats, "minecraft:killed"), group(stats, "minecraft:killed_by"));
    }

    private static Map<String, Integer> group(com.google.gson.JsonObject stats, String key) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!stats.has(key) || !stats.get(key).isJsonObject()) return out;
        for (Map.Entry<String, com.google.gson.JsonElement> e : stats.getAsJsonObject(key).entrySet()) {
            try {
                int n = e.getValue().getAsInt();
                if (n > 0) out.put(e.getKey(), n);
            } catch (Exception ignored) {
                // kaputter Eintrag
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Meilensteine

    private static final Map<String, long[]> MILESTONES = Map.ofEntries(
            Map.entry("playTime", hours(1, 10, 24, 50, 100, 250, 500, 1000, 2500, 5000)),
            Map.entry("deaths", new long[]{1, 10, 25, 50, 100, 250, 500, 1000}),
            Map.entry("diamonds", new long[]{1, 10, 64, 100, 250, 500, 1000, 5000}),
            Map.entry("mined", new long[]{1000, 10_000, 50_000, 100_000, 250_000, 500_000, 1_000_000}),
            Map.entry("placed", new long[]{1000, 10_000, 50_000, 100_000, 250_000, 500_000, 1_000_000}),
            Map.entry("mobKills", new long[]{1, 100, 500, 1000, 5000, 10_000, 50_000}),
            Map.entry("distance", new long[]{1_000_000, 10_000_000, 50_000_000, 100_000_000, 500_000_000, 1_000_000_000}),
            Map.entry("fish", new long[]{1, 100, 500, 1000}),
            Map.entry("bred", new long[]{1, 100, 500, 1000}),
            Map.entry("trades", new long[]{1, 100, 500, 1000}),
            Map.entry("crafted", new long[]{1000, 10_000, 100_000}),
            Map.entry("eaten", new long[]{100, 1000, 5000}),
            Map.entry("jumps", new long[]{10_000, 100_000, 1_000_000}));

    private static long[] hours(long... h) {
        long[] out = new long[h.length];
        for (int i = 0; i < h.length; i++) out[i] = h[i] * 3_600_000L;
        return out;
    }

    /** Welche Meilensteine wurden zwischen den beiden Ständen erreicht? (nur der höchste je Kennzahl) */
    static List<String> milestones(Map<String, Long> before, Map<String, Long> after) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, long[]> m : MILESTONES.entrySet()) {
            long a = before.getOrDefault(m.getKey(), 0L), b = after.getOrDefault(m.getKey(), 0L);
            long reached = -1;
            for (long t : m.getValue()) if (a < t && b >= t) reached = t;
            if (reached >= 0) out.add(milestoneText(m.getKey(), reached));
        }
        out.sort(null);
        return out;
    }

    static String milestoneText(String key, long n) {
        String c = Notices.num(n);
        return switch (key) {
            case "playTime" -> n == 3_600_000L ? "Erste Stunde gespielt" : Notices.num(n / 3_600_000L) + " Stunden gespielt";
            case "distance" -> Notices.num(n / 100_000L) + " km unterwegs";
            case "deaths" -> n == 1 ? "Erster Tod" : c + " Tode";
            case "diamonds" -> n == 1 ? "Erster Diamant abgebaut" : c + " Diamanten abgebaut";
            case "mobKills" -> n == 1 ? "Erstes Monster besiegt" : c + " Mobs besiegt";
            case "fish" -> n == 1 ? "Erster Fisch gefangen" : c + " Fische gefangen";
            case "bred" -> n == 1 ? "Erstes Tier gezüchtet" : c + " Tiere gezüchtet";
            case "trades" -> n == 1 ? "Erster Handel mit einem Dorfbewohner" : c + "-mal mit Dorfbewohnern gehandelt";
            case "mined" -> c + " Blöcke abgebaut";
            case "placed" -> c + " Blöcke platziert";
            case "crafted" -> c + " Gegenstände hergestellt";
            case "eaten" -> c + "-mal gegessen";
            case "jumps" -> c + " Sprünge";
            default -> c + " " + LABELS.getOrDefault(key, key);
        };
    }

    private static long custom(Raw raw, String key) {
        return raw.custom().getOrDefault("minecraft:" + key, 0);
    }

    private static long sum(Map<String, Integer> m) {
        long n = 0;
        for (int x : m.values()) n += x;
        return n;
    }

    static Map<String, Integer> top(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(m.entrySet());
        list.removeIf(e -> e.getValue() <= 0);
        list.sort((a, b) -> b.getValue() != a.getValue().intValue() ? Integer.compare(b.getValue(), a.getValue()) : a.getKey().compareTo(b.getKey()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < list.size() && i < TOP; i++) out.put(list.get(i).getKey(), list.get(i).getValue());
        return out;
    }

    private static Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("playTime", "Spielzeit");
        m.put("activeTime", "Aktiv (ohne AFK)");
        m.put("afkTime", "AFK");
        m.put("deaths", "Tode");
        m.put("diamonds", "Diamanten abgebaut");
        m.put("mined", "Blöcke abgebaut");
        m.put("placed", "Blöcke platziert");
        m.put("mobKills", "Mobs besiegt");
        m.put("playerKills", "Spieler besiegt");
        m.put("distance", "Strecke");
        m.put("elytra", "davon mit Elytra");
        m.put("jumps", "Sprünge");
        m.put("eaten", "Gegessen");
        m.put("fish", "Fische gefangen");
        m.put("bred", "Tiere gezüchtet");
        m.put("trades", "Handel mit Dorfbewohnern");
        m.put("crafted", "Gegenstände hergestellt");
        m.put("damageDealt", "Schaden ausgeteilt");
        m.put("damageTaken", "Schaden eingesteckt");
        m.put("totems", "Totems benutzt");
        m.put("chests", "Truhen geöffnet");
        m.put("enchanted", "Verzaubert");
        m.put("raids", "Überfälle abgewehrt");
        m.put("sinceDeath", "Spielzeit seit dem letzten Tod");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Map<String, Unit> units() {
        Map<String, Unit> m = new LinkedHashMap<>();
        for (String k : List.of("playTime", "activeTime", "afkTime", "sinceDeath")) m.put(k, Unit.TIME);
        for (String k : List.of("distance", "elytra")) m.put(k, Unit.DISTANCE);
        for (String k : List.of("damageDealt", "damageTaken")) m.put(k, Unit.DAMAGE);
        return java.util.Collections.unmodifiableMap(m);
    }

    static Unit unitOf(String key) {
        return UNITS.getOrDefault(key, Unit.COUNT);
    }

    /** Für Chat und Tests: "12 h 5 min", "3,4 km", "12,5 ♥", "1.234". */
    static String format(String key, long value) {
        return switch (unitOf(key)) {
            case TIME -> duration(value);
            case DISTANCE -> value >= 100_000 ? String.format(java.util.Locale.GERMANY, "%,.1f km", value / 100_000.0)
                    : Notices.num(Math.round(value / 100.0)) + " m";
            case DAMAGE -> String.format(java.util.Locale.GERMANY, "%,.1f ♥", value / 20.0);
            case COUNT -> Notices.num(value);
        };
    }

    static String duration(long ms) {
        long min = ms / 60_000;
        if (min < 60) return min + " min";
        long h = min / 60;
        if (h < 48) return h + " h " + (min % 60) + " min";
        return Notices.num(h / 24) + " Tage " + (h % 24) + " h";
    }
}
