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

        List<String> stale = new ArrayList<>();

        // Read the index rather than scanning: this Redis is shared, and a SCAN whose pattern
        // matches only a couple of keys still walks the whole keyspace to prove it.
        Set<String> members = client.smembers(keys.heartbeatIndex());
        if (members.isEmpty()) {
            // Either a fresh deployment or an index lost to a flush; fall back once so a peer that
            // is genuinely there is still found, and the next heartbeat rebuilds the index.
            members = new java.util.LinkedHashSet<>();
            for (String key : client.scan(keys.heartbeatPattern(), SCAN_LIMIT)) {
                String id = keys.serverIdFromHeartbeat(key);
                if (id != null) {
                    members.add(id);
                }
            }
        }

        List<Heartbeat> found = new ArrayList<>();
        List<String> ordered = new ArrayList<>(members);
        List<String> lookups = ordered.stream().map(keys::heartbeat).toList();

        if (!lookups.isEmpty()) {
            List<String> payloads = client.getAll(lookups);

            for (int i = 0; i < payloads.size() && i < ordered.size(); i++) {
                String raw = payloads.get(i);
                if (raw == null) {
                    // Its key expired, so it is gone; keep the index from growing without bound.
                    stale.add(ordered.get(i));
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

        stale.forEach(id -> client.srem(keys.heartbeatIndex(), id));

        found.sort(Comparator.comparing(Heartbeat::serverId));

        cached = List.copyOf(found);
        cachedAt = now;
        return cached;
    }

    public List<Heartbeat> aliveWithRole(NetworkRole role) {
        return alive().stream().filter(heartbeat -> heartbeat.role() == role).toList();
    }

    /**
     * Picks the Bedwars server most likely to be able to host.
     *
     * <p>Only a pre-flight: the chosen host still has to find a usable arena and accept, and if
     * several are up they race for the host claim regardless. The point is to avoid proposing to a
     * server that is visibly the wrong choice, and to have somewhere to hang the decision when more
     * hosts are added later.
     *
     * @param expected how many players the event hopes to seat
     * @return the best candidate, or empty when no Bedwars server is up
     */
    public java.util.Optional<Heartbeat> bestMinigameHost(int expected) {
        return aliveWithRole(NetworkRole.MINIGAME).stream()
                // Emptiest first: a server already busy with locals has fewer free arenas, and its
                // players are the ones an event would inconvenience.
                .min(Comparator.comparingInt(Heartbeat::onlinePlayers)
                        .thenComparing(Heartbeat::serverId));
    }

    /**
     * Drops the cache, so the next read reflects Redis immediately. Used by admin commands where a
     * three-second-stale answer would be confusing.
     */
    public void invalidate() {
        cachedAt = 0L;
    }
}
