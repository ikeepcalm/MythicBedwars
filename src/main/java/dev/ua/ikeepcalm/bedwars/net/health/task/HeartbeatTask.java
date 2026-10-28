package dev.ua.ikeepcalm.bedwars.net.health.task;

import com.google.gson.Gson;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.Heartbeat;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

@RequiredArgsConstructor
public class HeartbeatTask extends BukkitRunnable {

    private static final int INDEX_TTL_SECONDS = 86_400;

    private final MythicBedwars plugin;
    private final RedisClient client;
    private final RedisKeys keys;
    private final String serverId;
    private final String velocityServer;
    private final int ttlSeconds;
    private final Gson gson = new Gson();

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

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            client.probeIfDisconnected();
            client.setWithTtl(keys.heartbeat(serverId), payload, ttlSeconds);
            client.sadd(keys.heartbeatIndex(), serverId, INDEX_TTL_SECONDS);
        });
    }

    public void clearNow() {
        client.delete(keys.heartbeat(serverId));
        client.srem(keys.heartbeatIndex(), serverId);
    }
}
