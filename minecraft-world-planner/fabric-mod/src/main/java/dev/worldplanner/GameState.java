package dev.worldplanner;

import java.util.UUID;

/**
 * Was im Spiel gerade läuft, aber nicht gespeichert wird: Actionbar-Vorrang, Navigation, angezeigte Land-Grenzen.
 * Lebt nur auf dem Server-Thread (Befehle und Ticks laufen beide dort).
 */
final class GameState {
    final ActionBar actionBar = new ActionBar();
    final Waypoints waypoints = new Waypoints();
    final LandBorders borders = new LandBorders();
    /** Aktueller Server-Tick (20 pro Sekunde). */
    long tick;

    void left(UUID player) {
        actionBar.left(player);
        waypoints.clear(player);
        borders.left(player);
    }

    void clear() {
        waypoints.all().clear();
        borders.all().clear();
    }
}
