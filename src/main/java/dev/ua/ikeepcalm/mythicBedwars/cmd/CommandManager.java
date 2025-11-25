package dev.ua.ikeepcalm.mythicBedwars.cmd;

import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.cmd.impls.VotingDebugCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CommandManager implements CommandExecutor, TabCompleter {

    private final MythicBedwars plugin;

    public CommandManager(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("mythicbedwars.admin")) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.no_permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "toggle" -> handleToggle(sender);
            case "reload" -> handleReload(sender);
            case "stats" -> handleStats(sender);
            case "arena" -> handleArenaCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "balance" -> handleBalanceCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "pathways" -> handlePathwaysCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "voting" -> new VotingDebugCommand(plugin).execute(sender, args);
            default -> sendHelpMessage(sender);
        }

        return true;
    }

    private void handleToggle(CommandSender sender) {
        boolean newState = plugin.getConfigManager().toggleGlobalEnabled();
        String message = newState ? "MythicBedwars enabled globally!" : "MythicBedwars disabled globally!";
        sender.sendMessage(Component.text(message, newState ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void handleReload(CommandSender sender) {
        plugin.getConfigManager().loadConfig();
        plugin.getLocaleManager().loadLocales();
        sender.sendMessage(Component.text("Configuration reloaded!", NamedTextColor.GREEN));
    }

    private void handleStats(CommandSender sender) {
        var stats = plugin.getStatisticsManager().getPathwayStatistics();
        sender.sendMessage(Component.text("=== MythicBedwars Statistics ===", NamedTextColor.GOLD));

        stats.forEach((pathway, data) -> {
            double winRate = data.totalGames > 0 ? (double) data.wins / data.totalGames * 100 : 0;
            sender.sendMessage(Component.text(String.format("%s: %d wins, %d losses (%.1f%% win rate)",
                    pathway, data.wins, data.losses, winRate), NamedTextColor.YELLOW));
        });
    }

    private void handleArenaCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /mythicbedwars arena <arena> <enable/disable>", NamedTextColor.RED));
            return;
        }

        String arenaName = args[0];
        boolean enable = "enable".equalsIgnoreCase(args[1]);

        plugin.getConfigManager().setArenaEnabled(arenaName, enable);
        String message = String.format("MythicBedwars %s for arena %s", enable ? "enabled" : "disabled", arenaName);
        sender.sendMessage(Component.text(message, enable ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void handleBalanceCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            boolean current = plugin.getConfigManager().isPathwayBalancingEnabled();
            plugin.getConfigManager().getConfig().set("pathways.auto-balance", !current);
            plugin.saveConfig();

            String message = (!current) ? "Pathway balancing enabled!" : "Pathway balancing disabled!";
            sender.sendMessage(Component.text(message, (!current) ? NamedTextColor.GREEN : NamedTextColor.RED));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "report" -> {
                plugin.getPathwayBalancer().printBalanceReport();
                sender.sendMessage(Component.text("Balance report printed to console!", NamedTextColor.GREEN));
            }
            case "info" -> {
                boolean enabled = plugin.getConfigManager().isPathwayBalancingEnabled();
                double threshold = plugin.getConfigManager().getBalanceThreshold();
                int minGames = plugin.getConfigManager().getMinGamesForBalance();

                sender.sendMessage(Component.text("=== Pathway Balancing Info ===", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Status: " + (enabled ? "Enabled" : "Disabled"),
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
                sender.sendMessage(Component.text("Balance Threshold: ±" + (threshold * 100) + "%", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Min Games for Balance: " + minGames, NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text("Usage: /mb balance [report|info]", NamedTextColor.RED));
        }
    }

    private void handlePathwaysCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            var disabledPathways = plugin.getConfigManager().getDisabledPathways();
            sender.sendMessage(Component.text("=== Pathway Settings ===", NamedTextColor.GOLD));

            if (disabledPathways.isEmpty()) {
                sender.sendMessage(Component.text("All pathways are enabled", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("Disabled pathways: " + String.join(", ", disabledPathways), NamedTextColor.RED));
            }
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /mb pathways <enable|disable> <pathway>", NamedTextColor.RED));
            return;
        }

        String action = args[0].toLowerCase();
        String pathway = args[1];

        List<String> disabledPathways = new ArrayList<>(plugin.getConfigManager().getDisabledPathways());

        switch (action) {
            case "enable" -> {
                if (disabledPathways.remove(pathway)) {
                    plugin.getConfigManager().getConfig().set("pathways.disabled", disabledPathways);
                    plugin.saveConfig();
                    sender.sendMessage(Component.text("Enabled pathway: " + pathway, NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Pathway " + pathway + " is already enabled", NamedTextColor.YELLOW));
                }
            }
            case "disable" -> {
                if (!disabledPathways.contains(pathway)) {
                    disabledPathways.add(pathway);
                    plugin.getConfigManager().getConfig().set("pathways.disabled", disabledPathways);
                    plugin.saveConfig();
                    sender.sendMessage(Component.text("Disabled pathway: " + pathway, NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("Pathway " + pathway + " is already disabled", NamedTextColor.YELLOW));
                }
            }
            default ->
                    sender.sendMessage(Component.text("Usage: /mb pathways <enable|disable> <pathway>", NamedTextColor.RED));
        }
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(Component.text("=== MythicBedwars Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/mb toggle - Toggle global functionality", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb reload - Reload configuration", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb stats - View pathway statistics", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb arena <arena> <enable/disable> - Toggle per arena", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb balance [report|info] - Toggle/view pathway balancing", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb pathways [enable|disable] <pathway> - Manage pathways", NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("toggle", "reload", "stats", "arena", "balance", "pathways")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "arena" -> {
                    return plugin.getArenaNames().stream()
                            .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "balance" -> {
                    return Stream.of("report", "info")
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "pathways" -> {
                    return Stream.of("enable", "disable")
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        }

        if (args.length == 3) {
            if ("arena".equals(args[0])) {
                return Stream.of("enable", "disable")
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if ("pathways".equals(args[0])) {
                return plugin.getAvailablePathways().stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }
}