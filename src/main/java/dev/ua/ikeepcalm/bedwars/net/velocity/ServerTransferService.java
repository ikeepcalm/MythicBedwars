package dev.ua.ikeepcalm.bedwars.net.velocity;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Moves players between backends through Velocity.
 *
 * <p>Uses the classic BungeeCord plugin-messaging channel, which Velocity speaks as long as
 * {@code bungee-plugin-message-channel} is enabled in {@code velocity.toml} (the default). No
 * proxy-side plugin is required.
 *
 * <p>Only the {@code Connect} subchannel is used — never {@code ConnectOther}. A {@code Connect}
 * rides the transferring player's own connection, so "is anybody online to carry the message?" is
 * never a question: if the player we are moving is online, we can move them.
 */
public class ServerTransferService {

    public static final String BUNGEE_CHANNEL = "BungeeCord";

    private final MythicBedwars plugin;

    public ServerTransferService(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the outgoing channel. Must run during {@code onEnable} in both roles.
     */
    public void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
    }

    /**
     * Sends {@code player} to {@code targetServer}.
     *
     * <p>Self-bounces to the main thread when called from elsewhere — every Redis handler arrives on
     * the subscriber thread, and {@code sendPluginMessage} is not safe to call from there.
     *
     * <p>A {@code true} result means the move was dispatched, not that it completed - when called
     * off-thread the send happens a tick later. Arrival on the far side is the authoritative signal.
     *
     * @return {@code false} when the player is offline or no target was configured, in which case
     * the caller should treat them as a no-show rather than assume the move happened
     */
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

    /**
     * Transfers a group, spaced out over time.
     *
     * <p>Sending a whole roster at once gives the destination a login storm, and every arrival there
     * triggers an arena join, team assignment and Beyonder setup. Spacing them keeps that work
     * spread across ticks.
     *
     * @param onFailure called for each player who could not be dispatched (offline by the time
     *                  their slot came up), so the caller can mark them as no-shows
     */
    public void transferStaggered(Collection<UUID> playerIds, String targetServer, Consumer<UUID> onFailure) {
        int delay = 0;
        int stagger = Math.max(1, plugin.getConfigManager().getTransferStaggerTicks());

        for (UUID playerId : playerIds) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                boolean dispatched = player != null && transfer(player, targetServer);

                if (!dispatched && onFailure != null) {
                    onFailure.accept(playerId);
                }
            }, delay);

            delay += stagger;
        }
    }
}
