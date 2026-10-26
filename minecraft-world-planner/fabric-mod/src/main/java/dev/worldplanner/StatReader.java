package dev.worldplanner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Liest Minecrafts eigene Statistik eines Spielers (dieselben Zahlen wie im Spiel unter Esc → Statistiken).
 * Nur auf dem Server-Thread benutzen (Minecraft-Objekte). Die Rechnung selbst steht in StatValues.
 */
final class StatReader {
    private final Set<String> blockItems = new HashSet<>();
    private final Set<String> foods = new HashSet<>();

    /** Erst erzeugen, wenn der Server läuft (dann sind alle Items geladen). */
    StatReader() {
        for (Item item : BuiltInRegistries.ITEM) {
            String key = BuiltInRegistries.ITEM.getKey(item).toString();
            if (item instanceof BlockItem) blockItems.add(key);
            if (item.components().has(DataComponents.FOOD)) foods.add(key);
        }
    }

    StatValues.Snapshot read(ServerPlayer player) {
        StatsCounter c = player.getStats();
        StatValues.Raw raw = new StatValues.Raw(
                collect(c, Stats.CUSTOM, Object::toString),
                collect(c, Stats.BLOCK_MINED, b -> BuiltInRegistries.BLOCK.getKey(b).toString()),
                collect(c, Stats.ITEM_USED, i -> BuiltInRegistries.ITEM.getKey(i).toString()),
                collect(c, Stats.ITEM_CRAFTED, i -> BuiltInRegistries.ITEM.getKey(i).toString()),
                collect(c, Stats.ENTITY_KILLED, t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()),
                collect(c, Stats.ENTITY_KILLED_BY, t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
        return compute(raw);
    }

    StatValues.Snapshot compute(StatValues.Raw raw) {
        return StatValues.compute(raw, blockItems::contains, foods::contains);
    }

    /**
     * Ein StatType kennt alle Einträge, die es auf dem Server gibt (aus allen geladenen Spielern) – so müssen nicht alle
     * tausend Blöcke einzeln gefragt werden.
     */
    private static <T> Map<String, Integer> collect(StatsCounter c, StatType<T> type, Function<T, String> key) {
        Map<String, Integer> out = new HashMap<>();
        for (Stat<T> stat : type) {
            int v = c.getValue(stat);
            if (v > 0) out.merge(key.apply(stat.getValue()), v, Integer::sum);
        }
        return out;
    }

    /** {Schlüssel für die Zählung, Text für den Verlauf}. Spielernamen stehen nicht drin (die Statistik ist öffentlich). */
    static String[] deathCause(DamageSource source, ServerPlayer victim) {
        Entity killer = source.getEntity();
        if (killer instanceof ServerPlayer other && other != victim) return new String[]{"player", "Von einem anderen Spieler besiegt"};
        if (killer != null && killer != victim) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType()).toString();
            return new String[]{"mob:" + id, "Gestorben: " + StatNames.mob(id)};
        }
        String key = "type:" + source.getMsgId();
        return new String[]{key, "Gestorben: " + StatNames.cause(key)};
    }
}
