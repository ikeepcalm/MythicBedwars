package dev.ua.ikeepcalm.bedwars.net.protocol.source;

import java.util.Locale;

public enum CancelReason {

    TOO_FEW_SIGNUPS,
    TIMEOUT_SIGNUP,
    TOO_FEW_ARRIVALS,
    ARENA_LOST,
    HOST_GONE,
    PROPOSER_GONE,
    HOST_RESTARTED,
    NO_HOST,
    ADMIN,
    REDIS_DEGRADED;

    public String localeKey() {
        return "magic.event.cancelled." + name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
