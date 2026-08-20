package dev.ua.ikeepcalm.bedwars.domain.reward;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardKind;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Applies queued rewards on the survival server, where the player's real Beyonder lives.
 *
 * <p>Order matters, and it is enforced by sorting rather than by trusting the order the grants
 * happen to arrive in: acting first, then buffs, then items. Acting must precede the buffs because
 * {@code addActing} reads the live acting-speed and item multipliers — applying a buff first would
 * silently inflate the very grant it was rolled alongside. Items are last because a full inventory
 * is the one failure that has to be retried, and it must not block the half of the reward that
 * always succeeds.
 */
public class RewardRedeemer {

    /**
     * Acting and cooldown resolve first, then buffs, then items. See the class note on why this is
     * not merely cosmetic.
     */
    private static final Comparator<RewardGrant> APPLY_ORDER = Comparator.comparingInt(grant ->
            switch (grant.kind()) {
                case ACTING_PERCENT, COOLDOWN_CREDIT -> 0;
                case ACTING_SPEED, ACTING_ITEM_MULT -> 1;
                case ITEM -> 2;
            });

    private final MythicBedwars plugin;
    private final RewardConfig config;
    private final RewardQueue queue;

    public RewardRedeemer(MythicBedwars plugin, RewardConfig config, RewardQueue queue) {
        this.plugin = plugin;
        this.config = config;
        this.queue = queue;
    }

    /**
     * Drains and applies whatever this player is owed.
     *
     * <p>Scheduled a little after login so Circle of Imagination has finished loading their Beyonder
     * — sizing an acting grant against a half-loaded profile would silently pay the wrong amount.
     */
    public void redeemOnJoin(Player player) {
        if (!config.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                drain(player);
            }
        }, config.redeemDelayTicks());
    }

    /**
     * @return the token's power ceiling, clamped into range. Older bundles predate the rolled value
     * and decode as {@code 0}, which would hand out an everything-token — treat that as weakest.
     */
    private static int tokenCeiling(RewardGrant grant) {
        if (!grant.isExchangeToken()) {
            return 9;
        }
        int rolled = grant.maxSequence();
        if (rolled < 1 || rolled > 9) {
            return 9;
        }
        return rolled;
    }

    private static RewardGrant withAmount(RewardGrant grant, double amount) {
        return new RewardGrant(grant.kind(), grant.tier(), null, amount, 0, 1, null, 9, grant.epic());
    }

    /**
     * Thousands-separated, because a five-digit acting grant is the payoff line of the whole feature
     * and "+13214" does not read as one.
     */
    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String multiplier(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String formatDuration(int seconds) {
        if (seconds >= 3600) {
            int hours = seconds / 3600;
            int minutes = (seconds % 3600) / 60;
            return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private void drain(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<RewardBundle> claimed = new ArrayList<>();

            for (int i = 0; i < config.maxBundlesPerJoin(); i++) {
                RewardBundle bundle = queue.poll(player.getUniqueId()).orElse(null);
                if (bundle == null) {
                    break;
                }

                // Somebody already applied this one; popping it was the cleanup.
                if (!queue.claim(player.getUniqueId(), bundle.eventId())) {
                    continue;
                }

                claimed.add(bundle);
            }

            if (claimed.isEmpty()) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                // Collected rather than pushed inline: returning a bundle is two Redis round
                // trips, and this block runs on the main thread.
                List<RewardBundle> giveBack = new ArrayList<>();

                if (!player.isOnline()) {
                    // Put them back untouched rather than losing them to a badly timed logout.
                    giveBack.addAll(claimed);
                } else {
                    for (RewardBundle bundle : claimed) {
                        try {
                            apply(player, bundle, giveBack);
                        } catch (RuntimeException exception) {
                            // Everything in `claimed` is already popped and claimed, so letting this
                            // propagate would destroy the bundles after it as well as this one.
                            plugin.log("Failed to apply rewards for {} ({}): {}",
                                    player.getName(), bundle.eventId(), String.valueOf(exception.getMessage()));
                            giveBack.add(bundle);
                        }
                    }
                }

                if (!giveBack.isEmpty()) {
                    requeue(player.getUniqueId(), giveBack);
                }
            });
        });
    }

    /**
     * Pushes bundles back, oldest last, so the queue order survives. Each push goes to the head of
     * the list, hence the reversal.
     */
    private void requeue(java.util.UUID playerId, List<RewardBundle> bundles) {
        plugin.offMainThread(() -> {
            for (RewardBundle bundle : bundles.reversed()) {
                queue.returnToQueue(playerId, bundle);
                queue.releaseClaim(playerId, bundle.eventId());
            }
        });
    }

    private void apply(Player player, RewardBundle bundle, List<RewardBundle> giveBack) {
        CircleOfImaginationAPI api = plugin.getCircleOfImaginationAPI();
        CoiCapabilities capabilities = plugin.getCoiCapabilities();

        String pathway = primaryPathway(api, player);
        boolean beyonder = pathway != null;

        // HOLD means exactly that: park the whole bundle until they have somewhere to put it,
        // rather than applying the item half and quietly binning the rest.
        if (!beyonder && !config.substituteForNonBeyonders()) {
            giveBack.add(bundle);
            player.sendMessage(message(player, "magic.redeem.held_until_awakened"));
            return;
        }

        int needed = beyonder ? api.getActingRequiredForNextSequence(player, pathway) : 0;

        List<Component> summary = new ArrayList<>();
        List<ItemStack> items = new ArrayList<>();
        List<RewardGrant> itemGrants = new ArrayList<>();

        List<RewardGrant> ordered = new ArrayList<>(bundle.grants());
        ordered.sort(APPLY_ORDER);

        for (RewardGrant grant : ordered) {
            switch (grant.kind()) {
                case ACTING_PERCENT -> {
                    if (beyonder) {
                        applyActing(api, capabilities, player, pathway, needed, grant, summary);
                    } else {
                        substitute(api, items, itemGrants, grant);
                    }
                }
                case COOLDOWN_CREDIT -> {
                    if (beyonder && capabilities.cooldownCredit()) {
                        applyCooldown(api, capabilities, player, pathway, needed, grant, summary);
                    } else if (beyonder) {
                        // Older COI cannot credit cooldowns; pay the equivalent in acting instead.
                        applyActing(api, capabilities, player, pathway, needed,
                                withAmount(grant, config.cooldownSubstitutePercent()), summary);
                    } else {
                        substitute(api, items, itemGrants, grant);
                    }
                }
                case ACTING_SPEED -> {
                    if (beyonder) {
                        applySpeed(api, player, grant, summary);
                    } else {
                        substitute(api, items, itemGrants, grant);
                    }
                }
                case ACTING_ITEM_MULT -> {
                    if (beyonder) {
                        applyItemMultiplier(api, player, grant, summary);
                    } else {
                        substitute(api, items, itemGrants, grant);
                    }
                }
                case ITEM -> buildItem(api, player, needed, grant).ifPresent(stack -> {
                    items.add(stack);
                    itemGrants.add(grant);
                });
            }
        }

        player.sendMessage(message(player, "magic.redeem.header"));
        player.sendMessage(message(player, "magic.redeem.context",
                "pathway", bundle.eventPathway() == null ? "?" : bundle.eventPathway(),
                "result", plugin.getLocaleManager().getMessage(player,
                        bundle.won() ? "magic.redeem.result_victory" : "magic.redeem.result_participation"),
                "ago", describeAge(bundle.earnedAtEpochMs())));

        if (!beyonder) {
            player.sendMessage(message(player, "magic.redeem.not_beyonder"));
        }

        summary.forEach(player::sendMessage);
        giveItems(player, bundle, items, itemGrants);
        player.sendMessage(message(player, "magic.redeem.footer"));

        if (ordered.stream().anyMatch(RewardGrant::epic)) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
        }
    }

    private void applyActing(CircleOfImaginationAPI api, CoiCapabilities capabilities, Player player,
                             String pathway, int needed, RewardGrant grant, List<Component> summary) {
        if (needed <= 0) {
            // Sequence 0, or an outer pathway that advances by sacrifice rather than acting.
            summary.add(message(player, "magic.redeem.acting_not_applicable"));
            return;
        }

        int points = (int) Math.round(needed * grant.amount() / 100.0);
        if (points <= 0) {
            return;
        }

        int granted = api.grantActing(player, pathway, capabilities.rewardSource(), points);
        if (granted <= 0) {
            // Say so rather than dropping it silently - a reward that vanishes reads as a bug.
            summary.add(message(player, "magic.redeem.acting_capped"));
            return;
        }

        // Report what actually landed, not what was rolled. COI scales a grant by the player's
        // sequence on the way in, so the rolled percentage overstates it at low sequences and
        // understates it at high ones.
        summary.add(message(player, "magic.redeem.acting_granted",
                "amount", number(granted),
                "percent", percent(granted * 100.0 / needed),
                "sequence", api.getLowestSequence(player)));
    }

    private void applyCooldown(CircleOfImaginationAPI api, CoiCapabilities capabilities, Player player,
                               String pathway, int needed, RewardGrant grant, List<Component> summary) {
        String methodId = api.getActingMethodId(player, pathway);
        if (methodId == null) {
            summary.add(message(player, "magic.redeem.acting_not_applicable"));
            return;
        }

        long total = api.getActingMethodCooldownSeconds(methodId);
        long seconds = Math.round(total * grant.amount() / 100.0);
        if (seconds <= 0) {
            return;
        }

        long credited = api.creditActingCooldown(player, methodId, seconds);
        if (credited <= 0) {
            // Their method was already off cooldown, so there was nothing to shave. Paying the
            // acting equivalent instead keeps the largest slice of the loot table from being a
            // coin-flip no-op for anyone who logged off at a natural stopping point.
            summary.add(message(player, "magic.redeem.cooldown_ready"));
            applyActing(api, capabilities, player, pathway, needed,
                    withAmount(grant, config.cooldownSubstitutePercent()), summary);
            return;
        }

        long remaining = api.getActingCooldownRemaining(player.getUniqueId(), methodId);
        summary.add(message(player, "magic.redeem.cooldown_credited",
                "credited", formatDuration((int) credited),
                "remaining", formatDuration((int) Math.max(0, remaining))));
    }

    /**
     * Both multiplier setters take a <b>duration</b>; Circle of Imagination adds "now" itself. Passing
     * an absolute timestamp here would set an expiry decades out and make the buff permanent.
     */
    private void applySpeed(CircleOfImaginationAPI api, Player player, RewardGrant grant,
                            List<Component> summary) {
        if (api.getActingSpeedMultiplier(player) >= grant.amount()) {
            // They are already running a stronger buff; overwriting would be a downgrade.
            summary.add(message(player, "magic.redeem.buff_kept"));
            return;
        }

        api.setActingSpeedMultiplier(player, grant.amount(), grant.intArg() * 1000L);
        summary.add(message(player, "magic.redeem.speed_applied",
                "percent", Math.round(grant.amount() * 100),
                "duration", formatDuration(grant.intArg())));
    }

    private void applyItemMultiplier(CircleOfImaginationAPI api, Player player, RewardGrant grant,
                                     List<Component> summary) {
        if (api.getActingItemMultiplier(player) >= grant.amount()) {
            summary.add(message(player, "magic.redeem.buff_kept"));
            return;
        }

        api.setActingItemMultiplier(player, grant.amount(), grant.intArg() * 1000L);
        summary.add(message(player, "magic.redeem.item_mult_applied",
                "multiplier", multiplier(grant.amount()),
                "duration", formatDuration(grant.intArg())));
    }

    private String primaryPathway(CircleOfImaginationAPI api, Player player) {
        if (!api.isBeyonder(player)) {
            return null;
        }

        List<PathwayData> pathways = api.getPathwayData(player);
        return pathways.isEmpty() ? null : pathways.getFirst().name();
    }

    /**
     * Turns a grant the player cannot receive yet into something they can keep until they can.
     *
     * <p>Sized from the grant so a headline winner grant does not collapse into the same consolation
     * bottle as a minimal participation one.
     */
    private void substitute(CircleOfImaginationAPI api, List<ItemStack> items,
                            List<RewardGrant> itemGrants, RewardGrant grant) {
        double share = grant.kind() == RewardKind.ACTING_PERCENT || grant.kind() == RewardKind.COOLDOWN_CREDIT
                ? Math.max(1.0, grant.amount())
                : 1.0;

        int acting = (int) Math.round(config.fallbackBottleActing() * share / 2.0);
        ItemStack bottle = api.createActingBottle(Math.max(1, acting));
        if (bottle != null) {
            items.add(bottle);
            itemGrants.add(grant);
        }
    }

    private Optional<ItemStack> buildItem(CircleOfImaginationAPI api, Player player, int needed,
                                          RewardGrant grant) {
        RewardItemKind kind = grant.item();
        if (kind == null) {
            return Optional.empty();
        }

        String tier = grant.strArg() == null ? "small" : grant.strArg();
        int sequence = tokenCeiling(grant);

        ItemStack stack = switch (kind) {
            // The configured value is a percentage like every other amount in the file; resolve it
            // against the player's real bar here. Treating it as a raw point count would make one
            // bottle worth 10% of a Sequence-9 bar and 0.25% of a Sequence-3 one.
            case ACTING_BOTTLE -> api.createActingBottle(bottleActing(needed, grant));
            case SPIRITUALITY_POTION -> api.createSpiritualityPotion(tier, Math.max(1, grant.intArg()));
            case ACTING_MULTIPLIER -> api.createActingMultiplier(tier, grant.amount(), Math.max(1, grant.intArg()));
            case SPIRITUALITY_REGEN -> api.createSpiritualityRegenBooster(tier, grant.amount(), Math.max(1, grant.intArg()));
            case ANTI_CONTROL_CHARM -> api.createAntiControlCharm();
            case RITUAL_BOOK -> api.createRitualBook(Math.max(1, grant.intArg()));
            case INGREDIENT_TOKEN -> api.createIngredientToken();
            case RECIPE_TOKEN -> api.createRecipeToken(sequence);
            case POTION_TOKEN -> api.createPotionToken(sequence);
            case UNIVERSAL_RECIPE_TOKEN -> api.createUniversalRecipeToken(sequence);
            case UNIVERSAL_POTION_TOKEN -> api.createUniversalPotionToken(sequence);
            case PATHWAY_TRANSFER_TOKEN -> api.createPathwayTransferToken();
        };

        if (stack != null && grant.count() > 1) {
            stack.setAmount(Math.min(stack.getMaxStackSize(), grant.count()));
        }

        return Optional.ofNullable(stack);
    }

    /**
     * @return the acting a rewarded bottle should hold, as a share of the player's real next
     * sequence, falling back to the configured flat amount when there is no bar to measure
     */
    private int bottleActing(int needed, RewardGrant grant) {
        if (needed <= 0) {
            return Math.max(1, config.fallbackBottleActing());
        }
        return Math.max(1, (int) Math.round(needed * grant.amount() / 100.0));
    }

    private void giveItems(Player player, RewardBundle bundle, List<ItemStack> items,
                           List<RewardGrant> itemGrants) {
        if (items.isEmpty()) {
            return;
        }

        // addItem consumes the array in place, so keep an untouched copy to reason about later.
        ItemStack[] offered = new ItemStack[items.size()];
        for (int i = 0; i < items.size(); i++) {
            offered[i] = items.get(i).clone();
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(offered);

        List<RewardGrant> unplaced = new ArrayList<>();
        List<ItemStack> partial = new ArrayList<>();
        // By index: RewardGrant is a record, so two identical grants are equal, and matching by
        // value could drop the same leftover stack twice.
        List<Integer> unplacedIndexes = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            ItemStack rejected = leftovers.get(i);
            if (rejected != null) {
                if (rejected.getAmount() >= items.get(i).getAmount()) {
                    unplaced.add(itemGrants.get(i));
                    unplacedIndexes.add(i);
                } else {
                    // Part of the stack went in. Re-queuing the grant would hand over the whole
                    // thing again, so the remainder goes on the floor instead.
                    partial.add(rejected);
                }
                continue;
            }

            RewardGrant grant = itemGrants.get(i);
            player.sendMessage(message(player,
                    grant.epic() ? "magic.redeem.item_given_epic" : "magic.redeem.item_given",
                    "amount", items.get(i).getAmount(), "item", describeItem(items.get(i))));
        }

        if (leftovers.isEmpty()) {
            return;
        }

        // Whatever could only be partially placed is dropped regardless of the overflow policy.
        partial.forEach(stack -> player.getWorld().dropItem(player.getLocation(), stack));

        if (config.requeueOverflow() && !unplaced.isEmpty()) {
            // Re-queue under a distinct id so the claim guard does not reject the retry, and carry
            // the grants across - an overflow bundle with no grants would silently destroy them.
            RewardBundle overflow = new RewardBundle(
                    RewardBundle.SCHEMA, bundle.eventId() + ":overflow", bundle.arena(),
                    bundle.playerId(), bundle.playerName(), bundle.eventPathway(), bundle.won(),
                    bundle.earnedAtEpochMs(), List.copyOf(unplaced));
            plugin.offMainThread(() -> queue.returnToQueue(player.getUniqueId(), overflow));
            player.sendMessage(message(player, "magic.redeem.item_requeued", "count", unplaced.size()));
        } else if (!unplacedIndexes.isEmpty()) {
            for (int index : unplacedIndexes) {
                player.getWorld().dropItem(player.getLocation(), leftovers.get(index));
            }
            player.sendMessage(message(player, "magic.redeem.item_dropped", "count", unplacedIndexes.size()));
        }

        if (!partial.isEmpty()) {
            player.sendMessage(message(player, "magic.redeem.item_dropped", "count", partial.size()));
        }
    }

    private String describeItem(ItemStack stack) {
        return stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(stack.getItemMeta().displayName())
                : stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String describeAge(long earnedAt) {
        long minutes = Math.max(0, (System.currentTimeMillis() - earnedAt) / 60_000);
        if (minutes < 60) {
            return minutes + "m ago";
        }
        if (minutes < 1440) {
            return (minutes / 60) + "h ago";
        }
        return (minutes / 1440) + "d ago";
    }

    private Component message(Player player, String key, Object... args) {
        return plugin.getLocaleManager().formatMessage(player, key, args);
    }

}
