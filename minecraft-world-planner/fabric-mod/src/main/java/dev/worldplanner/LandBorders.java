package dev.worldplanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Land-Grenzen sichtbar machen: /land grenzen zeigt für ein paar Sekunden Partikel in der Landfarbe entlang des Randes –
 * nur für den Spieler, der gefragt hat. Auch direkt nach /land neu und /land erweitern.
 * Der Rand kommt aus LandOutline (dieselbe Rechnung wie für BlueMap). Nur Server-Thread.
 */
final class LandBorders {
    /** Rand-Stück in Blockkoordinaten. */
    record Segment(double x1, double z1, double x2, double z2) {}

    record Show(String dimension, List<Segment> segments, int color, long untilTick) {}

    /** Partikel nur in dieser Entfernung (weiter weg sieht man sie ohnehin kaum) und alle STEP Blöcke eins – so bleibt es bei
     *  höchstens ein paar hundert kleinen Paketen pro Sekunde, und das nur 20 Sekunden lang. */
    static final double RADIUS = 40;
    static final double STEP = 2;

    private final Map<UUID, Show> shows = new HashMap<>();

    void show(UUID player, String dimension, long[] chunks, String color, long untilTick) {
        shows.put(player, new Show(dimension, segments(chunks), GameText.rgb(color), untilTick));
    }

    Map<UUID, Show> all() {
        return shows;
    }

    void left(UUID player) {
        shows.remove(player);
    }

    /** Alle Randstücke eines Landes (Außenränder und Löcher). */
    static List<Segment> segments(long[] chunks) {
        List<Segment> out = new ArrayList<>();
        for (LandOutline.Polygon p : LandOutline.of(chunks)) {
            addRing(out, p.outer());
            for (List<int[]> hole : p.holes()) addRing(out, hole);
        }
        return out;
    }

    private static void addRing(List<Segment> out, List<int[]> ring) {
        for (int i = 0; i < ring.size(); i++) {
            int[] a = ring.get(i), b = ring.get((i + 1) % ring.size());
            out.add(new Segment(a[0] * 16.0, a[1] * 16.0, b[0] * 16.0, b[1] * 16.0));
        }
    }

    /** Stellen für Partikel (x, z) in der Nähe des Spielers. */
    static List<double[]> points(List<Segment> segments, double px, double pz) {
        List<double[]> out = new ArrayList<>();
        for (Segment s : segments) {
            double len = Math.hypot(s.x2() - s.x1(), s.z2() - s.z1());
            int n = Math.max(1, (int) Math.ceil(len / STEP));
            for (int i = 0; i <= n; i++) {
                double x = s.x1() + (s.x2() - s.x1()) * i / n, z = s.z1() + (s.z2() - s.z1()) * i / n;
                if (Math.hypot(x - px, z - pz) <= RADIUS) out.add(new double[]{x, z});
            }
        }
        return out;
    }
}
