package dev.ua.ikeepcalm.bedwars.domain.reward.service;

import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardEntry;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.TierConfig;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardTier;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
public class RewardRoller {

    private final RewardConfig config;

    public List<RewardGrant> roll(RewardTier tier, double scale) {
        TierConfig tierConfig = config.tier(tier);
        if (!tierConfig.isEnabled()) {
            return List.of();
        }

        List<RewardGrant> grants = new ArrayList<>();
        for (RewardEntry entry : tierConfig.getGuaranteed()) {
            grants.add(materialise(entry, tier, scale));
        }

        List<RewardEntry> remaining = new ArrayList<>(tierConfig.getPool());
        for (int i = 0; i < tierConfig.getRolls() && !remaining.isEmpty(); i++) {
            RewardEntry picked = pickWeighted(remaining);
            remaining.remove(picked);
            grants.add(materialise(picked, tier, scale));
        }
        return grants;
    }

    private RewardEntry pickWeighted(List<RewardEntry> pool) {
        int total = pool.stream().mapToInt(entry -> Math.max(0, entry.getWeight())).sum();
        if (total <= 0) {
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }

        int target = ThreadLocalRandom.current().nextInt(total);
        for (RewardEntry entry : pool) {
            target -= Math.max(0, entry.getWeight());
            if (target < 0) {
                return entry;
            }
        }
        return pool.getLast();
    }

    private RewardGrant materialise(RewardEntry entry, RewardTier tier, double scale) {
        double amount = switch (entry.getKind()) {
            case ACTING_PERCENT, COOLDOWN_CREDIT -> between(entry.getMinPercent(), entry.getMaxPercent()) * scale;
            case ACTING_SPEED, ACTING_ITEM_MULT -> between(entry.getMinValue(), entry.getMaxValue());
            case ITEM -> entry.getMinPercent() > 0 || entry.getMaxPercent() > 0
                    ? between(entry.getMinPercent(), entry.getMaxPercent())
                    : between(entry.getMinValue(), entry.getMaxValue());
        };

        return new RewardGrant(
                entry.getKind(),
                tier,
                entry.getItem(),
                round(amount),
                entry.getDurationSeconds() > 0 ? entry.getDurationSeconds() : entry.getAmount(),
                entry.getCount(),
                entry.getTier(),
                rollTokenSequence(entry.getItem()),
                entry.isEpic());
    }

    private int rollTokenSequence(RewardItemKind item) {
        if (item == null || !item.isExchangeToken()) {
            return 9;
        }

        Map<Integer, Double> weights = config.tokenSequenceWeights();
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            return 9;
        }

        double target = ThreadLocalRandom.current().nextDouble() * total;
        for (Map.Entry<Integer, Double> candidate : weights.entrySet()) {
            target -= candidate.getValue();
            if (target < 0) {
                return candidate.getKey();
            }
        }
        return 9;
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
