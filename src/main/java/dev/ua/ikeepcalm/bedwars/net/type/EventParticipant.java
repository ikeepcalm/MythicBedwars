package dev.ua.ikeepcalm.bedwars.net.type;

import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;

import java.util.Set;

public interface EventParticipant {

    Set<String> localEventIds();

    void abandonLocally(String eventId, CancelReason reason);
}
