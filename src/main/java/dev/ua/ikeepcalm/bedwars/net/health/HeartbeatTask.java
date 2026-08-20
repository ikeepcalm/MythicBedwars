package dev.ua.ikeepcalm.bedwars.net.health;

import com.google.gson.Gson;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.protocol.Heartbeat;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Republishes this instance's liveness key on a timer.
 *
 * <p>Runs <b>synchronously</b> and then hands the finished payload to an async write: the player
 * count has to be read on the main thread, while the Redis round trip must not happen there.
 */
public class HeartbeatTask extends BukkitRunnable {

    private final MythicBedwars plugin;
    private final RedisClient client;
    private final RedisKeys keys;
    private final String serverId;
    private final String velocityServer;
    private final int ttlSeconds;
    private final Gson gson = new Gson();

    public HeartbeatTask(MythicBedwars plugin, RedisClient client, RedisKeys keys,
                         String serverId, String velocityServer, int ttlSeconds) {
        this.plugin = plugin;
        this.client = client;
        this.keys = keys;
        this.serverId = serverId;
        this.velocityServer = velocityServer;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void run() {
        Heartbeat heartbeat = new Heartbeat(
                serverId,
                plugin.getNetworkRole(),
                velocityServer,
                Bukkit.getOnlinePlayers().size(),
                plugin.getPluginMeta().getVersion(),
                System.currentTimeMillis()
        );

        String payload = gson.toJson(heartbeat);
        String key = keys.heartbeat(serverId);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // The command path cannot recover on its own - every call short-circuits while the
            // client considers itself disconnected - so the heartbeat is what ends an outage.
            client.probeIfDisconnected();

            client.setWithTtl(key, payload, ttlSeconds);
            // Long TTL: the index is a hint, and entries are pruned on read anyway.
            client.sadd(keys.heartbeatIndex(), serverId, 86_400);
        });
    }

    /**
     * Removes this instance's key immediately, so peers see it go rather than waiting out the TTL.
     * Called synchronously on shutdown.
     */
    public void clearNow() {
        client.delete(keys.heartbeat(serverId));
        client.srem(keys.heartbeatIndex(), serverId);
    }
}
