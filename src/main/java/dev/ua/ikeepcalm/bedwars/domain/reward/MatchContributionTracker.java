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

    /**
     * Squared position delta above which a sample counts as movement — a tenth of a block, and
     * deliberately that small. All this number has to do is ignore a body standing perfectly still;
     * a defender shifting their feet on their own bed island is playing.
     */
    private static final double MOVED_DISTANCE_SQUARED = 0.01;

    /**
     * Degrees of yaw or pitch change that count as looking around. An AFK client sends the same
     * rotation forever; somebody watching a bridge does not.
     */
    private static final float LOOKED_DEGREES = 1.0f;

    /**
     * How recently the client must have sent an input for a sample to count wherever they happen to
     * be standing. This is what covers a stationary defender: swinging, shooting, placing blocks and
     * opening chests all move them nowhere at all.
     */
    private static final long INPUT_GRACE_SECONDS = 10L;

    /**
     * How long one recorded action keeps crediting activity. A player who lands a kill and then holds
     * position for the next minute is defending, not away.
     */
    private static final long ACTION_GRACE_MILLIS = 60_000L;

    private final Map<String, Map<UUID, Contribution>> byArena = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastSeen = new ConcurrentHashMap<>();

    /**
     * Records a departure, and decides whether it forfeits the reward.
     *
     * <p>Deliberately does <b>not</b> use {@code KickReason#isRageQuit()}, which is far broader than
     * its name: it also covers {@code SERVER_DISCONNECT}, {@code TELEPORT}, {@code SPECTATE} and any
     * third-party {@code PLUGIN} kick. Treating those as forfeits would punish a dropped connection,
     * make the whole point of consuming the quit lists moot, and deny anyone who died and chose to
     * spectate — which in Bedwars is simply how the game is played.
     *
     * @param whileRunning whether the match was actually in progress; walking out of a lobby or an
     *                     end screen costs nothing
     */
    public void markQuit(String arenaName, UUID playerId, KickReason reason, boolean whileRunning) {
        boolean voluntary = reason == KickReason.LEAVE || reason == KickReason.KICK;
        of(arenaName, playerId).forfeit = whileRunning && voluntary;
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
        contribution.touch();
    }

    public void recordBedBreak(String arenaName, UUID playerId) {
        Contribution contribution = of(arenaName, playerId);
        contribution.bedsBroken++;
        contribution.touch();
    }

    public void recordPurchase(String arenaName, UUID playerId) {
        Contribution contribution = of(arenaName, playerId);
        contribution.purchases++;
        contribution.touch();
    }

    /**
     * Notes that a player did something the scoreboard does not count — swung at somebody, took a
     * hit, walled their bed back up. It earns no reward of its own; it only says "this account is
     * being played", which is exactly what the participation ratio is trying to measure.
     */
    public void recordActivity(String arenaName, UUID playerId) {
        of(arenaName, playerId).touch();
    }

    /**
     * Samples one player once, counting both the sample and whether they looked alive for it.
     *
     * <p>Movement alone is a bad proxy for playing Bedwars. Holding a base means standing on it,
     * shooting whoever comes across the bridge and walling the bed back up — none of which involves
     * travelling anywhere. So four independent signals are OR-ed and any one of them suffices:
     * position, rotation, a recent client input, and a recent recorded action. Each can only add
     * credit, never withdraw it, which is the direction this check should err in.
     *
     * <p>The denominator is the number of samples actually taken, never wall-clock match length.
     * That matters as much as the signals do: sampling only happens while a player is in play, so
     * somebody eliminated five minutes into a twenty-minute match is judged on those five minutes
     * instead of being diluted towards zero by the fifteen they spent dead.
     */
    public void sample(String arenaName, Player player) {
        UUID playerId = player.getUniqueId();
        Contribution contribution = of(arenaName, playerId);
        contribution.sampledSeconds++;

        Location current = player.getLocation();
        Location previous = lastSeen.put(playerId, current.clone());

        if (looksActive(player, contribution, previous, current)) {
            contribution.activeSeconds++;
        }
    }

    private static boolean looksActive(Player player, Contribution contribution,
                                       Location previous, Location current) {
        if (previous == null || !current.getWorld().equals(previous.getWorld())) {
            return true;
        }

        if (previous.distanceSquared(current) > MOVED_DISTANCE_SQUARED) {
            return true;
        }

        if (angleDelta(previous.getYaw(), current.getYaw()) > LOOKED_DEGREES
            || angleDelta(previous.getPitch(), current.getPitch()) > LOOKED_DEGREES) {
            return true;
        }

        // Resets on essentially any packet the client sends, so it catches the actions that leave a
        // defender standing exactly where they were.
        if (player.getIdleDuration().toSeconds() < INPUT_GRACE_SECONDS) {
            return true;
        }

        return contribution.millisSinceAction() < ACTION_GRACE_MILLIS;
    }

    /**
     * @return the smaller of the two ways round the circle, so a player facing due north does not
     * register a 359-degree turn every time their yaw crosses zero
     */
    private static float angleDelta(float from, float to) {
        float delta = Math.abs(to - from) % 360f;
        return delta > 180f ? 360f - delta : delta;
    }

    /**
     * @return the MVP's id and score, or {@code null} when nobody cleared the threshold
     */
    public Map.Entry<UUID, Double> mvp(String arenaName, RewardConfig config,
                                       java.util.function.Predicate<Contribution> eligible) {
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
            // Skipping the ineligible matters: the top scorer being denied would otherwise award the
            // MVP tier to nobody, rather than to the best player who actually qualifies.
            if (!eligible.test(c)) {
                continue;
            }
            double score = c.kills() * killScore + c.finalKills() * finalKillScore + c.bedsBroken() * bedScore;
            if (best == null || score > best.getValue()) {
                best = Map.entry(entry.getKey(), score);
            }
        }

        return best != null && best.getValue() >= config.mvpMinScore() ? best : null;
    }

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
        private volatile int sampledSeconds;
        /** When they last did something. {@code 0} means never. */
        private volatile long lastActionAt;
        private volatile boolean forfeit;

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

        /**
         * @return how many times they were sampled, i.e. how many seconds of the match they spent
         * in play rather than dead, spectating or already gone
         */
        public int sampledSeconds() {
            return sampledSeconds;
        }

        public long millisSinceJoin() {
            return System.currentTimeMillis() - joinedAt;
        }

        /**
         * @return time since their last recorded action, or {@link Long#MAX_VALUE} if they have none
         */
        public long millisSinceAction() {
            long at = lastActionAt;
            return at == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - at;
        }

        private void touch() {
            lastActionAt = System.currentTimeMillis();
        }

        /**
         * @return whether they walked out mid-match rather than being eliminated or the game ending
         */
        public boolean isForfeit() {
            return forfeit;
        }
    }

    public void clear(String arenaName) {
        Map<UUID, Contribution> removed = byArena.remove(arenaName);
        if (removed != null) {
            removed.keySet().forEach(lastSeen::remove);
        }
    }
}
