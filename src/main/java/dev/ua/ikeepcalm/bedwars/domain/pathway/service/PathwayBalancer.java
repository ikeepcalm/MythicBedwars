package dev.ua.ikeepcalm.bedwars.domain.pathway.service;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.stats.model.PathwayStats;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public class PathwayBalancer {

    private final MythicBedwars plugin;

    public Map<Team, String> assignBalancedPathways(Arena arena) {
        List<Team> teams = new ArrayList<>(collectTeams(arena));
        Map<Team, String> assignments = new LinkedHashMap<>();
        if (teams.isEmpty()) {
            return assignments;
        }

        Collections.shuffle(teams);
        List<String> picks = drawDistinct(currentPool(), teams.size());
        if (picks.isEmpty()) {
            plugin.log("No pathway is available for arena " + arena.getName() + ", check the allowed pathway list");
            return assignments;
        }

        for (int i = 0; i < teams.size(); i++) {
            assignments.put(teams.get(i), picks.get(i));
        }
        return assignments;
    }

    public String pickPathway(Collection<String> alreadyAssigned) {
        List<String> pool = currentPool();
        if (pool.isEmpty()) {
            return null;
        }

        Collections.shuffle(pool);
        Set<String> used = new HashSet<>(alreadyAssigned);
        for (String pathway : pool) {
            if (!used.contains(pathway)) {
                return pathway;
            }
        }
        return pool.getFirst();
    }

    private List<String> currentPool() {
        return plugin.getConfigManager().isPathwayBalancingEnabled() ? getBalancedPathwayPool() : getAllowedPathways();
    }

    private Set<Team> collectTeams(Arena arena) {
        Set<Team> teams = new HashSet<>(arena.getEnabledTeams());
        if (!teams.isEmpty()) {
            return teams;
        }

        for (var player : arena.getPlayers()) {
            Team team = arena.getPlayerTeam(player);
            if (team != null) {
                teams.add(team);
            }
        }
        return teams;
    }

    private List<String> drawDistinct(List<String> pool, int count) {
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);

        List<String> picks = new ArrayList<>(count);
        Set<String> used = new HashSet<>();
        for (String pathway : shuffled) {
            if (used.add(pathway)) {
                picks.add(pathway);
                if (picks.size() == count) {
                    return picks;
                }
            }
        }

        int distinct = picks.size();
        if (distinct == 0) {
            return picks;
        }

        while (picks.size() < count) {
            picks.add(picks.get(picks.size() % distinct));
        }
        return picks;
    }

    private List<String> getAllowedPathways() {
        List<String> all = plugin.getCircleOfImaginationAPI().getAllPathwayNames();
        List<String> allowed = new ArrayList<>(all.stream().filter(plugin.getConfigManager()::isPathwayAllowed).toList());
        return allowed.isEmpty() ? new ArrayList<>(all) : allowed;
    }

    private List<String> getBalancedPathwayPool() {
        Map<String, PathwayStats> stats = plugin.getStatisticsManager().getPathwayStatistics();
        int minGames = plugin.getConfigManager().getMinGamesForBalance();
        Map<String, Double> winRates = new HashMap<>();

        for (String pathway : plugin.getCircleOfImaginationAPI().getAllPathwayNames()) {
            if (!plugin.getConfigManager().isPathwayAllowed(pathway)) {
                continue;
            }

            PathwayStats pathwayStats = stats.get(pathway);
            boolean enoughData = pathwayStats != null && pathwayStats.getTotalGames() >= minGames;
            winRates.put(pathway, enoughData ? (double) pathwayStats.getWins() / pathwayStats.getTotalGames() : Double.NaN);
        }

        if (winRates.isEmpty()) {
            return new ArrayList<>(plugin.getCircleOfImaginationAPI().getAllPathwayNames());
        }

        double avgWinRate = winRates.values().stream().filter(rate -> !rate.isNaN()).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        List<String> pool = new ArrayList<>();
        winRates.forEach((pathway, winRate) -> {
            int weight = winRate.isNaN() ? 2 : calculateWeight(winRate, avgWinRate);
            for (int i = 0; i < weight; i++) {
                pool.add(pathway);
            }
        });
        return pool;
    }

    private int calculateWeight(double winRate, double avgWinRate) {
        double threshold = plugin.getConfigManager().getBalanceThreshold();
        if (winRate > avgWinRate + threshold) {
            return 1;
        }
        if (winRate < avgWinRate - threshold) {
            return 3;
        }
        return 2;
    }

    public void printBalanceReport() {
        plugin.log("=== Pathway Balance Report ===");
        plugin.getStatisticsManager().getPathwayStatistics().entrySet().stream()
                .filter(entry -> entry.getValue().getTotalGames() > 0)
                .sorted((a, b) -> Double.compare(winRate(b.getValue()), winRate(a.getValue())))
                .forEach(entry -> plugin.log(String.format("%s: %.1f%% win rate (%d/%d games)", entry.getKey(),
                        winRate(entry.getValue()) * 100, entry.getValue().getWins(), entry.getValue().getTotalGames())));
    }

    private double winRate(PathwayStats stats) {
        return (double) stats.getWins() / stats.getTotalGames();
    }
}
