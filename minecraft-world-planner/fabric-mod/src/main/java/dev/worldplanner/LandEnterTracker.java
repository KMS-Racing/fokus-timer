package dev.worldplanner;

import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Land-Anzeige: Betritt man ein Land, steht sein Name kurz über der Hotbar (Actionbar), beim Verlassen "Wildnis".
 * Das ist nur eine Anzeige – ein Land schützt nicht automatisch vor Griefing.
 *
 * Läuft auf dem Server-Thread (braucht die Position der Spieler), fragt die Länder aber nur, wenn jemand den Chunk
 * gewechselt hat – ein Nachschlagen in einer Hash-Tabelle.
 */
final class LandEnterTracker {
    private record Where(String dimension, long chunk) {}

    private final Map<UUID, Where> lastChunk = new HashMap<>();
    /** id des Landes, in dem der Spieler zuletzt stand ("" = Wildnis). */
    private final Map<UUID, String> lastLand = new HashMap<>();

    /** @return Text für die Actionbar oder null, wenn sich nichts geändert hat (oder der Spieler es ausgeschaltet hat) */
    Component update(PlannerServices s, UUID player, String dimension, long chunk) {
        Where now = new Where(dimension, chunk);
        if (now.equals(lastChunk.put(player, now))) return null;
        LandStore.Here here = s.lands().landAt(dimension, chunk, player);
        String id = here == null ? "" : here.id();
        String before = lastLand.put(player, id);
        if (id.equals(before) || !s.prefs().game(player).landEnter()) return null;
        if (here == null) return before == null ? null : GameText.wilderness(); // eingeloggt in der Wildnis: nichts anzeigen
        return GameText.landEnter(here, s.teams().publicTag(here.teamId()));
    }

    void left(UUID player) {
        lastChunk.remove(player);
        lastLand.remove(player);
    }
}
