package dev.ua.ikeepcalm.bedwars.net;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.health.HeartbeatTask;
import dev.ua.ikeepcalm.bedwars.net.health.ServerRegistry;
import dev.ua.ikeepcalm.bedwars.net.transport.JedisRedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisBus;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;

/**
 * Owns the cross-server plumbing: the Redis connection, the message bus, this instance's heartbeat,
 * and the view of who else is online.
 *
 * <p>Constructed in both roles, but only when {@code network.enabled} is set. Everything it owns is
 * failure-tolerant, so an unreachable Redis leaves the rest of the plugin running normally.
 */
public class NetworkService {

    private final MythicBedwars plugin;
    private final String serverId;
    private final RedisKeys keys;
    private final RedisClient client;
    private final RedisBus bus;
    private final ServerRegistry registry;

    private HeartbeatTask heartbeatTask;

    public NetworkService(MythicBedwars plugin) {
        ConfigLoader config = plugin.getConfigManager();

        this.plugin = plugin;
        this.serverId = config.getServerId();
        this.keys = new RedisKeys(config.getRedisNamespace());
        this.client = new JedisRedisClient(plugin);
        this.bus = new RedisBus(plugin, client, keys, serverId);
        this.registry = new ServerRegistry(client, keys, config.getHeartbeatStaleAfterSeconds() * 1000L);
    }

    public void start() {
        ConfigLoader config = plugin.getConfigManager();

        // Subscriptions must be registered before the client starts - the subscriber thread picks
        // up whatever is registered at that moment and then blocks.
        bus.listenAs(plugin.getNetworkRole());
        client.start();

        long intervalTicks = Math.max(1L, config.getHeartbeatIntervalSeconds()) * 20L;
        heartbeatTask = new HeartbeatTask(plugin, client, keys, serverId,
                config.getThisVelocityServer(), config.getHeartbeatTtlSeconds());
        heartbeatTask.runTaskTimer(plugin, 20L, intervalTicks);

        plugin.log("Network service started as {} (server-id '{}').", plugin.getNetworkRole(), serverId);
    }

    public void shutdown() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            // Synchronous on purpose: peers should see us leave now, not in fifteen seconds.
            heartbeatTask.clearNow();
            heartbeatTask = null;
        }

        client.shutdown();
    }

    /**
     * @return whether cross-server messaging is currently working
     */
    public boolean isAvailable() {
        return client.isAvailable();
    }

    public String serverId() {
        return serverId;
    }

    public RedisKeys keys() {
        return keys;
    }

    public RedisClient client() {
        return client;
    }

    public RedisBus bus() {
        return bus;
    }

    public ServerRegistry registry() {
        return registry;
    }

    /**
     * @return the role whose channel this instance publishes <em>to</em>, i.e. the other half
     */
    public NetworkRole counterpartRole() {
        return plugin.getNetworkRole() == NetworkRole.SMP ? NetworkRole.MINIGAME : NetworkRole.SMP;
    }
}
