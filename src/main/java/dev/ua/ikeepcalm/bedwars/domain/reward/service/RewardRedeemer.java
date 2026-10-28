package dev.ua.ikeepcalm.bedwars.domain.reward.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.CoiCapabilities;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.ClaimOutcome;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardKind;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
public class RewardRedeemer {

    private static final Comparator<RewardGrant> APPLY_ORDER =
            Comparator.comparingInt(grant -> grant.getKind().getApplyOrder());

    private final MythicBedwars plugin;
    private final RewardConfig config;
    private final RewardQueue queue;

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
            List<RewardBundle> claimed = claimPending(player);
            if (!claimed.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> applyClaimed(player, claimed));
            }
        });
    }

    private List<RewardBundle> claimPending(Player player) {
        List<RewardBundle> claimed = new ArrayList<>();
        for (int i = 0; i < config.maxBundlesPerJoin(); i++) {
            RewardBundle bundle = queue.poll(player.getUniqueId()).orElse(null);
            if (bundle == null) {
                break;
            }

            ClaimOutcome outcome = queue.claim(bundle);
            if (outcome == ClaimOutcome.UNAVAILABLE) {
                queue.returnToQueue(bundle);
                break;
            }
            if (outcome == ClaimOutcome.CLAIMED) {
                claimed.add(bundle);
            }
        }
        return claimed;
    }

    private void applyClaimed(Player player, List<RewardBundle> claimed) {
        List<RewardBundle> giveBack = new ArrayList<>();

        if (!player.isOnline()) {
            giveBack.addAll(claimed);
        } else {
            for (RewardBundle bundle : claimed) {
                try {
                    apply(player, bundle, giveBack);
                } catch (RuntimeException exception) {
                    plugin.log("Failed to apply rewards for {} ({}): {}",
                            player.getName(), bundle.getEventId(), String.valueOf(exception.getMessage()));
                    giveBack.add(bundle);
                }
            }
        }

        if (!giveBack.isEmpty()) {
            plugin.offMainThread(() -> giveBack.reversed().forEach(queue::returnToQueue));
        }
    }

    private void apply(Player player, RewardBundle bundle, List<RewardBundle> giveBack) {
        CircleOfImaginationAPI api = plugin.getCircleOfImaginationAPI();
        String pathway = primaryPathway(api, player);
        boolean beyonder = pathway != null;

        if (!beyonder && !config.substituteForNonBeyonders()) {
            giveBack.add(bundle);
            player.sendMessage(message(player, "magic.redeem.held_until_awakened"));
            return;
        }

        int needed = beyonder ? api.getActingRequiredForNextSequence(player, pathway) : 0;
        Redemption redemption = new Redemption(api, plugin.getCoiCapabilities(), player, pathway, needed);

        List<RewardGrant> ordered = new ArrayList<>(bundle.getGrants());
        ordered.sort(APPLY_ORDER);
        for (RewardGrant grant : ordered) {
            try {
                applyGrant(redemption, grant);
            } catch (RuntimeException exception) {
                // Never rethrow: grants before this one already landed, so a requeue would pay them twice.
                plugin.log("Skipped a {} reward for {} ({}): {}", grant.getKind(), player.getName(),
                        bundle.getEventId(), String.valueOf(exception.getMessage()));
            }
        }

        sendSummary(player, bundle, redemption);
        if (ordered.stream().anyMatch(RewardGrant::isEpic)) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
        }
    }

    private void sendSummary(Player player, RewardBundle bundle, Redemption redemption) {
        player.sendMessage(message(player, "magic.redeem.header"));
        player.sendMessage(message(player, "magic.redeem.context",
                "pathway", bundle.getEventPathway() == null ? "?" : bundle.getEventPathway(),
                "result", plugin.getLocaleManager().getMessage(player,
                        bundle.isWon() ? "magic.redeem.result_victory" : "magic.redeem.result_participation"),
                "ago", describeAge(bundle.getEarnedAtEpochMs())));

        if (!redemption.isBeyonder()) {
            player.sendMessage(message(player, "magic.redeem.not_beyonder"));
        }

        redemption.summary.forEach(player::sendMessage);
        giveItems(player, bundle, redemption.items, redemption.itemGrants);
        player.sendMessage(message(player, "magic.redeem.footer"));
    }

    private void applyGrant(Redemption r, RewardGrant grant) {
        if (grant.getKind() == RewardKind.ITEM) {
            buildItem(r.api, r.needed, grant).ifPresent(stack -> r.addItem(stack, grant));
            return;
        }
        if (!r.isBeyonder()) {
            substitute(r, grant);
            return;
        }

        switch (grant.getKind()) {
            case ACTING_PERCENT -> applyActing(r, grant.getAmount());
            case COOLDOWN_CREDIT -> {
                if (r.capabilities.isCooldownCredit()) {
                    applyCooldown(r, grant);
                } else {
                    applyActing(r, cooldownFallbackPercent(grant));
                }
            }
            case ACTING_SPEED -> applySpeed(r, grant);
            case ACTING_ITEM_MULT -> applyItemMultiplier(r, grant);
        }
    }

    private void applyActing(Redemption r, double percentOfBar) {
        Player player = r.player;
        if (r.needed <= 0) {
            r.summary.add(message(player, "magic.redeem.acting_not_applicable"));
            return;
        }

        int points = (int) Math.round(r.needed * percentOfBar / 100.0);
        if (points <= 0) {
            return;
        }

        int granted = r.api.grantActing(player, r.pathway, r.capabilities.getRewardSource(), points);
        if (granted <= 0) {
            r.summary.add(message(player, "magic.redeem.acting_capped"));
            return;
        }

        r.summary.add(message(player, "magic.redeem.acting_granted",
                "amount", number(granted),
                "percent", percent(granted * 100.0 / r.needed),
                "sequence", r.api.getLowestSequence(player)));
    }

    private void applyCooldown(Redemption r, RewardGrant grant) {
        Player player = r.player;
        String methodId = r.api.getActingMethodId(player, r.pathway);
        if (methodId == null) {
            r.summary.add(message(player, "magic.redeem.acting_not_applicable"));
            return;
        }

        long seconds = Math.round(r.api.getActingMethodCooldownSeconds(methodId) * grant.getAmount() / 100.0);
        if (seconds <= 0) {
            return;
        }

        long credited = r.api.creditActingCooldown(player, methodId, seconds);
        if (credited <= 0) {
            r.summary.add(message(player, "magic.redeem.cooldown_ready"));
            applyActing(r, cooldownFallbackPercent(grant));
            return;
        }

        long remaining = r.api.getActingCooldownRemaining(player.getUniqueId(), methodId);
        r.summary.add(message(player, "magic.redeem.cooldown_credited",
                "credited", formatDuration((int) credited),
                "remaining", formatDuration((int) Math.max(0, remaining))));
    }

    private void applySpeed(Redemption r, RewardGrant grant) {
        if (r.api.getActingSpeedMultiplier(r.player) >= grant.getAmount()) {
            r.summary.add(message(r.player, "magic.redeem.buff_kept"));
            return;
        }

        r.api.setActingSpeedMultiplier(r.player, grant.getAmount(), grant.getIntArg() * 1000L);
        r.summary.add(message(r.player, "magic.redeem.speed_applied",
                "percent", Math.round(grant.getAmount() * 100),
                "duration", formatDuration(grant.getIntArg())));
    }

    private void applyItemMultiplier(Redemption r, RewardGrant grant) {
        if (r.api.getActingItemMultiplier(r.player) >= grant.getAmount()) {
            r.summary.add(message(r.player, "magic.redeem.buff_kept"));
            return;
        }

        r.api.setActingItemMultiplier(r.player, grant.getAmount(), grant.getIntArg() * 1000L);
        r.summary.add(message(r.player, "magic.redeem.item_mult_applied",
                "multiplier", multiplier(grant.getAmount()),
                "duration", formatDuration(grant.getIntArg())));
    }

    private String primaryPathway(CircleOfImaginationAPI api, Player player) {
        if (!api.isBeyonder(player)) {
            return null;
        }
        List<PathwayData> pathways = api.getPathwayData(player);
        return pathways.isEmpty() ? null : pathways.getFirst().name();
    }

    private void substitute(Redemption r, RewardGrant grant) {
        double share = switch (grant.getKind()) {
            case ACTING_PERCENT -> Math.max(1.0, grant.getAmount());
            case COOLDOWN_CREDIT -> Math.max(1.0, cooldownFallbackPercent(grant));
            default -> 1.0;
        };

        int acting = (int) Math.round(config.fallbackBottleActing() * share / 2.0);
        ItemStack bottle = r.api.createActingBottle(Math.max(1, acting));
        if (bottle != null) {
            r.addItem(bottle, grant);
        }
    }

    private double cooldownFallbackPercent(RewardGrant grant) {
        return Math.min(config.cooldownSubstitutePercent(), grant.getAmount() * config.cooldownSubstituteRatio());
    }

    private Optional<ItemStack> buildItem(CircleOfImaginationAPI api, int needed, RewardGrant grant) {
        RewardItemKind kind = grant.getItem();
        if (kind == null) {
            return Optional.empty();
        }

        String tier = grant.getStrArg() == null ? "small" : grant.getStrArg();
        int sequence = tokenCeiling(grant);
        int intArg = Math.max(1, grant.getIntArg());

        ItemStack stack = switch (kind) {
            case ACTING_BOTTLE -> api.createActingBottle(bottleActing(needed, grant));
            case SPIRITUALITY_POTION -> api.createSpiritualityPotion(tier, intArg);
            case ACTING_MULTIPLIER -> api.createActingMultiplier(tier, grant.getAmount(), intArg);
            case SPIRITUALITY_REGEN -> api.createSpiritualityRegenBooster(tier, grant.getAmount(), intArg);
            case ANTI_CONTROL_CHARM -> api.createAntiControlCharm();
            case RITUAL_BOOK -> api.createRitualBook(intArg);
            case INGREDIENT_TOKEN -> api.createIngredientToken();
            case RECIPE_TOKEN -> api.createRecipeToken(sequence);
            case POTION_TOKEN -> api.createPotionToken(sequence);
            case UNIVERSAL_RECIPE_TOKEN -> api.createUniversalRecipeToken(sequence);
            case UNIVERSAL_POTION_TOKEN -> api.createUniversalPotionToken(sequence);
            case PATHWAY_TRANSFER_TOKEN -> api.createPathwayTransferToken();
        };

        if (stack != null && grant.getCount() > 1) {
            stack.setAmount(Math.min(stack.getMaxStackSize(), grant.getCount()));
        }
        return Optional.ofNullable(stack);
    }

    private static int tokenCeiling(RewardGrant grant) {
        int rolled = grant.getMaxSequence();
        return !grant.isExchangeToken() || rolled < 1 || rolled > 9 ? 9 : rolled;
    }

    private int bottleActing(int needed, RewardGrant grant) {
        if (needed <= 0) {
            return Math.max(1, config.fallbackBottleActing());
        }
        return Math.max(1, (int) Math.round(needed * grant.getAmount() / 100.0));
    }

    private void giveItems(Player player, RewardBundle bundle, List<ItemStack> items, List<RewardGrant> itemGrants) {
        if (items.isEmpty()) {
            return;
        }

        ItemStack[] offered = items.stream().map(ItemStack::clone).toArray(ItemStack[]::new);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(offered);

        List<RewardGrant> unplaced = new ArrayList<>();
        List<ItemStack> unplacedStacks = new ArrayList<>();
        List<ItemStack> partial = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            ItemStack rejected = leftovers.get(i);
            if (rejected == null) {
                RewardGrant grant = itemGrants.get(i);
                player.sendMessage(message(player,
                        grant.isEpic() ? "magic.redeem.item_given_epic" : "magic.redeem.item_given",
                        "amount", items.get(i).getAmount(), "item", describeItem(items.get(i))));
            } else if (rejected.getAmount() >= items.get(i).getAmount()) {
                unplaced.add(itemGrants.get(i));
                unplacedStacks.add(rejected);
            } else {
                partial.add(rejected);
            }
        }

        handleOverflow(player, bundle, unplaced, unplacedStacks, partial);
    }

    private void handleOverflow(Player player, RewardBundle bundle, List<RewardGrant> unplaced,
                                List<ItemStack> unplacedStacks, List<ItemStack> partial) {
        partial.forEach(stack -> player.getWorld().dropItem(player.getLocation(), stack));

        if (config.requeueOverflow() && !unplaced.isEmpty()) {
            RewardBundle overflow = new RewardBundle(
                    RewardBundle.SCHEMA, bundle.getEventId() + ":overflow", bundle.getArena(),
                    bundle.getPlayerId(), bundle.getPlayerName(), bundle.getEventPathway(), bundle.isWon(),
                    bundle.getEarnedAtEpochMs(), List.copyOf(unplaced));
            plugin.offMainThread(() -> queue.returnToQueue(overflow));
            player.sendMessage(message(player, "magic.redeem.item_requeued", "count", unplaced.size()));
        } else if (!unplacedStacks.isEmpty()) {
            unplacedStacks.forEach(stack -> player.getWorld().dropItem(player.getLocation(), stack));
            player.sendMessage(message(player, "magic.redeem.item_dropped", "count", unplacedStacks.size()));
        }

        if (!partial.isEmpty()) {
            player.sendMessage(message(player, "magic.redeem.item_dropped", "count", partial.size()));
        }
    }

    private String describeItem(ItemStack stack) {
        return stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(stack.getItemMeta().displayName())
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

    @RequiredArgsConstructor
    private static class Redemption {
        private final CircleOfImaginationAPI api;
        private final CoiCapabilities capabilities;
        private final Player player;
        private final String pathway;
        private final int needed;
        private final List<Component> summary = new ArrayList<>();
        private final List<ItemStack> items = new ArrayList<>();
        private final List<RewardGrant> itemGrants = new ArrayList<>();

        boolean isBeyonder() {
            return pathway != null;
        }

        void addItem(ItemStack stack, RewardGrant grant) {
            items.add(stack);
            itemGrants.add(grant);
        }
    }
}
