package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Teams: Spieler schließen sich zusammen. Getrennt von den Mitbesitzern eines Landes.
 *
 * Regeln (alle hier auf dem Server geprüft, die Webseite zeigt nur an, was erlaubt ist):
 * - Rollen: Owner > Admin > Moderator > Mitglied > Anwärter. Man darf nur Leute unter dem eigenen Rang
 *   verwalten und niemanden auf den eigenen Rang oder höher setzen.
 * - Niemand wird ohne Zustimmung Mitglied: Einladung (muss angenommen werden) oder Bewerbung (muss das Team annehmen).
 * - Der Owner kann nicht einfach austreten. Übergabe nur zweistufig: Owner bietet an (Teamname eintippen),
 *   der Empfänger nimmt an. Auflösen nur mit eingetipptem Teamnamen.
 * - Server-Admins (OP) dürfen moderieren: Team bearbeiten, Mitglieder (außer Owner) entfernen, Team auflösen.
 */
final class TeamStore extends JsonStore {
    enum Role {
        RECRUIT(1, "Anwärter"), MEMBER(2, "Mitglied"), MODERATOR(3, "Moderator"), ADMIN(4, "Admin"), OWNER(5, "Owner");

        final int rank;
        final String label;

        Role(int rank, String label) {
            this.rank = rank;
            this.label = label;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Role parse(String raw) {
            for (Role r : values()) if (r.id().equals(raw)) return r;
            throw PlannerException.badRequest("Unbekannte Rolle.");
        }
    }

    static final Pattern TAG = Pattern.compile("[A-Z0-9]{2,5}");
    /** Logo zum Auswählen statt Bild-Upload (Uploads wären ein Sicherheits- und Moderationsrisiko). */
    static final Set<String> SYMBOLS = Set.of("⚔", "🛡", "⛏", "🏰", "🌲", "🔥", "💎", "⭐", "🐉", "⚓", "🌙", "☀", "❄", "🍀", "👑", "🏹");
    static final Set<String> VISIBILITY = Set.of("public", "private");
    static final Set<String> JOIN_POLICY = Set.of("invite", "apply");
    private static final int MAX_MEMBERS = 100;
    private static final int MAX_HISTORY = 50; // erledigte Einladungen/Bewerbungen pro Team, älteste fallen weg

    private static final class Member {
        final UUID uuid;
        Role role;
        final long joinedAt;

        Member(UUID uuid, Role role, long joinedAt) {
            this.uuid = uuid;
            this.role = role;
            this.joinedAt = joinedAt;
        }
    }

    private static final class Invite {
        final String id;
        final UUID player;
        final UUID by;
        final long createdAt;
        final long expiresAt;
        String status = "pending"; // pending, accepted, declined, withdrawn, expired
        long decidedAt;

        Invite(String id, UUID player, UUID by, long createdAt, long expiresAt) {
            this.id = id;
            this.player = player;
            this.by = by;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Application {
        final String id;
        final UUID player;
        final String message;
        final long createdAt;
        String status = "pending"; // pending, accepted, declined, withdrawn
        UUID decidedBy;
        long decidedAt;

        Application(String id, UUID player, String message, long createdAt) {
            this.id = id;
            this.player = player;
            this.message = message;
            this.createdAt = createdAt;
        }
    }

    private record Transfer(UUID to, UUID by, long createdAt) {}

    private static final class Team {
        final String id;
        String name, tag, description = "", color, symbol, visibility = "public", joinPolicy = "invite";
        final UUID createdBy;
        final long createdAt;
        long updatedAt;
        final LinkedHashMap<UUID, Member> members = new LinkedHashMap<>();
        final List<Invite> invites = new ArrayList<>();
        final List<Application> applications = new ArrayList<>();
        Transfer transfer;

        Team(String id, UUID createdBy, long createdAt) {
            this.id = id;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        UUID owner() {
            for (Member m : members.values()) if (m.role == Role.OWNER) return m.uuid;
            return null;
        }
    }

    /** Was nach dem Auflösen übrig bleibt, damit Länder/Projekte/Marker die Verknüpfung lösen können. */
    record Dissolved(String id, String name, List<UUID> members) {}

    /** Team-Kürzel für den Chat im Spiel (siehe ChatTagSync). */
    record ChatTag(String teamId, String tag, String name, String color) {}

    /** Uhr – in Tests austauschbar, um Abläufe ohne Warten zu prüfen. */
    volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final PlayerDirectory players;
    private final PlannerConfig config;

    TeamStore(Path path, Logger logger, PlayerDirectory players, PlannerConfig config) {
        super(path);
        this.players = players;
        this.config = config;
        JsonElement json = readOrBackup(logger);
        if (json != null && json.isJsonObject() && json.getAsJsonObject().has("teams")) {
            for (JsonElement el : json.getAsJsonObject().getAsJsonArray("teams")) {
                try {
                    Team t = fromJson(el.getAsJsonObject());
                    if (t.owner() != null) teams.put(t.id, t);
                } catch (Exception e) {
                    logger.warn("[World Planner] Ein Team in {} ist kaputt und wird übersprungen: {}", path, e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------ Abfragen (für andere Bereiche)

    synchronized boolean exists(String teamId) {
        return teamId != null && teams.containsKey(teamId);
    }

    /** Rolle des Spielers im Team oder null, wenn er kein Mitglied ist. */
    synchronized Role role(UUID player, String teamId) {
        Team t = teamId == null ? null : teams.get(teamId);
        Member m = t == null ? null : t.members.get(player);
        return m == null ? null : m.role;
    }

    synchronized List<String> teamIdsOf(UUID player) {
        List<String> out = new ArrayList<>();
        for (Team t : teams.values()) if (t.members.containsKey(player)) out.add(t.id);
        return out;
    }

    synchronized String tagOf(String teamId) {
        Team t = teamId == null ? null : teams.get(teamId);
        return t == null ? null : t.tag;
    }

    synchronized String nameOf(String teamId) {
        Team t = teamId == null ? null : teams.get(teamId);
        return t == null ? null : t.name;
    }

    synchronized boolean isPublic(String teamId) {
        Team t = teamId == null ? null : teams.get(teamId);
        return t != null && t.visibility.equals("public");
    }

    /** Darf der Betrachter das Team überhaupt sehen? (öffentlich, Mitglied, eingeladen, beworben, Server-Admin) */
    synchronized boolean canSee(UUID viewer, boolean admin, String teamId) {
        Team t = teams.get(teamId);
        return t != null && visible(t, viewer, admin);
    }

    // ------------------------------------------------------------------ Ändern

    synchronized String create(UUID actor, String name, String tag, String description, String color, String symbol,
                               String visibility, String joinPolicy) {
        requireBelowTeamLimit(actor);
        long now = clock.getAsLong();
        Team t = new Team(UUID.randomUUID().toString(), actor, now);
        applySettings(t, name, tag, description, color == null ? "#64b5f6" : color, symbol, visibility, joinPolicy);
        t.members.put(actor, new Member(actor, Role.OWNER, now));
        teams.put(t.id, t);
        changed();
        return t.id;
    }

    synchronized void edit(UUID actor, boolean admin, String teamId, String name, String tag, String description,
                           String color, String symbol, String visibility, String joinPolicy) {
        Team t = require(teamId);
        if (!admin) requireRole(t, actor, Role.ADMIN, "Nur Owner und Admins dürfen das Team bearbeiten.");
        applySettings(t, name, tag, description, color, symbol, visibility, joinPolicy);
        touch(t);
    }

    synchronized String invite(UUID actor, String teamId, UUID target) {
        Team t = require(teamId);
        expire(t);
        requireRole(t, actor, Role.MODERATOR, "Nur Moderatoren, Admins und der Owner dürfen einladen.");
        if (t.members.containsKey(target)) throw PlannerException.conflict(players.name(target) + " ist schon im Team.");
        for (Invite i : t.invites) {
            if (i.player.equals(target) && i.status.equals("pending")) throw PlannerException.conflict(players.name(target) + " ist schon eingeladen.");
        }
        if (t.members.size() >= MAX_MEMBERS) throw PlannerException.badRequest("Das Team ist voll (" + MAX_MEMBERS + " Mitglieder).");
        long now = clock.getAsLong();
        Invite inv = new Invite(UUID.randomUUID().toString(), target, actor, now, now + config.teamInviteDays() * 86_400_000L);
        t.invites.add(inv);
        prune(t);
        touch(t);
        return inv.id;
    }

    synchronized void withdrawInvite(UUID actor, boolean admin, String teamId, String inviteId) {
        Team t = require(teamId);
        expire(t);
        Invite inv = t.invites.stream().filter(i -> i.id.equals(inviteId)).findFirst()
                .orElseThrow(() -> PlannerException.notFound("Diese Einladung gibt es nicht."));
        if (!inv.status.equals("pending")) throw PlannerException.conflict("Die Einladung ist nicht mehr offen.");
        boolean own = inv.by.equals(actor) && rankOf(t, actor) >= Role.MODERATOR.rank;
        if (!admin && !own) requireRole(t, actor, Role.ADMIN, "Nur wer eingeladen hat oder ein Admin kann die Einladung zurückziehen.");
        inv.status = "withdrawn";
        inv.decidedAt = clock.getAsLong();
        touch(t);
    }

    synchronized void acceptInvite(UUID actor, String teamId) {
        Team t = require(teamId);
        expire(t);
        Invite inv = pendingInvite(t, actor);
        if (inv == null) throw PlannerException.notFound("Du hast keine offene Einladung für dieses Team (vielleicht abgelaufen).");
        requireBelowTeamLimit(actor);
        if (t.members.size() >= MAX_MEMBERS) throw PlannerException.badRequest("Das Team ist voll.");
        long now = clock.getAsLong();
        inv.status = "accepted";
        inv.decidedAt = now;
        t.members.put(actor, new Member(actor, Role.MEMBER, now));
        t.applications.stream().filter(a -> a.player.equals(actor) && a.status.equals("pending")).forEach(a -> {
            a.status = "withdrawn";
            a.decidedAt = now;
        });
        touch(t);
    }

    synchronized void declineInvite(UUID actor, String teamId) {
        Team t = require(teamId);
        expire(t);
        Invite inv = pendingInvite(t, actor);
        if (inv == null) throw PlannerException.notFound("Du hast keine offene Einladung für dieses Team.");
        inv.status = "declined";
        inv.decidedAt = clock.getAsLong();
        touch(t);
    }

    synchronized String apply(UUID actor, String teamId, String message) {
        Team t = require(teamId);
        if (!t.visibility.equals("public") || !t.joinPolicy.equals("apply")) {
            throw PlannerException.forbidden("Bei diesem Team kann man sich nicht bewerben – nur per Einladung.");
        }
        if (t.members.containsKey(actor)) throw PlannerException.conflict("Du bist schon im Team.");
        for (Application a : t.applications) {
            if (a.player.equals(actor) && a.status.equals("pending")) throw PlannerException.conflict("Du hast dich schon beworben.");
        }
        requireBelowTeamLimit(actor);
        Application app = new Application(UUID.randomUUID().toString(), actor, Validate.text(message, 300, "Nachricht"), clock.getAsLong());
        t.applications.add(app);
        prune(t);
        touch(t);
        return app.id;
    }

    synchronized void withdrawApplication(UUID actor, String teamId) {
        Team t = require(teamId);
        Application app = t.applications.stream().filter(a -> a.player.equals(actor) && a.status.equals("pending")).findFirst()
                .orElseThrow(() -> PlannerException.notFound("Du hast keine offene Bewerbung bei diesem Team."));
        app.status = "withdrawn";
        app.decidedAt = clock.getAsLong();
        touch(t);
    }

    /** Gibt zurück, wer sich beworben hat (für die Aktivitäts-Übersicht). */
    synchronized UUID decideApplication(UUID actor, String teamId, String applicationId, boolean accept) {
        Team t = require(teamId);
        requireRole(t, actor, Role.MODERATOR, "Nur Moderatoren, Admins und der Owner entscheiden über Bewerbungen.");
        Application app = t.applications.stream().filter(a -> a.id.equals(applicationId)).findFirst()
                .orElseThrow(() -> PlannerException.notFound("Diese Bewerbung gibt es nicht."));
        if (!app.status.equals("pending")) throw PlannerException.conflict("Über diese Bewerbung wurde schon entschieden.");
        long now = clock.getAsLong();
        if (accept) {
            if (teamIdsOfUnlocked(app.player).size() >= config.maxTeamsPerPlayer()) {
                throw PlannerException.conflict(players.name(app.player) + " ist inzwischen schon in einem anderen Team.");
            }
            if (t.members.size() >= MAX_MEMBERS) throw PlannerException.badRequest("Das Team ist voll.");
            t.members.put(app.player, new Member(app.player, Role.RECRUIT, now));
            t.invites.stream().filter(i -> i.player.equals(app.player) && i.status.equals("pending")).forEach(i -> i.status = "accepted");
        }
        app.status = accept ? "accepted" : "declined";
        app.decidedBy = actor;
        app.decidedAt = now;
        touch(t);
        return app.player;
    }

    synchronized void setRole(UUID actor, String teamId, UUID target, Role newRole) {
        Team t = require(teamId);
        Member m = t.members.get(target);
        if (m == null) throw PlannerException.notFound(players.name(target) + " ist nicht im Team.");
        if (newRole == Role.OWNER) throw PlannerException.badRequest("Owner wird man nur durch eine Übergabe.");
        if (target.equals(actor)) throw PlannerException.badRequest("Die eigene Rolle kannst du nicht ändern.");
        int mine = rankOf(t, actor);
        if (mine < Role.MODERATOR.rank) throw PlannerException.forbidden("Du darfst keine Rollen vergeben.");
        if (m.role.rank >= mine) throw PlannerException.forbidden("Du darfst nur Leute unter deinem Rang verwalten.");
        if (newRole.rank >= mine) throw PlannerException.forbidden("Du kannst niemanden auf deinen Rang oder höher setzen.");
        m.role = newRole;
        touch(t);
    }

    synchronized void kick(UUID actor, boolean admin, String teamId, UUID target) {
        Team t = require(teamId);
        Member m = t.members.get(target);
        if (m == null) throw PlannerException.notFound(players.name(target) + " ist nicht im Team.");
        if (m.role == Role.OWNER) throw PlannerException.forbidden("Der Owner kann nicht entfernt werden.");
        if (!admin) {
            int mine = rankOf(t, actor);
            if (mine < Role.MODERATOR.rank) throw PlannerException.forbidden("Du darfst niemanden entfernen.");
            if (m.role.rank >= mine) throw PlannerException.forbidden("Du darfst nur Leute unter deinem Rang entfernen.");
        }
        t.members.remove(target);
        if (t.transfer != null && t.transfer.to.equals(target)) t.transfer = null;
        touch(t);
    }

    synchronized void leave(UUID actor, String teamId) {
        Team t = require(teamId);
        Member m = t.members.get(actor);
        if (m == null) throw PlannerException.notFound("Du bist nicht in diesem Team.");
        if (m.role == Role.OWNER) {
            throw PlannerException.conflict("Als Owner kannst du nicht einfach austreten. Übergib das Team zuerst oder löse es auf.");
        }
        t.members.remove(actor);
        if (t.transfer != null && t.transfer.to.equals(actor)) t.transfer = null;
        touch(t);
    }

    synchronized void offerTransfer(UUID actor, String teamId, UUID target, String confirmName) {
        Team t = require(teamId);
        if (rankOf(t, actor) != Role.OWNER.rank) throw PlannerException.forbidden("Nur der Owner kann das Team übergeben.");
        requireConfirm(t, confirmName);
        if (target.equals(actor)) throw PlannerException.badRequest("Du bist schon Owner.");
        if (!t.members.containsKey(target)) throw PlannerException.badRequest(players.name(target) + " muss zuerst Mitglied sein.");
        t.transfer = new Transfer(target, actor, clock.getAsLong());
        touch(t);
    }

    synchronized void cancelTransfer(UUID actor, String teamId) {
        Team t = require(teamId);
        if (t.transfer == null) throw PlannerException.notFound("Es gibt keine offene Übergabe.");
        if (!actor.equals(t.owner()) && !actor.equals(t.transfer.to)) throw PlannerException.forbidden("Das darfst du nicht.");
        t.transfer = null;
        touch(t);
    }

    synchronized void acceptTransfer(UUID actor, String teamId) {
        Team t = require(teamId);
        if (t.transfer == null || !t.transfer.to.equals(actor)) throw PlannerException.notFound("Dir wurde dieses Team nicht übergeben.");
        Member m = t.members.get(actor);
        if (m == null) throw PlannerException.conflict("Du bist nicht mehr im Team.");
        UUID oldOwner = t.owner();
        if (oldOwner != null) t.members.get(oldOwner).role = Role.ADMIN;
        m.role = Role.OWNER;
        t.transfer = null;
        touch(t);
    }

    synchronized Dissolved dissolve(UUID actor, boolean admin, String teamId, String confirmName) {
        Team t = require(teamId);
        if (!admin && rankOf(t, actor) != Role.OWNER.rank) throw PlannerException.forbidden("Nur der Owner kann das Team auflösen.");
        requireConfirm(t, confirmName);
        teams.remove(teamId);
        changed();
        return new Dissolved(t.id, t.name, List.copyOf(t.members.keySet()));
    }

    // ------------------------------------------------------------------ Webseite

    synchronized JsonArray toApiJson(UUID viewer, boolean admin) {
        JsonArray arr = new JsonArray();
        for (Team t : teams.values()) {
            expire(t);
            if (visible(t, viewer, admin)) arr.add(teamJson(t, viewer, admin));
        }
        return arr;
    }

    synchronized JsonObject toApiJson(UUID viewer, boolean admin, String teamId) {
        Team t = teams.get(teamId);
        if (t == null || !visible(t, viewer, admin)) throw PlannerException.notFound("Dieses Team gibt es nicht (oder es ist privat).");
        expire(t);
        return teamJson(t, viewer, admin);
    }

    /**
     * Welches Kürzel steht im Spiel vor wessen Namen? Pro Spieler das Team mit der höchsten Rolle
     * (bei Gleichstand das, in dem er am längsten ist). Nur öffentliche Teams – sonst wüsste jeder im Spiel,
     * wer in einem privaten Team ist.
     */
    synchronized Map<UUID, ChatTag> chatTags() {
        Map<UUID, ChatTag> out = new HashMap<>();
        Map<UUID, Member> best = new HashMap<>();
        for (Team t : teams.values()) {
            if (!t.visibility.equals("public")) continue;
            for (Member m : t.members.values()) {
                Member b = best.get(m.uuid);
                if (b == null || m.role.rank > b.role.rank || (m.role.rank == b.role.rank && m.joinedAt < b.joinedAt)) {
                    best.put(m.uuid, m);
                    out.put(m.uuid, new ChatTag(t.id, t.tag, t.name, t.color));
                }
            }
        }
        return out;
    }

    /** Offene Einladungen eines Spielers (für Dashboard und Benachrichtigung). */
    synchronized List<String> pendingInvitesFor(UUID player) {
        List<String> out = new ArrayList<>();
        for (Team t : teams.values()) {
            expire(t);
            if (pendingInvite(t, player) != null) out.add(t.id);
        }
        return out;
    }

    /** Kürzel und Farbe eines öffentlichen Teams (für die Land-Anzeige im Spiel), sonst null. */
    synchronized ChatTag publicTag(String teamId) {
        Team t = teamId == null ? null : teams.get(teamId);
        return t == null || !t.visibility.equals("public") ? null : new ChatTag(t.id, t.tag, t.name, t.color);
    }

    record InviteNote(String inviteId, String teamId, String tag, String name, String by) {}

    record TransferNote(String teamId, String tag, String name, long createdAt) {}

    record ApplicationNote(String applicationId, String teamId, String tag, String player) {}

    /** Alles, was einen Spieler bei Teams betrifft – für die Hinweise im Spiel (siehe Notices). */
    record Notes(List<InviteNote> invites, List<TransferNote> transfers, List<ApplicationNote> applications,
                 List<ChatTag> memberships) {}

    synchronized Notes notesFor(UUID player) {
        List<InviteNote> invites = new ArrayList<>();
        List<TransferNote> transfers = new ArrayList<>();
        List<ApplicationNote> applications = new ArrayList<>();
        List<ChatTag> memberships = new ArrayList<>();
        for (Team t : teams.values()) {
            expire(t);
            Invite inv = pendingInvite(t, player);
            if (inv != null) invites.add(new InviteNote(inv.id, t.id, t.tag, t.name, players.name(inv.by)));
            if (t.transfer != null && t.transfer.to.equals(player)) transfers.add(new TransferNote(t.id, t.tag, t.name, t.transfer.createdAt));
            if (!t.members.containsKey(player)) continue;
            memberships.add(new ChatTag(t.id, t.tag, t.name, t.color));
            // Bewerbungen sehen nur die, die darüber entscheiden dürfen (wie auf der Webseite)
            if (rankOf(t, player) >= Role.MODERATOR.rank) {
                for (Application a : t.applications) {
                    if (a.status.equals("pending")) applications.add(new ApplicationNote(a.id, t.id, t.tag, players.name(a.player)));
                }
            }
        }
        return new Notes(invites, transfers, applications, memberships);
    }

    /** Team für den Team-Chat. */
    record ChatTeam(String id, String tag, String name, String color, List<UUID> members) {}

    /**
     * Mit welchem Team chattet der Spieler über /tc? Wie bei den Kürzeln das Team mit der höchsten Rolle
     * (bei Gleichstand das älteste) – hier aber auch private Teams, denn die Nachricht lesen nur Mitglieder.
     */
    synchronized ChatTeam chatTeamOf(UUID player) {
        Team best = null;
        Member bestMember = null;
        for (Team t : teams.values()) {
            Member m = t.members.get(player);
            if (m == null) continue;
            if (bestMember == null || m.role.rank > bestMember.role.rank || (m.role.rank == bestMember.role.rank && m.joinedAt < bestMember.joinedAt)) {
                best = t;
                bestMember = m;
            }
        }
        return best == null ? null : new ChatTeam(best.id, best.tag, best.name, best.color, List.copyOf(best.members.keySet()));
    }

    private JsonObject teamJson(Team t, UUID viewer, boolean admin) {
        Member me = t.members.get(viewer);
        int myRank = me == null ? 0 : me.role.rank;
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id);
        o.addProperty("name", t.name);
        o.addProperty("tag", t.tag);
        o.addProperty("description", t.description);
        o.addProperty("color", t.color);
        if (t.symbol != null) o.addProperty("symbol", t.symbol);
        o.addProperty("visibility", t.visibility);
        o.addProperty("joinPolicy", t.joinPolicy);
        o.addProperty("createdAt", t.createdAt);
        o.addProperty("updatedAt", t.updatedAt);
        o.add("createdBy", player(t.createdBy));
        UUID owner = t.owner();
        o.add("owner", owner == null ? null : player(owner));
        JsonArray members = new JsonArray();
        for (Member m : t.members.values()) {
            JsonObject mo = player(m.uuid);
            mo.addProperty("role", m.role.id());
            mo.addProperty("roleLabel", m.role.label);
            mo.addProperty("joinedAt", m.joinedAt);
            members.add(mo);
        }
        o.add("members", members);
        o.addProperty("memberCount", t.members.size());
        if (me != null) {
            o.addProperty("myRole", me.role.id());
            o.addProperty("myRoleLabel", me.role.label);
        }
        Invite myInvite = pendingInvite(t, viewer);
        if (myInvite != null) {
            JsonObject inv = new JsonObject();
            inv.addProperty("id", myInvite.id);
            inv.add("by", player(myInvite.by));
            inv.addProperty("expiresAt", myInvite.expiresAt);
            o.add("myInvite", inv);
        }
        t.applications.stream().filter(a -> a.player.equals(viewer) && a.status.equals("pending")).findFirst()
                .ifPresent(a -> o.addProperty("myApplication", a.id));
        if (t.transfer != null && (t.transfer.to.equals(viewer) || myRank == Role.OWNER.rank)) {
            JsonObject tr = new JsonObject();
            tr.add("to", player(t.transfer.to));
            tr.addProperty("createdAt", t.transfer.createdAt);
            o.add("pendingTransfer", tr);
        }
        // Einladungen und Bewerbungen sehen nur die, die darüber entscheiden dürfen
        if (myRank >= Role.MODERATOR.rank || admin) {
            JsonArray invites = new JsonArray();
            for (Invite i : t.invites) {
                JsonObject io = new JsonObject();
                io.addProperty("id", i.id);
                io.add("player", player(i.player));
                io.add("by", player(i.by));
                io.addProperty("createdAt", i.createdAt);
                io.addProperty("expiresAt", i.expiresAt);
                io.addProperty("status", i.status);
                if (i.decidedAt > 0) io.addProperty("decidedAt", i.decidedAt);
                invites.add(io);
            }
            o.add("invites", invites);
            JsonArray apps = new JsonArray();
            for (Application a : t.applications) {
                JsonObject ao = new JsonObject();
                ao.addProperty("id", a.id);
                ao.add("player", player(a.player));
                ao.addProperty("message", a.message);
                ao.addProperty("createdAt", a.createdAt);
                ao.addProperty("status", a.status);
                if (a.decidedBy != null) ao.add("decidedBy", player(a.decidedBy));
                apps.add(ao);
            }
            o.add("applications", apps);
        }
        JsonObject can = new JsonObject();
        can.addProperty("edit", myRank >= Role.ADMIN.rank || admin);
        can.addProperty("invite", myRank >= Role.MODERATOR.rank);
        can.addProperty("decide", myRank >= Role.MODERATOR.rank);
        can.addProperty("manageRoles", myRank >= Role.MODERATOR.rank);
        can.addProperty("leave", me != null && me.role != Role.OWNER);
        can.addProperty("transfer", myRank == Role.OWNER.rank);
        can.addProperty("dissolve", myRank == Role.OWNER.rank || admin);
        can.addProperty("apply", me == null && myInvite == null && t.visibility.equals("public") && t.joinPolicy.equals("apply"));
        can.addProperty("createProjects", myRank >= Role.MEMBER.rank);
        can.addProperty("sellForTeam", myRank >= Role.ADMIN.rank);
        o.add("can", can);
        return o;
    }

    // ------------------------------------------------------------------ Hilfsmethoden

    private boolean visible(Team t, UUID viewer, boolean admin) {
        if (admin || t.visibility.equals("public") || t.members.containsKey(viewer)) return true;
        if (pendingInvite(t, viewer) != null) return true;
        return t.transfer != null && t.transfer.to.equals(viewer);
    }

    /** Prüft erst alle Angaben und übernimmt sie dann – ein Fehler ändert nichts halb. null = unverändert. */
    private void applySettings(Team t, String name, String tag, String description, String color, String symbol,
                               String visibility, String joinPolicy) {
        String n = t.name;
        if (name != null) {
            n = Validate.name(name, 3, 32, "Teamname");
            for (Team other : teams.values()) {
                if (other != t && other.name.equalsIgnoreCase(n)) throw PlannerException.conflict("Es gibt schon ein Team namens »" + n + "«.");
            }
        } else if (n == null) {
            throw PlannerException.badRequest("Teamname fehlt.");
        }
        String g = t.tag;
        if (tag != null) {
            g = tag.strip().toUpperCase(Locale.ROOT).replaceAll("[\\[\\]]", "");
            if (!TAG.matcher(g).matches()) throw PlannerException.badRequest("Tag: 2–5 Großbuchstaben oder Ziffern, z. B. KMS.");
            for (Team other : teams.values()) {
                if (other != t && other.tag.equals(g)) throw PlannerException.conflict("Den Tag [" + g + "] hat schon ein anderes Team.");
            }
        } else if (g == null) {
            throw PlannerException.badRequest("Tag fehlt.");
        }
        String d = description == null ? t.description : Validate.text(description, 500, "Beschreibung");
        String c = color == null ? t.color : Validate.color(color);
        String sym = symbol == null ? t.symbol : symbol.isEmpty() ? null : Validate.oneOf(symbol, SYMBOLS, "Logo");
        String vis = visibility == null ? t.visibility : Validate.oneOf(visibility, VISIBILITY, "Sichtbarkeit");
        String join = joinPolicy == null ? t.joinPolicy : Validate.oneOf(joinPolicy, JOIN_POLICY, "Beitritt");
        t.name = n;
        t.tag = g;
        t.description = d;
        t.color = c;
        t.symbol = sym;
        t.visibility = vis;
        t.joinPolicy = join;
    }

    private void requireBelowTeamLimit(UUID player) {
        int count = teamIdsOfUnlocked(player).size();
        if (count >= config.maxTeamsPerPlayer()) {
            throw PlannerException.conflict(config.maxTeamsPerPlayer() == 1
                    ? "Du bist schon in einem Team. Verlasse es zuerst."
                    : "Du bist schon in " + count + " Teams (Maximum " + config.maxTeamsPerPlayer() + ").");
        }
    }

    private List<String> teamIdsOfUnlocked(UUID player) {
        List<String> out = new ArrayList<>();
        for (Team t : teams.values()) if (t.members.containsKey(player)) out.add(t.id);
        return out;
    }

    private void requireConfirm(Team t, String confirmName) {
        if (confirmName == null || !confirmName.strip().equalsIgnoreCase(t.name)) {
            throw PlannerException.badRequest("Zur Bestätigung den Teamnamen genau eintippen: " + t.name);
        }
    }

    private Invite pendingInvite(Team t, UUID player) {
        for (Invite i : t.invites) if (i.player.equals(player) && i.status.equals("pending")) return i;
        return null;
    }

    /** Abgelaufene Einladungen bekommen den Status "expired" (wird beim Lesen und Ändern erledigt). */
    private void expire(Team t) {
        long now = clock.getAsLong();
        boolean any = false;
        for (Invite i : t.invites) {
            if (i.status.equals("pending") && i.expiresAt < now) {
                i.status = "expired";
                i.decidedAt = i.expiresAt;
                any = true;
            }
        }
        if (any) changed();
    }

    /** Nur die letzten MAX_HISTORY erledigten Einladungen/Bewerbungen behalten. Offene bleiben immer. */
    private static void prune(Team t) {
        while (t.invites.size() > MAX_HISTORY) {
            Invite oldest = t.invites.stream().filter(i -> !i.status.equals("pending")).findFirst().orElse(null);
            if (oldest == null) break;
            t.invites.remove(oldest);
        }
        while (t.applications.size() > MAX_HISTORY) {
            Application oldest = t.applications.stream().filter(a -> !a.status.equals("pending")).findFirst().orElse(null);
            if (oldest == null) break;
            t.applications.remove(oldest);
        }
    }

    private int rankOf(Team t, UUID player) {
        Member m = t.members.get(player);
        return m == null ? 0 : m.role.rank;
    }

    private void requireRole(Team t, UUID actor, Role min, String message) {
        if (rankOf(t, actor) < min.rank) throw PlannerException.forbidden(message);
    }

    private Team require(String teamId) {
        Team t = teamId == null ? null : teams.get(teamId);
        if (t == null) throw PlannerException.notFound("Dieses Team gibt es nicht (mehr).");
        return t;
    }

    private void touch(Team t) {
        t.updatedAt = clock.getAsLong();
        changed();
    }

    private JsonObject player(UUID uuid) {
        JsonObject p = new JsonObject();
        p.addProperty("uuid", uuid.toString());
        p.addProperty("name", players.name(uuid));
        return p;
    }

    // ------------------------------------------------------------------ Speichern

    private static Team fromJson(JsonObject o) {
        Team t = new Team(o.get("id").getAsString(), UUID.fromString(o.get("createdBy").getAsString()), o.get("createdAt").getAsLong());
        t.name = o.get("name").getAsString();
        t.tag = o.get("tag").getAsString();
        t.description = o.has("description") ? o.get("description").getAsString() : "";
        t.color = o.get("color").getAsString();
        t.symbol = o.has("symbol") && !o.get("symbol").isJsonNull() ? o.get("symbol").getAsString() : null;
        t.visibility = o.get("visibility").getAsString();
        t.joinPolicy = o.get("joinPolicy").getAsString();
        t.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : t.createdAt;
        for (JsonElement e : o.getAsJsonArray("members")) {
            JsonObject m = e.getAsJsonObject();
            UUID u = UUID.fromString(m.get("uuid").getAsString());
            t.members.put(u, new Member(u, Role.parse(m.get("role").getAsString()), m.get("joinedAt").getAsLong()));
        }
        if (o.has("invites")) for (JsonElement e : o.getAsJsonArray("invites")) {
            JsonObject i = e.getAsJsonObject();
            Invite inv = new Invite(i.get("id").getAsString(), UUID.fromString(i.get("player").getAsString()),
                    UUID.fromString(i.get("by").getAsString()), i.get("createdAt").getAsLong(), i.get("expiresAt").getAsLong());
            inv.status = i.get("status").getAsString();
            inv.decidedAt = i.has("decidedAt") ? i.get("decidedAt").getAsLong() : 0;
            t.invites.add(inv);
        }
        if (o.has("applications")) for (JsonElement e : o.getAsJsonArray("applications")) {
            JsonObject a = e.getAsJsonObject();
            Application app = new Application(a.get("id").getAsString(), UUID.fromString(a.get("player").getAsString()),
                    a.get("message").getAsString(), a.get("createdAt").getAsLong());
            app.status = a.get("status").getAsString();
            app.decidedBy = a.has("decidedBy") ? UUID.fromString(a.get("decidedBy").getAsString()) : null;
            app.decidedAt = a.has("decidedAt") ? a.get("decidedAt").getAsLong() : 0;
            t.applications.add(app);
        }
        if (o.has("transfer") && o.get("transfer").isJsonObject()) {
            JsonObject tr = o.getAsJsonObject("transfer");
            t.transfer = new Transfer(UUID.fromString(tr.get("to").getAsString()), UUID.fromString(tr.get("by").getAsString()), tr.get("createdAt").getAsLong());
        }
        return t;
    }

    @Override
    protected JsonElement toJson() {
        JsonArray arr = new JsonArray();
        for (Team t : teams.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id);
            o.addProperty("name", t.name);
            o.addProperty("tag", t.tag);
            o.addProperty("description", t.description);
            o.addProperty("color", t.color);
            if (t.symbol != null) o.addProperty("symbol", t.symbol);
            o.addProperty("visibility", t.visibility);
            o.addProperty("joinPolicy", t.joinPolicy);
            o.addProperty("createdBy", t.createdBy.toString());
            o.addProperty("createdAt", t.createdAt);
            o.addProperty("updatedAt", t.updatedAt);
            JsonArray members = new JsonArray();
            for (Member m : t.members.values()) {
                JsonObject mo = new JsonObject();
                mo.addProperty("uuid", m.uuid.toString());
                mo.addProperty("role", m.role.id());
                mo.addProperty("joinedAt", m.joinedAt);
                members.add(mo);
            }
            o.add("members", members);
            JsonArray invites = new JsonArray();
            for (Invite i : t.invites) {
                JsonObject io = new JsonObject();
                io.addProperty("id", i.id);
                io.addProperty("player", i.player.toString());
                io.addProperty("by", i.by.toString());
                io.addProperty("createdAt", i.createdAt);
                io.addProperty("expiresAt", i.expiresAt);
                io.addProperty("status", i.status);
                if (i.decidedAt > 0) io.addProperty("decidedAt", i.decidedAt);
                invites.add(io);
            }
            o.add("invites", invites);
            JsonArray apps = new JsonArray();
            for (Application a : t.applications) {
                JsonObject ao = new JsonObject();
                ao.addProperty("id", a.id);
                ao.addProperty("player", a.player.toString());
                ao.addProperty("message", a.message);
                ao.addProperty("createdAt", a.createdAt);
                ao.addProperty("status", a.status);
                if (a.decidedBy != null) ao.addProperty("decidedBy", a.decidedBy.toString());
                if (a.decidedAt > 0) ao.addProperty("decidedAt", a.decidedAt);
                apps.add(ao);
            }
            o.add("applications", apps);
            if (t.transfer != null) {
                JsonObject tr = new JsonObject();
                tr.addProperty("to", t.transfer.to.toString());
                tr.addProperty("by", t.transfer.by.toString());
                tr.addProperty("createdAt", t.transfer.createdAt);
                o.add("transfer", tr);
            }
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("teams", arr);
        return root;
    }
}
