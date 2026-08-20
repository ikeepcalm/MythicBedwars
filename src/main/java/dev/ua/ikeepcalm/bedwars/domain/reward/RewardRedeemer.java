package dev.ua.ikeepcalm.bedwars.domain.reward;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardItemKind;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies queued rewards on the survival server, where the player's real Beyonder lives.
 *
 * <p>Order matters: acting, then buffs, then items. Items are last because a full inventory is the
 * one failure that has to be retried, and it must not block the half of the reward that always
 * succeeds.
 */
public class RewardRedeemer {

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
                if (!player.isOnline()) {
                    // Put them back untouched rather than losing them to a badly timed logout.
                    for (RewardBundle bundle : claimed) {
                        queue.returnToQueue(player.getUniqueId(), bundle);
                        queue.releaseClaim(player.getUniqueId(), bundle.eventId());
                    }
                    return;
                }

                claimed.forEach(bundle -> apply(player, bundle));
            });
        });
    }

    private void apply(Player player, RewardBundle bundle) {
        CircleOfImaginationAPI api = plugin.getCircleOfImaginationAPI();
        CoiCapabilities capabilities = plugin.getCoiCapabilities();

        String pathway = primaryPathway(api, player);
        boolean beyonder = pathway != null;

        List<Component> summary = new ArrayList<>();
        List<ItemStack> items = new ArrayList<>();

        for (RewardGrant grant : bundle.grants()) {
            switch (grant.kind()) {
                case ACTING_PERCENT -> {
                    if (beyonder) {
                        applyActing(api, capabilities, player, pathway, grant, summary);
                    } else {
                        substitute(api, items);
                    }
                }
                case COOLDOWN_CREDIT -> {
                    if (beyonder && capabilities.cooldownCredit()) {
                        applyCooldown(api, player, pathway, grant, summary);
                    } else if (beyonder) {
                        // Older COI cannot credit cooldowns; pay the equivalent in acting instead.
                        applyActing(api, capabilities, player, pathway,
                                new RewardGrant(grant.kind(), grant.tier(), null,
                                        config.cooldownSubstitutePercent(), 0, 1, null),
                                summary);
                    } else {
                        substitute(api, items);
                    }
                }
                case ACTING_SPEED -> {
                    api.setActingSpeedMultiplier(player, grant.amount(),
                            System.currentTimeMillis() + grant.intArg() * 1000L);
                    summary.add(message("magic.redeem.speed_applied",
                            "percent", Math.round(grant.amount() * 100), "duration", formatDuration(grant.intArg())));
                }
                case ACTING_ITEM_MULT -> {
                    api.setActingItemMultiplier(player, grant.amount(),
                            System.currentTimeMillis() + grant.intArg() * 1000L);
                    summary.add(message("magic.redeem.item_mult_applied",
                            "multiplier", grant.amount(), "duration", formatDuration(grant.intArg())));
                }
                case ITEM -> buildItem(api, grant).ifPresent(items::add);
            }
        }

        player.sendMessage(message("magic.redeem.header"));
        player.sendMessage(message("magic.redeem.context",
                "pathway", bundle.eventPathway() == null ? "?" : bundle.eventPathway(),
                "result", plugin.getLocaleManager().getMessage(
                        bundle.won() ? "magic.redeem.result_victory" : "magic.redeem.result_participation"),
                "ago", describeAge(bundle.earnedAtEpochMs())));

        if (!beyonder && config.substituteForNonBeyonders()) {
            player.sendMessage(message("magic.redeem.not_beyonder"));
        }

        summary.forEach(player::sendMessage);
        giveItems(player, bundle, items);
        player.sendMessage(message("magic.redeem.footer"));
    }

    private void applyActing(CircleOfImaginationAPI api, CoiCapabilities capabilities, Player player,
                             String pathway, RewardGrant grant, List<Component> summary) {
        int needed = api.getActingRequiredForNextSequence(player, pathway);
        if (needed <= 0) {
            // Sequence 0, or an outer pathway that advances by sacrifice rather than acting.
            summary.add(message("magic.redeem.acting_not_applicable"));
            return;
        }

        int points = (int) Math.round(needed * grant.amount() / 100.0);
        if (points <= 0) {
            return;
        }

        int granted = api.grantActing(player, pathway, capabilities.rewardSource(), points);
        if (granted <= 0) {
            // Say so rather than dropping it silently - a reward that vanishes reads as a bug.
            summary.add(message("magic.redeem.acting_capped"));
            return;
        }

        summary.add(message("magic.redeem.acting_granted",
                "amount", granted,
                "percent", grant.amount(),
                "sequence", api.getLowestSequence(player)));
    }

    private void applyCooldown(CircleOfImaginationAPI api, Player player, String pathway,
                               RewardGrant grant, List<Component> summary) {
        String methodId = api.getActingMethodId(player, pathway);
        if (methodId == null) {
            return;
        }

        long total = api.getActingMethodCooldownSeconds(methodId);
        long seconds = Math.round(total * grant.amount() / 100.0);
        if (seconds <= 0) {
            return;
        }

        long credited = api.creditActingCooldown(player, methodId, seconds);
        if (credited <= 0) {
            summary.add(message("magic.redeem.cooldown_ready"));
            return;
        }

        long remaining = api.getActingCooldownRemaining(player.getUniqueId(), methodId);
        summary.add(message("magic.redeem.cooldown_credited",
                "credited", formatDuration((int) credited),
                "remaining", formatDuration((int) Math.max(0, remaining))));
    }

    /**
     * Turns a grant the player cannot receive yet into something they can keep until they can.
     */
    private void substitute(CircleOfImaginationAPI api, List<ItemStack> items) {
        if (!config.substituteForNonBeyonders()) {
            return;
        }

        ItemStack bottle = api.createActingBottle(config.fallbackBottleActing());
        if (bottle != null) {
            items.add(bottle);
        }
    }

    private java.util.Optional<ItemStack> buildItem(CircleOfImaginationAPI api, RewardGrant grant) {
        RewardItemKind kind = grant.item();
        if (kind == null) {
            return java.util.Optional.empty();
        }

        String tier = grant.strArg() == null ? "small" : grant.strArg();

        ItemStack stack = switch (kind) {
            case ACTING_BOTTLE -> api.createActingBottle(Math.max(1, (int) Math.round(grant.amount() * 100)));
            case SPIRITUALITY_POTION -> api.createSpiritualityPotion(tier, Math.max(1, grant.intArg()));
            case ACTING_MULTIPLIER -> api.createActingMultiplier(tier, grant.amount(), Math.max(1, grant.intArg()));
            case SPIRITUALITY_REGEN -> api.createSpiritualityRegenBooster(tier, grant.amount(), Math.max(1, grant.intArg()));
            case ANTI_CONTROL_CHARM -> api.createAntiControlCharm();
            case RITUAL_BOOK -> api.createRitualBook(Math.max(1, grant.intArg()));
            case INGREDIENT_TOKEN -> api.createIngredientToken();
            case RECIPE_TOKEN -> api.createRecipeToken(9);
            case POTION_TOKEN -> api.createPotionToken(9);
            case UNIVERSAL_RECIPE_TOKEN -> api.createUniversalRecipeToken(9);
            case UNIVERSAL_POTION_TOKEN -> api.createUniversalPotionToken(9);
            case PATHWAY_TRANSFER_TOKEN -> api.createPathwayTransferToken();
        };

        return java.util.Optional.ofNullable(stack);
    }

    private void giveItems(Player player, RewardBundle bundle, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(items.toArray(new ItemStack[0]));

        for (ItemStack given : items) {
            if (!leftovers.containsValue(given)) {
                player.sendMessage(message("magic.redeem.item_given",
                        "amount", given.getAmount(), "item", describeItem(given)));
            }
        }

        if (leftovers.isEmpty()) {
            return;
        }

        if (config.requeueOverflow()) {
            // Re-queue under a distinct id so the claim guard does not reject the retry.
            RewardBundle overflow = new RewardBundle(
                    RewardBundle.SCHEMA, bundle.eventId() + ":overflow", bundle.arena(),
                    bundle.playerId(), bundle.playerName(), bundle.eventPathway(), bundle.won(),
                    bundle.earnedAtEpochMs(), List.of());
            queue.returnToQueue(player.getUniqueId(), overflow);
            player.sendMessage(message("magic.redeem.item_overflow", "count", leftovers.size()));
        } else {
            leftovers.values().forEach(stack -> player.getWorld().dropItem(player.getLocation(), stack));
            player.sendMessage(message("magic.redeem.item_overflow", "count", leftovers.size()));
        }
    }

    private String primaryPathway(CircleOfImaginationAPI api, Player player) {
        if (!api.isBeyonder(player)) {
            return null;
        }

        List<PathwayData> pathways = api.getPathwayData(player);
        return pathways.isEmpty() ? null : pathways.getFirst().name();
    }

    private String describeItem(ItemStack stack) {
        return stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(stack.getItemMeta().displayName())
                : stack.getType().name().toLowerCase().replace('_', ' ');
    }

    private static String formatDuration(int seconds) {
        if (seconds >= 3600) {
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
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

    private Component message(String key, Object... args) {
        return plugin.getLocaleManager().formatMessage(key, args);
    }

}
