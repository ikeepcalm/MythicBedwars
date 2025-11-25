package dev.ua.ikeepcalm.mythicBedwars.listener;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaBedBreakEvent;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.ArenaUnloadEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerKillPlayerEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerTeamChangeEvent;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ArenaListener implements Listener {

    private final MythicBedwars plugin;
    private final Map<String, Long> arenaStartTimes = new HashMap<>();
    private final CircleOfImaginationAPI circleOfImaginationAPI;

    public ArenaListener(MythicBedwars plugin) {
        this.plugin = plugin;
        this.circleOfImaginationAPI = plugin.getCircleOfImaginationAPI();
    }

    @EventHandler
    public void onArenaStatusChange(ArenaStatusChangeEvent event) {
        Arena arena = event.getArena();

        MythicBedwars.getInstance().log("Arena status change: " + event.getOldStatus() + " -> " + event.getNewStatus());

        if (event.getNewStatus() == ArenaStatus.LOBBY && event.getOldStatus() != ArenaStatus.LOBBY) {
            if (plugin.getConfigManager().isGloballyEnabled() && plugin.getConfigManager().isArenaEnabled(arena.getName())) {
                MythicBedwars.getInstance().log("Starting voting for arena: " + arena.getName());
                plugin.getVotingManager().startVoting(arena);
            }
        }

        if (event.getNewStatus() == ArenaStatus.RUNNING) {
            arenaStartTimes.put(arena.getName(), System.currentTimeMillis());
            plugin.getVotingManager().endVoting(arena);

            if (plugin.getVotingManager().isMagicEnabled(arena.getName())) {
                MythicBedwars.getInstance().log("Magic is enabled for arena: " + arena.getName() + ", assigning pathways");
                plugin.getArenaPathwayManager().assignPathwaysToTeams(arena);

                for (Player player : arena.getPlayers()) {
                    Team team = arena.getPlayerTeam(player);
                    if (team != null) {
                        plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
                    } else {
                        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            Team delayedTeam = arena.getPlayerTeam(player);
                            if (delayedTeam != null && plugin.getVotingManager().isMagicEnabled(arena.getName())) {
                                MythicBedwars.getInstance().log("Initializing delayed magic for player " + player.getName() + " on team " + delayedTeam.getDisplayName());
                                plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, delayedTeam);
                            }
                        }, 20L);
                    }
                }
            } else {
                MythicBedwars.getInstance().log("Magic is disabled for arena: " + arena.getName() + ", skipping pathway assignment");
            }
        }

        if (event.getNewStatus() == ArenaStatus.STOPPED) {
            Long startTime = arenaStartTimes.remove(arena.getName());
            if (startTime != null && plugin.getStatisticsManager() != null) {
                long duration = System.currentTimeMillis() - startTime;
                plugin.getStatisticsManager().recordGameDuration(arena, duration);
            }
            plugin.getArenaPathwayManager().cleanupArena(arena);
            plugin.getVotingManager().cleanupArena(arena.getName());
        }
    }

    @EventHandler
    public void onArenaEnd(ArenaUnloadEvent event) {
        Arena arena = event.getArena();
        plugin.getArenaPathwayManager().cleanupArena(arena);
        plugin.getVotingManager().cleanupArena(arena.getName());
        arenaStartTimes.remove(arena.getName());
    }

    @EventHandler
    public void onGameEnd(RoundEndEvent event) {
        Arena arena = event.getArena();
        Team winner = event.getWinnerTeam();

        if (plugin.getStatisticsManager() != null && plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            plugin.getStatisticsManager().recordGameEnd(arena, winner);
        }
    }

    @EventHandler
    public void onPlayerJoinArena(PlayerJoinArenaEvent event) {
        Player player = event.getPlayer();
        Arena arena = event.getArena();

        MythicBedwars.getInstance().log("Player " + player.getName() + " joined arena " + arena.getName() + " with status: " + arena.getStatus());

        if (arena.getStatus() == ArenaStatus.LOBBY) {
            if (plugin.getVotingManager().hasActiveVoting(arena.getName())) {
                MythicBedwars.getInstance().log("Giving voting items to late-joining player: " + player.getName());
                plugin.getVotingManager().giveVotingItems(player, arena);
            } else if (plugin.getConfigManager().isGloballyEnabled() && plugin.getConfigManager().isArenaEnabled(arena.getName())) {
                if (!plugin.getVotingManager().hasActiveVoting(arena.getName())) {
                    MythicBedwars.getInstance().log("First player joined, starting voting for arena: " + arena.getName());
                    plugin.getVotingManager().startVoting(arena);
                    plugin.getVotingManager().giveVotingItems(player, arena);
                }
            }
        }

        if (arena.getStatus() == ArenaStatus.RUNNING && plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            Team team = arena.getPlayerTeam(player);
            if (team != null) {
                if (plugin.getArenaPathwayManager().isPlayerInArena(player, arena.getName())) {
                    var data = plugin.getArenaPathwayManager().getPlayerData(player);
                    if (data != null) {
                        data.setActive(true);
                    }
                }
                plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitArenaEvent event) {
        Player player = event.getPlayer();
        Arena arena = event.getArena();

        if (arena.getStatus() == ArenaStatus.LOBBY) {
            plugin.getVotingManager().removeVotingItems(player);
        }

        boolean isGameEnding = event.getReason() == KickReason.GAME_LOSE
                               || event.getReason() == KickReason.GAME_END
                               || event.getReason() == KickReason.ARENA_STOP;

        boolean isVoluntaryLeave = event.getReason() == KickReason.LEAVE
                                   || event.getReason() == KickReason.PLUGIN_STOP;

        if (isGameEnding || isVoluntaryLeave || arena.getStatus() != ArenaStatus.RUNNING) {
            plugin.getArenaPathwayManager().cleanupPlayer(player);
        } else {
            plugin.getArenaPathwayManager().markPlayerInactive(player);
        }
    }

    @EventHandler
    public void onPlayerJoinTeam(PlayerTeamChangeEvent event) {
        Player player = event.getPlayer();
        Arena arena = event.getArena();
        Team team = event.getNewTeam();

        if (arena.getStatus() == ArenaStatus.RUNNING && team != null && plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
        }
    }

    @EventHandler
    public void onPlayerKill(PlayerKillPlayerEvent event) {
        Player killer = event.getKiller();
        if (killer == null) return;

        Arena arena = event.getArena();
        if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) return;

        if (!circleOfImaginationAPI.isBeyonder(killer)) return;

        double multiplier = event.isFatalDeath() ?
                plugin.getConfigManager().getFinalKillActingMultiplier() :
                plugin.getConfigManager().getKillActingMultiplier();

        int actingAmount = (int) (100 * multiplier);

        List<PathwayData> pathwayData = circleOfImaginationAPI.getPathwayData(killer);

        for (PathwayData pathway : pathwayData) {
            circleOfImaginationAPI.addActing(killer, pathway.name(), actingAmount);
        }
    }

    @EventHandler
    public void onBedBreak(ArenaBedBreakEvent event) {
        Player breaker = event.getPlayer();
        if (breaker == null) return;

        Arena arena = event.getArena();
        if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) return;

        if (!circleOfImaginationAPI.isBeyonder(breaker)) return;

        double multiplier = plugin.getConfigManager().getBedBreakActingMultiplier();
        int actingAmount = (int) (100 * multiplier);

        List<PathwayData> pathwayData = circleOfImaginationAPI.getPathwayData(breaker);

        for (PathwayData pathway : pathwayData) {
            circleOfImaginationAPI.addActing(breaker, pathway.name(), actingAmount);
        }

    }
}