package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import lombok.Data;

@Data
public class ArenaReadyPayload {

    private final String minigameServerId;
    private final String minigameServerName;
    private final String arenaName;
    private final long transferDeadline;
}
