package dev.ua.ikeepcalm.bedwars.net.event.model;

import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class EventRecord {

    private final String eventId;
    private final EventState state;
    private final String smpServerId;
    private final String smpServerName;
    private final String minigameServerId;
    private final String minigameServerName;
    private final String arenaName;
    private final int minPlayers;
    private final int maxPlayers;
    private final long createdAt;
    private final long signupDeadline;
    private final CancelReason cancelReason;

    public EventRecord withState(EventState newState) {
        return new EventRecord(eventId, newState, smpServerId, smpServerName, minigameServerId,
                minigameServerName, arenaName, minPlayers, maxPlayers, createdAt, signupDeadline, cancelReason);
    }

    public EventRecord accepted(String hostId, String hostName, String arena, int capacity, long deadline) {
        return new EventRecord(eventId, EventState.ACCEPTED, smpServerId, smpServerName, hostId,
                hostName, arena, minPlayers, Math.min(maxPlayers, capacity), createdAt, deadline, cancelReason);
    }

    public EventRecord cancelled(CancelReason reason) {
        return new EventRecord(eventId, EventState.CANCELLED, smpServerId, smpServerName, minigameServerId,
                minigameServerName, arenaName, minPlayers, maxPlayers, createdAt, signupDeadline, reason);
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        putIfPresent(map, "eventId", eventId);
        putIfPresent(map, "state", state == null ? null : state.name());
        putIfPresent(map, "smpServerId", smpServerId);
        putIfPresent(map, "smpServerName", smpServerName);
        putIfPresent(map, "minigameServerId", minigameServerId);
        putIfPresent(map, "minigameServerName", minigameServerName);
        putIfPresent(map, "arenaName", arenaName);
        map.put("minPlayers", Integer.toString(minPlayers));
        map.put("maxPlayers", Integer.toString(maxPlayers));
        map.put("createdAt", Long.toString(createdAt));
        map.put("signupDeadline", Long.toString(signupDeadline));
        putIfPresent(map, "cancelReason", cancelReason == null ? null : cancelReason.name());
        return map;
    }

    public static EventRecord fromMap(Map<String, String> map) {
        String eventId = map.get("eventId");
        EventState state = parseEnum(EventState.class, map.get("state"));
        if (eventId == null || state == null) {
            return null;
        }

        return new EventRecord(
                eventId,
                state,
                map.get("smpServerId"),
                map.get("smpServerName"),
                map.get("minigameServerId"),
                map.get("minigameServerName"),
                map.get("arenaName"),
                (int) parseLong(map.get("minPlayers")),
                (int) parseLong(map.get("maxPlayers")),
                parseLong(map.get("createdAt")),
                parseLong(map.get("signupDeadline")),
                parseEnum(CancelReason.class, map.get("cancelReason"))
        );
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static long parseLong(String raw) {
        try {
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String raw) {
        if (raw == null) {
            return null;
        }

        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException unknownToThisVersion) {
            return null;
        }
    }
}
