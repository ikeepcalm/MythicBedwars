package dev.ua.ikeepcalm.bedwars.net.protocol;

import com.google.gson.JsonObject;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;

/**
 * The wrapper every control-bus message travels in.
 *
 * <p>The typed payload stays an unparsed {@link JsonObject} so the envelope itself never has to know
 * about the individual message shapes — each handler deserialises {@link #data()} into whatever
 * record it expects.
 *
 * @param v        protocol version, so a rolling deploy can recognise and skip messages it predates
 * @param type     what this message is
 * @param msgId    unique per publish, used to drop the duplicates Redis pub/sub delivers on reconnect
 * @param eventId  the event this concerns, or {@code null} for messages that are not event-scoped
 * @param from     the {@code server-id} that published it
 * @param to       a specific {@code server-id}, or {@link #BROADCAST} for everyone on the channel
 * @param ts       publish time in epoch millis
 * @param data     the typed payload
 */
public record Envelope(
        int v,
        MessageType type,
        String msgId,
        String eventId,
        String from,
        String to,
        long ts,
        JsonObject data
) {

    /** Current protocol version. Bump only on an incompatible payload change. */
    public static final int VERSION = 1;

    /** {@link #to} value meaning "every subscriber on this channel". */
    public static final String BROADCAST = "*";

    /**
     * @return whether this message is addressed to {@code serverId}
     */
    public boolean isAddressedTo(String serverId) {
        return BROADCAST.equals(to) || to == null || to.equals(serverId);
    }
}
