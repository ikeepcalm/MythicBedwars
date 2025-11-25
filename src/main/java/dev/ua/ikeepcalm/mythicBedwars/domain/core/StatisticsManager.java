package dev.ua.ikeepcalm.mythicBedwars.domain.core;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.stats.db.PathwayStats;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class StatisticsManager {

    private final Map<String, PathwayStats> pathwayStatistics = new ConcurrentHashMap<>();
    private final MythicBedwars plugin;
    private final AtomicLong totalUniqueGames = new AtomicLong(0);

    public StatisticsManager(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    public void recordGameEnd(Arena arena, Team winningTeam) {
        Set<Team> allParticipatingTeams = plugin.getArenaPathwayManager().getAllParticipatingTeams(arena);

        if (allParticipatingTeams.isEmpty()) {
            return;
        }

        totalUniqueGames.incrementAndGet();

        for (Team team : allParticipatingTeams) {
            String pathway = plugin.getArenaPathwayManager().getTeamPathway(arena, team);
            if (pathway != null) {
                PathwayStats stats = pathwayStatistics.computeIfAbsent(pathway, k -> new PathwayStats());

                if (team.equals(winningTeam)) {
                    stats.wins++;
                } else {
                    stats.losses++;
                }
                stats.totalGames++;
            }
        }
    }

    public void recordSequenceReached(String pathway, int sequence) {
        PathwayStats stats = pathwayStatistics.computeIfAbsent(pathway, k -> new PathwayStats());
        stats.sequencesReached.add(sequence);
    }

    public void recordGameDuration(Arena arena, long durationMillis) {
        Set<Team> allParticipatingTeams = plugin.getArenaPathwayManager().getAllParticipatingTeams(arena);

        if (!allParticipatingTeams.isEmpty()) {
            for (Team team : allParticipatingTeams) {
                String pathway = plugin.getArenaPathwayManager().getTeamPathway(arena, team);
                if (pathway != null) {
                    PathwayStats stats = pathwayStatistics.computeIfAbsent(pathway, k -> new PathwayStats());
                    stats.gameDurations.add(durationMillis);
                    break;
                }
            }
        }
    }

    public void recordDamageDealt(String pathway, double damage) {
        PathwayStats stats = pathwayStatistics.computeIfAbsent(pathway, k -> new PathwayStats());
        stats.totalDamageDealt += damage;
    }

    public void recordAbilityUse(String pathway, String abilityName) {
        PathwayStats stats = pathwayStatistics.computeIfAbsent(pathway, k -> new PathwayStats());
        stats.abilityUsage.merge(abilityName, 1, Integer::sum);
    }

    // Public methods for Plan integration (accessed via reflection)
    public long totalGames() {
        return totalUniqueGames.get();
    }

    public String bestPathway() {
        if (pathwayStatistics.isEmpty()) {
            return "No pathways played";
        } else {
            return pathwayStatistics.entrySet().stream()
                    .filter(e -> e.getValue().totalGames > 0)
                    .max(Comparator.comparingDouble(e -> (double) e.getValue().wins / e.getValue().totalGames))
                    .map(Map.Entry::getKey)
                    .orElse("None");
        }
    }

    public long highestWinRate() {
        double maxWinRate = pathwayStatistics.values().stream()
                .filter(pathwayStats -> pathwayStats.totalGames > 0)
                .mapToDouble(pathwayStats -> (double) pathwayStats.wins / pathwayStats.totalGames * 100)
                .max()
                .orElse(0.0);
        return Math.round(maxWinRate);
    }

    public long averageGameDuration() {
        double avgDuration = pathwayStatistics.values().stream()
                                     .flatMap(stats -> stats.gameDurations.stream())
                                     .mapToLong(Long::longValue)
                                     .average()
                                     .orElse(0.0) / 1000 / 60;
        return Math.round(avgDuration);
    }

    public String mostPowerfulPathway() {
        return pathwayStatistics.entrySet().stream()
                .filter(e -> e.getValue().totalGames > 0)
                .max(Comparator.comparingDouble(e ->
                        e.getValue().totalDamageDealt / e.getValue().totalGames))
                .map(Map.Entry::getKey)
                .orElse("None");
    }

    public Map<String, PathwayStats> getPathwayStatistics() {
        return this.pathwayStatistics;
    }

    public void setPathwayStatistics(Map<String, PathwayStats> pathwayStatistics) {
        this.pathwayStatistics.clear();
        if (pathwayStatistics != null) {
            this.pathwayStatistics.putAll(pathwayStatistics);
        }

        long maxGames = pathwayStatistics != null ?
                pathwayStatistics.values().stream()
                        .mapToLong(stats -> stats.gameDurations.size())
                        .max()
                        .orElse(0) : 0;
        totalUniqueGames.set(maxGames);
    }
}