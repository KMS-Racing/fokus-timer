package dev.worldplanner;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Navigation im Spiel: /planer weg <Marker|Land|todespunkt> zeigt über der Hotbar einen Pfeil zum Ziel – gedreht nach
 * deiner Blickrichtung – und die Entfernung. Liegt das Ziel in der anderen Dimension (Oberwelt/Nether), führt der Pfeil
 * zur passenden Stelle für ein Portal (Nether = Oberwelt ÷ 8). Kein Teleport, nur ein Wegweiser. Nur Server-Thread.
 */
final class Waypoints {
    static final String OVERWORLD = "minecraft:overworld";
    static final String NETHER = "minecraft:the_nether";
    /** So nah = angekommen. */
    static final double ARRIVED_BLOCKS = 4;
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /** y = null: Höhe unbekannt. */
    record Target(String label, String symbol, String dimension, double x, Integer y, double z) {}

    record Shown(Component text, boolean arrived) {}

    /** Wegweiser, der auf der Webseite gestartet wurde – der Server-Thread holt ihn aus der Warteschlange ab. */
    record Request(UUID player, Target target) {}

    private final Map<UUID, Target> active = new HashMap<>();

    void set(UUID player, Target target) {
        active.put(player, target);
    }

    Target get(UUID player) {
        return active.get(player);
    }

    Target clear(UUID player) {
        return active.remove(player);
    }

    Map<UUID, Target> all() {
        return active;
    }

    /** Text für die Actionbar. */
    static Shown render(Target t, String playerDim, double px, double py, double pz, float yaw) {
        MutableComponent out = Component.empty()
                .append(GameText.text(t.symbol() + " " + t.label(), ChatFormatting.AQUA));
        double tx = t.x(), tz = t.z();
        boolean portal = false;
        if (!t.dimension().equals(playerDim)) {
            if (t.dimension().equals(OVERWORLD) && playerDim.equals(NETHER)) {
                tx = t.x() / 8;
                tz = t.z() / 8;
            } else if (t.dimension().equals(NETHER) && playerDim.equals(OVERWORLD)) {
                tx = t.x() * 8;
                tz = t.z() * 8;
            } else {
                return new Shown(out.append(GameText.text(" liegt in: " + GameText.dimensionName(t.dimension()) + " – von hier aus kein Weg",
                        ChatFormatting.GRAY)), false);
            }
            portal = true;
            out.append(GameText.text(" (" + GameText.dimensionName(t.dimension()) + ")", ChatFormatting.GRAY));
        }
        double dx = tx - px, dz = tz - pz;
        double dist = Math.hypot(dx, dz);
        if (dist < ARRIVED_BLOCKS) {
            if (portal) return new Shown(out.append(GameText.text(" – hier passt ein Portal", ChatFormatting.GREEN)), false);
            return new Shown(out.append(GameText.text(" – angekommen!", ChatFormatting.GREEN)), true);
        }
        out.append(GameText.text("  " + arrow(dx, dz, yaw) + " ", ChatFormatting.YELLOW))
                .append(GameText.text(Notices.num(Math.round(dist)) + " Blöcke", ChatFormatting.WHITE));
        if (portal) {
            out.append(GameText.text(" bis zur Portal-Stelle", ChatFormatting.GRAY));
        } else if (t.y() != null) {
            long dy = Math.round(t.y() - py);
            if (Math.abs(dy) >= 3) out.append(GameText.text(" · " + (dy > 0 ? "↑ " : "↓ ") + Math.abs(dy), ChatFormatting.GRAY));
        }
        return new Shown(out, false);
    }

    /**
     * Pfeil zum Ziel, gedreht nach der Blickrichtung. Minecraft-Drehung: 0° = Süden (+Z), 90° = Westen (−X),
     * nach rechts drehen = größer. ↑ = geradeaus, → = rechts.
     */
    static String arrow(double dx, double dz, float yaw) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - yaw) % 360 + 540) % 360 - 180; // −180 … 180, positiv = rechts
        return ARROWS[Math.floorMod((int) Math.round(rel / 45.0), 8)];
    }
}
