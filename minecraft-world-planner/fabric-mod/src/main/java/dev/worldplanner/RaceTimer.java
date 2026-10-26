package dev.worldplanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Zeitmessung für einen Spieler auf einer Strecke – reine Rechnung, ohne Minecraft (deshalb mit Unit-Tests prüfbar).
 *
 * Jeden Tick kommt die Position. Ein Tor ist eine Linie quer zur Fahrtrichtung; es zählt nur, wer es vorwärts
 * durchfährt. Der genaue Zeitpunkt wird zwischen den beiden Ticks geschätzt (Minecraft kennt Positionen nur alle
 * 0,05 Sekunden) – die Hundertstel sind also gerechnet, nicht gemessen. Gestartet wird fliegend: Die Uhr läuft ab
 * der Start-/Ziellinie.
 *
 * Eine Runde zählt nicht bei: ausgelassenem Tor, falschem Fahrzeug, Fliegen im Kreativmodus, Teleport, Dimension
 * gewechselt, über 30 Minuten. Hat der Server stark geruckelt, ist die Zeit "ungenau" und kommt nicht in die Bestenliste.
 */
final class RaceTimer {
    /** Weiter als so viele Blöcke in einem Tick und schneller als TELEPORT_SPEED = Teleport (Boote auf Blaueis schaffen ~72 Blöcke/s). */
    static final double TELEPORT_BLOCKS = 12;
    static final double TELEPORT_SPEED = 100;
    /** Ein Tick dauert normal 50 ms. Länger als das = der Server hat geruckelt, die Zeit ist ungenau. */
    static final double LAG_MS = 250;
    static final double MAX_LAP_MS = 30 * 60_000;
    /** So weit über oder unter dem Tor zählt eine Durchfahrt noch. */
    static final double VERTICAL = 10;

    sealed interface Event permits Started, Sector, Lap, Stopped {}

    /** Die Uhr läuft (Runde 1 – oder nach einem Neustart über die Startlinie). */
    record Started(int lap, String problem) implements Event {}

    /** Ein Tor geschafft. sector: 1 = erster Abschnitt. */
    record Sector(int lap, int sector, double sectorMs, double lapMs) implements Event {}

    /**
     * Runde (Rundkurs) oder Lauf (Sprint) fertig. valid = zählt; reason = warum nicht. clean = ohne Ruckler gemessen.
     * sectorsMs enthält bei gültigen Runden alle Abschnitte.
     */
    record Lap(int lap, double lapMs, double[] sectorsMs, boolean valid, String reason, boolean clean, boolean sprint) implements Event {}

    /** Messung abgebrochen (z. B. andere Dimension). Danach wartet die Uhr wieder auf die Startlinie. */
    record Stopped(String reason) implements Event {}

    private record Crossing(int gate, double t) {}

    private final RaceStore.Track track;
    private final int n;
    private final boolean sprint;
    private boolean running;
    private double lapStart, sectorStart;
    private int next, lap;
    private final List<Double> sectors = new ArrayList<>();
    private String invalid;
    private boolean lag;
    private boolean hasPrev;
    private double px, py, pz, pt;

    RaceTimer(RaceStore.Track track) {
        this.track = track;
        this.n = track.gates().size();
        this.sprint = track.type().equals("sprint");
    }

    RaceStore.Track track() {
        return track;
    }

    boolean running() {
        return running;
    }

    int lap() {
        return lap;
    }

    /** Nächstes Tor (0 = Start/Ziel). */
    int nextGate() {
        return next;
    }

    double lapMs(double nowMs) {
        return running ? nowMs - lapStart : 0;
    }

    String problem() {
        return invalid;
    }

    /**
     * Ein Tick.
     *
     * @param nowMs  Zeitpunkt des Ticks in Millisekunden (Echtzeit – so zählt ein ruckelnder Server nicht zu schnell)
     * @param mode   wie der Spieler unterwegs ist: foot, boat, horse, elytra, other (anderes Reittier), fly, creative
     * @param sameDimension ist der Spieler in der Dimension der Strecke?
     */
    List<Event> tick(double x, double y, double z, boolean sameDimension, double nowMs, String mode) {
        List<Event> out = new ArrayList<>();
        if (!sameDimension) {
            if (running) out.add(new Stopped("Dimension gewechselt"));
            running = false;
            hasPrev = false;
            return out;
        }
        if (!hasPrev) {
            remember(x, y, z, nowMs);
            return out;
        }
        double dx = x - px, dy = y - py, dz = z - pz, dt = nowMs - pt;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > TELEPORT_BLOCKS && (dt <= 0 || dist / dt * 1000 > TELEPORT_SPEED)) {
            if (running) out.add(new Lap(lap, nowMs - lapStart, null, false, "Teleport erkannt", !lag, sprint));
            running = false;
            remember(x, y, z, nowMs);
            return out;
        }
        if (running && dt > LAG_MS) lag = true;
        if (running && invalid == null) invalid = modeProblem(track.vehicle(), mode);
        for (Crossing c : crossings(px, py, pz, x, y, z)) handle(c.gate(), pt + c.t() * dt, mode, out);
        if (running && nowMs - lapStart > MAX_LAP_MS) {
            out.add(new Lap(lap, nowMs - lapStart, null, false, "über 30 Minuten", !lag, sprint));
            running = false;
        }
        remember(x, y, z, nowMs);
        return out;
    }

    /** /rennen stop, Tod, Logout. */
    List<Event> stop(String reason) {
        List<Event> out = new ArrayList<>();
        if (running) out.add(new Stopped(reason));
        running = false;
        hasPrev = false;
        return out;
    }

    private void remember(double x, double y, double z, double t) {
        px = x;
        py = y;
        pz = z;
        pt = t;
        hasPrev = true;
    }

    private void handle(int gate, double at, String mode, List<Event> out) {
        if (!running) {
            if (gate == 0) start(at, mode, out);
            return;
        }
        if (gate == next) {
            double sector = at - sectorStart;
            sectors.add(sector);
            sectorStart = at;
            boolean done = sprint ? gate == n - 1 : gate == 0;
            if (!done) {
                out.add(new Sector(lap, sectors.size(), sector, at - lapStart));
                next = (gate + 1) % n;
                return;
            }
            double[] s = sectors.stream().mapToDouble(Double::doubleValue).toArray();
            out.add(new Lap(lap, at - lapStart, invalid == null ? s : null, invalid == null, invalid, !lag, sprint));
            if (sprint) running = false; // fertig – über die Startlinie geht es wieder von vorn
            else startLap(at, lap + 1, mode, out, false);
            return;
        }
        if (gate == 0) {
            // Wieder an der Startlinie, ohne alle Tore: Diese Runde zählt nicht, eine neue beginnt hier
            if (!sprint) out.add(new Lap(lap, at - lapStart, null, false, gateName(next) + " ausgelassen", !lag, false));
            startLap(at, sprint ? 1 : lap + 1, mode, out, sprint);
            return;
        }
        if (invalid == null) invalid = gateName(next) + " ausgelassen";
        sectors.add(at - sectorStart);
        sectorStart = at;
        next = (gate + 1) % n;
    }

    private void start(double at, String mode, List<Event> out) {
        startLap(at, 1, mode, out, true);
    }

    private void startLap(double at, int number, String mode, List<Event> out, boolean announce) {
        running = true;
        lap = number;
        lapStart = at;
        sectorStart = at;
        next = 1;
        sectors.clear();
        lag = false;
        invalid = modeProblem(track.vehicle(), mode);
        if (announce) out.add(new Started(lap, invalid));
    }

    String gateName(int gate) {
        if (gate == 0) return sprint ? "der Start" : "Start/Ziel";
        if (sprint && gate == n - 1) return "das Ziel";
        return "Tor " + gate;
    }

    /** Darf man so fahren? Null = ja, sonst der Grund. */
    static String modeProblem(String vehicle, String mode) {
        if (mode.equals("creative")) return "Kreativ- oder Zuschauermodus";
        if (mode.equals("fly")) return "Fliegen ohne Elytra";
        return switch (vehicle) {
            case "foot" -> mode.equals("foot") ? null : "nur zu Fuß erlaubt";
            case "boat" -> mode.equals("boat") ? null : "nur im Boot erlaubt";
            case "horse" -> mode.equals("horse") ? null : "nur auf dem Pferd erlaubt";
            case "elytra" -> mode.equals("elytra") || mode.equals("foot") ? null : "nur mit Elytra (ohne Reittier) erlaubt";
            default -> null;
        };
    }

    /** Alle Tore, die die Bewegung von (x0,z0) nach (x1,z1) vorwärts durchquert – in der Reihenfolge der Durchfahrt. */
    private List<Crossing> crossings(double x0, double y0, double z0, double x1, double y1, double z1) {
        List<Crossing> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double t = crossing(track.gates().get(i), x0, y0, z0, x1, y1, z1);
            if (t >= 0) out.add(new Crossing(i, t));
        }
        out.sort(Comparator.comparingDouble(Crossing::t));
        return out;
    }

    /** Anteil der Bewegung (0 … 1), an dem das Tor vorwärts durchfahren wird – oder −1. */
    static double crossing(RaceStore.Gate g, double x0, double y0, double z0, double x1, double y1, double z1) {
        double rad = Math.toRadians(g.yaw());
        double fx = -Math.sin(rad), fz = Math.cos(rad); // vorwärts (Minecraft: 0° = Süden)
        double s0 = (x0 - g.x()) * fx + (z0 - g.z()) * fz;
        double s1 = (x1 - g.x()) * fx + (z1 - g.z()) * fz;
        if (!(s0 < 0 && s1 >= 0)) return -1; // nur von hinten nach vorn
        double t = s0 / (s0 - s1);
        double qx = x0 + (x1 - x0) * t, qz = z0 + (z1 - z0) * t, qy = y0 + (y1 - y0) * t;
        double across = (qx - g.x()) * Math.cos(rad) + (qz - g.z()) * Math.sin(rad);
        if (Math.abs(across) > g.width() / 2.0 || Math.abs(qy - g.y()) > VERTICAL) return -1;
        return t;
    }
}
