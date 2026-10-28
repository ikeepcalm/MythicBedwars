package dev.ua.ikeepcalm.bedwars.net.health.service;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.Heartbeat;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
public class ServerRegistry {

    private static final long CACHE_MILLIS = 3_000L;
    private static final int SCAN_LIMIT = 256;

    private final RedisClient client;
    private final RedisKeys keys;
    private final long staleAfterMillis;
    private final Gson gson = new Gson();

    private volatile List<Heartbeat> cached = List.of();
    private volatile long cachedAt;

    public List<Heartbeat> alive() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MILLIS) {
            return cached;
        }

        List<String> ids = new ArrayList<>(knownServerIds());
        List<String> payloads = client.getAll(ids.stream().map(keys::heartbeat).toList());
        List<Heartbeat> found = new ArrayList<>();

        for (int i = 0; i < payloads.size() && i < ids.size(); i++) {
            String raw = payloads.get(i);
            if (raw == null) {
                client.srem(keys.heartbeatIndex(), ids.get(i));
                continue;
            }

            Heartbeat heartbeat = parse(raw);
            if (heartbeat != null && heartbeat.getServerId() != null && heartbeat.isFresh(now, staleAfterMillis)) {
                found.add(heartbeat);
            }
        }

        found.sort(Comparator.comparing(Heartbeat::getServerId));
        cached = List.copyOf(found);
        cachedAt = now;
        return cached;
    }

    private Set<String> knownServerIds() {
        Set<String> members = client.smembers(keys.heartbeatIndex());
        if (!members.isEmpty()) {
            return members;
        }

        Set<String> scanned = new LinkedHashSet<>();
        for (String key : client.scan(keys.heartbeatPattern(), SCAN_LIMIT)) {
            String id = keys.serverIdFromHeartbeat(key);
            if (id != null) {
                scanned.add(id);
            }
        }
        return scanned;
    }

    private Heartbeat parse(String raw) {
        try {
            return gson.fromJson(raw, Heartbeat.class);
        } catch (JsonSyntaxException incompatibleVersion) {
            return null;
        }
    }

    public List<Heartbeat> aliveWithRole(NetworkRole role) {
        return alive().stream().filter(heartbeat -> heartbeat.getRole() == role).toList();
    }

    public Optional<Heartbeat> bestMinigameHost() {
        return aliveWithRole(NetworkRole.MINIGAME).stream()
                .min(Comparator.comparingInt(Heartbeat::getOnlinePlayers).thenComparing(Heartbeat::getServerId));
    }

    public void invalidate() {
        cachedAt = 0L;
    }
}
