package dev.ua.ikeepcalm.bedwars.cmd.impls;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Placeholder executor for commands declared in {@code plugin.yml} but not available in the current
 * {@link dev.ua.ikeepcalm.bedwars.config.NetworkRole}.
 *
 * <p>The command stays registered so players get an explanation instead of Bukkit's "unknown
 * command", but the real executor — which references MBedwars types — is never loaded.
 */
public class UnavailableCommand implements CommandExecutor, TabCompleter {

    private final MythicBedwars plugin;
    private final String messageKey;

    public UnavailableCommand(MythicBedwars plugin, String messageKey) {
        this.plugin = plugin;
        this.messageKey = messageKey;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        sender.sendMessage(plugin.getLocaleManager().formatMessage(messageKey));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        return List.of();
    }
}
