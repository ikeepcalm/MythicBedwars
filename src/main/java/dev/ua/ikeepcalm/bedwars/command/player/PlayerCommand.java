package dev.ua.ikeepcalm.bedwars.command.player;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.command.admin.EventCommand;
import dev.ua.ikeepcalm.bedwars.command.admin.Subcommands;
import dev.ua.ikeepcalm.bedwars.util.command.Completions;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

@RequiredArgsConstructor
public class PlayerCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("next", "join");

    private final MythicBedwars plugin;
    private final EventCommand event;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            event.handleNext(sender);
            if (sender.hasPermission(Subcommands.JOIN_PERMISSION)) {
                sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.event.countdown.help_join"));
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "next", "when" -> event.handleNext(sender);
            case "join" -> event.handleJoin(sender);
            default -> {
                sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.event.countdown.help_next"));
                sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.event.countdown.help_join"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission(Subcommands.JOIN_PERMISSION)) {
            return List.of();
        }
        return Completions.matching(SUBCOMMANDS, args[0]);
    }
}
