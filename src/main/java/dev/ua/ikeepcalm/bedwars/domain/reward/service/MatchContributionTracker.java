package dev.ua.ikeepcalm.bedwars.domain.reward.service;

import de.marcely.bedwars.api.arena.KickReason;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.Contribution;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class MatchContributionTracker {

    private static final double MOVED_DISTANCE_SQUARED = 0.01;
    private static final float LOOKED_DEGREES = 1.0f;
    private static final long INPUT_GRACE_SECONDS = 10L;
    private static final long ACTION_GRACE_MILLIS = 60_000L;

    private final Map<String, Map<UUID, Contribution>> byArena = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastSeen = new ConcurrentHashMap<>();

    public void markQuit(String arenaName, UUID playerId, KickReason reason, boolean whileRunning) {
        boolean voluntary = reason == KickReason.LEAVE || reason == KickReason.KICK;
        of(arenaName, playerId).setForfeit(whileRunning && voluntary);
    }

    public Contribution of(String arenaName, UUID playerId) {
        return byArena.computeIfAbsent(arenaName, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(playerId, key -> new Contribution());
    }

    public void recordKill(String arenaName, UUID playerId, boolean finalKill) {
        of(arenaName, playerId).addKill(finalKill);
    }

    public void recordBedBreak(String arenaName, UUID playerId) {
        of(arenaName, playerId).addBedBreak();
    }

    public void recordPurchase(String arenaName, UUID playerId) {
        of(arenaName, playerId).addPurchase();
    }

    public void recordActivity(String arenaName, UUID playerId) {
        of(arenaName, playerId).touch();
    }

    public void sample(String arenaName, Player player) {
        Contribution contribution = of(arenaName, player.getUniqueId());
        Location current = player.getLocation();
        Location previous = lastSeen.put(player.getUniqueId(), current.clone());
        contribution.addSample(looksActive(player, contribution, previous, current));
    }

    private static boolean looksActive(Player player, Contribution contribution, Location previous, Location current) {
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
        if (player.getIdleDuration().toSeconds() < INPUT_GRACE_SECONDS) {
            return true;
        }
        return contribution.millisSinceAction() < ACTION_GRACE_MILLIS;
    }

    private static float angleDelta(float from, float to) {
        float delta = Math.abs(to - from) % 360f;
        return delta > 180f ? 360f - delta : delta;
    }

    public Map.Entry<UUID, Double> mvp(String arenaName, RewardConfig config, Predicate<Contribution> eligible) {
        Map<UUID, Contribution> arena = byArena.get(arenaName);
        if (arena == null) {
            return null;
        }

        Map.Entry<UUID, Double> best = null;
        for (Map.Entry<UUID, Contribution> entry : arena.entrySet()) {
            if (!eligible.test(entry.getValue())) {
                continue;
            }
            double score = config.score(entry.getValue());
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
