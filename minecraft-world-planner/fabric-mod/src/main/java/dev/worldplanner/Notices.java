package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.text.NumberFormat;
import java.util.ArrayList;
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
     * @param live    neu dazugekommen = Nachricht im Spiel (sonst nur in der Zusammenfassung)
     * @param href    Ziel auf der Webseite, z. B. "#/teams/<id>"
     * @param command Befehl, den ein Klick im Chat einfügt (oder null)
     * @param group   Neuigkeiten derselben Gruppe kommen höchstens alle paar Sekunden (z. B. viele Gebote hintereinander)
     */
    record Notice(String id, boolean todo, boolean live, Level level, String text, String href, String command, String group) {}

    private Notices() {
    }

    /** @param today heutiges Datum als JJJJ-MM-TT (UTC, wie das Dashboard) – für überfällige Projekte */
    static List<Notice> collect(PlannerServices s, UUID player, boolean admin, String today) {
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
        for (TeamStore.ChatTag t : teams.memberships()) {
            out.add(news("member:" + t.teamId(), Level.INFO, "Du bist jetzt im Team [" + t.tag() + "] " + t.name(), "#/teams/" + t.teamId(), null));
        }
        return out;
    }

    /** Für /api/todo: nur "Zu erledigen", in derselben Reihenfolge wie im Spiel. */
    static JsonObject toApiJson(List<Notice> notices) {
        JsonArray arr = new JsonArray();
        for (Notice n : notices) {
            if (!n.todo()) continue;
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

    static List<Notice> todos(List<Notice> notices) {
        return notices.stream().filter(Notice::todo).toList();
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
