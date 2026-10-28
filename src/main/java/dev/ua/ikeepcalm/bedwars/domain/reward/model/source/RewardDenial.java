package dev.ua.ikeepcalm.bedwars.domain.reward.model.source;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RewardDenial {
    RAGE_QUIT("rage quit", "magic.rewards.denied_short"),
    TOO_SHORT("left too early", "magic.rewards.denied_short"),
    INACTIVE("did nothing", "magic.rewards.denied_inactive"),
    AFK("away for most of the match", "magic.rewards.denied_afk"),
    DAILY_CAP("hit the daily reward limit", "magic.rewards.denied_daily_cap");

    private final String reason;
    private final String messageKey;
}
