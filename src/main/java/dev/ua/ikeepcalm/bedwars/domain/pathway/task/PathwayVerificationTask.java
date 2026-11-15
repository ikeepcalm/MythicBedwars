package dev.ua.ikeepcalm.bedwars.domain.pathway.task;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.pathway.model.PlayerMagicData;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationApi;
import dev.ua.ikeepcalm.coi.api.model.BeyonderView;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

@RequiredArgsConstructor
public class PathwayVerificationTask extends BukkitRunnable {

    private final MythicBedwars plugin;

    @Override
    public void run() {
        for (Arena arena : BedwarsAPI.getGameAPI().getArenas()) {
            if (arena.getStatus() != ArenaStatus.RUNNING || !plugin.getVotingManager().isMagicEnabled(arena.getName())) {
                continue;
            }

            for (Player player : arena.getPlayers()) {
                verifyPlayerPathway(player, arena);
            }
        }
    }

    private void verifyPlayerPathway(Player player, Arena arena) {
        Team team = arena.getPlayerTeam(player);
        if (team == null) {
            return;
        }

        String expected = plugin.getArenaPathwayManager().getExpectedPathway(arena, team, player);
        PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
        if (expected == null || data == null) {
            return;
        }

        String problem = findMismatch(player, expected, data);
        if (problem != null) {
            plugin.log("Reinitialising {} in arena {}: {}", player.getName(), arena.getName(), problem);
            plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
        }
    }

    private String findMismatch(Player player, String expected, PlayerMagicData data) {
        if (!expected.equals(data.getPathway())) {
            return "holds " + data.getPathway() + " but should hold " + expected;
        }

        CircleOfImaginationApi api = plugin.getCircleOfImaginationApi();
        if (!api.beyonders().isVirtual(player.getUniqueId())) {
            return "no active sandbox loadout";
        }

        BeyonderView beyonderData = api.beyonders().find(player.getUniqueId()).orElse(null);
        if (beyonderData == null || beyonderData.getOwnedPathways().isEmpty()
            || !beyonderData.getOwnedPathways().getFirst().getKey().equals(expected)) {
            return "sandbox runs the wrong pathway";
        }
        return null;
    }
}
