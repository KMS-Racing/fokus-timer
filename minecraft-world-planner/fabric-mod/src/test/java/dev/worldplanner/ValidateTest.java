package dev.worldplanner;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Gemeinsame Prüfregeln für alle Eingaben. */
class ValidateTest {
    private static int status(Runnable r) {
        return assertThrows(PlannerException.class, r::run).status;
    }

    @Test
    void names() {
        assertEquals("Neue Burg", Validate.name("  Neue   Burg ", 2, 20, "Name"), "Leerzeichen werden vereinheitlicht");
        assertEquals("Über-Straße #2", Validate.name("Über-Straße #2", 2, 20, "Name"), "Umlaute sind erlaubt");
        assertEquals(400, status(() -> Validate.name("<img src=x>", 2, 20, "Name")));
        assertEquals(400, status(() -> Validate.name("a\"b", 2, 20, "Name")));
        assertEquals(400, status(() -> Validate.name("x", 2, 20, "Name")));
        assertEquals(400, status(() -> Validate.name(null, 2, 20, "Name")));
        assertEquals("Burg", Validate.name("Bu‎rg", 2, 20, "Name"), "unsichtbare Steuerzeichen werden entfernt");
    }

    @Test
    void texts() {
        assertEquals("", Validate.text(null, 10, "Text"));
        assertEquals("Zeile 1\nZeile 2", Validate.text("Zeile 1\nZeile 2\u0007", 50, "Text"), "Zeilenumbruch bleibt, Steuerzeichen nicht");
        assertEquals(400, status(() -> Validate.text("x".repeat(11), 10, "Text")));
        assertEquals(400, status(() -> Validate.requiredText("  ", 1, 10, "Text")));
    }

    @Test
    void colorsDatesChoicesAndNumbers() {
        assertEquals("#aabbcc", Validate.color("#AABBCC"));
        assertEquals(400, status(() -> Validate.color("#abc")));
        assertEquals(400, status(() -> Validate.color("red")));
        assertEquals("2026-12-24", Validate.optionalDate(" 2026-12-24 ", "Datum"));
        assertNull(Validate.optionalDate("", "Datum"));
        assertEquals(400, status(() -> Validate.optionalDate("24.12.2026", "Datum")));
        assertEquals(400, status(() -> Validate.optionalDate("2026-02-30", "Datum")));
        assertEquals("a", Validate.oneOf("a", Set.of("a", "b"), "Wahl"));
        assertEquals(400, status(() -> Validate.oneOf("c", Set.of("a", "b"), "Wahl")));
        assertEquals(5, Validate.range(5, 1, 10, "Zahl"));
        assertEquals(400, status(() -> Validate.range(11, 1, 10, "Zahl")));
        assertEquals(-2, Validate.coordinate(-1.5, "X"), "abrunden wie Minecraft");
        assertEquals(400, status(() -> Validate.coordinate(Double.NaN, "X")));
        assertEquals(400, status(() -> Validate.coordinate(30_000_001, "X")));
        assertEquals(400, status(() -> Validate.height(5000)));
    }
}
