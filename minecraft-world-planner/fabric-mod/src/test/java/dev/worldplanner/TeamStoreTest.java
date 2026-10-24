package dev.worldplanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static dev.worldplanner.TestSupport.OVERWORLD;
import static dev.worldplanner.TestSupport.chunks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Teams: Rollen, Zustimmung beim Beitritt, Übergabe, Auflösen ohne Datenverlust. */
class TeamStoreTest {
    @TempDir
    Path dir;
    TestSupport.World w;
    UUID owner, alice, bob, carl, op;
    TeamStore.Role R_ADMIN = TeamStore.Role.ADMIN, R_MOD = TeamStore.Role.MODERATOR, R_MEMBER = TeamStore.Role.MEMBER;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config());
        owner = w.player(1, "Olli");
        alice = w.player(2, "Alice");
        bob = w.player(3, "Bob");
        carl = w.player(4, "Carl");
        op = w.admin(9, "Chef");
    }

    private int status(Runnable r) {
        return assertThrows(PlannerException.class, r::run).status;
    }

    private String team(UUID by, String name, String tag) {
        return w.teams.create(by, name, tag, null, null, null, null, null);
    }

    /** Einladen + annehmen – der einzige Weg ins Team außer Bewerbung. */
    private void join(String teamId, UUID by, UUID player) {
        w.teams.invite(by, teamId, player);
        w.teams.acceptInvite(player, teamId);
    }

    @Test
    void createSetsOwnerAndValidatesNameAndTag() {
        String id = team(owner, "Kart Racing", "kms");
        assertEquals(TeamStore.Role.OWNER, w.teams.role(owner, id));
        assertEquals("KMS", w.teams.tagOf(id), "Tag wird groß geschrieben");
        assertEquals(409, status(() -> team(alice, "kart racing", "ABC")), "Name schon vergeben (ohne Groß/klein)");
        assertEquals(409, status(() -> team(alice, "Andere", "[kms]")), "Tag schon vergeben");
        assertEquals(400, status(() -> team(alice, "Andere", "X")), "Tag zu kurz");
        assertEquals(400, status(() -> team(alice, "Andere", "TOOLONG")), "Tag zu lang");
        assertEquals(400, status(() -> team(alice, "<script>", "ABC")), "HTML im Namen");
        assertEquals(400, status(() -> w.teams.create(alice, "Gut", "ABC", null, "rot", null, null, null)), "Farbe ungültig");
        assertEquals(409, status(() -> team(owner, "Zweites", "ZWEI")), "Standard: nur ein Team pro Spieler");
    }

    @Test
    void nobodyJoinsWithoutConsent() {
        String id = team(owner, "Kart Racing", "KMS");
        w.teams.invite(owner, id, alice);
        assertNull(w.teams.role(alice, id), "Eine Einladung allein macht niemanden zum Mitglied");
        assertEquals(1, w.teams.pendingInvitesFor(alice).size());
        w.teams.declineInvite(alice, id);
        assertNull(w.teams.role(alice, id));
        assertEquals(404, status(() -> w.teams.acceptInvite(alice, id)), "abgelehnte Einladung kann man nicht mehr annehmen");

        w.teams.invite(owner, id, alice);
        w.teams.acceptInvite(alice, id);
        assertEquals(R_MEMBER, w.teams.role(alice, id));
        assertEquals(409, status(() -> w.teams.invite(owner, id, alice)), "schon Mitglied");
    }

    @Test
    void onlyModeratorsAndAboveInvite() {
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        assertEquals(403, status(() -> w.teams.invite(alice, id, bob)), "Mitglieder dürfen nicht einladen");
        w.teams.setRole(owner, id, alice, R_MOD);
        String inv = w.teams.invite(alice, id, bob);
        assertEquals(409, status(() -> w.teams.invite(alice, id, bob)), "doppelte Einladung");
        assertEquals(403, status(() -> w.teams.invite(carl, id, bob)), "Fremde dürfen nicht einladen");
        w.teams.withdrawInvite(alice, false, id, inv);
        assertEquals(404, status(() -> w.teams.acceptInvite(bob, id)), "zurückgezogene Einladung");
    }

    @Test
    void invitesExpire() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        w.teams.clock = now::get;
        String id = team(owner, "Kart Racing", "KMS");
        w.teams.invite(owner, id, alice);
        now.addAndGet(w.config.teamInviteDays() * 86_400_000L + 1);
        assertEquals(404, status(() -> w.teams.acceptInvite(alice, id)), "abgelaufen");
        assertTrue(w.teams.pendingInvitesFor(alice).isEmpty());
        // Danach kann neu eingeladen werden
        w.teams.invite(owner, id, alice);
        w.teams.acceptInvite(alice, id);
        assertEquals(R_MEMBER, w.teams.role(alice, id));
    }

    @Test
    void applicationsNeedPublicApplyTeamAndADecision() {
        String closed = team(owner, "Geschlossen", "ZU");
        assertEquals(403, status(() -> w.teams.apply(alice, closed, "Hallo")), "nur per Einladung");
        String open = w.teams.create(carl, "Offen", "AUF", null, null, null, "public", "apply");
        String app = w.teams.apply(alice, open, "Ich baue gerne Redstone");
        assertEquals(409, status(() -> w.teams.apply(alice, open, "nochmal")), "doppelte Bewerbung");
        assertNull(w.teams.role(alice, open), "Bewerbung allein macht niemanden zum Mitglied");
        assertEquals(403, status(() -> w.teams.decideApplication(bob, open, app, true)), "Fremde entscheiden nicht");
        assertEquals(alice, w.teams.decideApplication(carl, open, app, true));
        assertEquals(TeamStore.Role.RECRUIT, w.teams.role(alice, open), "Bewerber starten als Anwärter");
        assertEquals(409, status(() -> w.teams.decideApplication(carl, open, app, false)), "schon entschieden");
    }

    @Test
    void acceptingRespectsTheTeamLimit() {
        String a = team(owner, "Team A", "AAA");
        String b = team(carl, "Team B", "BBB");
        join(a, owner, alice);
        w.teams.invite(carl, b, alice);
        assertEquals(409, status(() -> w.teams.acceptInvite(alice, b)), "schon in einem Team");
        w.teams.leave(alice, a);
        w.teams.acceptInvite(alice, b);
        assertEquals(R_MEMBER, w.teams.role(alice, b));
    }

    @Test
    void rolesOnlyBelowYourOwnRank() {
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        join(id, owner, bob);
        join(id, owner, carl);
        assertEquals(400, status(() -> w.teams.setRole(owner, id, alice, TeamStore.Role.OWNER)), "Owner nur per Übergabe");
        w.teams.setRole(owner, id, alice, R_ADMIN);
        w.teams.setRole(alice, id, bob, R_MOD);
        assertEquals(403, status(() -> w.teams.setRole(alice, id, carl, R_ADMIN)), "Admin kann niemanden zum Admin machen");
        assertEquals(403, status(() -> w.teams.setRole(bob, id, carl, R_MOD)), "Moderator kann niemanden zum Moderator machen");
        w.teams.setRole(bob, id, carl, TeamStore.Role.RECRUIT);
        assertEquals(403, status(() -> w.teams.setRole(bob, id, alice, R_MEMBER)), "nicht nach oben verwalten");
        assertEquals(400, status(() -> w.teams.setRole(alice, id, alice, R_MEMBER)), "eigene Rolle nicht änderbar");
        assertEquals(403, status(() -> w.teams.setRole(op, id, carl, R_MEMBER)), "Server-Admins vergeben keine Team-Rollen");
    }

    @Test
    void kickRules() {
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        join(id, owner, bob);
        w.teams.setRole(owner, id, alice, R_MOD);
        assertEquals(403, status(() -> w.teams.kick(owner, false, id, owner)), "Owner kann nicht entfernt werden");
        assertEquals(403, status(() -> w.teams.kick(op, true, id, owner)), "auch nicht von Server-Admins");
        assertEquals(403, status(() -> w.teams.kick(bob, false, id, alice)), "Mitglied entfernt keinen Moderator");
        w.teams.kick(alice, false, id, bob);
        assertNull(w.teams.role(bob, id));
        join(id, owner, bob);
        w.teams.kick(op, true, id, bob);
        assertNull(w.teams.role(bob, id), "Server-Admins dürfen moderieren");
    }

    @Test
    void ownerTransferNeedsConfirmationAndAcceptance() {
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        assertEquals(409, status(() -> w.teams.leave(owner, id)), "Owner kann nicht einfach gehen");
        assertEquals(400, status(() -> w.teams.offerTransfer(owner, id, alice, "falsch")), "Teamname muss stimmen");
        assertEquals(400, status(() -> w.teams.offerTransfer(owner, id, bob, "Kart Racing")), "nur an Mitglieder");
        assertEquals(403, status(() -> w.teams.offerTransfer(alice, id, alice, "Kart Racing")), "nur der Owner");
        w.teams.offerTransfer(owner, id, alice, "kart racing");
        assertEquals(TeamStore.Role.OWNER, w.teams.role(owner, id), "erst nach Annahme");
        assertEquals(404, status(() -> w.teams.acceptTransfer(bob, id)));
        w.teams.acceptTransfer(alice, id);
        assertEquals(TeamStore.Role.OWNER, w.teams.role(alice, id));
        assertEquals(R_ADMIN, w.teams.role(owner, id), "der alte Owner wird Admin");
        w.teams.leave(owner, id);
        assertNull(w.teams.role(owner, id));
    }

    @Test
    void kickedTransferTargetCancelsTheTransfer() {
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        w.teams.offerTransfer(owner, id, alice, "Kart Racing");
        w.teams.leave(alice, id);
        assertEquals(404, status(() -> w.teams.acceptTransfer(alice, id)));
        assertEquals(TeamStore.Role.OWNER, w.teams.role(owner, id), "nichts wurde halb übergeben");
    }

    @Test
    void privateTeamsAreInvisibleToOutsiders() {
        String id = w.teams.create(owner, "Geheim", "PSST", null, null, null, "private", null);
        assertFalse(w.teams.canSee(alice, false, id));
        assertEquals(0, w.teams.toApiJson(alice, false).size());
        assertEquals(404, status(() -> w.teams.toApiJson(alice, false, id)));
        w.teams.invite(owner, id, alice);
        assertTrue(w.teams.canSee(alice, false, id), "Eingeladene sehen das Team");
        assertTrue(w.teams.canSee(op, true, id), "Server-Admins sehen alles");
    }

    @Test
    void invitesAndApplicationsOnlyVisibleToDeciders() {
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        w.teams.invite(owner, id, bob);
        JsonObject asMember = w.teams.toApiJson(alice, false, id);
        assertFalse(asMember.has("invites"), "Mitglieder sehen keine Einladungsliste");
        assertFalse(asMember.getAsJsonObject("can").get("invite").getAsBoolean());
        JsonObject asOwner = w.teams.toApiJson(owner, false, id);
        JsonArray invites = asOwner.getAsJsonArray("invites");
        assertEquals(2, invites.size(), "Verlauf: Alices angenommene und Bobs offene Einladung");
        assertEquals("accepted", invites.get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("pending", invites.get(1).getAsJsonObject().get("status").getAsString());
        JsonObject asInvited = w.teams.toApiJson(bob, false, id);
        assertTrue(asInvited.has("myInvite"));
    }

    @Test
    void failedEditChangesNothing() {
        String id = team(owner, "Kart Racing", "KMS");
        assertEquals(400, status(() -> w.teams.edit(owner, false, id, "Neuer Name", "?", null, null, null, null, null)));
        assertEquals("Kart Racing", w.teams.nameOf(id), "gültiger Name darf bei ungültigem Tag nicht übernommen werden");
        assertEquals(403, status(() -> w.teams.edit(alice, false, id, "Fremd", null, null, null, null, null, null)));
        w.teams.edit(op, true, id, null, null, "Von Admin moderiert", null, null, null, null);
    }

    @Test
    void dissolveKeepsLandsProjectsMarkersAndListings() throws Exception {
        w.discover(-5, -5, 5, 5);
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        String land = w.lands.create(owner, false, "Basis", OVERWORLD, chunks(0, 0));
        w.lands.setTeam(owner, false, land, id);

        JsonObject p = new JsonObject();
        p.addProperty("name", "Rennstrecke");
        p.addProperty("teamId", id);
        p.addProperty("visibility", "team");
        String project = w.projects.create(alice, false, p);

        JsonObject m = new JsonObject();
        m.addProperty("name", "Boxengasse");
        m.addProperty("x", 10);
        m.addProperty("z", 10);
        m.addProperty("teamId", id);
        m.addProperty("visibility", "team");
        String marker = w.markers.create(alice, false, m);

        JsonObject t = new JsonObject();
        t.addProperty("kind", "fixed");
        t.addProperty("title", "Team-Elytra");
        t.addProperty("category", "armor");
        t.addProperty("itemName", "Elytra");
        t.addProperty("price", 30);
        t.addProperty("teamId", id);
        String listing = w.trades.create(owner, "team-listing-1", t).get("id").getAsString();

        assertEquals(400, status(() -> w.teams.dissolve(owner, false, id, "falsch")));
        assertEquals(403, status(() -> w.teams.dissolve(alice, false, id, "Kart Racing")));
        TeamStore.Dissolved d = w.teams.dissolve(owner, false, id, "Kart Racing");
        w.services.onTeamDissolved(d);
        assertEquals(2, d.members().size());

        assertFalse(w.teams.exists(id));
        assertTrue(w.lands.exists(land), "Land bleibt");
        assertNull(w.lands.teamOf(land));
        JsonObject pj = w.projects.toApiJson(alice, false, project);
        assertEquals("private", pj.get("visibility").getAsString(), "Nur-Team wird zu Nur-Beteiligte");
        assertFalse(pj.has("teamId"));
        JsonObject mj = find(w.markers.toApiJson(alice, false), marker);
        assertEquals("private", mj.get("visibility").getAsString(), "Team-Marker werden privat für den Ersteller");
        JsonObject lj = w.trades.toApiJson(owner, false, listing);
        assertEquals("active", lj.get("status").getAsString(), "Angebot bleibt bestehen");
        assertFalse(lj.has("teamId"));
        assertEquals(owner.toString(), lj.getAsJsonObject("seller").get("uuid").getAsString(), "verkauft jetzt im Namen des Erstellers");

        // Nach einem Neustart ist alles noch da
        TestSupport.World again = w.restart();
        assertTrue(again.lands.exists(land));
        assertEquals("Rennstrecke", again.projects.toApiJson(alice, false, project).get("name").getAsString());
        assertFalse(again.teams.exists(id));
    }

    @Test
    void leavingTheTeamUnlinksLandsWithoutTeamOwner() {
        w.discover(-5, -5, 5, 5);
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        String land = w.lands.create(alice, false, "Alices Haus", OVERWORLD, chunks(1, 1));
        w.lands.setTeam(alice, false, land, id);
        assertEquals(id, w.lands.teamOf(land));
        w.teams.leave(alice, id);
        w.services.onTeamMembershipChanged(id);
        assertNull(w.lands.teamOf(land), "kein Besitzer mehr im Team → Zuordnung gelöst");
        assertTrue(w.lands.exists(land));
    }

    @Test
    void teamsSurviveRestart() throws Exception {
        String id = team(owner, "Kart Racing", "KMS");
        join(id, owner, alice);
        w.teams.setRole(owner, id, alice, R_MOD);
        w.teams.invite(alice, id, bob);
        TestSupport.World again = w.restart();
        assertEquals(R_MOD, again.teams.role(alice, id));
        again.teams.acceptInvite(bob, id);
        assertEquals(R_MEMBER, again.teams.role(bob, id));
    }

    private static JsonObject find(JsonArray arr, String id) {
        for (JsonElement e : arr) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError("nicht gefunden: " + id);
    }
}
