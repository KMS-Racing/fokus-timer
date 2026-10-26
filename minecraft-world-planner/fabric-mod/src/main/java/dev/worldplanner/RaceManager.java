package dev.worldplanner;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Laufende Zeitmessungen im Spiel (nur Server-Thread): jeden Tick die Position an RaceTimer, Runden in RaceStore
 * eintragen, Nachrichten und Actionbar. Außerdem die Tore als Partikel (beim Bauen, /rennen zeigen, und während der
 * Fahrt das nächste Tor) – immer nur für den einen Spieler.
 */
final class RaceManager {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldPlannerMod.MOD_ID);
    private static final int START = 0xffffff, GATE = 0xffd36b, NEXT = 0x7cfc8a, FINISH = 0xff6b6b;
    /** Partikel nur für Tore in dieser Entfernung. */
    private static final double SHOW_RADIUS = 64;

    private static final class Session {
        final RaceTimer timer;
        long[] overall, personal;
        int flashSector;
        double flashMs;
        long flashUntil;

        Session(RaceTimer timer) {
            this.timer = timer;
        }
    }

    private record Show(String dimension, List<RaceStore.Gate> gates, String type, long untilTick) {}

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Show> shows = new HashMap<>();

    /** /rennen start – die Uhr wartet auf die Startlinie. */
    void arm(ServerPlayer player, PlannerServices s, RaceStore.Track track, long tick) {
        Session session = new Session(new RaceTimer(track));
        refreshBests(session, s, player.getUUID());
        sessions.put(player.getUUID(), session);
        show(player.getUUID(), track.dimension(), track.gates(), track.type(), tick, 20);
        send(player, RaceText.armed(track.name(), track.type()));
    }

    boolean racing(UUID player) {
        return sessions.containsKey(player);
    }

    RaceStore.Track trackOf(UUID player) {
        Session session = sessions.get(player);
        return session == null ? null : session.timer.track();
    }

    /** /rennen stop, Tod. */
    void stop(ServerPlayer player, String reason) {
        Session session = sessions.remove(player.getUUID());
        if (session == null) return;
        session.timer.stop(reason);
        send(player, RaceText.stopped(reason));
    }

    void left(UUID player) {
        sessions.remove(player);
        shows.remove(player);
    }

    void clear() {
        sessions.clear();
        shows.clear();
    }

    /** Tore als Partikel zeigen (seconds Sekunden lang). */
    void show(UUID player, String dimension, List<RaceStore.Gate> gates, String type, long tick, int seconds) {
        shows.put(player, new Show(dimension, List.copyOf(gates), type, tick + seconds * 20L));
    }

    /** Jeden Tick. */
    void tick(MinecraftServer server, PlannerServices s, ActionBar bar, long tick) {
        if (sessions.isEmpty() && shows.isEmpty()) return;
        double now = System.nanoTime() / 1e6;
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                sessions.remove(e.getKey());
                continue;
            }
            Session session = e.getValue();
            RaceStore.Track track = session.timer.track();
            boolean same = dimensionId(player).equals(track.dimension());
            List<RaceTimer.Event> events = session.timer.tick(player.getX(), player.getY(), player.getZ(), same, now, mode(player));
            for (RaceTimer.Event ev : events) handle(player, s, session, ev, tick);
            if (tick % 5 == 0 || !events.isEmpty()) showLive(player, session, now, bar, tick);
            if (tick % 10 == 0 && same && session.timer.running()) {
                // das nächste Tor leuchtet grün – so sieht man, wohin es geht
                drawGate(player, track.gates().get(session.timer.nextGate()), NEXT);
            }
        }
        if (tick % 10 == 0) drawShows(server, tick);
    }

    private void handle(ServerPlayer player, PlannerServices s, Session session, RaceTimer.Event ev, long tick) {
        RaceStore.Track track = session.timer.track();
        switch (ev) {
            case RaceTimer.Started st -> send(player, RaceText.started(track.name(), st.problem()));
            case RaceTimer.Sector sec -> {
                session.flashSector = sec.sector();
                session.flashMs = sec.sectorMs();
                session.flashUntil = tick + 60;
            }
            case RaceTimer.Lap lap -> {
                RaceStore.Result result = new RaceStore.Result(false, false, false, null, null, 0, 0);
                String holder = null;
                if (lap.valid()) {
                    long[] sectors = new long[lap.sectorsMs().length];
                    for (int i = 0; i < sectors.length; i++) sectors[i] = Math.round(lap.sectorsMs()[i]);
                    RaceStore.Lap stored = new RaceStore.Lap(player.getUUID(), Math.round(lap.lapMs()), sectors, System.currentTimeMillis(),
                            track.version(), mode(player), lap.clean());
                    result = s.races().recordLap(track.id(), stored);
                    java.util.Set<UUID> hidden = s.prefs().statsHidden();
                    if (result.recordBefore() != null) {
                        UUID before = result.recordBefore().player();
                        // Wer sich aus Ranglisten ausgeblendet hat, wird auch hier nicht genannt
                        holder = before.equals(player.getUUID()) ? "dir" : hidden.contains(before) ? null : s.players().name(before);
                    }
                    if (result.record() && !hidden.contains(player.getUUID())) {
                        s.activity().add(player.getUUID(), "race.record", s.players().name(player.getUUID()) + " hat den Streckenrekord auf »"
                                + track.name() + "« geholt: " + RaceStore.time(stored.ms()), "race", track.id(), "all");
                    }
                    if (lap.sectorsMs().length > 0) {
                        int last = lap.sectorsMs().length;
                        session.flashSector = last;
                        session.flashMs = lap.sectorsMs()[last - 1];
                        session.flashUntil = tick + 60;
                    }
                }
                send(player, RaceText.lap(lap, result, holder));
                refreshBests(session, s, player.getUUID());
            }
            case RaceTimer.Stopped stopped -> send(player, RaceText.stopped(stopped.reason()));
        }
    }

    private void refreshBests(Session session, PlannerServices s, UUID player) {
        String id = session.timer.track().id();
        session.overall = s.races().bestSectors(id);
        session.personal = s.races().personalSectors(id, player);
    }

    private void showLive(ServerPlayer player, Session session, double now, ActionBar bar, long tick) {
        RaceTimer timer = session.timer;
        RaceStore.Track track = timer.track();
        Component text;
        if (timer.running()) {
            boolean flash = tick < session.flashUntil;
            text = RaceText.live(track.name(), timer.lap(), track.type().equals("sprint"), timer.lapMs(now), flash ? session.flashSector : 0,
                    session.flashMs, session.overall, session.personal, timer.problem());
        } else {
            RaceStore.Gate start = track.gates().getFirst();
            Waypoints.Target target = new Waypoints.Target("Start »" + track.name() + "«", "🏁", track.dimension(), start.x(), (int) Math.round(start.y()), start.z());
            text = Waypoints.render(target, dimensionId(player), player.getX(), player.getY(), player.getZ(), player.getYRot()).text();
        }
        // eine halbe Sekunde halten: sonst würde ein Wegweiser dazwischenfunken (die Anzeige kommt alle 5 Ticks neu)
        if (bar.claim(player.getUUID(), ActionBar.RACE, 10, tick)) player.sendOverlayMessage(text);
    }

    private void drawShows(MinecraftServer server, long tick) {
        for (Map.Entry<UUID, Show> e : new ArrayList<>(shows.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Show show = e.getValue();
            if (player == null || show.untilTick() < tick) {
                shows.remove(e.getKey());
                continue;
            }
            if (!dimensionId(player).equals(show.dimension())) continue;
            List<RaceStore.Gate> gates = show.gates();
            for (int i = 0; i < gates.size(); i++) {
                int color = i == 0 ? START : show.type().equals("sprint") && i == gates.size() - 1 ? FINISH : GATE;
                drawGate(player, gates.get(i), color);
            }
        }
    }

    /** Ein Tor: Linie in Hüfthöhe und zwei Pfosten. */
    private static void drawGate(ServerPlayer player, RaceStore.Gate g, int color) {
        if (Math.hypot(g.x() - player.getX(), g.z() - player.getZ()) > SHOW_RADIUS) return;
        ServerLevel level = (ServerLevel) player.level();
        DustParticleOptions dust = new DustParticleOptions(color, 1.4f);
        double rad = Math.toRadians(g.yaw());
        double ax = Math.cos(rad), az = Math.sin(rad); // quer zur Fahrtrichtung
        double half = g.width() / 2.0;
        for (double d = -half; d <= half + 0.01; d += 2) {
            level.sendParticles(player, dust, true, false, g.x() + ax * d, g.y() + 1, g.z() + az * d, 1, 0, 0, 0, 0);
        }
        for (double side : new double[]{-half, half}) {
            for (double h = 0; h <= 3; h += 0.75) {
                level.sendParticles(player, dust, true, false, g.x() + ax * side, g.y() + h, g.z() + az * side, 1, 0, 0, 0, 0);
            }
        }
    }

    private static void send(ServerPlayer player, Component msg) {
        player.sendSystemMessage(msg);
        LOGGER.debug("[World Planner] Rennen-Nachricht an {}: {}", player.getGameProfile().name(), msg.getString());
    }

    /** Wie ist der Spieler unterwegs? (für die Fahrzeug-Regel der Strecke) */
    static String mode(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) return "creative";
        if (player.getAbilities().flying) return "fly";
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof AbstractBoat) return "boat";
        if (vehicle instanceof AbstractHorse) return "horse";
        if (vehicle != null) return "other";
        return player.isFallFlying() ? "elytra" : "foot";
    }

    private static String dimensionId(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }
}
