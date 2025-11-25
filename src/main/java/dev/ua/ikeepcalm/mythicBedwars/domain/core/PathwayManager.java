package dev.ua.ikeepcalm.mythicBedwars.domain.core;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.balancer.PathwayBalancer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PathwayManager {

    private final Map<String, Map<Team, String>> arenaPathways = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerMagicData> playerData = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> arenaPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerArenaCache = new ConcurrentHashMap<>();

    private final CircleOfImaginationAPI circleOfImaginationAPI = MythicBedwars.getInstance().getCircleOfImaginationAPI();

    public void assignPathwaysToTeams(Arena arena) {
        PathwayBalancer balancer = MythicBedwars.getInstance().getPathwayBalancer();
        Map<Team, String> teamPathways = balancer.assignBalancedPathways(arena);
        arenaPathways.put(arena.getName(), teamPathways);
    }

    public String getBalancingInfo(Arena arena) {
        Map<Team, String> teamPathways = arenaPathways.get(arena.getName());
        if (teamPathways == null || teamPathways.isEmpty()) {
            return "No pathways assigned yet";
        }

        StringBuilder info = new StringBuilder("Pathway assignments for " + arena.getName() + ":\n");
        for (Map.Entry<Team, String> entry : teamPathways.entrySet()) {
            info.append("- ").append(entry.getKey().getDisplayName()).append(": ").append(entry.getValue()).append("\n");
        }

        boolean isBalanced = MythicBedwars.getInstance().getConfigManager().isPathwayBalancingEnabled();
        info.append("Balancing: ").append(isBalanced ? "Enabled" : "Disabled");

        return info.toString();
    }

    public String getTeamPathway(Arena arena, Team team) {
        Map<Team, String> teamPathways = arenaPathways.get(arena.getName());
        if (teamPathways != null) {
            return teamPathways.get(team);
        }
        return null;
    }

    public Set<Team> getAllParticipatingTeams(Arena arena) {
        Map<Team, String> teamPathways = arenaPathways.get(arena.getName());
        if (teamPathways != null) {
            return teamPathways.keySet();
        }
        return Collections.emptySet();
    }

    public void initializePlayerMagic(Player player, Arena arena, Team team) {
        String pathway = getTeamPathway(arena, team);
        if (pathway == null) {
            MythicBedwars.getInstance().log("No pathway assigned to team " + team.getDisplayName() +
                                                            " in arena " + arena.getName() + " for player " + player.getName());
            return;
        }

        UUID playerId = player.getUniqueId();

        if (circleOfImaginationAPI.isBeyonder(player)) {
            circleOfImaginationAPI.destroyBeyonder(player);
        }

        PlayerMagicData existingData = playerData.get(playerId);
        if (existingData != null && existingData.getArenaName().equals(arena.getName())) {
            if (!pathway.equals(existingData.getPathway())) {
                MythicBedwars.getInstance().log("Player " + player.getName() +
                                                             " changed teams, updating pathway from " + existingData.getPathway() +
                                                             " to " + pathway);

                circleOfImaginationAPI.createBeyonder(player, pathway, existingData.getCurrentSequence());

                if (existingData.getStoredActing() > 0) {
                    circleOfImaginationAPI.setPrimaryActing(player, existingData.getStoredActing());
                }

                BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);
                existingData = new PlayerMagicData(playerId, pathway, arena.getName());
                existingData.setCurrentSequence(beyonderData.lowestSequence());
                playerData.put(playerId, existingData);
            } else {

                circleOfImaginationAPI.createBeyonder(player, pathway, existingData.getCurrentSequence());

                if (existingData.getStoredActing() > 0) {
                    circleOfImaginationAPI.setPrimaryActing(player, existingData.getStoredActing());
                }
            }

            existingData.resetGameStartTimeOnReconnect();
            existingData.setActive(true);
            return;
        }

        PlayerMagicData data = new PlayerMagicData(playerId, pathway, arena.getName());
        playerData.put(playerId, data);
        playerArenaCache.put(playerId, arena.getName());

        arenaPlayers.computeIfAbsent(arena.getName(), k -> ConcurrentHashMap.newKeySet()).add(playerId);

        circleOfImaginationAPI.createBeyonder(player, pathway, 9);
    }

    public void markPlayerInactive(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerMagicData data = playerData.get(playerId);
        if (data != null) {
            BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);
            if (beyonderData != null && !beyonderData.pathways().isEmpty()) {
                data.setCurrentSequence(beyonderData.lowestSequence());
                data.setStoredActing(beyonderData.pathways().getFirst().acting());
                circleOfImaginationAPI.destroyBeyonder(player);
            }

            data.updatePlayTimeOnDisconnect();
            data.setActive(false);
        }
    }

    public void cleanupPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerMagicData data = playerData.remove(playerId);
        if (data != null) {
            String arenaName = data.getArenaName();
            Set<UUID> players = arenaPlayers.get(arenaName);
            if (players != null) {
                players.remove(playerId);
            }

            if (circleOfImaginationAPI.isBeyonder(player)) {
                circleOfImaginationAPI.destroyBeyonder(player);
            }
        }
        playerArenaCache.remove(playerId);
    }

    public void cleanupArena(Arena arena) {
        String arenaName = arena.getName();
        arenaPathways.remove(arenaName);

        Set<UUID> players = arenaPlayers.remove(arenaName);
        if (players != null) {
            for (UUID playerId : players) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    if (circleOfImaginationAPI.isBeyonder(player)) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                circleOfImaginationAPI.destroyBeyonder(player);
                            }
                        }.runTaskLater(MythicBedwars.getInstance(), 60L);
                    }
                }
                playerData.remove(playerId);
                playerArenaCache.remove(playerId);
            }
        }
    }

    public void cleanupAll() {
        for (PlayerMagicData data : playerData.values()) {
            Player player = Bukkit.getPlayer(data.getPlayerId());
            if (player != null) {
                if (circleOfImaginationAPI.isBeyonder(player)) {
                    circleOfImaginationAPI.destroyBeyonder(player);
                }
            }
        }
        playerData.clear();
        arenaPathways.clear();
        arenaPlayers.clear();
        playerArenaCache.clear();
    }

    public PlayerMagicData getPlayerData(Player player) {
        return playerData.get(player.getUniqueId());
    }

    public boolean hasPlayerMagic(Player player) {
        return playerData.containsKey(player.getUniqueId());
    }

    public boolean isPlayerInArena(Player player, String arenaName) {
        Set<UUID> players = arenaPlayers.get(arenaName);
        return players != null && players.contains(player.getUniqueId());
    }

    public static class PlayerMagicData {
        private final UUID playerId;
        private final String pathway;
        private final String arenaName;
        private final Map<Integer, Integer> potionsPurchased;
        private int currentSequence;
        private boolean active;
        private int storedActing;
        private long gameStartTime; // Track when the player started playing in this arena
        private long totalPlayTime; // Track total time spent in arena (excluding disconnections)

        public PlayerMagicData(UUID playerId, String pathway, String arenaName) {
            this.playerId = playerId;
            this.pathway = pathway;
            this.arenaName = arenaName;
            this.currentSequence = 9;
            this.potionsPurchased = new HashMap<>();
            this.active = true;
            this.storedActing = 0;
            this.gameStartTime = System.currentTimeMillis();
            this.totalPlayTime = 0;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public String getPathway() {
            return pathway;
        }

        public String getArenaName() {
            return arenaName;
        }

        public int getCurrentSequence() {
            return currentSequence;
        }

        public void setCurrentSequence(int sequence) {
            this.currentSequence = sequence;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public int getStoredActing() {
            return storedActing;
        }

        public void setStoredActing(int acting) {
            this.storedActing = acting;
        }

        public void incrementPotionPurchase(int sequence) {
            potionsPurchased.merge(sequence, 1, Integer::sum);
        }

        public int getPotionPurchaseCount(int sequence) {
            return potionsPurchased.getOrDefault(sequence, 0);
        }

        public long getGameStartTime() {
            return gameStartTime;
        }

        public void setGameStartTime(long gameStartTime) {
            this.gameStartTime = gameStartTime;
        }

        public long getTotalPlayTime() {
            return totalPlayTime;
        }

        public void setTotalPlayTime(long totalPlayTime) {
            this.totalPlayTime = totalPlayTime;
        }

        /**
         * Updates total play time when player becomes inactive (disconnects)
         */
        public void updatePlayTimeOnDisconnect() {
            if (active && gameStartTime > 0) {
                totalPlayTime += System.currentTimeMillis() - gameStartTime;
            }
        }

        /**
         * Resets the game start time when player becomes active again (reconnects)
         */
        public void resetGameStartTimeOnReconnect() {
            this.gameStartTime = System.currentTimeMillis();
        }

        /**
         * Gets the effective play time including current session
         */
        public long getEffectivePlayTime() {
            if (active && gameStartTime > 0) {
                return totalPlayTime + (System.currentTimeMillis() - gameStartTime);
            }
            return totalPlayTime;
        }
    }
}
