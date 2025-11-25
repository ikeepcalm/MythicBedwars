package dev.ua.ikeepcalm.mythicBedwars.domain.balancer;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.stats.db.PathwayStats;

import java.util.*;
import java.util.stream.Collectors;

public class PathwayBalancer {

    private final MythicBedwars plugin;
    private final CircleOfImaginationAPI circleOfImaginationAPI;

    public PathwayBalancer(MythicBedwars plugin) {
        this.plugin = plugin;
        this.circleOfImaginationAPI = plugin.getCircleOfImaginationAPI();
    }

    public Map<Team, String> assignBalancedPathways(Arena arena) {
        if (!plugin.getConfigManager().isPathwayBalancingEnabled()) {
            return assignRandomPathways(arena);
        }

        List<String> availablePathways = getBalancedPathwayPool();
        Map<Team, String> assignments = new HashMap<>();

        List<Team> teams = new ArrayList<>(arena.getAliveTeams());
        Collections.shuffle(teams);

        for (int i = 0; i < teams.size(); i++) {
            if (i >= availablePathways.size()) {
                Collections.shuffle(availablePathways);
                i = i % availablePathways.size();
            }
            assignments.put(teams.get(i), availablePathways.get(i));
        }

        plugin.log("Assigned balanced pathways for arena " + arena.getName() + ": " +
                                assignments.entrySet().stream()
                                        .map(entry -> entry.getKey().getDisplayName() + "=" + entry.getValue())
                                        .collect(Collectors.joining(", ")));

        return assignments;
    }

    private List<String> getBalancedPathwayPool() {
        Map<String, PathwayStats> stats = plugin.getStatisticsManager().getPathwayStatistics();
        Map<String, Double> winRates = new HashMap<>();

        for (String pathway : circleOfImaginationAPI.getAllPathwayNames()) {
            if (!plugin.getConfigManager().isPathwayAllowed(pathway)) {
                continue;
            }

            PathwayStats pathwayStats = stats.get(pathway);
            if (pathwayStats != null && pathwayStats.totalGames >= 3) { // Minimum games for statistical relevance
                winRates.put(pathway, (double) pathwayStats.wins / pathwayStats.totalGames);
            } else {
                // Default to 50% win rate for pathways without enough data
                winRates.put(pathway, 0.5);
            }
        }

        if (winRates.isEmpty()) {
            return new ArrayList<>(circleOfImaginationAPI.getAllPathwayNames());
        }

        double avgWinRate = winRates.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.5);

        List<String> balancedPool = new ArrayList<>();

        for (Map.Entry<String, Double> entry : winRates.entrySet()) {
            String pathway = entry.getKey();
            double winRate = entry.getValue();

            int weight = calculateWeight(winRate, avgWinRate);
            for (int i = 0; i < weight; i++) {
                balancedPool.add(pathway);
            }
        }

        Collections.shuffle(balancedPool);

        plugin.log("Created balanced pathway pool with " + balancedPool.size() +
                                " entries, average win rate: " + String.format("%.2f", avgWinRate * 100) + "%");

        return balancedPool;
    }

    private int calculateWeight(double winRate, double avgWinRate) {
        double threshold = plugin.getConfigManager().getBalanceThreshold();

        if (winRate > avgWinRate + threshold) {
            // Overpowered pathways get lower weight
            return 1;
        } else if (winRate < avgWinRate - threshold) {
            // Underpowered pathways get higher weight
            return 3;
        } else {
            // Balanced pathways get normal weight
            return 2;
        }
    }

    private Map<Team, String> assignRandomPathways(Arena arena) {
        Map<Team, String> teamPathways = new HashMap<>();
        List<String> availablePathways = circleOfImaginationAPI.getAllPathwayNames().stream()
                .filter(plugin.getConfigManager()::isPathwayAllowed)
                .collect(Collectors.toList());

        if (availablePathways.isEmpty()) {
            availablePathways = new ArrayList<>(circleOfImaginationAPI.getAllPathwayNames());
        }

        Collections.shuffle(availablePathways);

        int index = 0;
        for (Team team : arena.getAliveTeams()) {
            if (index >= availablePathways.size()) {
                Collections.shuffle(availablePathways);
                index = 0;
            }
            teamPathways.put(team, availablePathways.get(index));
            index++;
        }

        plugin.log("Assigned random pathways for arena " + arena.getName() + ": " +
                                teamPathways.entrySet().stream()
                                        .map(entry -> entry.getKey().getDisplayName() + "=" + entry.getValue())
                                        .collect(Collectors.joining(", ")));

        return teamPathways;
    }

    public void printBalanceReport() {
        Map<String, PathwayStats> stats = plugin.getStatisticsManager().getPathwayStatistics();

        plugin.log("=== Pathway Balance Report ===");

        List<Map.Entry<String, PathwayStats>> sortedStats = stats.entrySet().stream()
                .filter(entry -> entry.getValue().totalGames > 0)
                .sorted((a, b) -> Double.compare(
                        (double) b.getValue().wins / b.getValue().totalGames,
                        (double) a.getValue().wins / a.getValue().totalGames
                ))
                .toList();

        for (Map.Entry<String, PathwayStats> entry : sortedStats) {
            String pathway = entry.getKey();
            PathwayStats pathwayStats = entry.getValue();
            double winRate = (double) pathwayStats.wins / pathwayStats.totalGames * 100;

            plugin.log(String.format("%s: %.1f%% win rate (%d/%d games)",
                    pathway, winRate, pathwayStats.wins, pathwayStats.totalGames));
        }
    }
}