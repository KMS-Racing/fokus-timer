package dev.worldplanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.worldplanner.TestSupport.chunks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Umriss-Berechnung für BlueMap. */
class LandOutlineTest {
    @Test
    void singleChunkIsOneSquare() {
        List<LandOutline.Polygon> p = LandOutline.of(chunks(0, 0));
        assertEquals(1, p.size());
        assertEquals(4, p.getFirst().outer().size());
        assertEquals(1.0, LandOutline.signedArea(p.getFirst().outer()));
        assertTrue(p.getFirst().holes().isEmpty());
    }

    @Test
    void rowOfChunksBecomesOneRectangle() {
        List<LandOutline.Polygon> p = LandOutline.of(chunks(-1, 0, 0, 0, 1, 0));
        assertEquals(1, p.size());
        assertEquals(4, p.getFirst().outer().size(), "Gerade Kanten werden zusammengefasst");
        assertEquals(3.0, LandOutline.signedArea(p.getFirst().outer()));
    }

    @Test
    void lShapeHasSixCorners() {
        List<LandOutline.Polygon> p = LandOutline.of(chunks(0, 0, 1, 0, 0, 1));
        assertEquals(1, p.size());
        assertEquals(6, p.getFirst().outer().size());
        assertEquals(3.0, LandOutline.signedArea(p.getFirst().outer()));
    }

    @Test
    void ringHasAHole() {
        // 3x3 ohne Mitte, mit negativen Koordinaten
        List<LandOutline.Polygon> p = LandOutline.of(chunks(-2, -2, -1, -2, 0, -2, -2, -1, 0, -1, -2, 0, -1, 0, 0, 0));
        assertEquals(1, p.size());
        assertEquals(1, p.getFirst().holes().size());
        assertEquals(9.0, LandOutline.signedArea(p.getFirst().outer()));
        assertEquals(-1.0, LandOutline.signedArea(p.getFirst().holes().getFirst()));
    }

    @Test
    void diagonalChunksStaySeparate() {
        assertEquals(2, LandOutline.of(chunks(0, 0, 1, 1)).size());
    }
}
