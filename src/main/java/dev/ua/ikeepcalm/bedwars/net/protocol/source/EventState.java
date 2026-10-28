package dev.ua.ikeepcalm.bedwars.net.protocol.source;

public enum EventState {

    PROPOSED,
    ACCEPTED,
    ANNOUNCED,
    SIGNUP_CLOSED,
    ARENA_READY,
    TRANSFERRING,
    RUNNING,
    FINISHED,
    CANCELLED;

    public boolean isTerminal() {
        return this == FINISHED || this == CANCELLED;
    }

    public boolean isPreMatch() {
        return ordinal() < RUNNING.ordinal();
    }
}
