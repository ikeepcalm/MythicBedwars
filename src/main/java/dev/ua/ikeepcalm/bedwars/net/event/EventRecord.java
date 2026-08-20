package dev.ua.ikeepcalm.bedwars.net.event;

import dev.ua.ikeepcalm.bedwars.net.protocol.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.EventState;

import java.util.HashMap;
import java.util.Map;

/**
 * The durable description of one event, as stored in its Redis hash.
 *
 * <p>This — not any in-memory field and not the pub/sub stream — is the source of truth. Both sides
 * write their transition to the hash <em>before</em> announcing it, so a dropped message costs
 * latency rather than wedging the event.
 */
public record EventRecord(
        String eventId,
        EventState state,
        String smpServerId,
        String smpServerName,
        String minigameServerId,
        String minigameServerName,
        String arenaName,
        int minPlayers,
        int maxPlayers,
        long createdAt,
        long signupDeadline,
        CancelReason cancelReason
) {

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
        put(map, "eventId", eventId);
        put(map, "state", state == null ? null : state.name());
        put(map, "smpServerId", smpServerId);
        put(map, "smpServerName", smpServerName);
        put(map, "minigameServerId", minigameServerId);
        put(map, "minigameServerName", minigameServerName);
        put(map, "arenaName", arenaName);
        map.put("minPlayers", Integer.toString(minPlayers));
        map.put("maxPlayers", Integer.toString(maxPlayers));
        map.put("createdAt", Long.toString(createdAt));
        map.put("signupDeadline", Long.toString(signupDeadline));
        put(map, "cancelReason", cancelReason == null ? null : cancelReason.name());
        return map;
    }

    /**
     * @return the record, or {@code null} when the hash is absent or missing its identity fields —
     * which the caller should read as "there is no such event" rather than as an error
     */
    public static EventRecord fromMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }

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
                parseInt(map.get("minPlayers")),
                parseInt(map.get("maxPlayers")),
                parseLong(map.get("createdAt")),
                parseLong(map.get("signupDeadline")),
                parseEnum(CancelReason.class, map.get("cancelReason"))
        );
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static int parseInt(String raw) {
        try {
            return raw == null ? 0 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String raw) {
        try {
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            // A state written by a newer version is not one we can act on.
            return null;
        }
    }
}
