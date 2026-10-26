package dev.worldplanner;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Die Actionbar (die Zeile über der Hotbar) teilen sich Land-Anzeige und Navigation. Wichtigeres hat Vorrang:
 * Solange ein Land-Name gerade angezeigt wird, überschreibt die Navigation ihn nicht.
 * Nur Server-Thread.
 */
final class ActionBar {
    static final int NAVIGATION = 1;
    static final int LAND = 2;

    private record Held(int priority, long untilTick) {}

    private final Map<UUID, Held> held = new HashMap<>();

    /**
     * Darf diese Anzeige jetzt erscheinen? Wenn ja, ist sie für holdTicks reserviert.
     * Gleich wichtige oder wichtigere Anzeigen dürfen immer – eine unwichtigere wartet, bis die Reservierung abläuft.
     */
    boolean claim(UUID player, int priority, int holdTicks, long tick) {
        Held h = held.get(player);
        if (h != null && h.untilTick > tick && h.priority > priority) return false;
        held.put(player, new Held(priority, tick + holdTicks));
        return true;
    }

    void left(UUID player) {
        held.remove(player);
    }
}
