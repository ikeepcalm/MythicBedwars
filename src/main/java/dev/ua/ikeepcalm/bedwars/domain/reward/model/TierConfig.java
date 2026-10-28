package dev.ua.ikeepcalm.bedwars.domain.reward.model;

import lombok.Data;

import java.util.List;

@Data
public class TierConfig {
    public static final TierConfig EMPTY = new TierConfig(false, 0, List.of(), List.of());

    private final boolean enabled;
    private final int rolls;
    private final List<RewardEntry> guaranteed;
    private final List<RewardEntry> pool;
}
