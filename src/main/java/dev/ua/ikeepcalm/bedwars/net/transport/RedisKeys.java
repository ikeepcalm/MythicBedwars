package dev.ua.ikeepcalm.bedwars.net.transport;

import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Locale;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class RedisKeys {

    private final String namespace;

    public String channel(NetworkRole consumer) {
        return key("ch", consumer.name().toLowerCase(Locale.ROOT));
    }

    public String heartbeat(String serverId) {
        return key("hb", serverId);
    }

    public String heartbeatIndex() {
        // Not key("hb", "index"): a server with id "index" would collide and MGET would hit WRONGTYPE.
        return key("hbindex");
    }

    public String serverIdFromHeartbeat(String heartbeatKey) {
        String prefix = key("hb", "");
        if (heartbeatKey == null || !heartbeatKey.startsWith(prefix)) {
            return null;
        }

        String id = heartbeatKey.substring(prefix.length());
        return id.isEmpty() || id.equals("index") ? null : id;
    }

    public String heartbeatPattern() {
        return key("hb", "*");
    }

    public String activeEvent() {
        return key("evt", "active");
    }

    public String event(String eventId) {
        return key("evt", eventId);
    }

    public String eventHost(String eventId) {
        return key("evt", eventId, "host");
    }

    public String eventRoster(String eventId) {
        return key("evt", eventId, "roster");
    }

    public String eventArrived(String eventId) {
        return key("evt", eventId, "arrived");
    }

    public String eventPendingReturn(String eventId) {
        return key("evt", eventId, "pending-return");
    }

    public String playerReturn(UUID playerId) {
        return key("ret", playerId.toString());
    }

    public String rewardsPending(UUID playerId) {
        return key("rewards", "pending", playerId.toString());
    }

    public String rewardsGranted(String eventId) {
        return key("rewards", "granted", eventId);
    }

    public String rewardsClaimed(UUID playerId, String eventId) {
        return key("rewards", "claimed", playerId.toString(), eventId);
    }

    public String rewardsDailyCount(UUID playerId, String day) {
        return key("rewards", "count", playerId.toString(), day);
    }

    public String rewardsDeadLetter() {
        return key("rewards", "dlq");
    }

    public String proposeLock() {
        return key("lock", "propose");
    }

    public String cooldown() {
        return key("cooldown");
    }

    public String scheduleWindow() {
        return key("schedule", "window");
    }

    private String key(String... parts) {
        return namespace + ":" + String.join(":", parts);
    }
}
