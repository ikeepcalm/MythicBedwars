package dev.ua.ikeepcalm.bedwars.domain.reward;

import de.marcely.bedwars.api.arena.KickReason;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-match record of what each player actually did, used to decide who has earned a reward.
 *
 * <p>Exists because "was in the arena when it ended" is not the same as "played". Without this, an
 * account that joins and stands still collects the same participation reward as somebody who
 * fought, and a pair of accounts can farm the winner tier in an empty lobby.
 */
public class MatchContributionTracker {

    private final Map<String, Map<UUID, Contribution>> byArena = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastSeen = new ConcurrentHashMap<>();

    /**
     * Mutable per-player counters. Touched from the main thread only, but held in concurrent maps
     * because the reward path reads them from Redis callbacks.
     */
    public static class Contribution {
        private final long joinedAt = System.currentTimeMillis();

        private volatile int kills;
        private volatile int finalKills;
        private volatile int bedsBroken;
        private volatile int purchases;
        private volatile int activeSeconds;
        private volatile KickReason quitReason;

        public int kills() {
            return kills;
        }

        public int finalKills() {
            return finalKills;
        }

        public int bedsBroken() {
            return bedsBroken;
        }

        public int actions() {
            return kills + finalKills + bedsBroken + purchases;
        }

        public int activeSeconds() {
            return activeSeconds;
        }

        public long millisSinceJoin() {
            return System.currentTimeMillis() - joinedAt;
        }

        /**
         * @return whether they walked out mid-match rather than being eliminated or the game ending
         */
        public boolean isRageQuit() {
            return quitReason != null && quitReason.isRageQuit();
        }
    }

    public Contribution of(String arenaName, UUID playerId) {
        return byArena.computeIfAbsent(arenaName, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(playerId, key -> new Contribution());
    }

    public void recordKill(String arenaName, UUID playerId, boolean finalKill) {
        Contribution contribution = of(arenaName, playerId);
        if (finalKill) {
            contribution.finalKills++;
        } else {
            contribution.kills++;
        }
    }

    public void recordBedBreak(String arenaName, UUID playerId) {
        of(arenaName, playerId).bedsBroken++;
    }

    public void recordPurchase(String arenaName, UUID playerId) {
        of(arenaName, playerId).purchases++;
    }

    /**
     * Ticked once a second for players who are not idle, giving a participation ratio that scales
     * the reward rather than gating it on a hard cliff.
     */
    public void recordActiveSecond(String arenaName, UUID playerId) {
        of(arenaName, playerId).activeSeconds++;
    }

    /**
     * Samples one player once. Counts the second only if they have moved since the last sample,
     * which is a crude but honest proxy for actually playing - and crucially it degrades to
     * "scaled-down reward" rather than "no reward", so a defender holding a base is not punished
     * as harshly as a body left at spawn.
     */
    public void sample(String arenaName, Player player) {
        UUID playerId = player.getUniqueId();
        Location current = player.getLocation();
        Location previous = lastSeen.put(playerId, current.clone());

        boolean moved = previous == null
                        || !previous.getWorld().equals(current.getWorld())
                        || previous.distanceSquared(current) > 0.25;

        if (moved) {
            recordActiveSecond(arenaName, playerId);
        }
    }

    public void markQuit(String arenaName, UUID playerId, KickReason reason) {
        of(arenaName, playerId).quitReason = reason;
    }

    /**
     * @return the MVP's id and score, or {@code null} when nobody cleared the threshold
     */
    public Map.Entry<UUID, Double> mvp(String arenaName, RewardConfig config) {
        Map<UUID, Contribution> arena = byArena.get(arenaName);
        if (arena == null || arena.isEmpty()) {
            return null;
        }

        double killScore = config.mvpScore("kill", 1.0);
        double finalKillScore = config.mvpScore("final-kill", 3.0);
        double bedScore = config.mvpScore("bed-break", 5.0);

        Map.Entry<UUID, Double> best = null;
        for (Map.Entry<UUID, Contribution> entry : arena.entrySet()) {
            Contribution c = entry.getValue();
            double score = c.kills() * killScore + c.finalKills() * finalKillScore + c.bedsBroken() * bedScore;
            if (best == null || score > best.getValue()) {
                best = Map.entry(entry.getKey(), score);
            }
        }

        return best != null && best.getValue() >= config.mvpMinScore() ? best : null;
    }

    public void clear(String arenaName) {
        Map<UUID, Contribution> removed = byArena.remove(arenaName);
        if (removed != null) {
            removed.keySet().forEach(lastSeen::remove);
        }
    }
}
