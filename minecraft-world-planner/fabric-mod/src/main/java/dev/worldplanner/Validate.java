package dev.worldplanner;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gemeinsame Prüfregeln für alle Eingaben. Alle Speicher benutzen diese Methoden, damit z. B. ein Name
 * überall nach denselben Regeln geprüft wird. Fehler werden als PlannerException (400) mit deutscher
 * Meldung geworfen.
 */
final class Validate {
    /** Namen: Buchstaben (auch Umlaute), Ziffern, Leerzeichen und ein paar harmlose Zeichen. Kein < > & " ' */
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _\\-.!?+()#:]+");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** Steuerzeichen außer Zeilenumbruch und Tab (z. B. unsichtbare Richtungswechsel-Zeichen) */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");
    static final int WORLD_LIMIT = 30_000_000;

    private Validate() {}

    /** Prüft einen Namen und vereinheitlicht Leerzeichen. */
    static String name(String raw, int min, int max, String label) {
        String value = raw == null ? "" : CONTROL.matcher(raw).replaceAll("").strip().replaceAll("\\s+", " ");
        if (value.length() < min || value.length() > max) {
            throw PlannerException.badRequest(label + " muss " + min + "–" + max + " Zeichen lang sein.");
        }
        if (!NAME.matcher(value).matches()) {
            throw PlannerException.badRequest(label + " darf nur Buchstaben, Zahlen, Leerzeichen und _ - . ! ? + ( ) # : enthalten.");
        }
        return value;
    }

    /** Freier Text (Beschreibung, Notiz). Leer erlaubt. Steuerzeichen werden entfernt. */
    static String text(String raw, int max, String label) {
        if (raw == null) return "";
        String value = CONTROL.matcher(raw).replaceAll("").strip();
        if (value.length() > max) throw PlannerException.badRequest(label + " ist zu lang (max. " + max + " Zeichen).");
        return value;
    }

    /** Pflicht-Text mit Mindestlänge. */
    static String requiredText(String raw, int min, int max, String label) {
        String value = text(raw, max, label);
        if (value.length() < min) throw PlannerException.badRequest(label + " muss mindestens " + min + " Zeichen lang sein.");
        return value;
    }

    static String color(String raw) {
        if (raw == null || !COLOR.matcher(raw).matches()) throw PlannerException.badRequest("Farbe muss so aussehen: #a1b2c3");
        return raw.toLowerCase(Locale.ROOT);
    }

    /** Datum im Format JJJJ-MM-TT oder null/leer für "kein Datum". */
    static String optionalDate(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.strip()).toString();
        } catch (DateTimeParseException e) {
            throw PlannerException.badRequest(label + " muss ein Datum wie 2026-12-24 sein.");
        }
    }

    static String oneOf(String raw, Set<String> allowed, String label) {
        if (raw == null || !allowed.contains(raw)) throw PlannerException.badRequest("Ungültige Angabe für " + label + ".");
        return raw;
    }

    static String oneOf(String raw, Map<String, String> allowed, String label) {
        return oneOf(raw, allowed.keySet(), label);
    }

    static int range(long value, int min, int max, String label) {
        if (value < min || value > max) throw PlannerException.badRequest(label + " muss zwischen " + min + " und " + max + " liegen.");
        return (int) value;
    }

    /** Blockkoordinate innerhalb der Minecraft-Welt (±30 Mio.). */
    static int coordinate(double value, String label) {
        if (!Double.isFinite(value) || Math.abs(value) > WORLD_LIMIT) throw PlannerException.badRequest("Koordinate " + label + " ist ungültig.");
        return (int) Math.floor(value);
    }

    /** Höhe (Y) – Minecraft erlaubt per Datapack bis ±2032, mehr ergibt keinen Sinn. */
    static int height(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 4096) throw PlannerException.badRequest("Koordinate Y ist ungültig.");
        return (int) Math.floor(value);
    }
}
