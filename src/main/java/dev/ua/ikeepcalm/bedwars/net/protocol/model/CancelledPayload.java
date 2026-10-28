package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import lombok.Data;

@Data
public class CancelledPayload {

    private final CancelReason reason;
    private final String byServerId;
}
