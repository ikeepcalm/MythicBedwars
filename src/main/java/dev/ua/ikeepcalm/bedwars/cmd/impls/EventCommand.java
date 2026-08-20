package dev.ua.ikeepcalm.bedwars.cmd.impls;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.protocol.Heartbeat;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.smp.RecruitmentManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@code /mb event ...} — the cross-server event controls, available in both roles.
 *
 * <p>Only {@code status} exists so far; the recruitment and hosting subcommands land with the
 * phases that implement them.
 */
public class EventCommand {

    public static final List<String> SUBCOMMANDS = List.of("status", "join", "preview", "start", "cancel", "send");

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

        switch (args[1].toLowerCase()) {
            case "status" -> handleStatus(sender);
            case "join" -> handleJoin(sender);
            case "preview" -> handlePreview(sender);
            case "start" -> handleStart(sender);
            case "cancel" -> handleCancel(sender);
            case "send" -> handleSend(sender, args);
            default -> sendHelp(sender);
        }
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("/mb event status - Cross-server link diagnostics", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event join - Sign up for the event being advertised",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event preview - Show the announcement without starting anything",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event start - Ask a Bedwars server to host an event (SMP only)",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event cancel - Call off the event in flight", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event send <player> <smp|minigame|server> - Move a player across the proxy",
                NamedTextColor.YELLOW));
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }

        if (args.length == 3 && "send".equalsIgnoreCase(args[1])) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                    .toList();
        }

        if (args.length == 4 && "send".equalsIgnoreCase(args[1])) {
            return Stream.of("smp", "minigame")
                    .filter(s -> s.startsWith(args[3].toLowerCase()))
                    .toList();
        }

        return List.of();
    }

    /**
     * The relog-proof way in: chat click callbacks die with the connection that rendered them, so a
     * player who reconnects mid-drive still needs a way to sign up.
     */
    private void handleJoin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.player_only"));
            return;
        }

        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.signup.closed"));
            return;
        }

        if (!player.hasPermission("mythicbedwars.event.join")) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.no_permission"));
            return;
        }

        recruitment.join(player);
    }

    private void handlePreview(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            sender.sendMessage(Component.text("Only the SMP server advertises events.", NamedTextColor.RED));
            return;
        }

        recruitment.previewAnnouncement(sender);
    }

    private void handleStart(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            sender.sendMessage(Component.text("Only the SMP server can start an event.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("Asking for a host...", NamedTextColor.GRAY));
        recruitment.propose(true, problem -> {
            if (problem == null) {
                sender.sendMessage(Component.text("Proposed. Watch the console for the host's answer.",
                        NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text(problem, NamedTextColor.RED));
            }
        });
    }

    private void handleCancel(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment != null) {
            recruitment.cancel(CancelReason.ADMIN, message ->
                    sender.sendMessage(Component.text(message, NamedTextColor.YELLOW)));
            return;
        }

        int released = plugin.cancelHostedEvents(CancelReason.ADMIN);
        if (released == 0) {
            sender.sendMessage(Component.text("No event is being hosted here.", NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text("Released " + released + " reserved arena(s).", NamedTextColor.YELLOW));
        }
    }

    /**
     * Admin smoke test for the proxy link, ahead of the automated flows that will use it.
     */
    private void handleSend(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /mb event send <player> <smp|minigame|server>", NamedTextColor.RED));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(Component.text("Player '" + args[2] + "' is not online here.", NamedTextColor.RED));
            return;
        }

        String destination = resolveServerName(args[3]);
        if (plugin.getTransferService().transfer(target, destination)) {
            target.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.transferring", "server", destination));
            sender.sendMessage(Component.text("Sending " + target.getName() + " to " + destination + "...", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Could not send " + target.getName() + " to " + destination + ".", NamedTextColor.RED));
        }
    }

    /**
     * Accepts the two role shorthands as well as a literal Velocity server name, so an admin does
     * not have to remember how the proxy names things.
     */
    private String resolveServerName(String raw) {
        return switch (raw.toLowerCase()) {
            case "smp" -> plugin.getConfigManager().getSmpVelocityServer();
            case "minigame", "bedwars" -> plugin.getConfigManager().getMinigameVelocityServer();
            default -> raw;
        };
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

        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment != null) {
            sender.sendMessage(Component.text("Event: ", NamedTextColor.GRAY)
                    .append(recruitment.currentEventId()
                            .map(id -> Component.text(id + " [" + recruitment.currentState() + "]"
                                                      + recruitment.arena().map(a -> " on " + a).orElse(""),
                                    NamedTextColor.WHITE))
                            .orElse(Component.text("none in flight", NamedTextColor.DARK_GRAY))));
        }

        if (plugin.isMinigameRole()) {
            var held = plugin.getReservedArenaNames();
            sender.sendMessage(Component.text("Reserved arenas: ", NamedTextColor.GRAY)
                    .append(held.isEmpty()
                            ? Component.text("none", NamedTextColor.DARK_GRAY)
                            : Component.text(String.join(", ", held), NamedTextColor.WHITE)));
        }

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
