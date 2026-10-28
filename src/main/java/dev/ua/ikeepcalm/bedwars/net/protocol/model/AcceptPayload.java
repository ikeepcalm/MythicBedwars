package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import lombok.Data;

@Data
public class AcceptPayload {

    private final String minigameServerId;
    private final String minigameServerName;
    private final String arenaName;
    private final int arenaCapacity;
    private final long signupDeadline;
}
