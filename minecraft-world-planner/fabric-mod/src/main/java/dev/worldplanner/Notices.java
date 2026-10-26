package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Hinweise für einen Spieler – immer aus dem echten Zustand der Speicher berechnet, nie ausgedacht.
 *
 * - "Zu erledigen" (todo): dieselben Punkte wie auf dem Dashboard der Webseite. Sie stehen in der Zusammenfassung
 *   beim Einloggen ins Spiel, bei /planer und in /api/todo.
 * - Neuigkeiten (kein todo): z. B. ein neues Gebot auf dein Angebot. Die gibt es nur live, solange man online ist –
 *   den aktuellen Stand zeigt ohnehin die Webseite.
 *
 * Die id bleibt gleich, solange sich an einer Sache nichts ändert. Taucht eine neue id auf, ist etwas Neues passiert –
 * daran erkennt GameNotifier, wofür es eine Nachricht im Spiel schickt.
 */
final class Notices {
    enum Level {
        INVITE("invite"), WARN("warn"), INFO("info"), DANGER("danger");

        final String id;

        Level(String id) {
            this.id = id;
        }
    }

    /**
     * @param live         neu dazugekommen = Nachricht im Spiel (sonst nur in der Zusammenfassung)
     * @param href         Ziel auf der Webseite, z. B. "#/teams/<id>"
     * @param command      Befehl, den ein Klick im Chat einfügt (oder null)
     * @param group        Neuigkeiten derselben Gruppe kommen höchstens alle paar Sekunden (z. B. viele Gebote hintereinander)
     * @param commandLabel Beschriftung des Knopfs für den Befehl (null = "[Annehmen]")
     * @param always       Sicherheits-Hinweis: kommt auch bei "/planer nachrichten aus"
     */
    record Notice(String id, boolean todo, boolean live, Level level, String text, String href, String command, String group,
                  String commandLabel, boolean always) {
        Notice(String id, boolean todo, boolean live, Level level, String text, String href, String command, String group) {
            this(id, todo, live, level, text, href, command, group, null, false);
        }
    }

    /** So lange gilt ein Web-Login als "neu" (für die Sicherheits-Nachricht). */
    static final long RECENT_LOGIN_MS = 10 * 60_000L;

    private Notices() {
    }

    /** @param today heutiges Datum als JJJJ-MM-TT (UTC, wie das Dashboard) – für überfällige Projekte */
    static List<Notice> collect(PlannerServices s, UUID player, boolean admin, String today) {
        return collect(s, player, admin, today, System.currentTimeMillis());
    }

    static List<Notice> collect(PlannerServices s, UUID player, boolean admin, String today, long now) {
        List<Notice> out = new ArrayList<>();
        // Reihenfolge wie auf dem Dashboard
        for (LandStore.Ref land : s.lands().invitesFor(player)) {
            out.add(todo("land-invite:" + land.id(), Level.INVITE, "Einladung: Land »" + land.name() + "« mitbesitzen",
                    "#/claims/" + land.id(), "/land annehmen " + StringArgumentType.escapeIfRequired(land.name())));
        }
        TeamStore.Notes teams = s.teams().notesFor(player);
        for (TeamStore.InviteNote i : teams.invites()) {
            out.add(todo("team-invite:" + i.inviteId(), Level.INVITE, "Team-Einladung von " + i.by() + ": [" + i.tag() + "] " + i.name(),
                    "#/teams/" + i.teamId(), null));
        }
        for (TeamStore.TransferNote t : teams.transfers()) {
            out.add(todo("transfer:" + t.teamId() + ":" + t.createdAt(), Level.WARN,
                    "Dir wird das Team [" + t.tag() + "] übergeben – annehmen oder ablehnen", "#/teams/" + t.teamId(), null));
        }
        // Bewerbungen: als Aufgabe pro Team zusammengefasst (wie auf dem Dashboard), live kommt jede neue einzeln.
        Map<String, List<TeamStore.ApplicationNote>> byTeam = new LinkedHashMap<>();
        for (TeamStore.ApplicationNote a : teams.applications()) byTeam.computeIfAbsent(a.teamId(), k -> new ArrayList<>()).add(a);
        byTeam.forEach((teamId, apps) -> {
            String href = "#/teams/" + teamId;
            String tag = apps.getFirst().tag();
            out.add(new Notice("applications:" + teamId, true, false, Level.INFO,
                    plural(apps.size(), "offene Bewerbung", "offene Bewerbungen") + " bei [" + tag + "]", href, null, null));
            for (TeamStore.ApplicationNote a : apps) {
                out.add(news("application:" + a.applicationId(), Level.INFO, "Neue Bewerbung von " + a.player() + " bei [" + tag + "]", href, null));
            }
        });

        for (TradeStore.Note n : s.trades().notesFor(player, admin)) {
            String unit = n.unit(); // die Einheit des Angebots – auch wenn der Admin sie später in der Config ändert
            String href = "#/handel/" + n.listingId();
            String title = "»" + n.title() + "«";
            String price = n.amount() == null ? "" : " (" + num(n.amount()) + " " + unit + ")";
            switch (n.type()) {
                case "outbid" -> out.add(todo("outbid:" + n.listingId() + ":" + n.key(), Level.WARN,
                        "Überboten: " + title + " (jetzt " + num(n.amount()) + " " + unit + ")", href, null));
                case "handover_seller" -> out.add(todo("handover:" + n.listingId(), Level.WARN,
                        title + " ist an " + n.other() + " verkauft" + price + " – Übergabe im Spiel machen und bestätigen", href, null));
                case "handover_buyer" -> out.add(todo("handover:" + n.listingId(), Level.WARN, switch (n.key()) {
                    case "bid" -> "Du hast die Auktion " + title + " gewonnen" + price;
                    case "offer" -> "Dein Gegenangebot für " + title + " wurde angenommen";
                    default -> "Du hast " + title + " gekauft" + price;
                } + " – nach der Übergabe bestätigen", href, null));
                case "dispute_admin" -> out.add(todo("dispute:" + n.listingId(), Level.DANGER, "Streitfall klären: " + title, href, null));
                case "bid" -> out.add(new Notice("bid:" + n.listingId() + ":" + n.key(), false, true, Level.INFO,
                        "Neues Gebot auf " + title + ": " + num(n.amount()) + " " + unit + " von " + n.other(), href, null, "listing:" + n.listingId()));
                case "offer" -> out.add(news("offer:" + n.key(), Level.INFO, "Neues Gegenangebot von " + n.other() + " für " + title, href, null));
                case "disputed" -> out.add(news("disputed:" + n.listingId(), Level.DANGER,
                        n.other() + " hat für " + title + " einen Streitfall gemeldet – ein Admin klärt ihn", href, null));
                case "resolved" -> out.add(news("resolved:" + n.listingId(), Level.INFO, "Streitfall zu " + title + " geklärt: "
                        + (n.key().equals("resolved_completed") ? "erledigt" : "abgebrochen"), href, null));
                case "completed" -> out.add(news("completed:" + n.listingId(), Level.INFO,
                        "Übergabe für " + title + " abgeschlossen – beide haben bestätigt", href, null));
                case "unsold" -> out.add(news("unsold:" + n.listingId(), Level.INFO, title + " ist abgelaufen, ohne verkauft zu werden", href, null));
                default -> {
                    // unbekannte Art – nichts anzeigen
                }
            }
        }
        for (ProjectStore.Overdue p : s.projects().overdueFor(player, today)) {
            out.add(todo("overdue:" + p.id(), Level.WARN, "Projekt überfällig: »" + p.name() + "«", "#/projekte/" + p.id(), null));
        }
        if (s.config().events()) eventNotices(s, player, now, out);
        for (TeamStore.ChatTag t : teams.memberships()) {
            out.add(news("member:" + t.teamId(), Level.INFO, "Du bist jetzt im Team [" + t.tag() + "] " + t.name(), "#/teams/" + t.teamId(), null));
        }
        // Sicherheit: Wer sich mit deinem Konto auf der Webseite anmeldet, soll dir nicht entgehen.
        for (SessionStore.SessionInfo login : s.sessions().recentLogins(player, now - RECENT_LOGIN_MS)) {
            out.add(new Notice("login:" + login.id(), false, true, Level.WARN,
                    "Neuer Web-Login auf „" + login.device() + "“. Warst du das nicht, melde dich überall ab:",
                    "#/einstellungen", "/planer abmelden", null, "[Überall abmelden]", true));
        }
        return out;
    }

    /**
     * Events: "Kommst du?" (neu, noch nicht beantwortet, in den nächsten 7 Tagen), Erinnerung 10 Minuten vorher und zum Start,
     * abgesagt und verschoben – die letzten vier nur für alle, die "Komme" oder "Vielleicht" gesagt haben.
     */
    private static void eventNotices(PlannerServices s, UUID player, long now, List<Notice> out) {
        java.time.ZoneId zone = s.config().zone();
        for (EventStore.Note e : s.events().notesFor(player, now)) {
            String href = "#/events/" + e.id();
            String title = "»" + e.title() + "«";
            String when = EventStore.when(e.start(), zone);
            boolean going = "yes".equals(e.myAnswer()) || "maybe".equals(e.myAnswer());
            long until = e.start() - now;
            if (e.cancelled()) {
                if (going && until > 0) {
                    out.add(todo("event-cancelled:" + e.id(), Level.WARN, "Abgesagt: " + title + " (" + when + ")"
                            + (e.cancelReason().isEmpty() ? "" : " – " + e.cancelReason()), href, null));
                }
                continue;
            }
            String where = e.landName() != null ? " – Treffpunkt: Land »" + e.landName() + "«"
                    : e.place() != null ? " – Treffpunkt: " + e.place().x() + " " + (e.place().y() == null ? "~" : e.place().y()) + " " + e.place().z() : "";
            String way = e.landName() != null ? "/planer weg " + StringArgumentType.escapeIfRequired(e.landName()) : null;
            if (going && until > 0 && until <= EventStore.SOON_MS) {
                String in = EventStore.inMinutes(until);
                out.add(new Notice("event-soon:" + e.id() + ":" + e.start(), true, true, Level.WARN,
                        Character.toUpperCase(in.charAt(0)) + in.substring(1) + ": " + title + where, href, way, null, "[Hinführen]", false));
            } else if (going && until <= 0 && -until < EventStore.JUST_STARTED_MS) {
                out.add(new Notice("event-now:" + e.id() + ":" + e.start(), true, true, Level.INFO, "Jetzt: " + title + " beginnt" + where,
                        href, way, null, "[Hinführen]", false));
            } else if (e.myAnswer() == null && !e.organizer() && until > 0 && until <= EventStore.ASK_AHEAD_MS) {
                out.add(new Notice("event-ask:" + e.id(), true, true, Level.INVITE, "Event: " + title + " am " + when + " – kommst du?",
                        href, "/planer event " + e.id() + " ja", "events", "[Komme]", false));
            }
            if (going && e.movedAt() > 0 && now - e.movedAt() < EventStore.MOVED_NOTICE_MS && until > EventStore.SOON_MS) {
                out.add(news("event-moved:" + e.id() + ":" + e.start(), Level.WARN, "Verschoben: " + title + " jetzt am " + when
                        + " (vorher " + EventStore.when(e.previousStart(), zone) + ")", href, null));
            }
        }
    }

    /** Für /api/todo: nur "Zu erledigen", in derselben Reihenfolge wie im Spiel. */
    static JsonObject toApiJson(List<Notice> notices) {
        JsonArray arr = new JsonArray();
        for (Notice n : todos(notices)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id());
            o.addProperty("kind", n.level().id);
            o.addProperty("text", n.text());
            o.addProperty("href", n.href());
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("items", arr);
        return o;
    }

    /**
     * "Zu erledigen", das Dringendste zuerst – im Spiel, in /api/todo und auf dem Dashboard gleich (dashboard.js: todoRank).
     * Wichtig, weil Spiel und Dashboard nur die ersten Einträge zeigen: Ein Event in 10 Minuten darf nicht hinter 20 Übergaben stehen.
     */
    static List<Notice> todos(List<Notice> notices) {
        return notices.stream().filter(Notice::todo).sorted(Comparator.comparingInt(Notices::rank)).toList(); // stabil: gleicher Rang bleibt in Reihenfolge
    }

    /** 0 = Event beginnt gleich oder gerade, 1 = Streitfall, 2 = Einladung, 3 = Warnung, 4 = Info. */
    static int rank(Notice n) {
        if (n.id().startsWith("event-soon:") || n.id().startsWith("event-now:")) return 0;
        return switch (n.level()) {
            case DANGER -> 1;
            case INVITE -> 2;
            case WARN -> 3;
            case INFO -> 4;
        };
    }

    private static Notice todo(String id, Level level, String text, String href, String command) {
        return new Notice(id, true, true, level, text, href, command, null);
    }

    private static Notice news(String id, Level level, String text, String href, String command) {
        return new Notice(id, false, true, level, text, href, command, null);
    }

    static String num(long n) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(n);
    }

    static String plural(int n, String one, String many) {
        return num(n) + " " + (n == 1 ? one : many);
    }

    static List<String> ids(List<Notice> notices) {
        return notices.stream().map(Notice::id).toList();
    }
}
