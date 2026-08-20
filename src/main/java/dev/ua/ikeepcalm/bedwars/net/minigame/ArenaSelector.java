package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Picks the arena an event should run on.
 *
 * <p>Selection is deterministic — ties break on arena name — so two Bedwars servers evaluating the
 * same roster reach the same answer, and a re-check just before start returns the same arena unless
 * something genuinely changed.
 */
public class ArenaSelector {

    private final MythicBedwars plugin;

    public ArenaSelector(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * @param expectedPlayers the roster size to fit
     * @param stillFree       lets the caller exclude arenas it has already reserved
     * @return the best-fitting usable arena, or empty when none can hold this many players
     */
    public Optional<Arena> select(int expectedPlayers, Predicate<Arena> stillFree) {
        return BedwarsAPI.getGameAPI().getArenas().stream()
                .filter(arena -> isEligible(arena, expectedPlayers))
                .filter(stillFree)
                .max(Comparator.comparingInt((Arena arena) -> score(arena, expectedPlayers))
                        .thenComparing(Arena::getName, Comparator.reverseOrder()));
    }

    /**
     * Re-checks an already-chosen arena. Anything that made it eligible can stop being true between
     * reservation and start — a build error introduced, a player wandering in, the arena being
     * stopped by an admin.
     */
    public boolean isStillUsable(Arena arena, int expectedPlayers) {
        return arena != null && arena.exists() && isEligible(arena, expectedPlayers);
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

    private int score(Arena arena, int expectedPlayers) {
        int perTeam = Math.max(1, arena.getPlayersPerTeam());
        int teamsNeeded = Math.max(2, ceilDiv(expectedPlayers, perTeam));

        int score = arena.getStatus() == ArenaStatus.STOPPED ? 1000 : 400;

        // Tightest fit wins: a 16-slot map with six players is a bad game.
        score -= Math.abs(arena.getMaxPlayers() - expectedPlayers) * 10;
        score -= Math.abs(arena.getEnabledTeams().size() - teamsNeeded) * 25;

        // A roster that divides evenly into teams avoids lopsided sides.
        if (expectedPlayers % perTeam == 0) {
            score += 200;
        }

        if (plugin.getConfigManager().getPreferredEventArenas().stream()
                .anyMatch(name -> name.equalsIgnoreCase(arena.getName()))) {
            score += 50;
        }

        // Clones exist to be consumed; leave the master arenas for normal play.
        if (!arena.isCloned()) {
            score -= 30;
        }

        return score;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
