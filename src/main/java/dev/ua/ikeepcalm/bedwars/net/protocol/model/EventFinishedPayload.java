package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import lombok.Data;

import java.util.List;

@Data
public class EventFinishedPayload {

    private final String arenaName;
    private final boolean tie;
    private final String winnerTeam;
    private final List<String> winners;
    private final List<String> losers;
    private final long durationMs;
}
