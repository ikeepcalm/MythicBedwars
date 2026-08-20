package dev.ua.ikeepcalm.bedwars.domain.reward;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.core.PathwayManager;
import dev.ua.ikeepcalm.bedwars.domain.reward.MatchContributionTracker.Contribution;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardTier;
import dev.ua.ikeepcalm.bedwars.net.minigame.EventReservation;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Works out who earned what at the end of an event match, and queues it for collection.
 *
 * <p>Runs on the Bedwars server at round end — the one moment when the match result, the
 * contribution counters, and the players' sandbox pathways are all still available. Everything it
 * produces is intent (percentages), never resolved amounts; the survival server sizes them against
 * the player's real progression.
 */
public class RewardService {

    private final MythicBedwars plugin;
    private final RewardConfig config;
    private final RewardRoller roller;
    private final RewardQueue queue;
    private final MatchContributionTracker tracker = new MatchContributionTracker();

    public RewardService(MythicBedwars plugin, RewardConfig config, RewardQueue queue) {
        this.plugin = plugin;
        this.config = config;
        this.queue = queue;
        this.roller = new RewardRoller(config);
    }

    public MatchContributionTracker tracker() {
        return tracker;
    }

    /**
     * Rolls and queues rewards for everybody who took part.
     *
     * @param quitWinners players who disconnected but were on the winning side; they still earn,
     *                    which is the whole reason the quit lists are consumed
     */
    public void payOut(EventReservation reservation, Arena arena,
                       List<Player> winners, List<Player> losers,
                       List<UUID> quitWinners, List<UUID> quitLosers, boolean tie) {
        if (!config.isEnabled()) {
            return;
        }

        int matchSize = winners.size() + losers.size() + quitWinners.size() + quitLosers.size();
        if (matchSize < config.minPlayers()) {
            plugin.log("Event {} had only {} player(s); no rewards paid.", reservation.eventId(), matchSize);
            return;
        }

        Map.Entry<UUID, Double> mvp = tracker.mvp(arena.getName(), config);

        for (Player player : winners) {
            award(reservation, arena, player.getUniqueId(), player.getName(), true, tie, mvp);
        }
        for (Player player : losers) {
            award(reservation, arena, player.getUniqueId(), player.getName(), false, tie, mvp);
        }
        for (UUID playerId : quitWinners) {
            award(reservation, arena, playerId, playerId.toString(), true, tie, mvp);
        }
        for (UUID playerId : quitLosers) {
            award(reservation, arena, playerId, playerId.toString(), false, tie, mvp);
        }
    }

    private void award(EventReservation reservation, Arena arena, UUID playerId, String name,
                       boolean won, boolean tie, Map.Entry<UUID, Double> mvp) {
        Contribution contribution = tracker.of(arena.getName(), playerId);

        String denial = denialReason(contribution, arena);
        if (denial != null) {
            plugin.log("No rewards for {}: {}", name, denial);
            notifyDenied(playerId, denial);
            return;
        }

        double ratio = participationRatio(contribution);

        List<RewardGrant> grants = new ArrayList<>();
        // Participation scales with how much of the match they were actually present for; a win
        // does not, because a carried teammate still genuinely won.
        grants.addAll(roller.roll(RewardTier.PARTICIPATION, ratio));
        if (won) {
            grants.addAll(roller.roll(RewardTier.WINNER, 1.0));
        }
        if (mvp != null && mvp.getKey().equals(playerId)) {
            grants.addAll(roller.roll(RewardTier.MVP, 1.0));
        }

        if (grants.isEmpty()) {
            return;
        }

        RewardBundle bundle = new RewardBundle(
                RewardBundle.SCHEMA,
                reservation.eventId(),
                arena.getName(),
                playerId,
                name,
                eventPathway(playerId),
                won && !tie,
                System.currentTimeMillis(),
                List.copyOf(grants));

        // Redis I/O, and the round-end tick is already busy.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (queue.emit(bundle)) {
                Bukkit.getScheduler().runTask(plugin, () -> preview(playerId, bundle));
            }
        });
    }

    /**
     * @return why this player earns nothing, or {@code null} if they qualify
     */
    private String denialReason(Contribution contribution, Arena arena) {
        if (config.denyRageQuit() && contribution.isRageQuit()) {
            return "rage quit";
        }

        if (contribution.millisSinceJoin() < config.minPlayTimeSeconds() * 1000L) {
            return "left too early";
        }

        if (contribution.actions() < config.minActions()) {
            return "did nothing";
        }

        if (participationRatio(contribution) < config.minParticipationRatio()) {
            return "away for most of the match";
        }

        return null;
    }

    private double participationRatio(Contribution contribution) {
        long matchSeconds = Math.max(1, contribution.millisSinceJoin() / 1000);
        return Math.min(1.0, (double) contribution.activeSeconds() / matchSeconds);
    }

    /**
     * Tells the player what is waiting for them, while they are still here to read it.
     */
    private void preview(UUID playerId, RewardBundle bundle) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.rewards.earned_header"));
        for (RewardGrant grant : bundle.grants()) {
            Component line = describe(grant);
            if (line != null) {
                player.sendMessage(line);
            }
        }
        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.rewards.earned_footer"));
    }

    private Component describe(RewardGrant grant) {
        return switch (grant.kind()) {
            case ACTING_PERCENT -> plugin.getLocaleManager().formatMessage(
                    "magic.rewards.line.acting_percent", "percent", grant.amount());
            case COOLDOWN_CREDIT -> plugin.getLocaleManager().formatMessage(
                    "magic.rewards.line.cooldown_credit", "percent", grant.amount());
            case ACTING_SPEED -> plugin.getLocaleManager().formatMessage(
                    "magic.rewards.line.acting_speed",
                    "percent", Math.round(grant.amount() * 100), "duration", grant.intArg() / 60 + "m");
            case ACTING_ITEM_MULT -> plugin.getLocaleManager().formatMessage(
                    "magic.rewards.line.acting_item_mult",
                    "multiplier", grant.amount(), "duration", grant.intArg() / 60 + "m");
            case ITEM -> grant.item() == null ? null : plugin.getLocaleManager().formatMessage(
                    "magic.rewards.line.item", "amount", grant.count(),
                    "item", plugin.getLocaleManager().getMessage(
                            "magic.rewards.item_name." + grant.item().name().toLowerCase()));
        };
    }

    private void notifyDenied(UUID playerId, String reason) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        String key = switch (reason) {
            case "away for most of the match" -> "magic.rewards.denied_afk";
            case "left too early", "rage quit" -> "magic.rewards.denied_short";
            default -> "magic.rewards.earned_none";
        };
        player.sendMessage(plugin.getLocaleManager().formatMessage(key));
    }

    /**
     * @return the pathway they played the match with, for display on the summary back home
     */
    private String eventPathway(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return null;
        }

        PathwayManager.PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
        if (data != null) {
            return data.getPathway();
        }

        CircleOfImaginationAPI api = plugin.getCircleOfImaginationAPI();
        var pathways = api.getPathwayData(player);
        return pathways.isEmpty() ? null : pathways.getFirst().name();
    }
}
