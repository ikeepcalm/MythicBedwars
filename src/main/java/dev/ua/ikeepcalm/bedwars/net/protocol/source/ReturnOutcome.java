package dev.ua.ikeepcalm.bedwars.net.protocol.source;

/**
 * How a player's event ended, carried home with them.
 *
 * <p>Written to the event's pending-return hash before any transfer is attempted, so the SMP can
 * greet them correctly even if the message or the connection is lost on the way.
 */
public enum ReturnOutcome {

    /** On the winning team. */
    WIN,

    /** Played it out and lost. */
    LOSE,

    /** The match ended in a draw. */
    TIE,

    /** Left of their own accord while the match was live. */
    QUIT,

    /** Signed up but never reached the arena. */
    NO_SHOW,

    /** The event was called off before it produced a result. */
    CANCELLED;

    /**
     * @return whether this outcome represents having actually taken part, and so is worth a reward
     */
    public boolean isParticipation() {
        return this == WIN || this == LOSE || this == TIE;
    }
}
