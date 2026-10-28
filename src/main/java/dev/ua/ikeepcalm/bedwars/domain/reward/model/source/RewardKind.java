package dev.ua.ikeepcalm.bedwars.domain.reward.model.source;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RewardKind {
    ACTING_PERCENT(0),
    COOLDOWN_CREDIT(0),
    ACTING_SPEED(1),
    ACTING_ITEM_MULT(1),
    ITEM(2);

    private final int applyOrder;
}
