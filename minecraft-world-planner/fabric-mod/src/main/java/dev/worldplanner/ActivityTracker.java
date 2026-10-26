package dev.worldplanner;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Aktiv oder AFK? Jede Sekunde wird geschaut, ob sich ein Spieler bewegt oder umgeschaut hat. Wer das afkMs lang nicht
 * getan hat, gilt ab dann als AFK. Bewegung zählt immer – auch im Boot, auf der Lore oder von Wasser geschoben.
 * Nur Server-Thread; die Zeit wird jede Minute abgeholt und in StatsStore gezählt.
 *
 * <p>Gezählt wird in Ticks (× 50 ms) wie Minecrafts Spielzeit – sonst wäre auf einem ruckelnden Server (18 statt 20 Ticks
 * pro Sekunde) „Aktiv“ größer als die Spielzeit. Hängt der Server, laufen keine Ticks, also zählt auch nichts. Ob jemand AFK
 * ist, entscheidet dagegen die echte Uhr: 5 Minuten ohne Bewegung sind 5 echte Minuten.
 */
final class ActivityTracker {
    private static final long MS_PER_TICK = 50;
    private static final double MOVE = 0.1;
    private static final float TURN = 1.0f;

    private static final class State {
        double x, y, z;
        float yaw, pitch;
        long lastActivity, lastTick, active, afk;
    }

    private final long afkMs;
    private final Map<UUID, State> players = new HashMap<>();

    ActivityTracker(long afkMs) {
        this.afkMs = afkMs;
    }

    /** Jede Sekunde für jeden Online-Spieler. now = echte Uhr (ms), tick = Server-Ticks seit dem Start. */
    void check(UUID id, double x, double y, double z, float yaw, float pitch, long now, long tick) {
        State st = players.get(id);
        if (st == null) {
            st = new State();
            st.lastActivity = now;
            st.lastTick = tick;
            set(st, x, y, z, yaw, pitch);
            players.put(id, st);
            return;
        }
        boolean moved = Math.abs(x - st.x) > MOVE || Math.abs(y - st.y) > MOVE || Math.abs(z - st.z) > MOVE
                || Math.abs(yaw - st.yaw) > TURN || Math.abs(pitch - st.pitch) > TURN;
        long step = Math.max(0, tick - st.lastTick) * MS_PER_TICK;
        if (moved) st.lastActivity = now;
        if (now - st.lastActivity >= afkMs) st.afk += step;
        else st.active += step;
        st.lastTick = tick;
        set(st, x, y, z, yaw, pitch);
    }

    private static void set(State st, double x, double y, double z, float yaw, float pitch) {
        st.x = x;
        st.y = y;
        st.z = z;
        st.yaw = yaw;
        st.pitch = pitch;
    }

    boolean isAfk(UUID id, long now) {
        State st = players.get(id);
        return st != null && now - st.lastActivity >= afkMs;
    }

    /** Gesammelte Zeit seit dem letzten Abholen: {aktiv, AFK} in Millisekunden. */
    long[] drain(UUID id) {
        State st = players.get(id);
        if (st == null) return new long[]{0, 0};
        long[] out = {st.active, st.afk};
        st.active = 0;
        st.afk = 0;
        return out;
    }

    void left(UUID id) {
        players.remove(id);
    }

    void clear() {
        players.clear();
    }
}
