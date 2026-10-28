package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import lombok.Data;

@Data
public class RejectPayload {

    private final String minigameServerId;
    private final String reason;
}
