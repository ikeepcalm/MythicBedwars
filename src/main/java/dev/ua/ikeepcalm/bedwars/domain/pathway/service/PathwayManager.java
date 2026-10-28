package dev.ua.ikeepcalm.bedwars.domain.pathway.service;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.pathway.model.PathwayOutcome;
import dev.ua.ikeepcalm.bedwars.domain.pathway.model.PlayerMagicData;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import lombok.Data;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PathwayManager {

    private static final long ANNOUNCE_DELAY_TICKS = 40L;
    private static final long ANNOUNCE_COOLDOWN_MILLIS = 5_000L;

    private final Map<UUID, Long> lastAnnouncement = new ConcurrentHashMap<>();
    private final Map<String, Map<Team, String>> arenaPathways = new ConcurrentHashMap<>();
    private final Map<String, Set<Team>> arenaPlayedTeams = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerMagicData> playerData = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> arenaPlayers = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, IndividualDraw>> arenaIndividualPathways = new ConcurrentHashMap<>();
    private final CircleOfImaginationAPI circleOfImaginationAPI = MythicBedwars.getInstance().getCircleOfImaginationAPI();

    @Data
    private static class IndividualDraw {
        private final String pathway;
        private final Team team;
    }

    private boolean isPerPlayer(Arena arena) {
        return MythicBedwars.getInstance().getVotingManager().isPerPlayerPathways(arena.getName());
    }

    public void assignPathwaysToTeams(Arena arena) {
        if (isPerPlayer(arena)) {
            arenaIndividualPathways.computeIfAbsent(arena.getName(), k -> new ConcurrentHashMap<>());
            return;
        }

        PathwayBalancer balancer = MythicBedwars.getInstance().getPathwayBalancer();
        arenaPathways.put(arena.getName(), new ConcurrentHashMap<>(balancer.assignBalancedPathways(arena)));
    }

    public String getBalancingInfo(Arena arena) {
        String assignments = isPerPlayer(arena) ? describeIndividualDraws(arena) : describeTeamPathways(arena);
        if (assignments == null) {
            return isPerPlayer(arena) ? "No pathways assigned yet (individual mode)" : "No pathways assigned yet";
        }

        boolean balanced = MythicBedwars.getInstance().getConfigManager().isPathwayBalancingEnabled();
        return assignments + "Balancing: " + (balanced ? "Enabled" : "Disabled");
    }

    private String describeIndividualDraws(Arena arena) {
        Map<UUID, IndividualDraw> draws = arenaIndividualPathways.get(arena.getName());
        if (draws == null || draws.isEmpty()) {
            return null;
        }

        StringBuilder info = new StringBuilder("Individual pathway draws for ").append(arena.getName()).append(":\n");
        draws.forEach((playerId, draw) -> {
            Player holder = Bukkit.getPlayer(playerId);
            String name = holder != null ? holder.getName() : playerId.toString();
            info.append("- ").append(name).append(" (").append(draw.getTeam().getDisplayName()).append("): ")
                    .append(draw.getPathway()).append("\n");
        });
        return info.toString();
    }

    private String describeTeamPathways(Arena arena) {
        Map<Team, String> teamPathways = arenaPathways.get(arena.getName());
        if (teamPathways == null || teamPathways.isEmpty()) {
            return null;
        }

        StringBuilder info = new StringBuilder("Pathway assignments for ").append(arena.getName()).append(":\n");
        teamPathways.forEach((team, pathway) ->
                info.append("- ").append(team.getDisplayName()).append(": ").append(pathway).append("\n"));
        return info.toString();
    }

    public String getTeamPathway(Arena arena, Team team) {
        Map<Team, String> teamPathways = arenaPathways.get(arena.getName());
        return teamPathways == null ? null : teamPathways.get(team);
    }

    public String getPlayerPathway(Player player) {
        PlayerMagicData data = playerData.get(player.getUniqueId());
        if (data != null) {
            return data.getPathway();
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            return null;
        }

        Team team = arena.getPlayerTeam(player);
        return team == null ? null : getTeamPathway(arena, team);
    }

    public List<PathwayOutcome> getRoundOutcomes(Arena arena, Team winner) {
        List<PathwayOutcome> outcomes = new ArrayList<>();

        if (isPerPlayer(arena)) {
            Map<UUID, IndividualDraw> draws = arenaIndividualPathways.get(arena.getName());
            if (draws != null) {
                for (IndividualDraw draw : draws.values()) {
                    outcomes.add(new PathwayOutcome(draw.getPathway(), winner != null && winner == draw.getTeam()));
                }
            }
            return outcomes;
        }

        for (Team team : getAllParticipatingTeams(arena)) {
            String pathway = getTeamPathway(arena, team);
            if (pathway != null) {
                outcomes.add(new PathwayOutcome(pathway, winner != null && winner == team));
            }
        }
        return outcomes;
    }

    // Must read the round's draw, never the player's own state: the verification task compares the two.
    public String getExpectedPathway(Arena arena, Team team, Player player) {
        if (!isPerPlayer(arena)) {
            return getTeamPathway(arena, team);
        }

        Map<UUID, IndividualDraw> draws = arenaIndividualPathways.get(arena.getName());
        IndividualDraw draw = draws == null ? null : draws.get(player.getUniqueId());
        return draw == null ? null : draw.getPathway();
    }

    public String getTeamPathwayDisplay(Arena arena, Team team) {
        if (!isPerPlayer(arena)) {
            return getTeamPathway(arena, team);
        }

        Map<UUID, IndividualDraw> draws = arenaIndividualPathways.get(arena.getName());
        if (draws == null || draws.isEmpty()) {
            return null;
        }

        Set<String> pathways = new LinkedHashSet<>();
        for (Player member : arena.getPlayers()) {
            if (team != arena.getPlayerTeam(member)) {
                continue;
            }

            IndividualDraw draw = draws.get(member.getUniqueId());
            if (draw != null) {
                pathways.add(displayName(draw.getPathway()));
            }
        }
        return pathways.isEmpty() ? null : String.join(", ", pathways);
    }

    public Set<Team> getAllParticipatingTeams(Arena arena) {
        Set<Team> playedTeams = arenaPlayedTeams.get(arena.getName());
        return playedTeams == null ? Collections.emptySet() : Set.copyOf(playedTeams);
    }

    private String resolvePathwayFor(Arena arena, Team team, Player player) {
        return isPerPlayer(arena) ? resolveIndividualPathway(arena, team, player) : resolveTeamPathway(arena, team);
    }

    private String resolveIndividualPathway(Arena arena, Team team, Player player) {
        Map<UUID, IndividualDraw> draws =
                arenaIndividualPathways.computeIfAbsent(arena.getName(), k -> new ConcurrentHashMap<>());

        IndividualDraw existing = draws.get(player.getUniqueId());
        if (existing != null) {
            if (existing.getTeam() != team) {
                draws.put(player.getUniqueId(), new IndividualDraw(existing.getPathway(), team));
            }
            return existing.getPathway();
        }

        List<String> taken = draws.values().stream().map(IndividualDraw::getPathway).toList();
        String picked = MythicBedwars.getInstance().getPathwayBalancer().pickPathway(taken);
        if (picked == null) {
            return null;
        }

        IndividualDraw raced = draws.putIfAbsent(player.getUniqueId(), new IndividualDraw(picked, team));
        return raced != null ? raced.getPathway() : picked;
    }

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
        return existing != null ? existing : picked;
    }

    private void markTeamPlayed(Arena arena, Team team) {
        arenaPlayedTeams.computeIfAbsent(arena.getName(), k -> ConcurrentHashMap.newKeySet()).add(team);
    }

    private void trackPlayer(Arena arena, UUID playerId) {
        arenaPlayers.computeIfAbsent(arena.getName(), k -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    private void announcePathway(Player player, String pathway) {
        long now = System.currentTimeMillis();
        Long last = lastAnnouncement.put(player.getUniqueId(), now);
        if (last != null && now - last < ANNOUNCE_COOLDOWN_MILLIS) {
            return;
        }

        MythicBedwars plugin = MythicBedwars.getInstance();
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

    private String displayName(String pathway) {
        if (pathway.isEmpty()) {
            return pathway;
        }
        return Character.toUpperCase(pathway.charAt(0)) + pathway.substring(1);
    }

    public void initializePlayerMagic(Player player, Arena arena, Team team) {
        if (!isPerPlayer(arena)) {
            Map<Team, String> teamPathways = arenaPathways.get(arena.getName());
            if (teamPathways == null || teamPathways.isEmpty()) {
                assignPathwaysToTeams(arena);
            }
        }

        String pathway = resolvePathwayFor(arena, team, player);
        if (pathway == null) {
            MythicBedwars.getInstance().log("No pathway could be assigned to {} (team {}) in arena {}",
                    player.getName(), team.getDisplayName(), arena.getName());
            return;
        }

        UUID playerId = player.getUniqueId();
        PlayerMagicData existing = playerData.get(playerId);
        if (existing != null && existing.getArenaName().equals(arena.getName())) {
            reopenLoadout(player, arena, team, pathway, existing);
            return;
        }

        if (!enterSandbox(player, pathway, 9)) {
            return;
        }

        markTeamPlayed(arena, team);
        announcePathway(player, pathway);
        playerData.put(playerId, new PlayerMagicData(playerId, pathway, arena.getName()));
        trackPlayer(arena, playerId);
    }

    private void reopenLoadout(Player player, Arena arena, Team team, String pathway, PlayerMagicData existing) {
        if (!enterSandbox(player, pathway, existing.getCurrentSequence())) {
            return;
        }

        markTeamPlayed(arena, team);
        announcePathway(player, pathway);

        existing.updatePlayTimeOnDisconnect();
        PlayerMagicData data = pathway.equals(existing.getPathway()) ? existing : swapPathway(player, pathway, existing);
        if (data.getStoredActing() > 0) {
            circleOfImaginationAPI.setPrimaryActing(player, data.getStoredActing());
        }

        data.resetGameStartTimeOnReconnect();
        data.setActive(true);
        trackPlayer(arena, player.getUniqueId());
    }

    private PlayerMagicData swapPathway(Player player, String pathway, PlayerMagicData existing) {
        PlayerMagicData replacement = new PlayerMagicData(player.getUniqueId(), pathway, existing.getArenaName());
        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);
        replacement.setCurrentSequence(beyonderData != null ? beyonderData.lowestSequence() : existing.getCurrentSequence());
        replacement.setStoredActing(existing.getStoredActing());
        replacement.setTotalPlayTime(existing.getTotalPlayTime());
        playerData.put(player.getUniqueId(), replacement);
        return replacement;
    }

    private boolean enterSandbox(Player player, String pathway, int sequence) {
        if (circleOfImaginationAPI.enterVirtualBeyonder(player, pathway, sequence)) {
            return true;
        }

        MythicBedwars.getInstance().log("Failed to open sandbox loadout for {} (pathway {}, sequence {})",
                player.getName(), pathway, sequence);
        return false;
    }

    public void markPlayerInactive(Player player) {
        PlayerMagicData data = playerData.get(player.getUniqueId());
        if (data == null) {
            return;
        }

        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);
        if (beyonderData != null && !beyonderData.pathways().isEmpty()) {
            data.setCurrentSequence(beyonderData.lowestSequence());
            data.setStoredActing(beyonderData.pathways().getFirst().acting());
        }

        circleOfImaginationAPI.exitVirtualBeyonder(player);
        data.updatePlayTimeOnDisconnect();
        data.setActive(false);
    }

    public void cleanupPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerMagicData data = playerData.remove(playerId);
        if (data != null) {
            Set<UUID> players = arenaPlayers.get(data.getArenaName());
            if (players != null) {
                players.remove(playerId);
            }
            circleOfImaginationAPI.exitVirtualBeyonder(player);
        }
        lastAnnouncement.remove(playerId);
    }

    public void cleanupArena(Arena arena) {
        String arenaName = arena.getName();
        arenaPathways.remove(arenaName);
        arenaIndividualPathways.remove(arenaName);
        arenaPlayedTeams.remove(arenaName);

        Set<UUID> players = arenaPlayers.remove(arenaName);
        if (players == null) {
            return;
        }

        for (UUID playerId : players) {
            PlayerMagicData data = playerData.get(playerId);
            if (data == null || !data.getArenaName().equals(arenaName)) {
                continue;
            }

            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                circleOfImaginationAPI.exitVirtualBeyonder(player);
            }
            playerData.remove(playerId, data);
            lastAnnouncement.remove(playerId);
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
        arenaIndividualPathways.clear();
        arenaPlayedTeams.clear();
        arenaPlayers.clear();
        lastAnnouncement.clear();
    }

    public PlayerMagicData getPlayerData(Player player) {
        return playerData.get(player.getUniqueId());
    }

    public boolean hasPlayerMagic(Player player) {
        return playerData.containsKey(player.getUniqueId());
    }
}
