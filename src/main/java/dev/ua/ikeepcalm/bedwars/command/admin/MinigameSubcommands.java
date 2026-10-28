package dev.ua.ikeepcalm.bedwars.command.admin;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import dev.ua.ikeepcalm.bedwars.util.command.Completions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MinigameSubcommands {

    private static final List<String> TOGGLES = List.of("enable", "disable");

    private final MythicBedwars plugin;
    private final VotingDebugCommand votingDebugCommand;

    public MinigameSubcommands(MythicBedwars plugin) {
        this.plugin = plugin;
        this.votingDebugCommand = new VotingDebugCommand(plugin);
    }

    public void dispatch(CommandSender sender, String[] args) {
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stats" -> handleStats(sender);
            case "arena" -> handleArenaCommand(sender, rest);
            case "balance" -> handleBalanceCommand(sender, rest);
            case "pathways" -> handlePathwaysCommand(sender, rest);
            case "voting" -> votingDebugCommand.execute(sender, args);
            default -> sendHelp(sender);
        }
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("/mb stats - View pathway statistics", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb arena <arena> <enable/disable> - Toggle per arena", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb balance [report|info] - Toggle/view pathway balancing", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb pathways [enable|disable] <pathway> - Manage pathways", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb voting [status|force|test|clear] - Voting diagnostics", NamedTextColor.YELLOW));
    }

    public List<String> tabComplete(String[] args) {
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if ("voting".equals(subcommand)) {
            return votingDebugCommand.tabComplete(args);
        }

        if (args.length == 2) {
            return switch (subcommand) {
                case "arena" -> Completions.matching(arenaNames(), args[1]);
                case "balance" -> Completions.matching(List.of("report", "info"), args[1]);
                case "pathways" -> Completions.matching(TOGGLES, args[1]);
                default -> List.of();
            };
        }

        if (args.length == 3) {
            return switch (subcommand) {
                case "arena" -> Completions.matching(TOGGLES, args[2]);
                case "pathways" -> Completions.matching(plugin.getCircleOfImaginationAPI().getAllPathwayNames(), args[2]);
                default -> List.of();
            };
        }
        return List.of();
    }

    static List<String> arenaNames() {
        return BedwarsAPI.getGameAPI().getArenas().stream().map(Arena::getName).toList();
    }

    private void handleStats(CommandSender sender) {
        sender.sendMessage(Component.text("=== MythicBedwars Statistics ===", NamedTextColor.GOLD));
        plugin.getStatisticsManager().getPathwayStatistics().forEach((pathway, data) -> {
            double winRate = data.getWinRate() * 100;
            sender.sendMessage(Component.text(String.format("%s: %d wins, %d losses (%.1f%% win rate)",
                    pathway, data.getWins(), data.getLosses(), winRate), NamedTextColor.YELLOW));
        });
    }

    private void handleArenaCommand(CommandSender sender, String[] args) {
        if (args.length < 2 || !TOGGLES.contains(args[1].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(Component.text("Usage: /mythicbedwars arena <arena> <enable/disable>", NamedTextColor.RED));
            return;
        }

        String arenaName = args[0];
        boolean enable = "enable".equalsIgnoreCase(args[1]);
        plugin.getConfigManager().setArenaEnabled(arenaName, enable);
        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender,
                enable ? "magic.commands.arena_enabled" : "magic.commands.arena_disabled", "arena", arenaName));
    }

    private void handleBalanceCommand(CommandSender sender, String[] args) {
        ConfigLoader config = plugin.getConfigManager();

        if (args.length == 0) {
            boolean current = config.isPathwayBalancingEnabled();
            config.setPathwayBalancingEnabled(!current);
            sender.sendMessage(plugin.getLocaleManager().formatMessage(sender,
                    current ? "magic.commands.balance_disabled" : "magic.commands.balance_enabled"));
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "report" -> {
                plugin.getPathwayBalancer().printBalanceReport();
                sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.commands.balance_report_printed"));
            }
            case "info" -> {
                boolean enabled = config.isPathwayBalancingEnabled();
                sender.sendMessage(Component.text("=== Pathway Balancing Info ===", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Status: " + (enabled ? "Enabled" : "Disabled"),
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
                sender.sendMessage(Component.text("Balance Threshold: ±" + (config.getBalanceThreshold() * 100) + "%", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Min Games for Balance: " + config.getMinGamesForBalance(), NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text("Usage: /mb balance [report|info]", NamedTextColor.RED));
        }
    }

    private void handlePathwaysCommand(CommandSender sender, String[] args) {
        List<String> disabledPathways = new ArrayList<>(plugin.getConfigManager().getDisabledPathways());

        if (args.length == 0) {
            sender.sendMessage(Component.text("=== Pathway Settings ===", NamedTextColor.GOLD));
            sender.sendMessage(disabledPathways.isEmpty()
                    ? Component.text("All pathways are enabled", NamedTextColor.GREEN)
                    : Component.text("Disabled pathways: " + String.join(", ", disabledPathways), NamedTextColor.RED));
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2 || !TOGGLES.contains(action)) {
            sender.sendMessage(Component.text("Usage: /mb pathways <enable|disable> <pathway>", NamedTextColor.RED));
            return;
        }

        String pathway = args[1];
        boolean enable = "enable".equals(action);
        boolean changed = enable ? disabledPathways.remove(pathway) : !disabledPathways.contains(pathway) && disabledPathways.add(pathway);
        if (changed) {
            plugin.getConfigManager().setDisabledPathways(disabledPathways);
        }

        String key = enable
                ? (changed ? "magic.commands.pathway_enabled" : "magic.commands.pathway_already_enabled")
                : (changed ? "magic.commands.pathway_disabled" : "magic.commands.pathway_already_disabled");
        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, key, "pathway", pathway));
    }
}
