package dev.ua.ikeepcalm.bedwars.domain.balancer;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.stats.db.PathwayStats;

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

        Map<Team, String> assignments = distribute(arena, getBalancedPathwayPool());

        plugin.log("Assigned balanced pathways for arena " + arena.getName() + ": " + describe(assignments));

        return assignments;
    }

    private Map<Team, String> assignRandomPathways(Arena arena) {
        Map<Team, String> assignments = distribute(arena, getAllowedPathways());

        plugin.log("Assigned random pathways for arena " + arena.getName() + ": " + describe(assignments));

        return assignments;
    }

    /**
     * Picks one pathway for a team the round's initial distribution did not cover, preferring one
     * that no other team of the arena is already running.
     *
     * @param alreadyAssigned the pathways the arena's other teams hold
     * @return the pathway to use, or {@code null} when no pathway is available at all
     */
    public String pickPathway(Collection<String> alreadyAssigned) {
        List<String> pool = plugin.getConfigManager().isPathwayBalancingEnabled()
                ? getBalancedPathwayPool()
                : getAllowedPathways();

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

    /**
     * Hands every team of the arena exactly one pathway, distinct per team for as long as the pool
     * has distinct entries left to give.
     */
    private Map<Team, String> distribute(Arena arena, List<String> pool) {
        List<Team> teams = new ArrayList<>(collectTeams(arena));
        Collections.shuffle(teams);

        Map<Team, String> assignments = new LinkedHashMap<>();

        if (teams.isEmpty()) {
            return assignments;
        }

        List<String> picks = drawDistinct(pool, teams.size());
        if (picks.isEmpty()) {
            plugin.log("No pathway is available for arena " + arena.getName() + ", check the allowed pathway list");
            return assignments;
        }

        for (int i = 0; i < teams.size(); i++) {
            assignments.put(teams.get(i), picks.get(i));
        }

        return assignments;
    }

    /**
     * Collects the teams that need a pathway.
     *
     * <p>Teams are what MBedwars hands to players as the round starts, so a set derived from
     * {@link Arena#getPlayerTeam(org.bukkit.entity.Player)} at the {@code LOBBY -> RUNNING}
     * transition holds only the teams players picked themselves - everyone the auto balancer
     * places a tick later is still team-less, and so their team is missing from the assignment.
     * The arena's enabled teams are known up front and cannot change mid-round, which makes them
     * the only snapshot that is already complete at this point.
     */
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

    /**
     * Draws up to {@code count} distinct pathways out of a weighted pool.
     *
     * <p>The pool repeats a pathway once per weight point, so shuffling it and keeping only the
     * first occurrence of each name is a weighted draw without replacement: a heavier pathway is
     * more likely to land early, yet it can still only be handed out once.
     *
     * <p>The result repeats a pathway only when the pool holds fewer distinct ones than there are
     * teams, which is the single case where two teams sharing a pathway is unavoidable.
     */
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

    private String describe(Map<Team, String> assignments) {
        if (assignments.isEmpty()) {
            return "none";
        }

        return assignments.entrySet().stream()
                .map(entry -> entry.getKey().getDisplayName() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private List<String> getAllowedPathways() {
        List<String> allowed = circleOfImaginationAPI.getAllPathwayNames().stream()
                .filter(plugin.getConfigManager()::isPathwayAllowed)
                .collect(Collectors.toList());

        if (allowed.isEmpty()) {
            return new ArrayList<>(circleOfImaginationAPI.getAllPathwayNames());
        }

        return allowed;
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
