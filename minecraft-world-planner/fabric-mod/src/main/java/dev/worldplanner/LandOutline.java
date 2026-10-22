package dev.worldplanner;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Macht aus einer Menge von Chunks die Umriss-Polygone (mit Löchern), damit BlueMap ein Land
 * als eine Fläche zeichnet statt als viele einzelne Quadrate.
 *
 * Idee: Jede Chunk-Seite, hinter der kein Chunk desselben Landes liegt, ist ein Stück Rand.
 * Die Randstücke haben eine Richtung (das Land liegt immer rechts davon). Folgt man ihnen,
 * entstehen geschlossene Ringe. Ringe im Uhrzeigersinn sind Außenränder, die anderen Löcher.
 * Koordinaten sind Chunk-Ecken (mal 16 = Blockkoordinaten).
 */
final class LandOutline {
    record Polygon(List<int[]> outer, List<List<int[]>> holes) {}

    // Richtungen: 0 = +x (Osten), 1 = +z (Süden), 2 = -x (Westen), 3 = -z (Norden)
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    private LandOutline() {}

    static List<Polygon> of(long[] chunks) {
        LongOpenHashSet set = new LongOpenHashSet(chunks);
        Long2ObjectOpenHashMap<IntArrayList> edges = new Long2ObjectOpenHashMap<>();
        for (long chunk : chunks) {
            int x = Chunks.x(chunk), z = Chunks.z(chunk);
            if (!set.contains(Chunks.key(x, z - 1))) addEdge(edges, x, z, 0);
            if (!set.contains(Chunks.key(x + 1, z))) addEdge(edges, x + 1, z, 1);
            if (!set.contains(Chunks.key(x, z + 1))) addEdge(edges, x + 1, z + 1, 2);
            if (!set.contains(Chunks.key(x - 1, z))) addEdge(edges, x, z + 1, 3);
        }

        List<List<int[]>> outers = new ArrayList<>();
        List<List<int[]>> holes = new ArrayList<>();
        while (!edges.isEmpty()) {
            long startVertex = edges.keySet().iterator().nextLong();
            int startX = Chunks.x(startVertex), startZ = Chunks.z(startVertex);
            int dir = takeEdge(edges, startVertex, -1);
            List<int[]> ring = new ArrayList<>();
            ring.add(new int[]{startX, startZ});
            int x = startX + DX[dir], z = startZ + DZ[dir];
            while (x != startX || z != startZ) {
                ring.add(new int[]{x, z});
                dir = takeEdge(edges, Chunks.key(x, z), dir);
                x += DX[dir];
                z += DZ[dir];
            }
            List<int[]> simple = removeStraightPoints(ring);
            if (signedArea(simple) > 0) outers.add(simple);
            else holes.add(simple);
        }

        List<Polygon> result = new ArrayList<>();
        List<List<List<int[]>>> holesPerOuter = new ArrayList<>();
        for (List<int[]> outer : outers) holesPerOuter.add(new ArrayList<>());
        for (List<int[]> hole : holes) {
            double[] probe = pointInsideHole(hole);
            int best = -1;
            double bestArea = Double.MAX_VALUE;
            for (int i = 0; i < outers.size(); i++) {
                double area = signedArea(outers.get(i));
                if (area < bestArea && contains(outers.get(i), probe[0], probe[1])) {
                    best = i;
                    bestArea = area;
                }
            }
            if (best >= 0) holesPerOuter.get(best).add(hole);
        }
        for (int i = 0; i < outers.size(); i++) result.add(new Polygon(outers.get(i), holesPerOuter.get(i)));
        return result;
    }

    private static void addEdge(Long2ObjectOpenHashMap<IntArrayList> edges, int x, int z, int dir) {
        edges.computeIfAbsent(Chunks.key(x, z), k -> new IntArrayList(2)).add(dir);
    }

    /** Nimmt eine ausgehende Kante. An Ecken, wo sich zwei Ringe berühren, lieber rechts abbiegen. */
    private static int takeEdge(Long2ObjectOpenHashMap<IntArrayList> edges, long vertex, int incomingDir) {
        IntArrayList out = edges.get(vertex);
        int chosen = out.getInt(0);
        if (incomingDir >= 0 && out.size() > 1) {
            for (int preferred : new int[]{(incomingDir + 1) % 4, incomingDir, (incomingDir + 3) % 4}) {
                if (out.contains(preferred)) {
                    chosen = preferred;
                    break;
                }
            }
        }
        out.rem(chosen);
        if (out.isEmpty()) edges.remove(vertex);
        return chosen;
    }

    private static List<int[]> removeStraightPoints(List<int[]> ring) {
        List<int[]> out = new ArrayList<>();
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            int[] prev = ring.get((i - 1 + n) % n), cur = ring.get(i), next = ring.get((i + 1) % n);
            boolean straight = (prev[0] == cur[0] && cur[0] == next[0]) || (prev[1] == cur[1] && cur[1] == next[1]);
            if (!straight) out.add(cur);
        }
        return out;
    }

    /** Gaußsche Trapezformel. Positiv = im Uhrzeigersinn (bei z nach unten) = Außenrand. */
    static double signedArea(List<int[]> ring) {
        double sum = 0;
        for (int i = 0; i < ring.size(); i++) {
            int[] a = ring.get(i), b = ring.get((i + 1) % ring.size());
            sum += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return sum / 2;
    }

    /** Ein Punkt, der sicher im Loch liegt: Mitte der ersten Kante, ein halbes Feld nach links versetzt. */
    private static double[] pointInsideHole(List<int[]> hole) {
        int[] a = hole.get(0), b = hole.get(1);
        double dx = Math.signum(b[0] - a[0]), dz = Math.signum(b[1] - a[1]);
        // links von der Laufrichtung (z zeigt nach unten): (dz, -dx)
        return new double[]{a[0] + dx * 0.5 + dz * 0.5, a[1] + dz * 0.5 - dx * 0.5};
    }

    /** Strahl-Test: Liegt der Punkt im Polygon? */
    private static boolean contains(List<int[]> ring, double px, double pz) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            int[] a = ring.get(i), b = ring.get(j);
            if ((a[1] > pz) != (b[1] > pz) && px < (double) (b[0] - a[0]) * (pz - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return inside;
    }
}
