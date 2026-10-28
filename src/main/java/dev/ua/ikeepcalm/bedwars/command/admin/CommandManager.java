package dev.ua.ikeepcalm.bedwars.command.admin;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.util.command.Completions;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CommandManager implements CommandExecutor, TabCompleter {

    private final MythicBedwars plugin;
    @Getter
    private final EventCommand eventCommand;
    @Setter
    private MinigameSubcommands minigameSubcommands;

    public CommandManager(MythicBedwars plugin) {
        this.plugin = plugin;
        this.eventCommand = new EventCommand(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String subcommand = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if ("event".equals(subcommand)) {
            eventCommand.dispatch(sender, args);
            return true;
        }

        if (!sender.hasPermission(Subcommands.ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.commands.no_permission"));
            return true;
        }

        switch (subcommand) {
            case "toggle" -> handleToggle(sender);
            case "reload" -> handleReload(sender);
            default -> {
                if (!Subcommands.isMinigameOnly(subcommand)) {
                    sendHelpMessage(sender);
                } else if (minigameSubcommands == null) {
                    sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.commands.minigame_only"));
                } else {
                    minigameSubcommands.dispatch(sender, args);
                }
            }
        }
        return true;
    }

    private void handleToggle(CommandSender sender) {
        boolean enabled = plugin.getConfigManager().toggleGlobalEnabled();
        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender,
                enabled ? "magic.commands.global_enabled" : "magic.commands.global_disabled"));
    }

    private void handleReload(CommandSender sender) {
        List<String> rearmed = plugin.reload();

        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.commands.config_reloaded"));
        if (!rearmed.isEmpty()) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage(sender,
                    "magic.commands.tasks_rearmed", "tasks", String.join(", ", rearmed)));
        }
        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.commands.reload_restart_only"));
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(Component.text("=== MythicBedwars Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/mb toggle - Toggle global functionality", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb reload - Reload config, language and reward files", NamedTextColor.YELLOW));
        eventCommand.sendHelp(sender);

        if (minigameSubcommands != null) {
            minigameSubcommands.sendHelp(sender);
        } else {
            sender.sendMessage(Component.text("Running in SMP role - Bedwars subcommands are unavailable here.",
                    NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        boolean admin = sender.hasPermission(Subcommands.ADMIN_PERMISSION);

        if (args.length == 1) {
            List<String> available = new ArrayList<>();
            if (admin) {
                available.addAll(Subcommands.SHARED);
                if (minigameSubcommands != null) {
                    available.addAll(Subcommands.MINIGAME);
                }
            } else if (sender.hasPermission(Subcommands.JOIN_PERMISSION)) {
                available.add("event");
            }
            return Completions.matching(available, args[0]);
        }

        if ("event".equalsIgnoreCase(args[0])) {
            return eventCommand.tabComplete(sender, args);
        }
        return admin && minigameSubcommands != null ? minigameSubcommands.tabComplete(args) : List.of();
    }
}
