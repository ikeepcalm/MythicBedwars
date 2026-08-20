package dev.ua.ikeepcalm.bedwars.cmd.impls;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.protocol.Heartbeat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@code /mb event ...} — the cross-server event controls, available in both roles.
 *
 * <p>Only {@code status} exists so far; the recruitment and hosting subcommands land with the
 * phases that implement them.
 */
public class EventCommand {

    public static final List<String> SUBCOMMANDS = List.of("status");

    private final MythicBedwars plugin;

    public EventCommand(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * @param args the full argument array, with {@code "event"} still at index 0
     */
    public void dispatch(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        if ("status".equalsIgnoreCase(args[1])) {
            handleStatus(sender);
            return;
        }

        sendHelp(sender);
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("/mb event status - Cross-server link diagnostics", NamedTextColor.YELLOW));
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return Stream.of("status")
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    private void handleStatus(CommandSender sender) {
        NetworkService network = plugin.getNetworkService();

        sender.sendMessage(Component.text("=== MythicBedwars Network ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Role: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.getNetworkRole().name(), NamedTextColor.AQUA)));

        if (network == null) {
            sender.sendMessage(Component.text("Networking is disabled (network.enabled: false).", NamedTextColor.YELLOW));
            return;
        }

        sender.sendMessage(Component.text("Server ID: ", NamedTextColor.GRAY)
                .append(Component.text(network.serverId(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Namespace: ", NamedTextColor.GRAY)
                .append(Component.text(network.keys().namespace(), NamedTextColor.WHITE)));

        boolean available = network.isAvailable();
        sender.sendMessage(Component.text("Redis: ", NamedTextColor.GRAY)
                .append(Component.text(available ? "connected" : "unavailable",
                        available ? NamedTextColor.GREEN : NamedTextColor.RED)));

        if (!available) {
            sender.sendMessage(Component.text("Peers cannot be listed while Redis is down.", NamedTextColor.YELLOW));
            return;
        }

        // The registry does Redis I/O; keep it off the main thread and report back when it lands.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            network.registry().invalidate();
            List<Heartbeat> peers = network.registry().alive();
            long now = System.currentTimeMillis();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (peers.isEmpty()) {
                    sender.sendMessage(Component.text("No live servers found - not even this one, which means "
                                                      + "the first heartbeat has not been written yet.", NamedTextColor.YELLOW));
                    return;
                }

                sender.sendMessage(Component.text("Live servers (" + peers.size() + "):", NamedTextColor.GOLD));
                for (Heartbeat peer : peers) {
                    boolean self = peer.serverId().equals(network.serverId());
                    sender.sendMessage(Component.text("  " + peer.serverId(), self ? NamedTextColor.AQUA : NamedTextColor.WHITE)
                            .append(Component.text(" [" + peer.role() + "]", NamedTextColor.GRAY))
                            .append(Component.text(" via " + peer.velocityServer(), NamedTextColor.DARK_GRAY))
                            .append(Component.text(" - " + peer.onlinePlayers() + " online", NamedTextColor.GRAY))
                            .append(Component.text(", v" + peer.pluginVersion(), NamedTextColor.DARK_GRAY))
                            .append(Component.text(", seen " + ((now - peer.ts()) / 1000) + "s ago", NamedTextColor.DARK_GRAY)));
                }
            });
        });
    }
}
