package dev.ua.ikeepcalm.bedwars.net.health;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.protocol.Heartbeat;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Who else is on the network right now.
 *
 * <p>Backed entirely by the short-TTL heartbeat keys, so a server that dies simply stops appearing —
 * there is no membership list to keep consistent.
 */
public class ServerRegistry {

    /**
     * Results are cached briefly: this is consulted on a timer and per admin command, and a SCAN
     * plus MGET on every call would be wasteful for data that only changes every few seconds.
     */
    private static final long CACHE_MILLIS = 3_000L;

    private static final int SCAN_LIMIT = 256;

    private final RedisClient client;
    private final RedisKeys keys;
    private final long staleAfterMillis;
    private final Gson gson = new Gson();

    private volatile List<Heartbeat> cached = List.of();
    private volatile long cachedAt;

    public ServerRegistry(RedisClient client, RedisKeys keys, long staleAfterMillis) {
        this.client = client;
        this.keys = keys;
        this.staleAfterMillis = staleAfterMillis;
    }

    /**
     * Every instance whose heartbeat is present and recent, sorted by server id for stable output.
     *
     * <p>Performs Redis I/O on a cache miss — call it off the main thread where practical.
     */
    public List<Heartbeat> alive() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MILLIS) {
            return cached;
        }

        Set<String> heartbeatKeys = client.scan(keys.heartbeatPattern(), SCAN_LIMIT);
        List<Heartbeat> found = new ArrayList<>();

        if (!heartbeatKeys.isEmpty()) {
            for (String raw : client.getAll(heartbeatKeys)) {
                if (raw == null) {
                    continue;
                }

                try {
                    Heartbeat heartbeat = gson.fromJson(raw, Heartbeat.class);
                    if (heartbeat != null && heartbeat.serverId() != null && heartbeat.isFresh(now, staleAfterMillis)) {
                        found.add(heartbeat);
                    }
                } catch (JsonSyntaxException ignored) {
                    // A heartbeat written by an incompatible version is just not a peer we can use.
                }
            }
        }

        found.sort(Comparator.comparing(Heartbeat::serverId));

        cached = List.copyOf(found);
        cachedAt = now;
        return cached;
    }

    public List<Heartbeat> aliveWithRole(NetworkRole role) {
        return alive().stream().filter(heartbeat -> heartbeat.role() == role).toList();
    }

    /**
     * @return whether at least one Bedwars server is up and could be asked to host
     */
    public boolean hasLiveMinigameServer() {
        return !aliveWithRole(NetworkRole.MINIGAME).isEmpty();
    }

    /**
     * Drops the cache, so the next read reflects Redis immediately. Used by admin commands where a
     * three-second-stale answer would be confusing.
     */
    public void invalidate() {
        cachedAt = 0L;
    }
}
