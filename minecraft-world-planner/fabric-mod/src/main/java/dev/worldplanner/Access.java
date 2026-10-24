package dev.worldplanner;

import java.util.UUID;

/**
 * Rechte-Abfragen über Bereiche hinweg: Wem gehört welches Land? Wer ist in welchem Team mit welcher Rolle?
 *
 * Reihenfolge der Sperren (verhindert Deadlocks): Handel/Projekte/Marker -> Länder -> Teams.
 * Ein Speicher fragt nur bei Speichern "unter" sich nach, nie umgekehrt. Teams fragen niemanden.
 */
final class Access {
    private final LandStore lands;
    private final TeamStore teams;

    Access(LandStore lands, TeamStore teams) {
        this.lands = lands;
        this.teams = teams;
    }

    boolean landExists(String landId) {
        return landId != null && lands.exists(landId);
    }

    boolean isLandOwner(UUID player, String landId) {
        return landId != null && lands.isOwner(player, landId);
    }

    /** Team, dem das Land zugeordnet ist, oder null. */
    String landTeam(String landId) {
        return landId == null ? null : lands.teamOf(landId);
    }

    boolean teamExists(String teamId) {
        return teams.exists(teamId);
    }

    TeamStore.Role teamRole(UUID player, String teamId) {
        return teamId == null ? null : teams.role(player, teamId);
    }

    boolean isTeamMember(UUID player, String teamId) {
        return teamRole(player, teamId) != null;
    }

    /** Mindestens diese Rolle im Team? */
    boolean hasTeamRole(UUID player, String teamId, TeamStore.Role min) {
        TeamStore.Role r = teamRole(player, teamId);
        return r != null && r.rank >= min.rank;
    }

    /** Darf der Spieler etwas mit diesem Land verknüpfen? (Besitzer oder Mitglied des Teams, dem es gehört) */
    boolean canLinkLand(UUID player, String landId) {
        if (!landExists(landId)) return false;
        if (isLandOwner(player, landId)) return true;
        String team = landTeam(landId);
        return team != null && hasTeamRole(player, team, TeamStore.Role.MEMBER);
    }
}
