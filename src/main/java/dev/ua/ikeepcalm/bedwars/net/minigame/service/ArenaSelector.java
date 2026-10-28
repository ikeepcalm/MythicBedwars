package dev.ua.ikeepcalm.bedwars.net.minigame.service;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

@RequiredArgsConstructor
public class ArenaSelector {

    private final MythicBedwars plugin;

    public Optional<Arena> select(int expectedPlayers, Predicate<Arena> stillFree) {
        List<Arena> candidates = BedwarsAPI.getGameAPI().getArenas().stream()
                .filter(arena -> isEligible(arena, expectedPlayers))
                .filter(stillFree)
                .toList();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        int best = candidates.stream().mapToInt(arena -> score(arena, expectedPlayers)).max().orElseThrow();
        List<Arena> tied = candidates.stream().filter(arena -> score(arena, expectedPlayers) == best).toList();
        return Optional.of(tied.get(ThreadLocalRandom.current().nextInt(tied.size())));
    }

    public boolean isStillUsable(Arena arena, int expectedPlayers) {
        return arena.exists() && isEligible(arena, expectedPlayers);
    }

    public Optional<Arena> findBetterFit(Arena current, int actualPlayers, Predicate<Arena> stillFree) {
        int currentGap = Math.abs(current.getMaxPlayers() - actualPlayers);
        if (currentGap == 0) {
            return Optional.empty();
        }

        return select(actualPlayers, stillFree)
                .filter(candidate -> !candidate.getName().equals(current.getName()))
                .filter(candidate -> Math.abs(candidate.getMaxPlayers() - actualPlayers) <= currentGap - 2);
    }

    private boolean isEligible(Arena arena, int expectedPlayers) {
        if (!arena.getIssues().isEmpty()) {
            return false;
        }

        ArenaStatus status = arena.getStatus();
        if (status != ArenaStatus.STOPPED && !(status == ArenaStatus.LOBBY && arena.getPlayers().isEmpty())) {
            return false;
        }

        // an arena in another voting pool refuses every addPlayer with VOTING_PARTICIPATING
        if (arena.getParticipatingVotingPool() != null) {
            return false;
        }

        if (!plugin.getConfigManager().isGloballyEnabled()
            || !plugin.getConfigManager().isArenaEnabled(arena.getName())
            || !passesNameFilters(arena.getName())
            || arena.getMaxPlayers() < expectedPlayers) {
            return false;
        }

        int perTeam = Math.max(1, arena.getPlayersPerTeam());
        int teamsNeeded = Math.max(2, (expectedPlayers + perTeam - 1) / perTeam);
        return arena.getEnabledTeams().size() >= teamsNeeded;
    }

    private boolean passesNameFilters(String arenaName) {
        String lower = arenaName.toLowerCase(Locale.ROOT);

        if (plugin.getConfigManager().getEventArenaBlacklist().stream().anyMatch(entry -> entry.equalsIgnoreCase(lower))) {
            return false;
        }

        List<String> whitelist = plugin.getConfigManager().getEventArenaWhitelist();
        return whitelist.isEmpty() || whitelist.stream().anyMatch(entry -> entry.equalsIgnoreCase(lower));
    }

    private int score(Arena arena, int expectedPlayers) {
        int perTeam = Math.max(1, arena.getPlayersPerTeam());
        int teams = arena.getEnabledTeams().size();

        int score = -Math.abs(arena.getMaxPlayers() - expectedPlayers) * 1000;
        score -= Math.abs(teams - plugin.getConfigManager().getEventPreferredTeamCount()) * 100;

        if (expectedPlayers % Math.max(1, teams) == 0) {
            score += 60;
        }
        if (arena.getStatus() == ArenaStatus.STOPPED) {
            score += 40;
        }
        if (plugin.getConfigManager().getPreferredEventArenas().stream().anyMatch(name -> name.equalsIgnoreCase(arena.getName()))) {
            score += 30;
        }
        if (!arena.isCloned()) {
            score -= 20;
        }
        if (perTeam > 1 && expectedPlayers % perTeam != 0) {
            score -= 10;
        }

        return score;
    }
}
