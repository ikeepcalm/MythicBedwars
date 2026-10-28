package dev.ua.ikeepcalm.bedwars.domain.reward.model;

import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardKind;
import lombok.Data;

@Data
public class RewardEntry {
    private final RewardKind kind;
    private final int weight;
    private final RewardItemKind item;
    private final double minPercent;
    private final double maxPercent;
    private final double minValue;
    private final double maxValue;
    private final int durationSeconds;
    private final int amount;
    private final int count;
    private final boolean epic;
    private final String tier;
}
