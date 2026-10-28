package dev.ua.ikeepcalm.bedwars.net.protocol.source;

public enum ReturnOutcome {

    WIN,
    LOSE,
    TIE,
    QUIT,
    NO_SHOW,
    CANCELLED;

    public boolean isParticipation() {
        return this == WIN || this == LOSE || this == TIE;
    }
}
