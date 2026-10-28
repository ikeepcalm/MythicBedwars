package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import lombok.Data;

@Data
public class EventStartedPayload {

    private final String arenaName;
    private final int playerCount;
}
