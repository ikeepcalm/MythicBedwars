package dev.ua.ikeepcalm.bedwars.domain.reward;

import dev.ua.ikeepcalm.bedwars.domain.reward.RewardConfig.TierConfig;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardEntry;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardTier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns a tier's config into the grants one player actually earned.
 *
 * <p>Rolling happens once, on the Bedwars server, at the end of the match — so a reconnect cannot
 * re-roll for a better outcome, and the player can be told what they won before they travel.
 */
public class RewardRoller {

    private final RewardConfig config;

    public RewardRoller(RewardConfig config) {
        this.config = config;
    }

    /**
     * @param scale multiplies percentage-shaped grants, used to scale participation by how much of
     *              the match the player was actually present for. Pass {@code 1.0} to leave alone.
     */
    public List<RewardGrant> roll(RewardTier tier, double scale) {
        TierConfig tierConfig = config.tier(tier);
        if (!tierConfig.enabled()) {
            return List.of();
        }

        List<RewardGrant> grants = new ArrayList<>();

        for (RewardEntry entry : tierConfig.guaranteed()) {
            grants.add(materialise(entry, tier, scale));
        }

        List<RewardEntry> pool = tierConfig.pool();
        for (int i = 0; i < tierConfig.rolls() && !pool.isEmpty(); i++) {
            RewardEntry picked = pickWeighted(pool);
            if (picked != null) {
                grants.add(materialise(picked, tier, scale));
            }
        }

        return grants;
    }

    private RewardEntry pickWeighted(List<RewardEntry> pool) {
        int total = pool.stream().mapToInt(entry -> Math.max(0, entry.weight())).sum();
        if (total <= 0) {
            return pool.getFirst();
        }

        int target = ThreadLocalRandom.current().nextInt(total);
        for (RewardEntry entry : pool) {
            target -= Math.max(0, entry.weight());
            if (target < 0) {
                return entry;
            }
        }

        return pool.getLast();
    }

    /**
     * Fixes the roll's random element now, leaving only the "how big is that percentage for this
     * player" question for the survival server to answer.
     */
    private RewardGrant materialise(RewardEntry entry, RewardTier tier, double scale) {
        double amount = switch (entry.kind()) {
            case ACTING_PERCENT, COOLDOWN_CREDIT -> between(entry.minPercent(), entry.maxPercent()) * scale;
            case ACTING_SPEED, ACTING_ITEM_MULT -> between(entry.minValue(), entry.maxValue());
            case ITEM -> entry.minPercent() > 0 || entry.maxPercent() > 0
                    ? between(entry.minPercent(), entry.maxPercent())
                    : between(entry.minValue(), entry.maxValue());
        };

        return new RewardGrant(
                entry.kind(),
                tier,
                entry.item(),
                round(amount),
                entry.durationSeconds() > 0 ? entry.durationSeconds() : entry.amount(),
                Math.max(1, entry.kind() == RewardKind.ITEM ? 1 : 1),
                entry.tier());
    }

    private static double between(double min, double max) {
        if (max <= min) {
            return min;
        }
        return min + ThreadLocalRandom.current().nextDouble() * (max - min);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
