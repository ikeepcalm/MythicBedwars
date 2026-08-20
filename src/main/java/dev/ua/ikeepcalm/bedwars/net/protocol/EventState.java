package dev.ua.ikeepcalm.bedwars.net.protocol;

/**
 * Lifecycle of one cross-server event.
 *
 * <p>Stored in the event's Redis hash rather than only in memory: pub/sub can drop a message, but
 * the hash cannot, so both sides can always reconcile against it.
 */
public enum EventState {

    /** SMP has asked for a host and is waiting for an answer. */
    PROPOSED,

    /** A minigame server has claimed the event and reserved an arena. */
    ACCEPTED,

    /** The SMP is advertising it and taking signups. */
    ANNOUNCED,

    /** Signups are closed and the roster has been handed to the host. */
    SIGNUP_CLOSED,

    /** The arena is held open, waiting for recruits to arrive. */
    ARENA_READY,

    /** Players are being moved across the proxy. */
    TRANSFERRING,

    /** The match is in progress. */
    RUNNING,

    /** The match ended normally. */
    FINISHED,

    /** Terminal failure state; see the record's cancel reason. */
    CANCELLED;

    /**
     * @return whether no further transitions are expected, so the event's keys may be reaped
     */
    public boolean isTerminal() {
        return this == FINISHED || this == CANCELLED;
    }

    /**
     * @return whether the match has not started yet, and so can still be called off cleanly
     */
    public boolean isPreMatch() {
        return ordinal() < RUNNING.ordinal();
    }
}
