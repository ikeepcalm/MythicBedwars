package dev.ua.ikeepcalm.bedwars.domain.core;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.balancer.PathwayBalancer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PathwayManager {

    private static final long ANNOUNCE_DELAY_TICKS = 40L;
    private static final long ANNOUNCE_COOLDOWN_MILLIS = 5_000L;

    private final Map<UUID, Long> lastAnnouncement = new ConcurrentHashMap<>();
    private final Map<String, Map<Team, String>> arenaPathways = new ConcurrentHashMap<>();
    private final Map<String, Set<Team>> arenaPlayedTeams = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerMagicData> playerData = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> arenaPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerArenaCache = new ConcurrentHashMap<>();

    private final CircleOfImaginationAPI circleOfImaginationAPI = MythicBedwars.getInstance().getCircleOfImaginationAPI();

    public void assignPathwaysToTeams(Arena arena) {
        PathwayBalancer balancer = MythicBedwars.getInstance().getPathwayBalancer();
        Map<Team, String> teamPathways = new ConcurrentHashMap<>(balancer.assignBalancedPathways(arena));
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

    /**
     * Returns the teams that actually fielded a player this round.
     *
     * <p>Pathways are handed to every team the arena has enabled, including ones that end up
     * empty, so this deliberately reports the narrower set: crediting a win or a loss to a
     * pathway nobody played would feed the balancer noise.
     */
    public Set<Team> getAllParticipatingTeams(Arena arena) {
        Set<Team> playedTeams = arenaPlayedTeams.get(arena.getName());
        if (playedTeams != null) {
            return Set.copyOf(playedTeams);
        }
        return Collections.emptySet();
    }

    /**
     * Resolves the team's pathway, assigning one on the spot if the round's distribution somehow
     * missed this team.
     */
    private String resolveTeamPathway(Arena arena, Team team) {
        Map<Team, String> teamPathways = arenaPathways.computeIfAbsent(arena.getName(), k -> new ConcurrentHashMap<>());

        String pathway = teamPathways.get(team);
        if (pathway != null) {
            return pathway;
        }

        String picked = MythicBedwars.getInstance().getPathwayBalancer().pickPathway(List.copyOf(teamPathways.values()));
        if (picked == null) {
            return null;
        }

        String existing = teamPathways.putIfAbsent(team, picked);
        if (existing != null) {
            return existing;
        }

        MythicBedwars.getInstance().log("Team " + team.getDisplayName() + " in arena " + arena.getName() +
                                                        " had no pathway, assigned " + picked);
        return picked;
    }

    /**
     * Records that the team fielded a player whose loadout actually opened, which is what makes
     * its pathway eligible for the round's statistics.
     */
    private void markTeamPlayed(Arena arena, Team team) {
        arenaPlayedTeams.computeIfAbsent(arena.getName(), k -> ConcurrentHashMap.newKeySet()).add(team);
    }

    /**
     * Tells the player which pathway they are holding and how to grow it.
     *
     * <p>Deliberately delayed: the loadout opens as the round starts, in the same window MBedwars
     * puts its own start title on screen, and a title pushed into that window just gets replaced.
     */
    private void announcePathway(Player player, String pathway) {
        MythicBedwars plugin = MythicBedwars.getInstance();

        // The round-start pass and PlayerTeamChangeEvent both reach initializePlayerMagic, so the
        // same player can land here twice within a second - one title, not two.
        long now = System.currentTimeMillis();
        Long last = lastAnnouncement.put(player.getUniqueId(), now);
        if (last != null && now - last < ANNOUNCE_COOLDOWN_MILLIS) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            player.showTitle(Title.title(
                    plugin.getLocaleManager().formatMessage(player, "magic.pathway_assigned.title",
                            "pathway", displayName(pathway)),
                    plugin.getLocaleManager().formatMessage(player, "magic.pathway_assigned.subtitle"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500))));

            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);
        }, ANNOUNCE_DELAY_TICKS);
    }

    /** Pathway names arrive from COI lowercase ("fortune"), which reads as a typo in a title. */
    private String displayName(String pathway) {
        if (pathway.isEmpty()) {
            return pathway;
        }

        return Character.toUpperCase(pathway.charAt(0)) + pathway.substring(1);
    }

    public void initializePlayerMagic(Player player, Arena arena, Team team) {
        // Check if pathways have been assigned for this arena, if not assign them now
        Map<Team, String> teamPathways = arenaPathways.get(arena.getName());
        if (teamPathways == null || teamPathways.isEmpty()) {
            MythicBedwars.getInstance().log("Pathways not assigned yet for arena " + arena.getName() + ", assigning now");
            assignPathwaysToTeams(arena);
        }

        String pathway = resolveTeamPathway(arena, team);
        if (pathway == null) {
            MythicBedwars.getInstance().log("No pathway could be assigned to team " + team.getDisplayName() +
                                                            " in arena " + arena.getName() + " for player " + player.getName());
            return;
        }

        UUID playerId = player.getUniqueId();

        PlayerMagicData existingData = playerData.get(playerId);
        if (existingData != null && existingData.getArenaName().equals(arena.getName())) {
            if (!enterSandbox(player, pathway, existingData.getCurrentSequence())) {
                return;
            }

            markTeamPlayed(arena, team);
            announcePathway(player, pathway);

            if (!pathway.equals(existingData.getPathway())) {
                MythicBedwars.getInstance().log("Player " + player.getName() +
                                                             " changed teams, updating pathway from " + existingData.getPathway() +
                                                             " to " + pathway);

                PlayerMagicData replacement = new PlayerMagicData(playerId, pathway, arena.getName());
                BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);
                if (beyonderData != null) {
                    replacement.setCurrentSequence(beyonderData.lowestSequence());
                } else {
                    replacement.setCurrentSequence(existingData.getCurrentSequence());
                }
                // Carry the accumulated match state across the swap, so changing team does not
                // silently reset the player's acting progress or their tracked play time.
                replacement.setStoredActing(existingData.getStoredActing());
                replacement.setTotalPlayTime(existingData.getTotalPlayTime());

                playerData.put(playerId, replacement);
                existingData = replacement;
            }

            if (existingData.getStoredActing() > 0) {
                circleOfImaginationAPI.setPrimaryActing(player, existingData.getStoredActing());
            }

            existingData.resetGameStartTimeOnReconnect();
            existingData.setActive(true);
            playerArenaCache.put(playerId, arena.getName());
            arenaPlayers.computeIfAbsent(arena.getName(), k -> ConcurrentHashMap.newKeySet()).add(playerId);
            return;
        }

        if (!enterSandbox(player, pathway, 9)) {
            return;
        }

        markTeamPlayed(arena, team);
        announcePathway(player, pathway);

        PlayerMagicData data = new PlayerMagicData(playerId, pathway, arena.getName());
        playerData.put(playerId, data);
        playerArenaCache.put(playerId, arena.getName());

        arenaPlayers.computeIfAbsent(arena.getName(), k -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    /**
     * Opens (or swaps) the player's sandbox loadout for the match.
     *
     * <p>The sandbox stashes their real Beyonder rather than destroying it, so nothing the match
     * does can reach their persisted progression, and COI discards it automatically when they
     * disconnect or when the plugin disables.
     *
     * @return {@code false} when the loadout could not be built, in which case the caller must not
     * record any magic state for this player
     */
    private boolean enterSandbox(Player player, String pathway, int sequence) {
        if (circleOfImaginationAPI.enterVirtualBeyonder(player, pathway, sequence)) {
            return true;
        }

        MythicBedwars.getInstance().log("Failed to open sandbox loadout for {} (pathway {}, sequence {})",
                player.getName(), pathway, sequence);
        return false;
    }

    public void markPlayerInactive(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerMagicData data = playerData.get(playerId);
        if (data != null) {
            BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);
            if (beyonderData != null && !beyonderData.pathways().isEmpty()) {
                data.setCurrentSequence(beyonderData.lowestSequence());
                data.setStoredActing(beyonderData.pathways().getFirst().acting());
            }

            // Unconditional: a sandbox opened for a player whose data we can no longer read must
            // still be closed, or their real Beyonder stays stashed for the rest of the session.
            circleOfImaginationAPI.exitVirtualBeyonder(player);

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

            circleOfImaginationAPI.exitVirtualBeyonder(player);
        }
        playerArenaCache.remove(playerId);
        lastAnnouncement.remove(playerId);
    }

    public void cleanupArena(Arena arena) {
        String arenaName = arena.getName();
        arenaPathways.remove(arenaName);
        arenaPlayedTeams.remove(arenaName);

        Set<UUID> players = arenaPlayers.remove(arenaName);
        if (players != null) {
            for (UUID playerId : players) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    circleOfImaginationAPI.exitVirtualBeyonder(player);
                }
                playerData.remove(playerId);
                playerArenaCache.remove(playerId);
                lastAnnouncement.remove(playerId);
            }
        }
    }

    public void cleanupAll() {
        for (PlayerMagicData data : playerData.values()) {
            Player player = Bukkit.getPlayer(data.getPlayerId());
            if (player != null) {
                circleOfImaginationAPI.exitVirtualBeyonder(player);
            }
        }
        playerData.clear();
        arenaPathways.clear();
        arenaPlayedTeams.clear();
        arenaPlayers.clear();
        playerArenaCache.clear();
        lastAnnouncement.clear();
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
