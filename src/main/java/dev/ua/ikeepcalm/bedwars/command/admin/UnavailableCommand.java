package dev.ua.ikeepcalm.bedwars.command.admin;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@RequiredArgsConstructor
public class UnavailableCommand implements CommandExecutor, TabCompleter {

    private final MythicBedwars plugin;
    private final String messageKey;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, messageKey));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        return List.of();
    }
}
