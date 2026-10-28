package dev.ua.ikeepcalm.bedwars.net.protocol.model;

import com.google.gson.JsonObject;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import lombok.Data;

@Data
public class Envelope {

    public static final int VERSION = 1;
    public static final String BROADCAST = "*";

    private final int v;
    private final MessageType type;
    private final String msgId;
    private final String eventId;
    private final String from;
    private final String to;
    private final long ts;
    private final JsonObject data;

    public boolean isAddressedTo(String serverId) {
        return BROADCAST.equals(to) || serverId.equals(to);
    }
}
