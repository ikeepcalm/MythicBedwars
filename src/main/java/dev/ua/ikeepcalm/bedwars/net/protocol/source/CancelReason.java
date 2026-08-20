package dev.ua.ikeepcalm.bedwars.net.protocol.source;

import java.util.Locale;

/**
 * Why an event ended before it produced a match.
 *
 * <p>Carried on {@link MessageType#EVENT_CANCELLED} and persisted on the event record, so the side
 * that did not make the decision can still explain it to players.
 */
public enum CancelReason {

    /** Nobody, or not enough people, signed up before the window closed. */
    TOO_FEW_SIGNUPS,

    /** The signup window expired with the event stuck mid-flight. */
    TIMEOUT_SIGNUP,

    /** Enough signed up, but too few actually reached the arena. */
    TOO_FEW_ARRIVALS,

    /** The reserved arena stopped being usable before the match could start. */
    ARENA_LOST,

    /** The hosting Bedwars server stopped heartbeating. */
    HOST_GONE,

    /** The SMP that proposed the event stopped heartbeating. */
    PROPOSER_GONE,

    /** The host restarted; arena state cannot be recovered, so the event is abandoned. */
    HOST_RESTARTED,

    /** No Bedwars server was available to host. */
    NO_HOST,

    /** Called off by an administrator. */
    ADMIN,

    /** Redis became unusable for long enough that continuing was unsafe. */
    REDIS_DEGRADED;

    /**
     * @return a lower-case, human-facing form for messages and logs
     */
    public String display() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
