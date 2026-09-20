package dev.ua.ikeepcalm.bedwars.domain.core;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.stats.db.PathwayStats;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class StatisticsManager {

    private final Map<String, PathwayStats> pathwayStatistics = new ConcurrentHashMap<>();
    private final MythicBedwars plugin;
    private final AtomicLong totalUniqueGames = new AtomicLong(0);

    public StatisticsManager(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * Credits the round to every pathway that actually played it.
     *
     * <p>Asks the pathway manager for outcomes rather than walking teams itself: in individual mode
     * a team holds several pathways and a pathway may be held on several teams, so the team is no
     * longer the unit the statistics are keyed on.
     */
    public void recordGameEnd(Arena arena, Team winningTeam) {
        List<PathwayManager.PathwayOutcome> outcomes =
                plugin.getArenaPathwayManager().getRoundOutcomes(arena, winningTeam);

        if (outcomes.isEmpty()) {
            return;
        }

        totalUniqueGames.incrementAndGet();

        for (PathwayManager.PathwayOutcome outcome : outcomes) {
            PathwayStats stats = pathwayStatistics.computeIfAbsent(outcome.pathway(), k -> new PathwayStats());

            if (outcome.won()) {
                stats.wins++;
            } else {
                stats.losses++;
            }
            stats.totalGames++;
        }
    }

    public void recordSequenceReached(String pathway, int sequence) {
        PathwayStats stats = pathwayStatistics.computeIfAbsent(pathway, k -> new PathwayStats());
        stats.sequencesReached.add(sequence);
    }

    /**
     * Records the round's length once, against the first pathway that played it.
     *
     * <p>The duration is a property of the round, not of a pathway, and the original deliberately
     * broke after one entry rather than counting the same match once per team. That stays true in
     * individual mode - which is why this reads the outcome list rather than the team set.
     */
    public void recordGameDuration(Arena arena, long durationMillis) {
        List<PathwayManager.PathwayOutcome> outcomes =
                plugin.getArenaPathwayManager().getRoundOutcomes(arena, null);

        if (outcomes.isEmpty()) {
            return;
        }

        PathwayStats stats = pathwayStatistics.computeIfAbsent(
                outcomes.getFirst().pathway(), k -> new PathwayStats());
        stats.gameDurations.add(durationMillis);
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