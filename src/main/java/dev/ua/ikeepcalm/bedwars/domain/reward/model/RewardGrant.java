package dev.ua.ikeepcalm.bedwars.domain.reward.model;

import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardTier;
import lombok.Data;

@Data
public class RewardGrant {
    private final RewardKind kind;
    private final RewardTier tier;
    private final RewardItemKind item;
    private final double amount;
    private final int intArg;
    private final int count;
    private final String strArg;
    private final int maxSequence;
    private final boolean epic;

    public boolean isExchangeToken() {
        return item != null && item.isExchangeToken();
    }
}
