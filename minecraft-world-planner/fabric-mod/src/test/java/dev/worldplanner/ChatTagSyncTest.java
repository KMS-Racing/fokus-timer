package dev.worldplanner;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Team-Kürzel im Spiel: Abgleich der Planer-Teams mit Minecrafts Scoreboard-Teams (ohne laufenden Server). */
class ChatTagSyncTest {
    @TempDir
    Path dir;
    TestSupport.World w;
    Scoreboard sb;
    ChatTagSync sync;
    UUID alice, bob, carl;

    @BeforeEach
    void setUp() {
        w = new TestSupport.World(dir, TestSupport.config("maxTeamsPerPlayer", 2));
        alice = w.player(1, "Alice");
        bob = w.player(2, "Bob");
        carl = w.player(3, "Carl");
        sb = new Scoreboard();
        sync = new ChatTagSync(TestSupport.LOG, w.teams, w.players);
    }

    private String team(UUID owner, String name, String tag, String visibility) {
        return w.teams.create(owner, name, tag, null, "#ff8800", null, visibility, null);
    }

    private void join(String teamId, UUID by, UUID player) {
        w.teams.invite(by, teamId, player);
        w.teams.acceptInvite(player, teamId);
    }

    private PlayerTeam sbTeam(String teamId) {
        return sb.getPlayerTeam(ChatTagSync.scoreboardName(teamId));
    }

    @Test
    void publicTeamGetsPrefixAndMembers() {
        String id = team(alice, "Kart Racing", "KMS", "public");
        join(id, alice, bob);
        sync.sync(sb);
        PlayerTeam t = sbTeam(id);
        assertNotNull(t, "Scoreboard-Team angelegt");
        assertEquals("[KMS] ", t.getPlayerPrefix().getString(), "Kürzel als Präfix vor dem Namen");
        assertEquals("Kart Racing", t.getDisplayName().getString());
        assertEquals(Set.of("Alice", "Bob"), Set.copyOf(t.getPlayers()));
        assertFalse(t.canSeeFriendlyInvisibles(), "keine Spieländerung: Unsichtbare bleiben unsichtbar");
        assertTrue(t.isAllowFriendlyFire(), "Schaden unter Teammitgliedern bleibt wie ohne Team");
        assertNull(sb.getPlayersTeam("Carl"), "Carl ist in keinem Team");
    }

    @Test
    void privateTeamsGetNoTag() {
        team(alice, "Geheimbund", "PSST", "private");
        sync.sync(sb);
        assertTrue(sb.getPlayerTeams().isEmpty(), "sonst wüsste jeder im Spiel, wer drin ist");
    }

    @Test
    void foreignScoreboardTeamsAreLeftAlone() {
        PlayerTeam minigame = sb.addPlayerTeam("Minispiel");
        sb.addPlayerToTeam("Bob", minigame);
        String id = team(alice, "Kart Racing", "KMS", "public");
        join(id, alice, bob);
        sync.sync(sb);
        assertEquals("Minispiel", sb.getPlayersTeam("Bob").getName(), "Bob bleibt im fremden Team");
        assertEquals(Set.of("Alice"), Set.copyOf(sbTeam(id).getPlayers()));
        ChatTagSync.removeAll(sb);
        assertNotNull(sb.getPlayerTeam("Minispiel"), "removeAll entfernt nur eigene Teams");
        assertNull(sbTeam(id));
    }

    @Test
    void followsLeavingTagChangesAndDissolving() {
        String id = team(alice, "Kart Racing", "KMS", "public");
        join(id, alice, bob);
        sync.sync(sb);
        w.teams.leave(bob, id);
        w.teams.edit(alice, false, id, null, "RACE", null, null, null, null, null);
        sync.sync(sb);
        assertEquals(Set.of("Alice"), Set.copyOf(sbTeam(id).getPlayers()), "ausgetreten = kein Kürzel mehr");
        assertNull(sb.getPlayersTeam("Bob"));
        assertEquals("[RACE] ", sbTeam(id).getPlayerPrefix().getString(), "neues Kürzel");
        w.teams.edit(alice, false, id, null, null, null, null, null, "private", null);
        sync.sync(sb);
        assertNull(sbTeam(id), "auf privat gestellt = Kürzel weg");
        w.teams.edit(alice, false, id, null, null, null, null, null, "public", null);
        sync.sync(sb);
        w.teams.dissolve(alice, false, id, "Kart Racing");
        sync.sync(sb);
        assertTrue(sb.getPlayerTeams().isEmpty(), "aufgelöst = Scoreboard-Team weg");
    }

    @Test
    void highestRoleWinsWhenInTwoTeams() {
        String a = team(alice, "Team A", "AAA", "public");
        String b = team(carl, "Team B", "BBB", "public");
        join(a, alice, bob);           // Bob: Mitglied in A
        join(b, carl, bob);            // Bob: Mitglied in B (später beigetreten)
        sync.sync(sb);
        assertEquals(ChatTagSync.scoreboardName(a), sb.getPlayersTeam("Bob").getName(), "gleicher Rang: das ältere Team");
        w.teams.setRole(carl, b, bob, TeamStore.Role.ADMIN);
        sync.sync(sb);
        assertEquals(ChatTagSync.scoreboardName(b), sb.getPlayersTeam("Bob").getName(), "höhere Rolle gewinnt");
        assertFalse(sbTeam(a).getPlayers().contains("Bob"), "nur ein Kürzel pro Spieler");
    }

    @Test
    void renamedPlayerKeepsTheTagUnderTheNewName() {
        String id = team(alice, "Kart Racing", "KMS", "public");
        sync.sync(sb);
        w.players.seen(alice, "Alicia", false); // neuer Minecraft-Name, gleiche UUID
        sync.sync(sb);
        assertEquals(Set.of("Alicia"), Set.copyOf(sbTeam(id).getPlayers()));
        assertNull(sb.getPlayersTeam("Alice"), "der alte Name wird entfernt");
    }
}
