package dev.worldplanner;

import java.util.HashMap;
import java.util.Map;

/**
 * Einfache Bremse: höchstens {@code limit} Ereignisse pro Schlüssel (z. B. Absender oder Spieler) in einem
 * Zeitfenster. Thread-sicher. Alte Einträge werden aufgeräumt, damit die Map nicht beliebig wächst.
 */
final class RateLimiter {
    private static final int MAX_KEYS = 10_000;

    private final int limit;
    private final long windowMs;
    private final Map<String, long[]> windows = new HashMap<>(); // Schlüssel -> {Fensterbeginn, Anzahl}

    RateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    synchronized boolean isBlocked(String key, long now) {
        long[] w = windows.get(key);
        return w != null && now - w[0] < windowMs && w[1] >= limit;
    }

    synchronized void record(String key, long now) {
        long[] w = windows.get(key);
        if (w == null || now - w[0] >= windowMs) {
            if (windows.size() >= MAX_KEYS) windows.values().removeIf(old -> now - old[0] >= windowMs);
            windows.put(key, new long[]{now, 1});
        } else {
            w[1]++;
        }
    }

    /** Prüft und zählt in einem Schritt. Gibt false zurück, wenn die Grenze erreicht ist. */
    synchronized boolean tryAcquire(String key, long now) {
        if (isBlocked(key, now)) return false;
        record(key, now);
        return true;
    }
}
