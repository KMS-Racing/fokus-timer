package dev.worldplanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Chunk-Koordinaten nach Minecraft-Regeln – besonders knapp unter null. */
class ChunksTest {
    @Test
    void blockToChunkFollowsMinecraftRules() {
        assertEquals(0, Chunks.fromBlock(0));
        assertEquals(0, Chunks.fromBlock(15));
        assertEquals(0, Chunks.fromBlock(15.99));
        assertEquals(1, Chunks.fromBlock(16));
        assertEquals(-1, Chunks.fromBlock(-0.01), "Block -0,01 liegt in Block -1 und damit in Chunk -1");
        assertEquals(-1, Chunks.fromBlock(-1));
        assertEquals(-1, Chunks.fromBlock(-16));
        assertEquals(-2, Chunks.fromBlock(-17));
        assertEquals(1_874_999, Chunks.fromBlock(29_999_999));
        assertEquals(-1_875_000, Chunks.fromBlock(-30_000_000));
    }

    @Test
    void keyRoundTripIncludingNegativesAndExtremes() {
        int[][] cases = {{0, 0}, {1, -1}, {-1, 1}, {-1, -1}, {1_875_000, -1_875_000}, {Integer.MAX_VALUE, Integer.MIN_VALUE}};
        for (int[] c : cases) {
            long key = Chunks.key(c[0], c[1]);
            assertEquals(c[0], Chunks.x(key));
            assertEquals(c[1], Chunks.z(key));
        }
    }

    @Test
    void keyMatchesMinecraftFormat() {
        // Minecraft: (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32
        assertEquals(0x00000002_00000001L, Chunks.key(1, 2));
        assertEquals(0xFFFFFFFF_FFFFFFFFL, Chunks.key(-1, -1));
    }
}
