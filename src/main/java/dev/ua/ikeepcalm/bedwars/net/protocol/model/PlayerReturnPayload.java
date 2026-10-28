package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import lombok.Data;

@Data
public class PlayerReturnPayload {

    private final String uuid;
    private final ReturnOutcome outcome;
}
