package dev.ua.ikeepcalm.bedwars.domain.stats.service;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.pathway.model.PathwayOutcome;
import dev.ua.ikeepcalm.bedwars.domain.stats.model.PathwayStats;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
public class StatisticsManager {

    private final MythicBedwars plugin;
    @Getter
    private final Map<String, PathwayStats> pathwayStatistics = new ConcurrentHashMap<>();
    private final AtomicLong totalUniqueGames = new AtomicLong();

    public void recordGameEnd(Arena arena, Team winningTeam) {
        List<PathwayOutcome> outcomes = plugin.getArenaPathwayManager().getRoundOutcomes(arena, winningTeam);
        if (outcomes.isEmpty()) {
            return;
        }

        totalUniqueGames.incrementAndGet();
        for (PathwayOutcome outcome : outcomes) {
            statsOf(outcome.getPathway()).recordOutcome(outcome.isWon());
        }
    }

    public void recordGameDuration(Arena arena, long durationMillis) {
        List<PathwayOutcome> outcomes = plugin.getArenaPathwayManager().getRoundOutcomes(arena, null);
        if (!outcomes.isEmpty()) {
            statsOf(outcomes.getFirst().getPathway()).getGameDurations().add(durationMillis);
        }
    }

    public void recordSequenceReached(String pathway, int sequence) {
        statsOf(pathway).getSequencesReached().add(sequence);
    }

    public void recordDamageDealt(String pathway, double damage) {
        statsOf(pathway).addDamage(damage);
    }

    public void recordAbilityUse(String pathway, String abilityName) {
        statsOf(pathway).addAbilityUse(abilityName);
    }

    private PathwayStats statsOf(String pathway) {
        return pathwayStatistics.computeIfAbsent(pathway, ignored -> new PathwayStats());
    }

    public long totalGames() {
        return totalUniqueGames.get();
    }

    public String bestPathway() {
        if (pathwayStatistics.isEmpty()) {
            return "No pathways played";
        }
        return pathwayStatistics.entrySet().stream()
                .filter(entry -> entry.getValue().getTotalGames() > 0)
                .max(Comparator.comparingDouble(entry -> entry.getValue().getWinRate()))
                .map(Map.Entry::getKey)
                .orElse("None");
    }

    public long highestWinRate() {
        return Math.round(pathwayStatistics.values().stream()
                .mapToDouble(PathwayStats::getWinRate)
                .max()
                .orElse(0.0) * 100);
    }

    public long averageGameDuration() {
        return Math.round(pathwayStatistics.values().stream()
                .flatMap(stats -> stats.getGameDurations().stream())
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0) / 1000 / 60);
    }

    public String mostPowerfulPathway() {
        return pathwayStatistics.entrySet().stream()
                .filter(entry -> entry.getValue().getTotalGames() > 0)
                .max(Comparator.comparingDouble(entry -> entry.getValue().getTotalDamageDealt() / entry.getValue().getTotalGames()))
                .map(Map.Entry::getKey)
                .orElse("None");
    }

    public void setPathwayStatistics(Map<String, PathwayStats> loaded, long totalGames) {
        pathwayStatistics.clear();
        pathwayStatistics.putAll(loaded);
        totalUniqueGames.set(totalGames);
    }
}
