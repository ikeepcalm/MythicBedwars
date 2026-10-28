package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import lombok.Data;

@Data
public class PlayerArrivedPayload {

    private final String uuid;
    private final int arrived;
    private final int expected;
}
