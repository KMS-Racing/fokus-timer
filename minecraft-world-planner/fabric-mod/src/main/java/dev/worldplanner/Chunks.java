package dev.worldplanner;

/**
 * Ein Chunk ist ein 16x16-Block-Feld. Wir speichern seine Position (x, z) als eine
 * einzige long-Zahl, damit Sets und Maps schnell und sparsam sind.
 * Das Format ist dasselbe wie Minecrafts ChunkPos.pack().
 */
final class Chunks {
    private Chunks() {}

    static long key(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    static int x(long key) {
        return (int) key;
    }

    static int z(long key) {
        return (int) (key >>> 32);
    }

    /** Chunk-Koordinate eines Blocks: Block 0..15 → Chunk 0, Block -1..-16 → Chunk -1. */
    static int fromBlock(double block) {
        return Math.floorDiv((int) Math.floor(block), 16);
    }
}
