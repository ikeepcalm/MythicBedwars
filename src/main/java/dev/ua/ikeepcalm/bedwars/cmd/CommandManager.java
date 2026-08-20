package dev.ua.ikeepcalm.bedwars.cmd;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.cmd.impls.EventCommand;
import dev.ua.ikeepcalm.bedwars.cmd.impls.MinigameSubcommands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Router for {@code /mythicbedwars}.
 *
 * <p>Registered in both network roles, but only the role-neutral subcommands live here. Everything
 * that needs MBedwars is held behind {@link #minigame}, which stays {@code null} on an SMP instance
 * so {@link MinigameSubcommands} is never loaded there.
 */
public class CommandManager implements CommandExecutor, TabCompleter {

    private static final List<String> SHARED_SUBCOMMANDS = List.of("toggle", "reload", "event");

    private final MythicBedwars plugin;

    private final EventCommand event;

    private MinigameSubcommands minigame;

    public CommandManager(MythicBedwars plugin) {
        this.plugin = plugin;
        this.event = new EventCommand(plugin);
    }

    /**
     * Enables the MBedwars-backed subcommands. Called only from the minigame role's bootstrap.
     */
    public void installMinigameSubcommands(MinigameSubcommands minigame) {
        this.minigame = minigame;
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
            case "event" -> event.dispatch(sender, args);
            default -> {
                if (!MinigameSubcommands.handles(args[0])) {
                    sendHelpMessage(sender);
                } else if (minigame == null) {
                    sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.minigame_only"));
                } else {
                    minigame.dispatch(sender, args);
                }
            }
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

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(Component.text("=== MythicBedwars Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/mb toggle - Toggle global functionality", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb reload - Reload configuration", NamedTextColor.YELLOW));
        event.sendHelp(sender);

        if (minigame != null) {
            minigame.sendHelp(sender);
        } else {
            sender.sendMessage(Component.text("Running in SMP role - Bedwars subcommands are unavailable here.",
                    NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            Stream<String> available = minigame == null
                    ? SHARED_SUBCOMMANDS.stream()
                    : Stream.concat(SHARED_SUBCOMMANDS.stream(), MinigameSubcommands.SUBCOMMANDS.stream());

            return available
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if ("event".equalsIgnoreCase(args[0])) {
            return event.tabComplete(args);
        }

        return minigame == null ? List.of() : minigame.tabComplete(args);
    }
}
