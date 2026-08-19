package dev.ua.ikeepcalm.bedwars.domain.reward;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.ActingSourceCategory;
import org.bukkit.entity.Player;

/**
 * What the Circle of Imagination jar actually loaded at runtime can do.
 *
 * <p>MythicBedwars compiles against the newest API but may well be dropped onto a server running an
 * older COI. Both additions this feature depends on — the {@code EVENT} acting source and the
 * acting-cooldown credit methods — are probed once here so the reward layer can substitute rather
 * than blow up with {@code AbstractMethodError} or {@code IllegalArgumentException} halfway through
 * granting somebody their prize.
 *
 * <p>Note the deliberate absence of a direct {@code ActingSourceCategory.EVENT} reference anywhere
 * else in the codebase: naming the constant in a field initialiser would fold it into the constant
 * pool and fail at class-load time against an older API jar. Everything goes through
 * {@link #rewardSource()}.
 */
public record CoiCapabilities(boolean cooldownCredit, ActingSourceCategory rewardSource) {

    /**
     * The bucket event rewards fall back to when COI predates the dedicated {@code EVENT} category.
     * Not ideal — it competes with duel bounties for the same headroom — but it is capped, which
     * {@code ADMIN} is not.
     */
    private static final ActingSourceCategory FALLBACK_SOURCE = ActingSourceCategory.PLAYER_INTERACTION;

    public static CoiCapabilities probe(CircleOfImaginationAPI api) {
        return new CoiCapabilities(probeCooldownCredit(api), probeRewardSource());
    }

    /**
     * @return {@code true} when the loaded API declares the cooldown-credit methods
     */
    private static boolean probeCooldownCredit(CircleOfImaginationAPI api) {
        try {
            // Probe the runtime-loaded interface, not the one we compiled against.
            api.getClass().getMethod("creditActingCooldown", Player.class, String.class, long.class);
            return true;
        } catch (NoSuchMethodException | LinkageError e) {
            return false;
        }
    }

    private static ActingSourceCategory probeRewardSource() {
        try {
            return ActingSourceCategory.valueOf("EVENT");
        } catch (IllegalArgumentException e) {
            return FALLBACK_SOURCE;
        }
    }

    /**
     * @return {@code true} when either capability is missing, so the caller can log a single
     * explanatory warning at startup instead of failing silently later
     */
    public boolean isDegraded() {
        return !cooldownCredit || rewardSource == FALLBACK_SOURCE;
    }

    public String describe() {
        return "cooldown-credit=" + (cooldownCredit ? "yes" : "no")
               + ", acting-source=" + rewardSource.id();
    }
}
