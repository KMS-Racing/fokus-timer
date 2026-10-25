package dev.worldplanner;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Team-Kürzel im Spiel: [KMS] vor dem Namen – im Chat, in der Tab-Liste und über dem Kopf.
 *
 * Dafür nutzt die Mod Minecrafts eigene Scoreboard-Teams (wie /team). Das braucht keine Mod im Spiel
 * der Spieler. Jedes öffentliche Planer-Team bekommt ein Scoreboard-Team "wpt_…" mit dem Kürzel als Präfix.
 *
 * Regeln:
 * - Es werden nur eigene Teams (Name beginnt mit "wpt_") angelegt, geändert oder entfernt.
 * - Wer schon in einem anderen Scoreboard-Team ist (z. B. von einem Minispiel oder /team), wird nicht
 *   herausgenommen – dann gibt es für ihn eben kein Kürzel.
 * - Private Teams bekommen kein Kürzel, sonst wüsste jeder im Spiel, wer drin ist.
 * - Damit sich am Spiel nichts ändert: Teammitglieder sehen unsichtbare Kollegen nicht (Minecraft-Standard
 *   wäre "halb durchsichtig sehen"), Schaden unter Teammitgliedern bleibt an.
 *   Eine Kleinigkeit bleibt: Minecraft zählt Teammitglieder als Verbündete – Schwert-Schwungangriffe
 *   (Sweep) treffen sie deshalb nicht.
 * Muss auf dem Server-Thread laufen (Scoreboard ist Minecraft-Zustand).
 */
final class ChatTagSync {
    static final String PREFIX = "wpt_";

    private final Logger logger;
    private final TeamStore teams;
    private final PlayerDirectory players;
    /** Spieler, für die schon gemeldet wurde, dass sie in einem fremden Scoreboard-Team sind (nur einmal loggen). */
    private final Set<String> warnedForeign = new HashSet<>();

    ChatTagSync(Logger logger, TeamStore teams, PlayerDirectory players) {
        this.logger = logger;
        this.teams = teams;
        this.players = players;
    }

    static String scoreboardName(String teamId) {
        return PREFIX + teamId.replace("-", "").substring(0, 12);
    }

    /** Bringt die Scoreboard-Teams auf den Stand der Planer-Teams. Ändert nur, was nicht schon stimmt. */
    void sync(Scoreboard scoreboard) {
        Map<String, TeamStore.ChatTag> wantedTeams = new HashMap<>(); // Scoreboard-Name -> Kürzel
        Map<String, String> wantedMember = new HashMap<>();           // Spielername -> Scoreboard-Name
        for (Map.Entry<UUID, TeamStore.ChatTag> e : teams.chatTags().entrySet()) {
            if (!players.known(e.getKey())) continue;
            String sbName = scoreboardName(e.getValue().teamId());
            wantedTeams.put(sbName, e.getValue());
            wantedMember.put(players.name(e.getKey()), sbName);
        }

        for (Map.Entry<String, TeamStore.ChatTag> e : wantedTeams.entrySet()) {
            PlayerTeam team = scoreboard.getPlayerTeam(e.getKey());
            if (team == null) team = scoreboard.addPlayerTeam(e.getKey());
            Component prefix = prefix(e.getValue());
            if (!prefix.equals(team.getPlayerPrefix())) team.setPlayerPrefix(prefix);
            Component display = Component.literal(e.getValue().name());
            if (!display.equals(team.getDisplayName())) team.setDisplayName(display);
            if (team.canSeeFriendlyInvisibles()) team.setSeeFriendlyInvisibles(false);
            if (!team.isAllowFriendlyFire()) team.setAllowFriendlyFire(true);
        }

        for (Map.Entry<String, String> e : wantedMember.entrySet()) {
            PlayerTeam target = scoreboard.getPlayerTeam(e.getValue());
            PlayerTeam current = scoreboard.getPlayersTeam(e.getKey());
            if (current == target) continue;
            if (current != null && !current.getName().startsWith(PREFIX)) {
                if (warnedForeign.add(e.getKey())) {
                    logger.info("[World Planner] {} ist im Scoreboard-Team »{}« – das bleibt so, darum kein Team-Kürzel im Chat.",
                            e.getKey(), current.getName());
                }
                continue;
            }
            scoreboard.addPlayerToTeam(e.getKey(), target); // nimmt ihn vorher aus seinem alten wpt_-Team
        }

        // Aufräumen: nur eigene Teams, fremde bleiben unberührt
        for (PlayerTeam team : List.copyOf(scoreboard.getPlayerTeams())) {
            if (!team.getName().startsWith(PREFIX)) continue;
            if (!wantedTeams.containsKey(team.getName())) {
                scoreboard.removePlayerTeam(team);
                continue;
            }
            for (String member : List.copyOf(team.getPlayers())) {
                if (!team.getName().equals(wantedMember.get(member))) scoreboard.removePlayerFromTeam(member, team);
            }
        }
    }

    /** Für chatTeamTags=false: alle eigenen Scoreboard-Teams entfernen. Gibt zurück, wie viele es waren. */
    static int removeAll(Scoreboard scoreboard) {
        int n = 0;
        for (PlayerTeam team : List.copyOf(scoreboard.getPlayerTeams())) {
            if (team.getName().startsWith(PREFIX)) {
                scoreboard.removePlayerTeam(team);
                n++;
            }
        }
        return n;
    }

    /** "[KMS] " – Klammern grau, Kürzel in der Teamfarbe. */
    static Component prefix(TeamStore.ChatTag tag) {
        MutableComponent out = Component.literal("[").withStyle(ChatFormatting.GRAY);
        out.append(Component.literal(tag.tag()).withStyle(style -> style.withColor(TextColor.fromRgb(GameText.rgb(tag.color())))));
        out.append(Component.literal("] ").withStyle(ChatFormatting.GRAY));
        return out;
    }
}
