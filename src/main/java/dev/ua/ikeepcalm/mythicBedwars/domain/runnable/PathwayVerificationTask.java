package dev.ua.ikeepcalm.mythicBedwars.domain.runnable;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.core.PathwayManager;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathwayVerificationTask extends BukkitRunnable {

    private static final Logger log = LoggerFactory.getLogger(PathwayVerificationTask.class);

    private final MythicBedwars plugin;
    private final CircleOfImaginationAPI circleOfImaginationAPI;

    public PathwayVerificationTask(MythicBedwars plugin) {
        this.plugin = plugin;
        this.circleOfImaginationAPI = plugin.getCircleOfImaginationAPI();
    }

    @Override
    public void run() {
        for (Arena arena : BedwarsAPI.getGameAPI().getArenas()) {
            if (arena.getStatus() != ArenaStatus.RUNNING) continue;

            if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) continue;

            for (Player player : arena.getPlayers()) {
                verifyPlayerPathway(player, arena);
            }
        }
    }

    private void verifyPlayerPathway(Player player, Arena arena) {
        Team team = arena.getPlayerTeam(player);
        if (team == null) {
            log.debug("Player {} has no team in arena {}", player.getName(), arena.getName());
            return;
        }

        String teamPathway = plugin.getArenaPathwayManager().getTeamPathway(arena, team);
        if (teamPathway == null) {
            log.debug("Team {} has no assigned pathway in arena {}", team.getDisplayName(), arena.getName());
            return;
        }

        PathwayManager.PlayerMagicData playerData = plugin.getArenaPathwayManager().getPlayerData(player);
        if (playerData == null) {
            log.debug("Player {} has no magic data in arena {}", player.getName(), arena.getName());
            return;
        }

        if (!teamPathway.equals(playerData.getPathway())) {
            log.info("Detected pathway mismatch for player {}: has {} but should have {} (team {})", player.getName(), playerData.getPathway(), teamPathway, team.getDisplayName());

            plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
            log.info("Fixed pathway for player {}: now has {}", player.getName(), teamPathway);
        }

        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);

        if (beyonderData == null) {
            log.info("Player {} has magic data but no Beyonder instance, reinitializing", player.getName());
            plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
        } else if (beyonderData.pathways().isEmpty() || !beyonderData.pathways().getFirst().name().equals(teamPathway)) {
            log.info("Player {} has incorrect Beyonder pathway, reinitializing", player.getName());
            circleOfImaginationAPI.destroyBeyonder(player);
            plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
        }
    }
}