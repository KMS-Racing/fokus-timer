package dev.worldplanner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Befehle im Spiel:
 *   /planer                  was gerade zu erledigen ist (wie das Dashboard der Webseite)
 *   /planer hilfe            alle Befehle
 *   /planer login            Code für die Webseite holen
 *   /planer abmelden         auf allen Geräten von der Webseite abmelden
 *   /planer einstellungen    Nachrichten, Land-Anzeige und Todespunkt an/aus (auch einzeln: /planer nachrichten aus)
 *   /planer todespunkt       wo du zuletzt gestorben bist
 *   /planer marker <Name>    privaten Marker an deiner Position setzen
 *   /planer weg <Ziel>       Pfeil über der Hotbar zu einem Marker, Land oder zum Todespunkt (/planer weg aus = beenden)
 *   /planer statistik [Spieler]  Spielzeit, Tode, Blöcke … und Ränge (wie bei CraftAttack)
 *   /planer top [Rangliste]  wer führt? (z. B. /planer top dauerzocker)
 *   /planer ranglisten an|aus  in Ranglisten und Statistik für andere sichtbar sein
 *   /tc <Nachricht>          Team-Chat (auch /teamchat) – nur Mitglieder deines Teams lesen mit
 *   /land [info]             wem gehört dieser Chunk?
 *   /land neu <Name>         neues Land mit dem Chunk, in dem du stehst
 *   /land erweitern [Name]   Chunk zu deinem Land hinzufügen (muss angrenzen)
 *   /land abgeben            Chunk aus deinem Land entfernen
 *   /land liste              deine Länder und Einladungen
 *   /land grenzen [Land]     Rand des Landes 20 Sekunden lang als Partikel zeigen (nur für dich)
 *   /land einladen <Land> <Spieler>, annehmen <Land>, ablehnen <Land>, verlassen <Land>
 *   /land admin loeschen <Land>, /land admin entfernen <Land> <Spieler>
 *
 * Namen mit Leerzeichen in Anführungszeichen schreiben: /land neu "Kims Base"
 */
final class PlannerCommands {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldPlannerMod.MOD_ID);
    private final Supplier<PlannerServices> services;
    private final GameState game;

    PlannerCommands(Supplier<PlannerServices> services, GameState game) {
        this.services = services;
        this.game = game;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("planer")
                .executes(this::overview)
                .then(Commands.literal("hilfe").executes(this::help))
                .then(Commands.literal("login").executes(this::login))
                .then(Commands.literal("abmelden").executes(this::logoutAll))
                .then(Commands.literal("einstellungen").executes(this::settings))
                .then(toggle("nachrichten", "notify"))
                .then(toggle("landanzeige", "landEnter"))
                .then(toggle("todespunkt", "deathMarker").executes(this::deathPoint))
                .then(Commands.literal("marker")
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::marker)))
                .then(Commands.literal("weg")
                        .executes(this::stopWay)
                        .then(Commands.literal("aus").executes(this::stopWay))
                        .then(Commands.argument("ziel", StringArgumentType.greedyString()).suggests(wayTargets()).executes(this::way)))
                .then(Commands.literal("statistik")
                        .executes(ctx -> stats(ctx, null))
                        .then(Commands.argument("spieler", StringArgumentType.word()).suggests(knownPlayers())
                                .executes(ctx -> stats(ctx, StringArgumentType.getString(ctx, "spieler")))))
                .then(Commands.literal("top")
                        .executes(ctx -> top(ctx, null))
                        .then(Commands.argument("rangliste", StringArgumentType.word()).suggests(rankings())
                                .executes(ctx -> top(ctx, StringArgumentType.getString(ctx, "rangliste")))))
                .then(toggle("ranglisten", "rankings")));

        var teamChat = dispatcher.register(Commands.literal("teamchat")
                .then(Commands.argument("nachricht", StringArgumentType.greedyString()).executes(this::teamChat)));
        dispatcher.register(Commands.literal("tc").redirect(teamChat));

        dispatcher.register(Commands.literal("land")
                .executes(this::info)
                .then(Commands.literal("info").executes(this::info))
                .then(Commands.literal("liste").executes(this::list))
                .then(Commands.literal("neu")
                        .then(Commands.argument("name", StringArgumentType.string()).executes(this::create)))
                .then(Commands.literal("erweitern")
                        .executes(ctx -> extend(ctx, null))
                        .then(Commands.argument("land", StringArgumentType.string()).suggests(ownLands())
                                .executes(ctx -> extend(ctx, StringArgumentType.getString(ctx, "land")))))
                .then(Commands.literal("abgeben").executes(this::release))
                .then(Commands.literal("grenzen")
                        .executes(ctx -> borders(ctx, null))
                        .then(Commands.argument("land", StringArgumentType.string()).suggests(allLands())
                                .executes(ctx -> borders(ctx, StringArgumentType.getString(ctx, "land")))))
                .then(Commands.literal("einladen")
                        .then(Commands.argument("land", StringArgumentType.string()).suggests(ownLands())
                                .then(Commands.argument("spieler", StringArgumentType.word()).suggests(knownPlayers())
                                        .executes(this::invite))))
                .then(Commands.literal("annehmen")
                        .then(Commands.argument("land", StringArgumentType.string()).suggests(invitedLands())
                                .executes(ctx -> byLand(ctx, (me, id) -> {
                                    PlannerServices s = services.get();
                                    s.lands().accept(me, isAdmin(ctx), id);
                                    s.activity().add(me, "land.joined", s.players().name(me) + " besitzt jetzt »" + s.lands().nameOf(id) + "« mit.", "land", id, "all");
                                    return "Willkommen! Du besitzt jetzt " + s.lands().summary(id);
                                }))))
                .then(Commands.literal("ablehnen")
                        .then(Commands.argument("land", StringArgumentType.string()).suggests(invitedLands())
                                .executes(ctx -> byLand(ctx, (me, id) -> {
                                    services.get().lands().decline(me, id);
                                    return "Einladung abgelehnt.";
                                }))))
                .then(Commands.literal("verlassen")
                        .then(Commands.argument("land", StringArgumentType.string()).suggests(ownLands())
                                .executes(ctx -> byLand(ctx, (me, id) -> {
                                    PlannerServices s = services.get();
                                    String name = s.lands().nameOf(id);
                                    if (s.lands().leave(me, id)) {
                                        landDeleted(s, me, id, name);
                                        return "Du warst der letzte Besitzer – das Land ist jetzt frei.";
                                    }
                                    s.activity().add(me, "land.left", s.players().name(me) + " ist aus »" + name + "« ausgetreten.", "land", id, "all");
                                    return "Du bist ausgetreten.";
                                }))))
                .then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("loeschen")
                                .then(Commands.argument("land", StringArgumentType.string()).suggests(allLands())
                                        .executes(this::adminDelete)))
                        .then(Commands.literal("entfernen")
                                .then(Commands.argument("land", StringArgumentType.string()).suggests(allLands())
                                        .then(Commands.argument("spieler", StringArgumentType.word()).suggests(knownPlayers())
                                                .executes(this::adminRemoveOwner))))));
    }

    // ------------------------------------------------------------------ /planer

    private int login(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        s.players().seen(player.getUUID(), player.getGameProfile().name(), playerIsAdmin(player));
        String code = s.sessions().createCode(player.getUUID());

        MutableComponent msg = prefix().append(Component.literal("Dein Login-Code: ").withStyle(ChatFormatting.GRAY));
        msg.append(Component.literal(code).withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(code))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Klicken zum Kopieren")))));
        msg.append(Component.literal(" (5 Minuten gültig, nur einmal benutzbar)").withStyle(ChatFormatting.GRAY));
        String publicUrl = s.config().publicUrl();
        if (!publicUrl.isBlank()) {
            try {
                URI uri = URI.create(publicUrl + "#login=" + code);
                msg.append(Component.literal(" [Webseite öffnen]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.OpenUrl(uri))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Öffnet den Planer und loggt dich ein")))));
            } catch (IllegalArgumentException ignored) {
                // publicUrl in der Config ist keine gültige Adresse
            }
        }
        // Nur an den Spieler selbst – niemals in den Chat für alle.
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int logoutAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int count = services.get().sessions().logoutAll(player.getUUID());
        reply(ctx, "Du wurdest auf " + count + " Gerät(en) abgemeldet.");
        return 1;
    }

    private int overview(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return notReady(ctx);
        s.players().seen(player.getUUID(), player.getGameProfile().name(), playerIsAdmin(player));
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        List<Notices.Notice> todos = Notices.todos(Notices.collect(s, player.getUUID(), playerIsAdmin(player), today));
        Component summary = GameText.summary(todos, s.config().publicUrl(), false);
        ctx.getSource().sendSuccess(() -> summary, false);
        MutableComponent more = GameText.message().append(GameText.command("Alle Befehle: /planer hilfe", "/planer hilfe", ChatFormatting.GRAY));
        more.append(GameText.webLink(s.config().publicUrl(), "#/dashboard", " [Webseite öffnen]"));
        ctx.getSource().sendSuccess(() -> more, false);
        return 1;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        String[][] lines = {
                {"/planer", "was gerade zu erledigen ist"},
                {"/planer login", "Login-Code für die Webseite"},
                {"/planer einstellungen", "Nachrichten, Land-Anzeige, Todespunkt, Ranglisten an/aus"},
                {"/planer todespunkt", "wo du zuletzt gestorben bist"},
                {"/planer marker ", "privaten Marker hier setzen (Name dahinter)"},
                {"/planer weg ", "Pfeil zu einem Marker, Land oder „todespunkt“ (Ziel dahinter)"},
                {"/land grenzen", "Rand des Landes als Partikel zeigen"},
                {"/planer statistik", "deine Statistik (oder die eines anderen: Name dahinter)"},
                {"/planer top", "Ranglisten wie bei CraftAttack – wer führt?"},
                {"/land", "wem gehört dieser Chunk? Außerdem: neu, erweitern, abgeben, liste, einladen"},
                {"/tc ", "Team-Chat – nur dein Team liest mit (Nachricht dahinter)"},
                {"/planer abmelden", "auf allen Geräten von der Webseite abmelden"},
        };
        MutableComponent out = GameText.message().append(GameText.text("Befehle (anklicken zum Einfügen):", ChatFormatting.WHITE));
        for (String[] line : lines) {
            out.append("\n ").append(GameText.command(line[0].strip(), line[0], ChatFormatting.AQUA))
                    .append(GameText.text(" – " + line[1], ChatFormatting.GRAY));
        }
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    /** "/planer <name>" zeigt den Schalter, "/planer <name> an|aus" stellt ihn um. */
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> toggle(String name, String key) {
        return Commands.literal(name)
                .executes(ctx -> settings(ctx))
                .then(Commands.literal("an").executes(ctx -> setToggle(ctx, key, true)))
                .then(Commands.literal("aus").executes(ctx -> setToggle(ctx, key, false)));
    }

    private int setToggle(CommandContext<CommandSourceStack> ctx, String key, boolean on) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return notReady(ctx);
        s.prefs().setGame(player.getUUID(), key, on);
        String label = PrefsStore.GAME_KEYS.get(key);
        String back = "/planer " + commandName(key) + (on ? " aus" : " an");
        String extra = serverAllows(s, key) ? "" : " (Hinweis: Der Server-Admin hat das gerade für alle abgeschaltet.)";
        MutableComponent msg = GameText.message().append(GameText.text(label + ": " + (on ? "an" : "aus") + "." + extra, ChatFormatting.WHITE))
                .append(GameText.command(" [Rückgängig]", back, ChatFormatting.GRAY));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int settings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return notReady(ctx);
        PrefsStore.Game game = s.prefs().game(player.getUUID());
        MutableComponent out = GameText.message().append(GameText.text("Deine Einstellungen im Spiel:", ChatFormatting.WHITE));
        for (String key : PrefsStore.GAME_KEYS.keySet()) {
            boolean on = game.get(key);
            out.append("\n ").append(GameText.text(PrefsStore.GAME_KEYS.get(key) + ": ", ChatFormatting.GRAY));
            if (!serverAllows(s, key)) {
                out.append(GameText.text("vom Server ausgeschaltet", ChatFormatting.DARK_GRAY));
                continue;
            }
            out.append(GameText.text(on ? "an " : "aus ", on ? ChatFormatting.GREEN : ChatFormatting.RED));
            String cmd = "/planer " + commandName(key) + (on ? " aus" : " an");
            out.append(GameText.button(on ? "[ausschalten]" : "[anschalten]", ChatFormatting.AQUA, new ClickEvent.RunCommand(cmd), cmd));
        }
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private static String commandName(String key) {
        return switch (key) {
            case "notify" -> "nachrichten";
            case "landEnter" -> "landanzeige";
            case "rankings" -> "ranglisten";
            default -> "todespunkt";
        };
    }

    private static boolean serverAllows(PlannerServices s, String key) {
        return switch (key) {
            case "notify" -> s.config().gameNotifications();
            case "landEnter" -> s.config().landEnterMessages();
            case "rankings" -> s.config().statistics();
            default -> s.config().deathMarkers();
        };
    }

    private int deathPoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return notReady(ctx);
        DeathPoints.Point p = DeathPoints.load(s, player.getUUID());
        if (p == null) {
            reply(ctx, s.config().deathMarkers() && s.prefs().game(player.getUUID()).deathMarker()
                    ? "Noch kein Todespunkt gespeichert – sobald du stirbst, merkt sich der Planer die Stelle."
                    : "Kein Todespunkt gespeichert (Todespunkt ist ausgeschaltet).");
            return 1;
        }
        Component msg = GameText.deathWhere(p, dimension(player), player.getX(), player.getZ(), s.config().publicUrl());
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int marker(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            com.google.gson.JsonObject data = new com.google.gson.JsonObject();
            String name = StringArgumentType.getString(ctx, "name");
            data.addProperty("name", name);
            data.addProperty("type", "other");
            data.addProperty("dimension", dimension(player));
            data.addProperty("x", player.getBlockX());
            data.addProperty("y", player.getBlockY());
            data.addProperty("z", player.getBlockZ());
            data.addProperty("visibility", "private");
            s.markers().create(player.getUUID(), playerIsAdmin(player), data);
            return "Marker »" + name.strip() + "« gesetzt bei " + player.getBlockX() + " / " + player.getBlockY() + " / " + player.getBlockZ()
                    + " (privat). Teilen und bearbeiten kannst du ihn auf der Webseite unter Marker.";
        });
    }

    private int teamChat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return notReady(ctx);
        if (!s.config().teamChat()) return fail(ctx, "Der Team-Chat ist auf diesem Server ausgeschaltet.");
        TeamStore.ChatTeam team = s.teams().chatTeamOf(player.getUUID());
        if (team == null) return fail(ctx, "Du bist in keinem Team. Teams gründest oder findest du auf der Webseite unter Teams.");
        String text = StringArgumentType.getString(ctx, "nachricht").strip();
        if (text.isEmpty()) return fail(ctx, "Schreib eine Nachricht dahinter: /tc Hallo");
        Component msg = GameText.teamChat(team, player.getGameProfile().name(), text);
        net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
        int others = 0;
        for (UUID member : team.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(member);
            if (p == null) continue;
            p.sendSystemMessage(msg);
            LOGGER.debug("[World Planner] Team-Chat an {}: {}", p.getGameProfile().name(), msg.getString());
            if (!member.equals(player.getUUID())) others++;
        }
        server.sendSystemMessage(msg); // steht wie normaler Chat im Server-Log
        if (others == 0) {
            ctx.getSource().sendSuccess(() -> GameText.message().append(GameText.text("Gerade ist niemand sonst aus ["
                    + team.tag() + "] online.", ChatFormatting.GRAY)), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ Navigation und Grenzen

    private int way(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String name = StringArgumentType.getString(ctx, "ziel").strip();
            Waypoints.Target target = wayTarget(s, player, name);
            game.waypoints.set(player.getUUID(), target);
            return "Wegweiser zu »" + target.label() + "« – der Pfeil steht über der Hotbar. Beenden: /planer weg aus";
        });
    }

    private int stopWay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Waypoints.Target old = game.waypoints.clear(player.getUUID());
        reply(ctx, old == null ? "Du hast gerade keinen Wegweiser. Starten: /planer weg <Marker, Land oder todespunkt>"
                : "Wegweiser zu »" + old.label() + "« beendet.");
        return 1;
    }

    /** Ziel per Name: "todespunkt", ein Marker, den du sehen darfst (eigene zuerst), oder ein Land. */
    private static Waypoints.Target wayTarget(PlannerServices s, ServerPlayer player, String name) {
        if (name.equalsIgnoreCase("todespunkt")) {
            DeathPoints.Point p = DeathPoints.load(s, player.getUUID());
            if (p == null) throw PlannerException.notFound("Du hast noch keinen Todespunkt.");
            return new Waypoints.Target("Todespunkt", "☠", p.dimension(), p.x() + 0.5, p.y(), p.z() + 0.5);
        }
        MarkerStore.Marker m = s.markers().findVisible(player.getUUID(), name);
        if (m != null) return new Waypoints.Target(m.name(), m.symbol() == null ? "◆" : m.symbol(), m.dimension(), m.x() + 0.5, m.y(), m.z() + 0.5);
        LandStore.Shape land = s.lands().shapeByName(name);
        if (land != null) return new Waypoints.Target(land.name(), "⌂", land.dimension(), land.centerX() + 0.5, null, land.centerZ() + 0.5);
        throw PlannerException.notFound("Kein Marker und kein Land namens »" + name + "«. Mit Tab siehst du die Namen.");
    }

    private int borders(CommandContext<CommandSourceStack> ctx, String landName) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            LandStore.Shape shape = landName == null
                    ? s.lands().shapeAt(dimension(player), Chunks.key(player.chunkPosition().x(), player.chunkPosition().z()))
                    : s.lands().shapeByName(landName);
            if (shape == null) {
                throw PlannerException.notFound(landName == null ? "Du stehst in keinem Land. Von weitem geht es mit /land grenzen <Land>."
                        : "Es gibt kein Land namens »" + landName + "«.");
            }
            if (!shape.dimension().equals(dimension(player))) {
                throw PlannerException.badRequest("»" + shape.name() + "« liegt in: " + GameText.dimensionName(shape.dimension()) + ".");
            }
            showBorders(player, shape, 20);
            return "Die Grenzen von »" + shape.name() + "« leuchten jetzt 20 Sekunden lang (nur für dich, in der Landfarbe).";
        });
    }

    private void showBorders(ServerPlayer player, LandStore.Shape shape, int seconds) {
        if (shape != null) game.borders.show(player.getUUID(), shape.dimension(), shape.chunks(), shape.color(), game.tick + seconds * 20L);
    }

    /** Vorschläge für /planer weg – ohne Anführungszeichen, weil das Ziel der ganze Rest der Zeile ist. */
    private SuggestionProvider<CommandSourceStack> wayTargets() {
        return (ctx, builder) -> {
            ServerPlayer p = ctx.getSource().getPlayer();
            PlannerServices s = services.get();
            List<String> names = new java.util.ArrayList<>(List.of("aus", "todespunkt"));
            if (p != null && s != null) {
                names.addAll(s.markers().visibleNames(p.getUUID()));
                names.addAll(s.lands().allNames());
            }
            return SharedSuggestionProvider.suggest(names, builder);
        };
    }

    // ------------------------------------------------------------------ Statistik

    private int stats(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return notReady(ctx);
        if (!s.config().statistics()) return fail(ctx, "Die Statistik ist auf diesem Server ausgeschaltet.");
        UUID target = player.getUUID();
        if (name != null) {
            java.util.Optional<UUID> found = s.players().findByName(name);
            if (found.isEmpty()) return fail(ctx, "»" + name + "« war noch nie auf dem Server.");
            target = found.get();
        }
        java.util.Set<UUID> hidden = s.prefs().statsHidden();
        boolean self = target.equals(player.getUUID());
        if (!self && hidden.contains(target)) return fail(ctx, "»" + s.players().name(target) + "« hat die Statistik ausgeblendet.");
        StatsStore.Summary summary = s.stats().summary(target, java.time.LocalDate.now(s.config().zone()), hidden);
        if (summary == null) return fail(ctx, "Für »" + s.players().name(target) + "« gibt es noch keine Statistik.");
        Component msg = GameText.statsSummary(s.players().name(target), summary, System.currentTimeMillis(), s.config().publicUrl(),
                "#/statistik/spieler/" + target);
        ctx.getSource().sendSuccess(() -> msg, false);
        if (self && hidden.contains(target)) reply(ctx, "Du bist ausgeblendet – nur du siehst deine Statistik. Wieder sichtbar: /planer ranglisten an");
        return 1;
    }

    private int top(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return notReady(ctx);
        if (!s.config().statistics()) return fail(ctx, "Die Statistik ist auf diesem Server ausgeschaltet.");
        java.time.LocalDate today = java.time.LocalDate.now(s.config().zone());
        java.util.Set<UUID> hidden = s.prefs().statsHidden();
        if (id == null) {
            java.util.Map<String, List<StatsStore.Entry>> lists = new java.util.HashMap<>();
            for (StatValues.Category c : StatValues.CATEGORIES) lists.put(c.id(), s.stats().ranking(c, "all", today, hidden));
            Component msg = GameText.leaders(StatValues.CATEGORIES, lists, s.players()::name, s.config().publicUrl());
            ctx.getSource().sendSuccess(() -> msg, false);
            return 1;
        }
        StatValues.Category c = StatValues.category(id.toLowerCase(java.util.Locale.ROOT));
        if (c == null) return fail(ctx, "Diese Rangliste gibt es nicht. Alle: /planer top");
        Component msg = GameText.ranking(c, s.stats().ranking(c, "all", today, hidden), s.players()::name, player.getUUID(), s.config().publicUrl());
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private static SuggestionProvider<CommandSourceStack> rankings() {
        return suggestFrom(source -> StatValues.CATEGORIES.stream().map(StatValues.Category::id).toList());
    }

    private static int notReady(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.literal("World Planner startet noch."));
        return 0;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(prefix().append(Component.literal(message).withStyle(ChatFormatting.RED)));
        return 0;
    }

    // ------------------------------------------------------------------ /land

    private int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ChunkPos pos = player.chunkPosition();
        String owner = services.get().lands().describeChunk(dimension(player), Chunks.key(pos.x(), pos.z()));
        reply(ctx, "Chunk " + pos.x() + "/" + pos.z() + ": " + (owner == null ? "frei – mit /land neu <Name> kannst du ihn claimen." : "gehört zu " + owner));
        return 1;
    }

    private int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        UUID me = player.getUUID();
        List<String> own = s.lands().namesOwnedBy(me);
        List<String> invites = s.lands().namesInviting(me);
        reply(ctx, "Du besitzt " + s.lands().ownedChunkCount(me) + "/" + s.config().maxChunksPerPlayer() + " Chunks.");
        if (own.isEmpty()) reply(ctx, "Du hast noch kein Land.");
        for (String name : own) s.lands().findIdByName(name).ifPresent(id -> reply(ctx, "• " + s.lands().summary(id)));
        for (String name : invites) reply(ctx, "✉ Einladung für »" + name + "« – /land annehmen \"" + name + "\"");
        return 1;
    }

    private int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            long chunk = currentChunk(player, s);
            String id = s.lands().create(player.getUUID(), isAdmin(ctx), StringArgumentType.getString(ctx, "name"),
                    dimension(player), new long[]{chunk});
            s.activity().add(player.getUUID(), "land.created", s.players().name(player.getUUID()) + " hat das Land »" + s.lands().nameOf(id) + "« erstellt.", "land", id, "all");
            showBorders(player, s.lands().shapeByName(s.lands().nameOf(id)), 10);
            return "Land erstellt: " + s.lands().summary(id) + ". Erweitern mit /land erweitern.";
        });
    }

    private int extend(CommandContext<CommandSourceStack> ctx, String landName) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            long chunk = currentChunk(player, s);
            String name = landName;
            if (name == null) {
                List<String> neighbours = s.lands().ownLandsNextTo(player.getUUID(), dimension(player), chunk);
                if (neighbours.isEmpty()) throw PlannerException.badRequest("Keines deiner Länder grenzt an diesen Chunk.");
                if (neighbours.size() > 1) throw PlannerException.badRequest("Mehrere deiner Länder grenzen hier an. Schreib den Namen dazu: /land erweitern <Land>");
                name = neighbours.getFirst();
            }
            String id = landId(s, name);
            s.lands().addChunks(player.getUUID(), isAdmin(ctx), id, new long[]{chunk});
            showBorders(player, s.lands().shapeByName(s.lands().nameOf(id)), 10);
            return "Chunk hinzugefügt: " + s.lands().summary(id);
        });
    }

    private int release(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            ChunkPos pos = player.chunkPosition();
            long chunk = Chunks.key(pos.x(), pos.z());
            String name = s.lands().landNameAt(dimension(player), chunk)
                    .orElseThrow(() -> PlannerException.badRequest("Dieser Chunk gehört niemandem."));
            String id = landId(s, name);
            boolean deleted = s.lands().removeChunks(player.getUUID(), false, id, new long[]{chunk});
            if (deleted) landDeleted(s, player.getUUID(), id, name);
            return deleted ? "Das war der letzte Chunk – »" + name + "« ist jetzt aufgelöst." : "Chunk aus »" + name + "« entfernt.";
        });
    }

    private int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String landName = StringArgumentType.getString(ctx, "land");
            String targetName = StringArgumentType.getString(ctx, "spieler");
            UUID target = s.players().findByName(targetName)
                    .orElseThrow(() -> PlannerException.notFound("Spieler »" + targetName + "« war noch nie auf dem Server."));
            String id = landId(s, landName);
            s.lands().invite(player.getUUID(), id, target);
            // Sind die Hinweise im Spiel an, meldet GameNotifier die Einladung (und beachtet "/planer nachrichten aus").
            ServerPlayer online = s.config().gameNotifications() ? null : ctx.getSource().getServer().getPlayerList().getPlayer(target);
            if (online != null) {
                online.sendSystemMessage(prefix().append(Component.literal(player.getGameProfile().name()
                        + " lädt dich als Mitbesitzer von »" + landName + "« ein. ").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal("[Annehmen]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent.SuggestCommand("/land annehmen " + StringArgumentType.escapeIfRequired(landName))))));
            }
            return s.players().name(target) + " ist eingeladen. Sobald die Einladung angenommen ist, besitzt ihr »" + landName + "« gemeinsam.";
        });
    }

    private int adminDelete(CommandContext<CommandSourceStack> ctx) {
        PlannerServices s = services.get();
        return run(ctx, () -> {
            String name = StringArgumentType.getString(ctx, "land");
            String id = landId(s, name);
            s.lands().adminDelete(id);
            landDeleted(s, null, id, name);
            return "Land »" + name + "« gelöscht.";
        });
    }

    private int adminRemoveOwner(CommandContext<CommandSourceStack> ctx) {
        PlannerServices s = services.get();
        return run(ctx, () -> {
            String name = StringArgumentType.getString(ctx, "land");
            String targetName = StringArgumentType.getString(ctx, "spieler");
            UUID target = s.players().findByName(targetName)
                    .orElseThrow(() -> PlannerException.notFound("Spieler »" + targetName + "« ist unbekannt."));
            String id = landId(s, name);
            boolean deleted = s.lands().adminRemoveOwner(id, target);
            if (deleted) landDeleted(s, null, id, name);
            return targetName + " entfernt." + (deleted ? " Das Land hatte keine Besitzer mehr und wurde gelöscht." : "");
        });
    }

    // ------------------------------------------------------------------ Hilfsmethoden

    /** Wie auf der Webseite: Projekte und Marker bleiben, nur die Verknüpfung zum Land wird gelöst. */
    private static void landDeleted(PlannerServices s, UUID actor, String id, String name) {
        s.onLandDeleted(id);
        s.activity().add(actor, "land.deleted", "Das Land »" + name + "« wurde aufgelöst.", "land", id, "all");
    }

    private interface PlayerAction {
        String run(ServerPlayer player, PlannerServices services);
    }

    private interface LandAction {
        String run(UUID me, String landId);
    }

    private int withPlayer(CommandContext<CommandSourceStack> ctx, PlayerAction action) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        s.players().seen(player.getUUID(), player.getGameProfile().name(), playerIsAdmin(player));
        return run(ctx, () -> action.run(player, s));
    }

    private int byLand(CommandContext<CommandSourceStack> ctx, LandAction action) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> action.run(player.getUUID(), landId(s, StringArgumentType.getString(ctx, "land"))));
    }

    /** Führt eine Aktion aus und zeigt Erfolg oder die (deutsche) Fehlermeldung an. */
    private int run(CommandContext<CommandSourceStack> ctx, Supplier<String> action) {
        if (services.get() == null) {
            ctx.getSource().sendFailure(Component.literal("World Planner startet noch."));
            return 0;
        }
        try {
            reply(ctx, action.get());
            return 1;
        } catch (PlannerException e) {
            ctx.getSource().sendFailure(prefix().append(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED)));
            return 0;
        }
    }

    private static long currentChunk(ServerPlayer player, PlannerServices s) {
        ChunkPos pos = player.chunkPosition();
        // Wo du gerade stehst, ist auf jeden Fall entdeckt.
        s.discovery().markArea(dimension(player), pos.x(), pos.z(), 0);
        return Chunks.key(pos.x(), pos.z());
    }

    private static String landId(PlannerServices s, String name) {
        return s.lands().findIdByName(name).orElseThrow(() -> PlannerException.notFound("Es gibt kein Land namens »" + name + "«."));
    }

    private static String dimension(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }

    private static boolean isAdmin(CommandContext<CommandSourceStack> ctx) {
        return Commands.LEVEL_GAMEMASTERS.check(ctx.getSource().permissions());
    }

    /**
     * Admin-Status für die Webseite kommt immer von den Rechten des Spielers selbst –
     * nicht vom Befehl. Sonst würde "/execute as Bob ..." durch einen OP Bob kurz zum Web-Admin machen.
     */
    private static boolean playerIsAdmin(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    private static MutableComponent prefix() {
        return GameText.prefix();
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal(text).withStyle(ChatFormatting.WHITE)), false);
    }

    private SuggestionProvider<CommandSourceStack> ownLands() {
        return suggestFrom(source -> {
            ServerPlayer p = source.getPlayer();
            return p == null || services.get() == null ? List.of() : services.get().lands().namesOwnedBy(p.getUUID());
        });
    }

    private SuggestionProvider<CommandSourceStack> invitedLands() {
        return suggestFrom(source -> {
            ServerPlayer p = source.getPlayer();
            return p == null || services.get() == null ? List.of() : services.get().lands().namesInviting(p.getUUID());
        });
    }

    private SuggestionProvider<CommandSourceStack> allLands() {
        return suggestFrom(source -> services.get() == null ? List.of() : services.get().lands().allNames());
    }

    private SuggestionProvider<CommandSourceStack> knownPlayers() {
        return suggestFrom(source -> services.get() == null ? List.of() : services.get().players().names());
    }

    private static SuggestionProvider<CommandSourceStack> suggestFrom(Function<CommandSourceStack, List<String>> names) {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(
                names.apply(ctx.getSource()).stream().map(StringArgumentType::escapeIfRequired), builder);
    }
}
