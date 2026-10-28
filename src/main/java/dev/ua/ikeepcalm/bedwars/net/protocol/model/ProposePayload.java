package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import lombok.Data;

@Data
public class ProposePayload {

    private final String smpServerId;
    private final String smpServerName;
    private final int minPlayers;
    private final int maxPlayers;
    private final int onlinePlayers;
}
