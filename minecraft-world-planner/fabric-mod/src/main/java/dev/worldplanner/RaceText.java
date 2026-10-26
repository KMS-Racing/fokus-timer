package dev.worldplanner;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Texte fürs Rennen – Chat und Actionbar. Farben wie in der Formel 1: lila = schnellster Abschnitt von allen,
 * grün = eigene Bestzeit, gelb = langsamer als die eigene Bestzeit.
 */
final class RaceText {
    private RaceText() {
    }

    static MutableComponent message() {
        return Component.empty().append(Component.literal("[Rennen] ").withStyle(ChatFormatting.GOLD));
    }

    static Component armed(String track, String type) {
        return message().append(GameText.text("Zeitmessung für »" + track + "« bereit. Die Uhr startet, wenn du über die "
                + (type.equals("sprint") ? "Startlinie" : "Start-/Ziellinie") + " fährst – in Fahrtrichtung. Beenden: /rennen stop", ChatFormatting.WHITE));
    }

    static Component started(String track, String problem) {
        MutableComponent out = message().append(GameText.text("🏁 Los! »" + track + "« – die Uhr läuft.", ChatFormatting.WHITE));
        if (problem != null) out.append(GameText.text(" Achtung: Diese Runde zählt nicht – " + problem + ".", ChatFormatting.RED));
        return out;
    }

    static Component stopped(String reason) {
        return message().append(GameText.text("Zeitmessung beendet: " + reason + ".", ChatFormatting.GRAY));
    }

    /**
     * Ergebnis einer Runde.
     *
     * @param recordHolder Name des bisherigen Rekordhalters (wenn es einen gab)
     */
    static Component lap(RaceTimer.Lap lap, RaceStore.Result r, String recordHolder) {
        String what = lap.sprint() ? "Ziel! " : "Runde " + lap.lap() + ": ";
        MutableComponent out = message();
        if (!lap.valid()) {
            return out.append(GameText.text((lap.sprint() ? "Der Lauf" : "Runde " + lap.lap()) + " zählt nicht: " + lap.reason()
                    + " (" + RaceStore.time(lap.lapMs()) + ").", ChatFormatting.RED));
        }
        out.append(GameText.text(what, ChatFormatting.WHITE)).append(GameText.text(RaceStore.time(lap.lapMs()), ChatFormatting.WHITE).withStyle(ChatFormatting.BOLD));
        if (!lap.clean()) {
            return out.append(GameText.text(" – ungenau: Der Server hat geruckelt, deshalb kommt sie nicht in die Bestenliste.", ChatFormatting.GRAY));
        }
        if (!r.counted()) return out.append(GameText.text(" – die Strecke wurde inzwischen geändert, deshalb zählt sie nicht.", ChatFormatting.GRAY));
        if (r.record()) {
            out.append(GameText.text(" – Streckenrekord!", ChatFormatting.LIGHT_PURPLE));
            if (r.recordBefore() != null) {
                out.append(GameText.text(" (vorher " + RaceStore.time(r.recordBefore().ms()) + (recordHolder == null ? "" : " von " + recordHolder) + ")", ChatFormatting.GRAY));
            }
            return out;
        }
        if (r.personalBest()) {
            out.append(GameText.text(" – persönliche Bestzeit!", ChatFormatting.GREEN));
            if (r.previousBest() != null) out.append(GameText.text(" (" + RaceStore.delta(Math.round(lap.lapMs()) - r.previousBest()) + ")", ChatFormatting.GREEN));
        } else if (r.previousBest() != null) {
            out.append(GameText.text(" – " + RaceStore.delta(Math.round(lap.lapMs()) - r.previousBest()) + " zu deiner Bestzeit", ChatFormatting.YELLOW));
        }
        return out.append(GameText.text(" · Platz " + r.place() + " von " + r.of(), ChatFormatting.GRAY));
    }

    /** Farbe eines Abschnitts: lila (schnellster von allen), grün (eigene Bestzeit), gelb (langsamer). */
    static ChatFormatting sectorColor(double ms, long[] overall, long[] personal, int index) {
        if (overall == null || index >= overall.length || ms <= overall[index]) return ChatFormatting.LIGHT_PURPLE;
        if (personal == null || index >= personal.length || ms <= personal[index]) return ChatFormatting.GREEN;
        return ChatFormatting.YELLOW;
    }

    /**
     * Actionbar während der Fahrt: "🏁 Name · Runde 2 · 1:02,3" und nach einem Tor ein paar Sekunden lang dessen Zeit.
     *
     * @param lastSector letzter Abschnitt (1 = erster) oder 0 = keiner gerade
     */
    static Component live(String track, int lap, boolean sprint, double lapMs, int lastSector, double lastMs, long[] overall, long[] personal, String problem) {
        MutableComponent out = Component.empty().append(GameText.text("🏁 " + track, ChatFormatting.GOLD));
        if (!sprint) out.append(GameText.text(" · Runde " + lap, ChatFormatting.GRAY));
        out.append(GameText.text(" · " + tenths(lapMs), ChatFormatting.WHITE));
        if (lastSector > 0) {
            int i = lastSector - 1;
            out.append(GameText.text(" · S" + lastSector + " " + RaceStore.time(lastMs), sectorColor(lastMs, overall, personal, i)));
            if (personal != null && i < personal.length) out.append(GameText.text(" " + RaceStore.delta(lastMs - personal[i]), ChatFormatting.GRAY));
        }
        if (problem != null) out.append(GameText.text(" · zählt nicht: " + problem, ChatFormatting.RED));
        return out;
    }

    /** Laufende Uhr mit Zehnteln (Hundertstel würden in der Actionbar nur flackern). */
    static String tenths(double ms) {
        long ds = (long) Math.floor(ms / 100);
        long m = ds / 600, s = (ds / 10) % 60, d = ds % 10;
        return (m > 0 ? m + ":" + (s < 10 ? "0" : "") + s : String.valueOf(s)) + "," + d;
    }
}
