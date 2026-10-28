package dev.ua.ikeepcalm.bedwars.net.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.health.service.ServerRegistry;
import dev.ua.ikeepcalm.bedwars.net.health.task.HeartbeatTask;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisBus;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;
import lombok.Getter;

public class NetworkService {

    private final MythicBedwars plugin;
    @Getter
    private final String serverId;
    @Getter
    private final RedisKeys keys;
    @Getter
    private final RedisClient client;
    @Getter
    private final RedisBus bus;
    @Getter
    private final ServerRegistry registry;

    private HeartbeatTask heartbeatTask;

    public NetworkService(MythicBedwars plugin) {
        ConfigLoader config = plugin.getConfigManager();
        this.plugin = plugin;
        this.serverId = config.getServerId();
        this.keys = new RedisKeys(config.getRedisNamespace());
        this.client = new RedisClient(plugin);
        this.bus = new RedisBus(plugin, client, keys, serverId);
        this.registry = new ServerRegistry(client, keys, config.getHeartbeatStaleAfterSeconds() * 1000L);
    }

    public void start() {
        ConfigLoader config = plugin.getConfigManager();
        // The subscriber thread snapshots its channels on start, so listen first.
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
            heartbeatTask.clearNow();
            heartbeatTask = null;
        }

        client.shutdown();
    }

    public boolean isAvailable() {
        return client.isAvailable();
    }

    public NetworkRole counterpartRole() {
        return plugin.getNetworkRole() == NetworkRole.SMP ? NetworkRole.MINIGAME : NetworkRole.SMP;
    }
}
