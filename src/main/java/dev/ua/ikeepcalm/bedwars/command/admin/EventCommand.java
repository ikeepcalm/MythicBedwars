package dev.ua.ikeepcalm.bedwars.command.admin;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.Heartbeat;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.service.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.smp.service.RecruitmentManager;
import dev.ua.ikeepcalm.bedwars.util.command.Completions;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

@RequiredArgsConstructor
public class EventCommand {

    private static final List<String> SUBCOMMANDS = List.of("status", "next", "join", "preview", "start", "cancel", "send");
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("join", "next");

    private final MythicBedwars plugin;

    public void dispatch(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        String subcommand = args[1].toLowerCase(Locale.ROOT);
        if (!PLAYER_SUBCOMMANDS.contains(subcommand) && !sender.hasPermission(Subcommands.ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.commands.no_permission"));
            return;
        }

        switch (subcommand) {
            case "status" -> handleStatus(sender);
            case "next" -> handleNext(sender);
            case "join" -> handleJoin(sender);
            case "preview" -> handlePreview(sender);
            case "start" -> handleStart(sender);
            case "cancel" -> handleCancel(sender);
            case "send" -> handleSend(sender, args);
            default -> sendHelp(sender);
        }
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("/mb event join - Sign up for the event being advertised", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event next - When the next event is due", NamedTextColor.YELLOW));

        if (!sender.hasPermission(Subcommands.ADMIN_PERMISSION)) {
            return;
        }

        sender.sendMessage(Component.text("/mb event status - Cross-server link diagnostics", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event preview - Show the announcement without starting anything", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event start - Offer an event now, ignoring the quiet period (SMP only)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event cancel - Call off the event in flight", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event send <player> <smp|minigame|server> - Move a player across the proxy", NamedTextColor.YELLOW));
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission(Subcommands.ADMIN_PERMISSION);

        if (args.length == 2) {
            if (admin) {
                return Completions.matching(SUBCOMMANDS, args[1]);
            }
            return sender.hasPermission(Subcommands.JOIN_PERMISSION) ? Completions.matching(PLAYER_SUBCOMMANDS, args[1]) : List.of();
        }

        if (!admin || !"send".equalsIgnoreCase(args[1])) {
            return List.of();
        }
        if (args.length == 3) {
            return Completions.matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        return args.length == 4 ? Completions.matching(List.of("smp", "minigame"), args[3]) : List.of();
    }

    public void handleNext(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            handleStatus(sender);
            return;
        }

        if (!player.hasPermission(Subcommands.JOIN_PERMISSION)) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.commands.no_permission"));
            return;
        }

        if (plugin.getScheduleAnnouncer() == null) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.not_here"));
            return;
        }

        plugin.getScheduleAnnouncer().describeFor(player).forEach(player::sendMessage);
    }

    public void handleJoin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.commands.player_only"));
            return;
        }

        if (!player.hasPermission(Subcommands.JOIN_PERMISSION)) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.commands.no_permission"));
            return;
        }

        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.signup.closed"));
            return;
        }

        recruitment.join(player);
    }

    private void handlePreview(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            admin(sender, "Only the SMP server advertises events.", NamedTextColor.RED);
            return;
        }

        recruitment.previewAnnouncement(sender);
    }

    private void handleStart(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            admin(sender, "Only the SMP server can start an event.", NamedTextColor.RED);
            return;
        }

        admin(sender, "Asking for a host (ignoring the quiet period)...", NamedTextColor.GRAY);
        recruitment.propose(true, problem -> {
            if (problem == null) {
                admin(sender, "Offered. Nothing is announced to players until a host accepts.", NamedTextColor.GREEN);
            } else {
                admin(sender, problem, NamedTextColor.RED);
            }
        });
    }

    private void handleCancel(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment != null) {
            recruitment.cancel(CancelReason.ADMIN, message -> admin(sender, message, NamedTextColor.YELLOW));
            return;
        }

        int released = plugin.cancelHostedEvents(CancelReason.ADMIN);
        admin(sender, released == 0 ? "No event is being hosted here." : "Released " + released + " reserved arena(s).",
                NamedTextColor.YELLOW);
    }

    private void admin(CommandSender sender, String message, NamedTextColor colour) {
        sender.sendMessage(Component.text("[MythicBedwars] ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(message, colour)));
    }

    private void handleSend(CommandSender sender, String[] args) {
        if (args.length < 4) {
            admin(sender, "Usage: /mb event send <player> <smp|minigame|server>", NamedTextColor.RED);
            return;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            admin(sender, "Player '" + args[2] + "' is not online here.", NamedTextColor.RED);
            return;
        }

        String destination = resolveServerName(args[3]);
        if (destination.isBlank()) {
            admin(sender, "No Velocity server name is configured for '" + args[3] + "'.", NamedTextColor.RED);
            return;
        }

        if (plugin.getTransferService().transfer(target, destination)) {
            target.sendMessage(plugin.getLocaleManager().formatMessage(target, "magic.event.transferring", "server", destination));
            admin(sender, "Sending " + target.getName() + " to " + destination + "...", NamedTextColor.GREEN);
        } else {
            admin(sender, "Could not send " + target.getName() + " to " + destination + ".", NamedTextColor.RED);
        }
    }

    private String resolveServerName(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "smp" -> plugin.getConfigManager().getSmpVelocityServer();
            case "minigame", "bedwars" -> plugin.getConfigManager().getMinigameVelocityServer();
            default -> raw;
        };
    }

    private void handleStatus(CommandSender sender) {
        NetworkService network = plugin.getNetworkService();

        sender.sendMessage(Component.text("=== MythicBedwars Network ===", NamedTextColor.GOLD));
        sender.sendMessage(label("Role: ", plugin.getNetworkRole().name()));

        if (network == null) {
            sender.sendMessage(Component.text("Networking is disabled (network.enabled: false).", NamedTextColor.YELLOW));
            return;
        }

        sender.sendMessage(label("Server ID: ", network.getServerId()));
        sender.sendMessage(label("Namespace: ", network.getKeys().getNamespace()));
        sendRecruitmentStatus(sender);

        if (plugin.isMinigameRole()) {
            var held = plugin.getReservedArenaNames();
            sender.sendMessage(label("Reserved arenas: ", held.isEmpty() ? "none" : String.join(", ", held)));
        }

        boolean available = network.isAvailable();
        sender.sendMessage(Component.text("Redis: ", NamedTextColor.GRAY)
                .append(Component.text(available ? "connected" : "unavailable", available ? NamedTextColor.GREEN : NamedTextColor.RED)));

        if (!available) {
            sender.sendMessage(Component.text("Peers cannot be listed while Redis is down.", NamedTextColor.YELLOW));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            network.getRegistry().invalidate();
            List<Heartbeat> peers = network.getRegistry().alive();
            Bukkit.getScheduler().runTask(plugin, () -> sendPeers(sender, network, peers));
        });
    }

    private void sendRecruitmentStatus(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            return;
        }

        sender.sendMessage(label("Schedule: ", recruitment.describeSchedule()));
        sender.sendMessage(label("Event: ", recruitment.currentEventId()
                .map(id -> id + " [" + recruitment.currentState() + "]" + recruitment.arena().map(a -> " on " + a).orElse(""))
                .orElse("none in flight")));
    }

    private void sendPeers(CommandSender sender, NetworkService network, List<Heartbeat> peers) {
        if (peers.isEmpty()) {
            sender.sendMessage(Component.text("No live servers found - not even this one, which means "
                                              + "the first heartbeat has not been written yet.", NamedTextColor.YELLOW));
            return;
        }

        long now = System.currentTimeMillis();
        sender.sendMessage(Component.text("Live servers (" + peers.size() + "):", NamedTextColor.GOLD));
        for (Heartbeat peer : peers) {
            boolean self = peer.getServerId().equals(network.getServerId());
            sender.sendMessage(Component.text("  " + peer.getServerId(), self ? NamedTextColor.AQUA : NamedTextColor.WHITE)
                    .append(Component.text(" [" + peer.getRole() + "]", NamedTextColor.GRAY))
                    .append(Component.text(" via " + peer.getVelocityServer(), NamedTextColor.DARK_GRAY))
                    .append(Component.text(" - " + peer.getOnlinePlayers() + " online", NamedTextColor.GRAY))
                    .append(Component.text(", v" + peer.getPluginVersion(), NamedTextColor.DARK_GRAY))
                    .append(Component.text(", seen " + ((now - peer.getTs()) / 1000) + "s ago", NamedTextColor.DARK_GRAY)));
        }
    }

    private Component label(String name, String value) {
        return Component.text(name, NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
