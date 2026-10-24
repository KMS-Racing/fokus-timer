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
 *   /planer login            Code für die Webseite holen
 *   /planer abmelden         auf allen Geräten von der Webseite abmelden
 *   /land [info]             wem gehört dieser Chunk?
 *   /land neu <Name>         neues Land mit dem Chunk, in dem du stehst
 *   /land erweitern [Name]   Chunk zu deinem Land hinzufügen (muss angrenzen)
 *   /land abgeben            Chunk aus deinem Land entfernen
 *   /land liste              deine Länder und Einladungen
 *   /land einladen <Land> <Spieler>, annehmen <Land>, ablehnen <Land>, verlassen <Land>
 *   /land admin loeschen <Land>, /land admin entfernen <Land> <Spieler>
 *
 * Namen mit Leerzeichen in Anführungszeichen schreiben: /land neu "Kims Base"
 */
final class PlannerCommands {
    private final Supplier<PlannerServices> services;

    PlannerCommands(Supplier<PlannerServices> services) {
        this.services = services;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("planer")
                .then(Commands.literal("login").executes(this::login))
                .then(Commands.literal("abmelden").executes(this::logoutAll)));

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
            ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(target);
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
        return Component.literal("[Planer] ").withStyle(ChatFormatting.GOLD);
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
