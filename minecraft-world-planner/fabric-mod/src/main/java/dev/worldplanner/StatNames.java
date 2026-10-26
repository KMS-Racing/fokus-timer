package dev.worldplanner;

import java.util.Locale;
import java.util.Map;

/**
 * Deutsche Namen für Blöcke, Mobs und Todesursachen in der Statistik. Der Server hat nur Minecrafts englische Texte –
 * deshalb stehen hier die häufigsten Namen so, wie sie im deutschen Minecraft heißen. Alles andere wird aus der ID
 * gebaut ("minecraft:sulfur_cube" → "Sulfur Cube"), statt eine Übersetzung zu raten.
 */
final class StatNames {
    private static final Map<String, String> BLOCKS = Map.ofEntries(
            Map.entry("stone", "Stein"), Map.entry("deepslate", "Tiefenschiefer"), Map.entry("cobblestone", "Bruchstein"),
            Map.entry("cobbled_deepslate", "Bruchtiefenschiefer"), Map.entry("dirt", "Erde"), Map.entry("grass_block", "Grasblock"),
            Map.entry("coarse_dirt", "Grobe Erde"), Map.entry("rooted_dirt", "Wurzelerde"), Map.entry("podzol", "Podsol"),
            Map.entry("mycelium", "Myzel"), Map.entry("dirt_path", "Trampelpfad"), Map.entry("farmland", "Ackerboden"),
            Map.entry("mud", "Schlamm"), Map.entry("moss_block", "Moosblock"), Map.entry("sand", "Sand"), Map.entry("red_sand", "Roter Sand"),
            Map.entry("gravel", "Kies"), Map.entry("clay", "Ton"), Map.entry("sandstone", "Sandstein"), Map.entry("terracotta", "Keramik"),
            Map.entry("andesite", "Andesit"), Map.entry("diorite", "Diorit"), Map.entry("granite", "Granit"), Map.entry("tuff", "Tuffstein"),
            Map.entry("calcite", "Calcit"), Map.entry("dripstone_block", "Tropfsteinblock"), Map.entry("smooth_stone", "Glatter Stein"),
            Map.entry("netherrack", "Netherrack"), Map.entry("basalt", "Basalt"), Map.entry("blackstone", "Schwarzstein"),
            Map.entry("soul_sand", "Seelensand"), Map.entry("soul_soil", "Seelenerde"), Map.entry("magma_block", "Magmablock"),
            Map.entry("glowstone", "Leuchtstein"), Map.entry("end_stone", "Endstein"), Map.entry("obsidian", "Obsidian"),
            Map.entry("ice", "Eis"), Map.entry("packed_ice", "Packeis"), Map.entry("snow", "Schnee"), Map.entry("snow_block", "Schneeblock"),
            Map.entry("sculk", "Sculk"), Map.entry("tall_grass", "Hohes Gras"), Map.entry("sugar_cane", "Zuckerrohr"),
            Map.entry("kelp", "Seetang"), Map.entry("bamboo", "Bambus"), Map.entry("oak_log", "Eichenstamm"),
            Map.entry("spruce_log", "Fichtenstamm"), Map.entry("birch_log", "Birkenstamm"), Map.entry("jungle_log", "Tropenbaumstamm"),
            Map.entry("acacia_log", "Akazienstamm"), Map.entry("dark_oak_log", "Schwarzeichenstamm"), Map.entry("mangrove_log", "Mangrovenstamm"),
            Map.entry("oak_leaves", "Eichenlaub"), Map.entry("oak_planks", "Eichenholzbretter"), Map.entry("spruce_planks", "Fichtenholzbretter"),
            Map.entry("birch_planks", "Birkenholzbretter"), Map.entry("coal_ore", "Steinkohle"), Map.entry("deepslate_coal_ore", "Tiefenschiefer-Steinkohle"),
            Map.entry("iron_ore", "Eisenerz"), Map.entry("deepslate_iron_ore", "Tiefenschiefer-Eisenerz"), Map.entry("copper_ore", "Kupfererz"),
            Map.entry("deepslate_copper_ore", "Tiefenschiefer-Kupfererz"), Map.entry("gold_ore", "Golderz"),
            Map.entry("deepslate_gold_ore", "Tiefenschiefer-Golderz"), Map.entry("redstone_ore", "Redstone-Erz"),
            Map.entry("deepslate_redstone_ore", "Tiefenschiefer-Redstone-Erz"), Map.entry("lapis_ore", "Lapislazulierz"),
            Map.entry("deepslate_lapis_ore", "Tiefenschiefer-Lapislazulierz"), Map.entry("diamond_ore", "Diamanterz"),
            Map.entry("deepslate_diamond_ore", "Tiefenschiefer-Diamanterz"), Map.entry("emerald_ore", "Smaragderz"),
            Map.entry("deepslate_emerald_ore", "Tiefenschiefer-Smaragderz"), Map.entry("nether_quartz_ore", "Netherquarzerz"),
            Map.entry("nether_gold_ore", "Nethergolderz"), Map.entry("ancient_debris", "Antiker Schutt"), Map.entry("glass", "Glas"),
            Map.entry("torch", "Fackel"), Map.entry("crafting_table", "Werkbank"), Map.entry("furnace", "Ofen"), Map.entry("chest", "Truhe"),
            Map.entry("stone_bricks", "Steinziegel"), Map.entry("bricks", "Ziegelsteine"), Map.entry("rail", "Schiene"),
            Map.entry("scaffolding", "Gerüst"), Map.entry("white_wool", "Weiße Wolle"));

    private static final Map<String, String> MOBS = Map.ofEntries(
            Map.entry("zombie", "Zombie"), Map.entry("skeleton", "Skelett"), Map.entry("creeper", "Creeper"), Map.entry("spider", "Spinne"),
            Map.entry("cave_spider", "Höhlenspinne"), Map.entry("enderman", "Enderman"), Map.entry("witch", "Hexe"), Map.entry("slime", "Schleim"),
            Map.entry("phantom", "Phantom"), Map.entry("drowned", "Ertrunkener"), Map.entry("husk", "Wüstenzombie"), Map.entry("stray", "Eiswanderer"),
            Map.entry("bogged", "Sumpfskelett"), Map.entry("zombie_villager", "Zombiedorfbewohner"), Map.entry("pillager", "Plünderer"),
            Map.entry("vindicator", "Diener"), Map.entry("evoker", "Magier"), Map.entry("ravager", "Verwüster"), Map.entry("vex", "Plagegeist"),
            Map.entry("blaze", "Lohe"), Map.entry("ghast", "Ghast"), Map.entry("magma_cube", "Magmawürfel"), Map.entry("wither_skeleton", "Witherskelett"),
            Map.entry("piglin", "Piglin"), Map.entry("piglin_brute", "Piglin-Barbar"), Map.entry("zombified_piglin", "Zombifizierter Piglin"),
            Map.entry("hoglin", "Hoglin"), Map.entry("zoglin", "Zoglin"), Map.entry("guardian", "Wächter"), Map.entry("elder_guardian", "Großer Wächter"),
            Map.entry("shulker", "Shulker"), Map.entry("silverfish", "Silberfischchen"), Map.entry("endermite", "Endermite"),
            Map.entry("warden", "Wärter"), Map.entry("breeze", "Breeze"), Map.entry("ender_dragon", "Enderdrache"), Map.entry("wither", "Wither"),
            Map.entry("iron_golem", "Eisengolem"), Map.entry("snow_golem", "Schneegolem"), Map.entry("wolf", "Wolf"), Map.entry("bee", "Biene"),
            Map.entry("llama", "Lama"), Map.entry("polar_bear", "Eisbär"), Map.entry("panda", "Panda"), Map.entry("dolphin", "Delfin"),
            Map.entry("goat", "Ziege"), Map.entry("cow", "Kuh"), Map.entry("pig", "Schwein"), Map.entry("sheep", "Schaf"), Map.entry("chicken", "Huhn"),
            Map.entry("rabbit", "Kaninchen"), Map.entry("horse", "Pferd"), Map.entry("donkey", "Esel"), Map.entry("mule", "Maultier"),
            Map.entry("villager", "Dorfbewohner"), Map.entry("wandering_trader", "Fahrender Händler"), Map.entry("fox", "Fuchs"),
            Map.entry("cat", "Katze"), Map.entry("ocelot", "Ozelot"), Map.entry("parrot", "Papagei"), Map.entry("bat", "Fledermaus"),
            Map.entry("squid", "Tintenfisch"), Map.entry("glow_squid", "Leuchttintenfisch"), Map.entry("cod", "Kabeljau"), Map.entry("salmon", "Lachs"),
            Map.entry("tropical_fish", "Tropenfisch"), Map.entry("pufferfish", "Kugelfisch"), Map.entry("turtle", "Schildkröte"),
            Map.entry("axolotl", "Axolotl"), Map.entry("frog", "Frosch"), Map.entry("camel", "Kamel"), Map.entry("armadillo", "Gürteltier"),
            Map.entry("strider", "Schreiter"), Map.entry("allay", "Hilfsgeist"), Map.entry("sniffer", "Schnüffler"), Map.entry("player", "Spieler"));

    /** Todesursache ohne Mob oder Spieler – Schlüssel ist Minecrafts "message_id" der Schadensart. */
    private static final Map<String, String> CAUSES = Map.ofEntries(
            Map.entry("fall", "Fallschaden"), Map.entry("lava", "Lava"), Map.entry("inFire", "Feuer"), Map.entry("onFire", "verbrannt"),
            Map.entry("hotFloor", "Magmablock"), Map.entry("drown", "ertrunken"), Map.entry("starve", "verhungert"), Map.entry("cactus", "Kaktus"),
            Map.entry("inWall", "im Block erstickt"), Map.entry("cramming", "zerquetscht"), Map.entry("flyIntoWall", "gegen die Wand geflogen"),
            Map.entry("outOfWorld", "aus der Welt gefallen"), Map.entry("outsideBorder", "Weltbarriere"), Map.entry("magic", "Magie"),
            Map.entry("indirectMagic", "Magie"), Map.entry("wither", "Wither-Effekt"), Map.entry("dragonBreath", "Drachenatem"),
            Map.entry("freeze", "erfroren"), Map.entry("lightningBolt", "Blitz"), Map.entry("explosion", "Explosion"),
            Map.entry("explosion.player", "Explosion"), Map.entry("badRespawnPoint", "Bett oder Seelenanker explodiert"),
            Map.entry("fireworks", "Feuerwerk"), Map.entry("anvil", "Amboss"), Map.entry("fallingBlock", "fallender Block"),
            Map.entry("fallingStalactite", "fallender Tropfstein"), Map.entry("stalagmite", "Tropfstein"), Map.entry("sweetBerryBush", "Beerenstrauch"),
            Map.entry("dryout", "ausgetrocknet"), Map.entry("thorns", "Dornen"), Map.entry("sting", "gestochen"), Map.entry("genericKill", "/kill"),
            Map.entry("generic", "unbekannt"), Map.entry("sonic_boom", "Schallschlag"), Map.entry("fireball", "Feuerball"),
            Map.entry("witherSkull", "Wither-Schädel"), Map.entry("trident", "Dreizack"), Map.entry("arrow", "Pfeil"), Map.entry("thrown", "Wurfgeschoss"),
            Map.entry("mace_smash", "Streitkolben"), Map.entry("spear", "Speer"), Map.entry("mob", "Angriff"), Map.entry("player", "Angriff"));

    private StatNames() {
    }

    static String block(String id) {
        return lookup(BLOCKS, id);
    }

    static String mob(String id) {
        return lookup(MOBS, id);
    }

    /** "mob:minecraft:zombie" → "Zombie", "type:fall" → "Fallschaden", "player" → "anderer Spieler". */
    static String cause(String key) {
        if (key.startsWith("mob:")) return mob(key.substring(4));
        if (key.equals("player")) return "anderer Spieler";
        if (key.startsWith("type:")) {
            String msg = key.substring(5);
            return CAUSES.getOrDefault(msg, pretty(msg));
        }
        return pretty(key);
    }

    private static String lookup(Map<String, String> names, String id) {
        String path = id.startsWith("minecraft:") ? id.substring(10) : id;
        String german = names.get(path);
        return german != null ? german : pretty(id);
    }

    /** "minecraft:sulfur_cube" → "Sulfur Cube", "mod:some_block" → "Some Block (mod)", "sulfurCubeHot" → "Sulfur Cube Hot". */
    static String pretty(String id) {
        String ns = "", path = id;
        int colon = id.indexOf(':');
        if (colon >= 0) {
            ns = id.substring(0, colon);
            path = id.substring(colon + 1);
        }
        path = path.replaceAll("([a-z])([A-Z])", "$1_$2").replace('.', '_');
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) out.append(id);
        return ns.isEmpty() || ns.equals("minecraft") ? out.toString() : out + " (" + ns + ")";
    }
}
