package dev.worldplanner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Rennstrecken im Spiel:
 *   /rennen neu <Name> [runde|sprint] [beliebig|zu_fuss|boot|pferd|elytra]   neue Strecke anfangen
 *   /rennen tor [Breite]       Tor hier, quer zur Blickrichtung (erstes Tor = Start/Ziel) – /rennen tor weg nimmt das letzte zurück
 *   /rennen fertig             Strecke fertig (oder /rennen abbrechen)
 *   /rennen bearbeiten <Strecke>  Tore neu setzen (alte Zeiten kommen ins Archiv)
 *   /rennen start <Strecke>    Zeitmessung – die Uhr läuft ab der Startlinie; /rennen stop beendet
 *   /rennen zeiten [Strecke], /rennen liste, /rennen zeigen [Strecke]
 */
final class RaceCommands {
    private static final Map<String, String> VEHICLE_WORDS = Map.of("beliebig", "any", "zu_fuss", "foot", "boot", "boat", "pferd", "horse", "elytra", "elytra");
    private final Supplier<PlannerServices> services;
    private final GameState game;

    RaceCommands(Supplier<PlannerServices> services, GameState game) {
        this.services = services;
        this.game = game;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rennen")
                .executes(this::help)
                .then(Commands.literal("hilfe").executes(this::help))
                .then(Commands.literal("liste").executes(this::list))
                .then(Commands.literal("neu")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> create(ctx, "circuit", "any"))
                                .then(typed("runde", "circuit"))
                                .then(typed("sprint", "sprint"))))
                .then(Commands.literal("tor")
                        .executes(ctx -> gate(ctx, RaceStore.DEFAULT_WIDTH))
                        .then(Commands.literal("weg").executes(this::removeGate))
                        .then(Commands.argument("breite", IntegerArgumentType.integer(RaceStore.MIN_WIDTH, RaceStore.MAX_WIDTH))
                                .executes(ctx -> gate(ctx, IntegerArgumentType.getInteger(ctx, "breite")))))
                .then(Commands.literal("fertig").executes(this::finish))
                .then(Commands.literal("abbrechen").executes(this::cancel))
                .then(Commands.literal("bearbeiten")
                        .then(Commands.argument("strecke", StringArgumentType.string()).suggests(ownTracks()).executes(this::edit)))
                .then(Commands.literal("start")
                        .then(Commands.argument("strecke", StringArgumentType.string()).suggests(tracks(true)).executes(this::start)))
                .then(Commands.literal("stop").executes(this::stop))
                .then(Commands.literal("zeiten")
                        .executes(ctx -> times(ctx, null))
                        .then(Commands.argument("strecke", StringArgumentType.string()).suggests(tracks(true))
                                .executes(ctx -> times(ctx, StringArgumentType.getString(ctx, "strecke")))))
                .then(Commands.literal("zeigen")
                        .executes(ctx -> show(ctx, null))
                        .then(Commands.argument("strecke", StringArgumentType.string()).suggests(tracks(false))
                                .executes(ctx -> show(ctx, StringArgumentType.getString(ctx, "strecke"))))));
    }

    /** "/rennen neu <Name> runde [boot]" */
    private LiteralArgumentBuilder<CommandSourceStack> typed(String word, String type) {
        LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(word).executes(ctx -> create(ctx, type, "any"));
        VEHICLE_WORDS.forEach((w, vehicle) -> b.then(Commands.literal(w).executes(ctx -> create(ctx, type, vehicle))));
        return b;
    }

    // ------------------------------------------------------------------ Bauen

    private int create(CommandContext<CommandSourceStack> ctx, String type, String vehicle) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String id = s.races().create(player.getUUID(), isAdmin(player), StringArgumentType.getString(ctx, "name"), dimension(player), type, vehicle);
            RaceStore.Track t = s.races().track(id);
            s.activity().add(player.getUUID(), "race.created", s.players().name(player.getUUID()) + " baut die Rennstrecke »" + t.name() + "«.", "race", id, "all");
            return "Strecke »" + t.name() + "« angelegt (" + RaceStore.TYPES.get(type) + ", Fahrzeug: " + RaceStore.VEHICLES.get(vehicle) + "). "
                    + "Stell dich jetzt auf die " + (type.equals("sprint") ? "Startlinie" : "Start-/Ziellinie") + ", schau in Fahrtrichtung und tippe /rennen tor.";
        });
    }

    private int gate(CommandContext<CommandSourceStack> ctx, int width) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String id = editingOrFail(s, player);
            RaceStore.Track t = s.races().track(id);
            if (!t.dimension().equals(dimension(player))) throw PlannerException.badRequest("Die Strecke liegt in einer anderen Dimension.");
            float yaw = Math.round(player.getYRot() * 10) / 10f;
            RaceStore.Gate g = new RaceStore.Gate(round(player.getX()), round(player.getY()), round(player.getZ()), yaw, width);
            int count = s.races().addGate(player.getUUID(), id, g);
            game.races.show(player.getUUID(), t.dimension(), s.races().draft(id), t.type(), game.tick, 10);
            String which = count == 1 ? (t.type().equals("sprint") ? "Start" : "Start/Ziel") : "Tor " + (count - 1);
            int min = RaceStore.minGates(t.type());
            String next = count < min ? " Weiter zum nächsten Tor (" + (min - count) + " fehlen noch) und wieder /rennen tor."
                    : t.type().equals("sprint") ? " Das letzte Tor ist das Ziel. Noch eins: /rennen tor – fertig: /rennen fertig."
                    : " Noch eins: /rennen tor – oder fertig: /rennen fertig.";
            return which + " gesetzt (" + width + " Blöcke breit, quer zu deiner Blickrichtung)." + next;
        });
    }

    private int removeGate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String id = editingOrFail(s, player);
            int count = s.races().removeLastGate(player.getUUID(), id);
            RaceStore.Track t = s.races().track(id);
            game.races.show(player.getUUID(), t.dimension(), s.races().draft(id), t.type(), game.tick, 10);
            return "Letztes Tor entfernt – noch " + count + ".";
        });
    }

    private int finish(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String id = editingOrFail(s, player);
            RaceStore.Track t = s.races().finishEdit(player.getUUID(), id);
            game.races.show(player.getUUID(), t.dimension(), t.gates(), t.type(), game.tick, 10);
            return "»" + t.name() + "« ist fertig (" + t.gates().size() + " Tore" + (t.version() > 1 ? ", Version " + t.version() + " – alte Zeiten sind im Archiv" : "")
                    + "). Losfahren: /rennen start \"" + t.name() + "\"";
        });
    }

    private int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String id = editingOrFail(s, player);
            String name = s.races().track(id).name();
            return s.races().cancelEdit(player.getUUID(), id) ? "Abgebrochen – »" + name + "« hatte noch keine Tore und ist wieder weg."
                    : "Abgebrochen – »" + name + "« bleibt, wie sie war.";
        });
    }

    private int edit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String id = trackId(s, StringArgumentType.getString(ctx, "strecke"));
            RaceStore.Track t = s.races().startEdit(player.getUUID(), isAdmin(player), id);
            return "Du baust »" + t.name() + "« neu: Setz alle Tore noch einmal mit /rennen tor (erstes = Start). Bis /rennen fertig gilt die alte Strecke."
                    + (s.races().leaderboard(id).isEmpty() ? "" : " Die bisherigen Zeiten kommen danach ins Archiv – sie wären nicht vergleichbar.");
        });
    }

    // ------------------------------------------------------------------ Fahren

    private int start(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return fail(ctx, "World Planner startet noch.");
        if (!s.config().races()) return fail(ctx, "Rennen sind auf diesem Server ausgeschaltet.");
        String name = StringArgumentType.getString(ctx, "strecke");
        String id = s.races().findIdByName(name);
        if (id == null) return fail(ctx, "Es gibt keine Strecke namens »" + name + "«. Alle: /rennen liste");
        RaceStore.Track t = s.races().track(id);
        if (!t.ready()) return fail(ctx, "»" + t.name() + "« ist noch nicht fertig gebaut.");
        if (player.isCreative() || player.isSpectator()) return fail(ctx, "Zeitmessung nur im Überlebens- oder Abenteuermodus.");
        game.races.arm(player, s, t, game.tick);
        return 1;
    }

    private int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!game.races.racing(player.getUUID())) return fail(ctx, "Du fährst gerade kein Rennen.");
        game.races.stop(player, "auf eigenen Wunsch");
        return 1;
    }

    private int times(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return fail(ctx, "World Planner startet noch.");
        RaceStore.Track t = name == null ? game.races.trackOf(player.getUUID()) : null;
        if (t == null) {
            if (name == null) return list(ctx);
            String id = s.races().findIdByName(name);
            if (id == null) return fail(ctx, "Es gibt keine Strecke namens »" + name + "«.");
            t = s.races().track(id);
        }
        List<RaceStore.Lap> board = s.races().leaderboard(t.id());
        java.util.Set<java.util.UUID> hidden = s.prefs().statsHidden();
        MutableComponent out = RaceText.message().append(GameText.text("Bestenliste »" + t.name() + "«", ChatFormatting.WHITE));
        if (board.isEmpty()) out.append("\n ").append(GameText.text("Noch keine Zeiten – fahr die erste: /rennen start \"" + t.name() + "\"", ChatFormatting.GRAY));
        boolean me = false;
        for (int i = 0; i < board.size() && i < 5; i++) {
            RaceStore.Lap l = board.get(i);
            boolean mine = l.player().equals(player.getUUID());
            me |= mine;
            out.append("\n ").append(GameText.text(RaceStore.rank(board, i) + ". ", ChatFormatting.YELLOW))
                    .append(GameText.text(!mine && hidden.contains(l.player()) ? "ausgeblendet" : s.players().name(l.player()),
                            mine ? ChatFormatting.GREEN : ChatFormatting.WHITE))
                    .append(GameText.text(" " + RaceStore.time(l.ms()), i == 0 ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
        }
        RaceStore.Lap pb = s.races().personalBest(t.id(), player.getUUID());
        if (!me && pb != null) {
            for (int i = 0; i < board.size(); i++) {
                if (board.get(i).player().equals(player.getUUID())) {
                    out.append("\n ").append(GameText.text("… du: Platz " + RaceStore.rank(board, i) + " mit " + RaceStore.time(pb.ms()), ChatFormatting.GREEN));
                }
            }
        }
        out.append(GameText.webLink(s.config().publicUrl(), "#/rennen/" + t.id(), " [Alle Zeiten]"));
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        PlannerServices s = services.get();
        if (s == null) return fail(ctx, "World Planner startet noch.");
        List<String> names = s.races().names(false);
        java.util.Set<java.util.UUID> hidden = s.prefs().statsHidden();
        MutableComponent out = RaceText.message().append(GameText.text(names.isEmpty() ? "Noch keine Strecken. Bau die erste: /rennen neu <Name>"
                : "Strecken (anklicken zum Starten):", ChatFormatting.WHITE));
        for (String name : names) {
            RaceStore.Track t = s.races().track(s.races().findIdByName(name));
            List<RaceStore.Lap> board = s.races().leaderboard(t.id());
            String cmd = "/rennen start " + StringArgumentType.escapeIfRequired(t.name());
            out.append("\n ").append(t.ready() ? GameText.command(t.name(), cmd, ChatFormatting.AQUA) : GameText.text(t.name(), ChatFormatting.GRAY))
                    .append(GameText.text(" · " + RaceStore.TYPES.get(t.type()) + ", " + RaceStore.VEHICLES.get(t.vehicle()), ChatFormatting.GRAY))
                    .append(GameText.text(!t.ready() ? " · wird noch gebaut" : board.isEmpty() ? " · noch keine Zeit"
                            : " · Rekord " + RaceStore.time(board.getFirst().ms()) + (hidden.contains(board.getFirst().player()) ? ""
                            : " von " + s.players().name(board.getFirst().player())), ChatFormatting.GRAY));
        }
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private int show(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return withPlayer(ctx, (player, s) -> {
            String id = name != null ? trackId(s, name) : s.races().editing(player.getUUID());
            if (id == null && game.races.trackOf(player.getUUID()) != null) id = game.races.trackOf(player.getUUID()).id();
            if (id == null) throw PlannerException.badRequest("Welche Strecke? /rennen zeigen <Strecke>");
            RaceStore.Track t = s.races().track(id);
            List<RaceStore.Gate> gates = s.races().editing(player.getUUID()) != null && id.equals(s.races().editing(player.getUUID())) ? s.races().draft(id) : t.gates();
            if (!t.dimension().equals(dimension(player))) throw PlannerException.badRequest("»" + t.name() + "« liegt in: " + GameText.dimensionName(t.dimension()) + ".");
            game.races.show(player.getUUID(), t.dimension(), gates, t.type(), game.tick, 20);
            return "Die Tore von »" + t.name() + "« leuchten 20 Sekunden lang (weiß = Start" + (t.type().equals("sprint") ? ", rot = Ziel" : "/Ziel") + ").";
        });
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        String[][] lines = {
                {"/rennen liste", "alle Strecken mit Rekord"},
                {"/rennen start ", "Zeitmessung – die Uhr läuft ab der Startlinie (Strecke dahinter)"},
                {"/rennen zeiten", "Bestenliste"},
                {"/rennen neu ", "Strecke bauen: Name, dann runde/sprint und Fahrzeug"},
                {"/rennen tor", "Tor hier, quer zur Blickrichtung (beim Bauen)"},
                {"/rennen fertig", "Strecke fertig"},
                {"/rennen zeigen", "Tore als Partikel zeigen"},
        };
        MutableComponent out = RaceText.message().append(GameText.text("Rennen (anklicken zum Einfügen):", ChatFormatting.WHITE));
        for (String[] line : lines) {
            out.append("\n ").append(GameText.command(line[0].strip(), line[0], ChatFormatting.AQUA)).append(GameText.text(" – " + line[1], ChatFormatting.GRAY));
        }
        out.append("\n ").append(GameText.text("Die Zeit misst der Server alle 0,05 Sekunden; die Hundertstel sind dazwischen geschätzt.", ChatFormatting.DARK_GRAY));
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    // ------------------------------------------------------------------ Hilfen

    private interface PlayerAction {
        String run(ServerPlayer player, PlannerServices s);
    }

    private int withPlayer(CommandContext<CommandSourceStack> ctx, PlayerAction action) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlannerServices s = services.get();
        if (s == null) return fail(ctx, "World Planner startet noch.");
        if (!s.config().races()) return fail(ctx, "Rennen sind auf diesem Server ausgeschaltet.");
        s.players().seen(player.getUUID(), player.getGameProfile().name(), isAdmin(player));
        try {
            String text = action.run(player, s);
            ctx.getSource().sendSuccess(() -> RaceText.message().append(GameText.text(text, ChatFormatting.WHITE)), false);
            return 1;
        } catch (PlannerException e) {
            return fail(ctx, e.getMessage());
        }
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(RaceText.message().append(GameText.text(message, ChatFormatting.RED)));
        return 0;
    }

    private static String editingOrFail(PlannerServices s, ServerPlayer player) {
        String id = s.races().editing(player.getUUID());
        if (id == null) throw PlannerException.badRequest("Du baust gerade keine Strecke. Neu: /rennen neu <Name> – ändern: /rennen bearbeiten <Strecke>");
        return id;
    }

    private static String trackId(PlannerServices s, String name) {
        String id = s.races().findIdByName(name);
        if (id == null) throw PlannerException.notFound("Es gibt keine Strecke namens »" + name + "«.");
        return id;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static boolean isAdmin(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    private static String dimension(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }

    private SuggestionProvider<CommandSourceStack> tracks(boolean readyOnly) {
        return suggestFrom(source -> services.get() == null ? List.of() : services.get().races().names(readyOnly));
    }

    private SuggestionProvider<CommandSourceStack> ownTracks() {
        return suggestFrom(source -> {
            ServerPlayer p = source.getPlayer();
            return p == null || services.get() == null ? List.of() : services.get().races().namesOwnedBy(p.getUUID());
        });
    }

    private static SuggestionProvider<CommandSourceStack> suggestFrom(Function<CommandSourceStack, List<String>> names) {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(names.apply(ctx.getSource()).stream().map(StringArgumentType::escapeIfRequired), builder);
    }
}
