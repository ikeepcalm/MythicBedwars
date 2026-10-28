package dev.ua.ikeepcalm.bedwars.net.velocity;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class ServerTransferService {

    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private final MythicBedwars plugin;

    public void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
    }

    public boolean transfer(Player player, String targetServer) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        if (targetServer == null || targetServer.isBlank()) {
            plugin.log("Refusing to transfer {} - no target server configured.", player.getName());
            return false;
        }

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> transfer(player, targetServer));
            return true;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(targetServer);
        player.sendPluginMessage(plugin, BUNGEE_CHANNEL, out.toByteArray());
        return true;
    }

    public void transferStaggered(Collection<UUID> playerIds, String targetServer, Consumer<UUID> onFailure) {
        int stagger = Math.max(1, plugin.getConfigManager().getTransferStaggerTicks());
        int delay = 0;

        for (UUID playerId : playerIds) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (!(player != null && transfer(player, targetServer)) && onFailure != null) {
                    onFailure.accept(playerId);
                }
            }, delay);
            delay += stagger;
        }
    }
}
