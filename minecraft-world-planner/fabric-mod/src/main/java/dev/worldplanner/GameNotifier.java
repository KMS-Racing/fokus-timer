package dev.worldplanner;

import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Nachrichten im Spiel: kurz nach dem Einloggen eine Zusammenfassung "Zu erledigen", danach live alles Neue.
 *
 * So funktioniert es: Der Timer ruft alle 2 Sekunden poll() auf – absichtlich NICHT auf dem Server-Thread, denn ein
 * Speicher kann kurz gesperrt sein (z. B. während der Handel auf die Platte schreibt) und das Spiel soll dabei nicht
 * ruckeln. poll() berechnet für jeden Online-Spieler die Hinweise (Notices) aus dem echten Zustand und vergleicht sie
 * mit dem letzten Mal: neue id = neue Nachricht. Verschickt wird danach auf dem Server-Thread (WorldPlannerMod).
 *
 * Es gibt keine Nachrichten "auf Vorrat": Wer offline war, bekommt beim Einloggen nur, was jetzt noch zu erledigen ist.
 * Wer "/planer nachrichten aus" eingestellt hat, bekommt nichts – auch keine Nachholung, wenn er es wieder anschaltet.
 */
final class GameNotifier {
    /** Nach dem Einloggen kurz warten, damit die Zusammenfassung nicht zwischen den Join-Meldungen untergeht. */
    static final long SUMMARY_DELAY_MS = 3000;
    /** Neuigkeiten derselben Gruppe (z. B. viele Gebote auf ein Angebot) höchstens alle 15 Sekunden. */
    static final long GROUP_COOLDOWN_MS = 15_000;
    static final int MAX_LIVE_PER_POLL = 3;

    record Outgoing(UUID player, Component message) {}

    private static final class Online {
        final long joinedAt;
        boolean started;
        Set<String> seen = Set.of();
        final Map<String, Long> groupSentAt = new HashMap<>();
        /** Wegen der Pause zurückgehalten – je Gruppe nur der neueste Hinweis. */
        final Map<String, Notices.Notice> waiting = new LinkedHashMap<>();

        Online(long joinedAt) {
            this.joinedAt = joinedAt;
        }
    }

    private final PlannerServices s;
    private final Map<UUID, Online> online = new ConcurrentHashMap<>();
    /** Uhr – in Tests austauschbar. */
    volatile LongSupplier clock = System::currentTimeMillis;

    GameNotifier(PlannerServices services) {
        this.s = services;
    }

    void joined(UUID player) {
        online.put(player, new Online(clock.getAsLong()));
    }

    void left(UUID player) {
        online.remove(player);
    }

    /** Nur vom Timer-Thread aufrufen (immer derselbe Thread). */
    List<Outgoing> poll() {
        long now = clock.getAsLong();
        String today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString();
        String url = s.config().publicUrl();
        List<Outgoing> out = new ArrayList<>();
        for (Map.Entry<UUID, Online> e : online.entrySet()) {
            UUID player = e.getKey();
            Online st = e.getValue();
            if (!st.started && now - st.joinedAt < SUMMARY_DELAY_MS) continue;
            List<Notices.Notice> notices = Notices.collect(s, player, s.players().isAdmin(player), today);
            Set<String> ids = new HashSet<>(Notices.ids(notices));
            boolean wanted = s.prefs().game(player).notices();
            if (!st.started) {
                st.started = true;
                List<Notices.Notice> todos = Notices.todos(notices);
                if (wanted && !todos.isEmpty()) out.add(new Outgoing(player, GameText.summary(todos, url, true)));
            } else if (wanted) {
                List<Notices.Notice> send = new ArrayList<>();
                // Zurückgehaltene Neuigkeiten, deren Pause vorbei ist – aber nur, wenn sie noch stimmen.
                for (Iterator<Notices.Notice> it = st.waiting.values().iterator(); it.hasNext(); ) {
                    Notices.Notice n = it.next();
                    if (!ids.contains(n.id())) {
                        it.remove();
                    } else if (cooledDown(st, n.group(), now)) {
                        send.add(n);
                        st.groupSentAt.put(n.group(), now);
                        it.remove();
                    }
                }
                for (Notices.Notice n : notices) {
                    if (!n.live() || st.seen.contains(n.id())) continue;
                    if (n.group() != null) {
                        if (!cooledDown(st, n.group(), now)) {
                            st.waiting.put(n.group(), n);
                            continue;
                        }
                        st.groupSentAt.put(n.group(), now);
                    }
                    send.add(n);
                }
                for (int i = 0; i < send.size() && i < MAX_LIVE_PER_POLL; i++) out.add(new Outgoing(player, GameText.live(send.get(i), url)));
                if (send.size() > MAX_LIVE_PER_POLL) out.add(new Outgoing(player, GameText.more(send.size() - MAX_LIVE_PER_POLL)));
            } else {
                st.waiting.clear();
            }
            st.seen = ids;
        }
        return out;
    }

    private static boolean cooledDown(Online st, String group, long now) {
        Long last = st.groupSentAt.get(group);
        return last == null || now - last >= GROUP_COOLDOWN_MS;
    }
}
