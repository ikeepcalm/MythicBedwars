package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Picks the arena an event should run on.
 *
 * <p>Sized to the turnout, not to a minimum. Capacity fit dominates every other consideration, so
 * sixteen players get a sixteen-slot map and four players get a four-slot one rather than four
 * people rattling around a map built for sixteen. Where several maps fit equally well the choice is
 * <b>random</b>, so a regular event does not run on the same arena every time.
 *
 * <p>That randomness means two Bedwars servers evaluating the same roster may choose differently.
 * Harmless: only one of them wins the host claim, and each hosts on the arena it picked. The
 * re-check before start is against the arena already reserved, not a fresh selection.
 */
public class ArenaSelector {

    private final MythicBedwars plugin;

    public ArenaSelector(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * @param expectedPlayers the turnout to fit
     * @param stillFree       lets the caller exclude arenas it has already reserved
     * @return the best-fitting usable arena, or empty when none can hold this many players
     */
    public Optional<Arena> select(int expectedPlayers, Predicate<Arena> stillFree) {
        List<Arena> candidates = BedwarsAPI.getGameAPI().getArenas().stream()
                .filter(arena -> isEligible(arena, expectedPlayers))
                .filter(stillFree)
                .toList();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        int best = candidates.stream()
                .mapToInt(arena -> score(arena, expectedPlayers))
                .max()
                .orElseThrow();

        List<Arena> tied = new ArrayList<>();
        for (Arena arena : candidates) {
            if (score(arena, expectedPlayers) == best) {
                tied.add(arena);
            }
        }

        return Optional.of(tied.get(ThreadLocalRandom.current().nextInt(tied.size())));
    }

    /**
     * Re-checks an already-chosen arena. Anything that made it eligible can stop being true between
     * reservation and start — a build error introduced, a player wandering in, the arena being
     * stopped by an admin.
     */
    public boolean isStillUsable(Arena arena, int expectedPlayers) {
        return arena != null && arena.exists() && isEligible(arena, expectedPlayers);
    }

    /**
     * @return whether this arena would be a materially better fit for {@code actualPlayers} than
     * {@code current} is. Used once signups close and the real turnout is known, which is the first
     * moment a right-sized choice is even possible — the arena has to be reserved before that, when
     * all anybody has is a guess.
     */
    public Optional<Arena> findBetterFit(Arena current, int actualPlayers, Predicate<Arena> stillFree) {
        if (current == null) {
            return select(actualPlayers, stillFree);
        }

        int currentGap = Math.abs(current.getMaxPlayers() - actualPlayers);
        if (currentGap == 0) {
            return Optional.empty();
        }

        return select(actualPlayers, stillFree)
                .filter(candidate -> !candidate.getName().equals(current.getName()))
                // Only move for an improvement of at least two slots; shaving one off is not worth
                // re-reserving an arena and re-announcing it.
                .filter(candidate -> Math.abs(candidate.getMaxPlayers() - actualPlayers) <= currentGap - 2);
    }

    private boolean isEligible(Arena arena, int expectedPlayers) {
        if (!arena.getIssues().isEmpty()) {
            return false;
        }

        // STOPPED is the ideal state; an idle LOBBY is fine as long as nobody is waiting in it.
        ArenaStatus status = arena.getStatus();
        if (status != ArenaStatus.STOPPED && !(status == ArenaStatus.LOBBY && arena.getPlayers().isEmpty())) {
            return false;
        }

        // An arena in somebody else's voting pool refuses every addPlayer with
        // VOTING_PARTICIPATING, so reserving one would strand the whole roster on arrival.
        if (arena.getParticipatingVotingPool() != null) {
            return false;
        }

        if (!plugin.getConfigManager().isGloballyEnabled()
            || !plugin.getConfigManager().isArenaEnabled(arena.getName())) {
            return false;
        }

        if (!passesNameFilters(arena.getName())) {
            return false;
        }

        if (arena.getMaxPlayers() < expectedPlayers) {
            return false;
        }

        // Two teams minimum, and enough of them to seat everybody.
        int perTeam = Math.max(1, arena.getPlayersPerTeam());
        int teamsNeeded = Math.max(2, ceilDiv(expectedPlayers, perTeam));
        return arena.getEnabledTeams().size() >= teamsNeeded;
    }

    private boolean passesNameFilters(String arenaName) {
        String lower = arenaName.toLowerCase(Locale.ROOT);

        List<String> blacklist = plugin.getConfigManager().getEventArenaBlacklist();
        if (blacklist.stream().anyMatch(entry -> entry.equalsIgnoreCase(lower))) {
            return false;
        }

        List<String> whitelist = plugin.getConfigManager().getEventArenaWhitelist();
        return whitelist.isEmpty() || whitelist.stream().anyMatch(entry -> entry.equalsIgnoreCase(lower));
    }

    /**
     * Scores a candidate.
     *
     * <p>Capacity fit is weighted a thousand to one, so it strictly dominates: {@code isEligible} has
     * already excluded anything too small, and among what is left the closest size always wins. The
     * terms below it are tie-breakers between maps of the same size — with the realistic team counts
     * (2, 4, 8) the team-shape term stays clear of the smaller bonuses.
     */
    private int score(Arena arena, int expectedPlayers) {
        int perTeam = Math.max(1, arena.getPlayersPerTeam());
        int teams = arena.getEnabledTeams().size();

        int score = 0;

        // 1. Capacity fit, above everything else. An exact match is the whole point: sixteen players
        //    should get a 4x4, four players a 1v1v1v1.
        score -= Math.abs(arena.getMaxPlayers() - expectedPlayers) * 1000;

        // 2. Team shape. Among maps of the same size, favour the house format - which is what makes
        //    sixteen players land on a 4x4 rather than an 8x2, and four on a 1v1v1v1 rather than a 2v2.
        int preferredTeams = plugin.getConfigManager().getEventPreferredTeamCount();
        score -= Math.abs(teams - preferredTeams) * 100;

        // 3. A turnout that divides evenly into the teams avoids lopsided sides.
        if (expectedPlayers % Math.max(1, teams) == 0) {
            score += 60;
        }

        // 4. Prefer an arena that is already idle over one sitting in an empty lobby.
        if (arena.getStatus() == ArenaStatus.STOPPED) {
            score += 40;
        }

        if (plugin.getConfigManager().getPreferredEventArenas().stream()
                .anyMatch(name -> name.equalsIgnoreCase(arena.getName()))) {
            score += 30;
        }

        // 5. Clones exist to be consumed; leave the master arenas for normal play.
        if (!arena.isCloned()) {
            score -= 20;
        }

        // Keeps the "does it split evenly" bonus honest for odd team sizes.
        if (perTeam > 1 && expectedPlayers % perTeam != 0) {
            score -= 10;
        }

        return score;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
