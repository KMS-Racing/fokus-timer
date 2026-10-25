package dev.worldplanner;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Todespunkt: Stirbt ein Spieler, speichert die Mod an der Stelle einen privaten Marker "Todespunkt" (☠).
 * Jeder hat genau einen – beim nächsten Tod wird er verschoben. Name, Sichtbarkeit und Notizen, die man auf der
 * Webseite ändert, bleiben dabei erhalten. Löscht man ihn, entsteht beim nächsten Tod ein neuer.
 *
 * Ehrlich: Das ist nur eine Markierung. Die Items schützt sie nicht – die verschwinden wie immer nach 5 Minuten,
 * wenn der Chunk geladen ist.
 */
final class DeathPoints {
    /** y = null: Höhe unbekannt (wurde auf der Webseite gelöscht). */
    record Point(String dimension, int x, Integer y, int z) {}

    private DeathPoints() {
    }

    /** @return null, wenn gespeichert – sonst der Grund (z. B. Marker-Limit erreicht) */
    static String save(PlannerServices s, UUID player, boolean admin, Point p) {
        JsonObject data = new JsonObject();
        data.addProperty("dimension", p.dimension());
        data.addProperty("x", p.x());
        data.addProperty("y", p.y());
        data.addProperty("z", p.z());
        String id = s.prefs().game(player).deathMarkerId();
        try {
            if (id != null && s.markers().ownMarker(player, id) != null) {
                s.markers().update(player, admin, id, data); // nur die Stelle – was der Spieler geändert hat, bleibt
                return null;
            }
            data.addProperty("name", "Todespunkt");
            data.addProperty("type", "death");
            data.addProperty("symbol", "☠");
            data.addProperty("visibility", "private");
            data.addProperty("notes", "Automatisch gespeichert, als du gestorben bist. Wird beim nächsten Tod verschoben. "
                    + "Abschalten im Spiel: /planer todespunkt aus");
            s.prefs().setDeathMarkerId(player, s.markers().create(player, admin, data));
            return null;
        } catch (PlannerException e) {
            return e.getMessage();
        }
    }

    /** Der gespeicherte Todespunkt (aus dem Marker) oder null. */
    static Point load(PlannerServices s, UUID player) {
        MarkerStore.Marker m = s.markers().ownMarker(player, s.prefs().game(player).deathMarkerId());
        return m == null ? null : new Point(m.dimension(), m.x(), m.y(), m.z());
    }
}
