package dev.ua.ikeepcalm.bedwars.domain.reward.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The reward vocabulary, grouped in one file because the pieces only make sense together.
 *
 * <p>The load-bearing rule for everything here: <b>a grant carries intent, never a resolved amount,
 * and never a pathway.</b>
 *
 * <p>Amounts are percentages because the Bedwars server cannot size them. There, a player is a
 * synthetic Sequence-9 Beyonder whose acting bar is one to two orders of magnitude smaller than a
 * real Sequence-4 player's — so a number computed on the minigame server would be worthless to
 * advanced players and identical for everybody. The SMP resolves the percentage against the
 * player's real bar at redemption.
 *
 * <p>Pathways are absent because {@code grantActing} silently returns zero for a pathway the player
 * does not hold. Targeting the event pathway would quietly pay nothing; everything resolves against
 * their real primary pathway instead.
 */
public final class RewardModel {

    private RewardModel() {
    }

    /**
     * What a single grant does.
     */
    public enum RewardKind {
        /**
         * Acting worth a percentage of the player's next sequence.
         */
        ACTING_PERCENT,
        /**
         * Weighted active-seconds shaved off the next acting method, as a percentage of it.
         */
        COOLDOWN_CREDIT,
        /**
         * Additive acting-speed buff for a duration.
         */
        ACTING_SPEED,
        /**
         * Multiplicative acting-gain buff for a duration.
         */
        ACTING_ITEM_MULT,
        /**
         * A Circle of Imagination item.
         */
        ITEM
    }

    /**
     * A closed set mapping onto the COI item factories, so a typo in YAML is a config error rather
     * than a way to reach arbitrary code.
     */
    public enum RewardItemKind {
        ACTING_BOTTLE,
        SPIRITUALITY_POTION,
        ACTING_MULTIPLIER,
        SPIRITUALITY_REGEN,
        ANTI_CONTROL_CHARM,
        RITUAL_BOOK,
        INGREDIENT_TOKEN,
        RECIPE_TOKEN,
        POTION_TOKEN,
        UNIVERSAL_RECIPE_TOKEN,
        UNIVERSAL_POTION_TOKEN,
        PATHWAY_TRANSFER_TOKEN
    }

    /**
     * Which pool a grant came from.
     */
    public enum RewardTier {
        PARTICIPATION,
        WINNER,
        MVP
    }

    /**
     * One rollable line from the config.
     *
     * @param weight relative likelihood within its pool; ignored for guaranteed entries
     */
    public record RewardEntry(
            RewardKind kind,
            int weight,
            @Nullable RewardItemKind item,
            double minPercent,
            double maxPercent,
            double minValue,
            double maxValue,
            int durationSeconds,
            int amount,
            @Nullable String tier
    ) {
    }

    /**
     * A rolled grant, ready to be serialised.
     *
     * @param amount percentage, buff strength, or multiplier — read according to {@code kind}
     * @param intArg duration in seconds, or an item's restore percentage / book tier
     * @param strArg an item's size tier ("small"/"medium"/"large")
     */
    public record RewardGrant(
            RewardKind kind,
            RewardTier tier,
            @Nullable RewardItemKind item,
            double amount,
            int intArg,
            int count,
            @Nullable String strArg
    ) {
    }

    /**
     * Everything owed to one player for one match.
     *
     * @param schema  bumped on any incompatible payload change; unknown values are quarantined
     *                rather than guessed at
     * @param eventId the idempotency key for both the emit guard and the apply guard
     */
    public record RewardBundle(
            int schema,
            String eventId,
            String arena,
            UUID playerId,
            String playerName,
            String eventPathway,
            boolean won,
            long earnedAtEpochMs,
            List<RewardGrant> grants
    ) {
        public static final int SCHEMA = 1;
    }
}
