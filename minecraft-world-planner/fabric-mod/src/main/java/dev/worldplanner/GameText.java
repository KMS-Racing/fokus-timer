package dev.worldplanner;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Texte für Chat und Actionbar im Spiel – reine Anzeige, die Inhalte kommen aus Notices, LandStore usw.
 * Links zur Webseite gibt es nur, wenn "publicUrl" in der Config steht.
 *
 * Jede Nachricht beginnt mit einem leeren Text: In Minecraft erben angehängte Teile sonst Farbe und
 * Fettdruck des ersten Teils.
 */
final class GameText {
    /** Beim Einloggen kurz halten; wer /planer tippt, will mehr sehen. */
    static final int SUMMARY_LINES = 6;
    static final int OVERVIEW_LINES = 12;
    private static final String[] DIRECTIONS = {"Norden", "Nordosten", "Osten", "Südosten", "Süden", "Südwesten", "Westen", "Nordwesten"};

    private GameText() {
    }

    static MutableComponent prefix() {
        return Component.literal("[Planer] ").withStyle(ChatFormatting.GOLD);
    }

    static MutableComponent message() {
        return Component.empty().append(prefix());
    }

    // ------------------------------------------------------------------ Hinweise

    /** Zusammenfassung beim Einloggen (onJoin) und bei /planer. */
    static Component summary(List<Notices.Notice> todos, String publicUrl, boolean onJoin) {
        MutableComponent out = message();
        if (todos.isEmpty()) return out.append(text("Nichts zu erledigen.", ChatFormatting.GREEN));
        out.append(text("Zu erledigen (" + todos.size() + "):", ChatFormatting.WHITE));
        int shown = Math.min(todos.size(), onJoin ? SUMMARY_LINES : OVERVIEW_LINES);
        for (int i = 0; i < shown; i++) out.append("\n ").append(line(todos.get(i), publicUrl));
        if (todos.size() > shown) {
            boolean link = publicUrl != null && !publicUrl.isBlank();
            out.append("\n ").append(text("… und " + (todos.size() - shown) + " weitere"
                            + (link ? "" : (onJoin ? " – /planer zeigt mehr, alle stehen" : " – alle stehen") + " auf der Webseite im Dashboard"),
                    ChatFormatting.GRAY)).append(webLink(publicUrl, "#/dashboard", " [Alle auf der Webseite]"));
        }
        if (onJoin) out.append("\n ").append(command("Ausschalten: /planer nachrichten aus", "/planer nachrichten aus", ChatFormatting.DARK_GRAY));
        return out;
    }

    /** Ein neuer Hinweis, während man online ist. */
    static Component live(Notices.Notice n, String publicUrl) {
        return message().append(line(n, publicUrl));
    }

    static Component more(int count) {
        return message().append(text("… und " + count + " weitere Hinweise – /planer zeigt, was zu erledigen ist.", ChatFormatting.GRAY));
    }

    static MutableComponent line(Notices.Notice n, String publicUrl) {
        MutableComponent out = Component.empty();
        out.append(text(icon(n.level()) + " ", color(n.level())));
        out.append(text(n.text(), ChatFormatting.WHITE));
        if (n.command() != null) {
            out.append(button(" " + (n.commandLabel() == null ? "[Annehmen]" : n.commandLabel()), ChatFormatting.GREEN,
                    new ClickEvent.SuggestCommand(n.command()), "Fügt »" + n.command() + "« in den Chat ein – mit Enter bestätigen"));
        }
        out.append(webLink(publicUrl, n.href(), " [Öffnen]"));
        return out;
    }

    private static String icon(Notices.Level level) {
        return switch (level) {
            case INVITE -> "✉";
            case WARN -> "⚠";
            case DANGER -> "‼";
            case INFO -> "•";
        };
    }

    private static ChatFormatting color(Notices.Level level) {
        return switch (level) {
            case INVITE -> ChatFormatting.AQUA;
            case WARN -> ChatFormatting.YELLOW;
            case DANGER -> ChatFormatting.RED;
            case INFO -> ChatFormatting.GRAY;
        };
    }

    // ------------------------------------------------------------------ Land-Anzeige (Actionbar)

    static Component landEnter(LandStore.Here here, TeamStore.ChatTag team) {
        MutableComponent out = Component.empty();
        out.append(Component.literal("▶ " + here.name()).withStyle(style -> style.withColor(TextColor.fromRgb(rgb(here.color()))).withBold(true)));
        if (team != null) {
            out.append(text(" [", ChatFormatting.GRAY));
            out.append(Component.literal(team.tag()).withStyle(style -> style.withColor(TextColor.fromRgb(rgb(team.color())))));
            out.append(text("]", ChatFormatting.GRAY));
        }
        String owners = here.mine() ? "dein Land"
                : "von " + here.firstOwner() + (here.ownerCount() > 1 ? " +" + (here.ownerCount() - 1) : "");
        out.append(text(" · " + owners, ChatFormatting.GRAY));
        return out;
    }

    static Component wilderness() {
        return Component.empty().append(text("Wildnis", ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------------ Todespunkt

    static Component deathSaved(DeathPoints.Point p, String publicUrl) {
        return message()
                .append(text("☠ Todespunkt gespeichert: " + coords(p) + " (" + dimensionName(p.dimension()) + ")", ChatFormatting.WHITE))
                .append(text(" – als privater Marker auf der Karte.", ChatFormatting.GRAY))
                .append(copyButton(p))
                .append(webLink(publicUrl, mapHref(p.dimension(), p.x(), p.z()), " [Karte]"));
    }

    static Component deathNotSaved(String reason) {
        return message().append(text("☠ Todespunkt nicht gespeichert: " + reason, ChatFormatting.RED));
    }

    /** Wo liegt der Todespunkt von hier aus? (nach dem Wiederbeleben und bei /planer todespunkt) */
    static Component deathWhere(DeathPoints.Point p, String playerDim, double px, double pz, String publicUrl) {
        MutableComponent out = message().append(text("☠ Dein Todespunkt: " + coords(p) + " (" + dimensionName(p.dimension()) + ")", ChatFormatting.WHITE));
        if (p.dimension().equals(playerDim)) {
            double tx = p.x() + 0.5, tz = p.z() + 0.5;
            long dist = Math.round(Math.hypot(tx - px, tz - pz));
            out.append(text(dist < 2 ? " – du stehst direkt dort." : " – " + Notices.num(dist) + " Blöcke Richtung " + direction(px, pz, tx, tz) + ".", ChatFormatting.GRAY));
        } else {
            out.append(text(" – du bist gerade in: " + dimensionName(playerDim) + ".", ChatFormatting.GRAY));
        }
        return out.append(copyButton(p)).append(webLink(publicUrl, mapHref(p.dimension(), p.x(), p.z()), " [Karte]"));
    }

    private static String coords(DeathPoints.Point p) {
        return p.x() + " / " + (p.y() == null ? "?" : p.y()) + " / " + p.z();
    }

    /** "~" für eine unbekannte Höhe: So passt der Text direkt in /tp (Höhe bleibt dann, wie sie ist). */
    private static MutableComponent copyButton(DeathPoints.Point p) {
        String text = p.x() + " " + (p.y() == null ? "~" : p.y()) + " " + p.z();
        return button(" [Kopieren]", ChatFormatting.GREEN, new ClickEvent.CopyToClipboard(text), "Koordinaten kopieren");
    }

    static String mapHref(String dim, int x, int z) {
        return "#/karte?dim=" + dim + "&x=" + x + "&z=" + z + "&zoom=2";
    }

    // ------------------------------------------------------------------ Team-Chat

    static Component teamChat(TeamStore.ChatTeam team, String sender, String message) {
        return Component.empty()
                .append(Component.literal("[Team " + team.tag() + "] ").withStyle(style -> style.withColor(TextColor.fromRgb(rgb(team.color())))))
                .append(text(sender, ChatFormatting.WHITE))
                .append(text(": ", ChatFormatting.GRAY))
                .append(text(message, ChatFormatting.WHITE));
    }

    // ------------------------------------------------------------------ Statistik

    /** /planer statistik [Spieler] */
    static Component statsSummary(String name, StatsStore.Summary st, long now, String publicUrl, String href) {
        Map<String, Long> v = st.values();
        MutableComponent out = message().append(text("Statistik von " + name, ChatFormatting.WHITE))
                .append(text(" (Stand: " + ago(now - st.updated()) + ")", ChatFormatting.DARK_GRAY));
        long today = st.today().getOrDefault("playTime", 0L);
        out.append("\n ").append(text("Spielzeit: ", ChatFormatting.GRAY)).append(text(StatValues.duration(v.getOrDefault("playTime", 0L)), ChatFormatting.WHITE))
                .append(text(today > 0 ? " (heute +" + StatValues.duration(today) + ")" : "", ChatFormatting.GRAY));
        out.append("\n ").append(pairs(v, "deaths", "mobKills", "diamonds"));
        out.append("\n ").append(pairs(v, "mined", "placed", "distance"));
        if (!st.places().isEmpty()) {
            List<Map.Entry<String, Integer>> best = new java.util.ArrayList<>(st.places().entrySet());
            best.sort(Map.Entry.comparingByValue());
            MutableComponent places = Component.empty().append(text("Ränge: ", ChatFormatting.GRAY));
            for (int i = 0; i < best.size() && i < 3; i++) {
                StatValues.Category c = StatValues.category(best.get(i).getKey());
                if (i > 0) places.append(text(" · ", ChatFormatting.DARK_GRAY));
                places.append(text(c.rank() + " ", ChatFormatting.GOLD)).append(text("Platz " + best.get(i).getValue(), ChatFormatting.WHITE));
            }
            out.append("\n ").append(places);
        }
        return out.append(webLink(publicUrl, href, "\n [Ganze Statistik auf der Webseite]"));
    }

    private static MutableComponent pairs(Map<String, Long> v, String... keys) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) out.append(text(" · ", ChatFormatting.DARK_GRAY));
            out.append(text(StatValues.LABELS.get(keys[i]) + ": ", ChatFormatting.GRAY))
                    .append(text(StatValues.format(keys[i], v.getOrDefault(keys[i], 0L)), ChatFormatting.WHITE));
        }
        return out;
    }

    /** /planer top <Rangliste>: die ersten fünf und dein Platz. */
    static Component ranking(StatValues.Category c, List<StatsStore.Entry> list, java.util.function.Function<java.util.UUID, String> names,
                             java.util.UUID me, String publicUrl) {
        MutableComponent out = message().append(text(c.rank(), ChatFormatting.GOLD)).append(text(" – " + c.label(), ChatFormatting.GRAY));
        if (list.isEmpty()) return out.append("\n ").append(text("Noch niemand – sei die oder der Erste!", ChatFormatting.GRAY));
        boolean meShown = false;
        for (int i = 0; i < list.size() && i < 5; i++) {
            StatsStore.Entry e = list.get(i);
            boolean mine = e.player().equals(me);
            meShown |= mine;
            out.append("\n ").append(text(StatsStore.place(list, i) + ". ", ChatFormatting.YELLOW))
                    .append(text(names.apply(e.player()), mine ? ChatFormatting.GREEN : ChatFormatting.WHITE))
                    .append(text(" " + StatValues.format(c.key(), e.value()), ChatFormatting.GRAY));
        }
        for (int i = 5; !meShown && i < list.size(); i++) {
            if (!list.get(i).player().equals(me)) continue;
            out.append("\n ").append(text("… du: Platz " + StatsStore.place(list, i) + " mit " + StatValues.format(c.key(), list.get(i).value()), ChatFormatting.GREEN));
        }
        return out.append(webLink(publicUrl, "#/statistik/ranglisten", " [Alle Ranglisten]"));
    }

    /** /planer top ohne Rangliste: wer führt wo? */
    static Component leaders(List<StatValues.Category> cats, Map<String, List<StatsStore.Entry>> lists,
                             java.util.function.Function<java.util.UUID, String> names, String publicUrl) {
        MutableComponent out = message().append(text("Ranglisten – wer führt? Mehr: /planer top <Rangliste>", ChatFormatting.WHITE));
        for (StatValues.Category c : cats) {
            List<StatsStore.Entry> list = lists.get(c.id());
            out.append("\n ").append(command(c.rank(), "/planer top " + c.id(), ChatFormatting.GOLD)).append(text(": ", ChatFormatting.GRAY));
            if (list.isEmpty()) out.append(text("noch niemand", ChatFormatting.DARK_GRAY));
            else out.append(text(names.apply(list.getFirst().player()), ChatFormatting.WHITE))
                    .append(text(" (" + StatValues.format(c.key(), list.getFirst().value()) + ")", ChatFormatting.GRAY));
        }
        return out.append(webLink(publicUrl, "#/statistik/ranglisten", " [Webseite]"));
    }

    private static String ago(long ms) {
        if (ms < 90_000) return "gerade eben";
        return "vor " + StatValues.duration(ms);
    }

    // ------------------------------------------------------------------ Bausteine

    static MutableComponent text(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }

    static MutableComponent button(String label, ChatFormatting color, ClickEvent click, String hover) {
        return Component.literal(label).withStyle(style -> style.withColor(color).withClickEvent(click)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** Klickbarer Befehl (wird in den Chat eingefügt, nicht sofort ausgeführt). */
    static MutableComponent command(String label, String command, ChatFormatting color) {
        return button(label, color, new ClickEvent.SuggestCommand(command), "Klicken fügt »" + command + "« ein");
    }

    /** Link zur Webseite oder nichts, wenn keine publicUrl eingetragen ist. */
    static MutableComponent webLink(String publicUrl, String href, String label) {
        if (publicUrl == null || publicUrl.isBlank() || href == null) return Component.empty();
        try {
            URI uri = URI.create(publicUrl + href);
            return button(label, ChatFormatting.AQUA, new ClickEvent.OpenUrl(uri), "Auf der Planer-Webseite öffnen");
        } catch (IllegalArgumentException e) {
            return Component.empty(); // publicUrl in der Config ist keine gültige Adresse
        }
    }

    static String dimensionName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Oberwelt";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "Ende";
            default -> dim;
        };
    }

    /** Himmelsrichtung von a nach b (Minecraft: −Z = Norden, +X = Osten) – wie coords.js auf der Webseite. */
    static String direction(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX, dz = toZ - fromZ;
        if (dx == 0 && dz == 0) return "hier";
        double deg = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
        return DIRECTIONS[(int) (Math.round(deg / 45) % 8)];
    }

    static int rgb(String hex) {
        try {
            return Integer.parseInt(hex.substring(1), 16);
        } catch (Exception e) {
            return 0x64b5f6;
        }
    }
}
