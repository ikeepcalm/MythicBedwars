package dev.ua.ikeepcalm.bedwars.cmd.impls;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.cmd.Subcommands;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * {@code /bedwars} — the two things an ordinary player ever needs.
 *
 * <p>Everything here was already reachable through {@code /mb event …}, which is the wrong shape
 * for a player-facing command: {@code /mb} is an admin tool whose help output is mostly things
 * they cannot run, and "event" is internal vocabulary for what a player experiences as "the
 * Bedwars thing that happens every few hours". This gives them a command named after the thing
 * they want.
 *
 * <p>Bare {@code /bedwars} answers the question people actually have — when is the next one — so
 * the common case costs no subcommand at all.
 *
 * <p><b>Role-neutral.</b> Registered on both backends and free of any MBedwars reference, because
 * it exists mainly for the survival server, where MBedwars is not installed. It delegates to
 * {@link EventCommand}, which is role-neutral for the same reason.
 *
 * <p>Not permission-gated in {@code plugin.yml}, for the reason spelled out on
 * {@link dev.ua.ikeepcalm.bedwars.cmd.CommandManager}: Bukkit would reject the player before the
 * executor ran. Each branch checks {@code mythicbedwars.event.join} itself.
 */
public class PlayerCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("next", "join");

    private final MythicBedwars plugin;
    private final EventCommand event;

    public PlayerCommand(MythicBedwars plugin, EventCommand event) {
        this.plugin = plugin;
        this.event = event;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            // The countdown is what almost everybody typing this wants; making them find a
            // subcommand for it would be a worse command than the one it replaces.
            event.handleNext(sender);
            sendFooter(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "next", "when" -> event.handleNext(sender);
            case "join" -> event.handleJoin(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        // Localised, unlike the /mb help text: every line here is read by ordinary players, on a
        // server where most of them are not reading English.
        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.event.countdown.help_next"));
        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.event.countdown.help_join"));
    }

    /**
     * One line pointing at the other half of the command, shown under the countdown so a player who
     * checks the time also learns how to sign up.
     */
    private void sendFooter(CommandSender sender) {
        if (!sender.hasPermission(Subcommands.JOIN_PERMISSION)) {
            return;
        }

        sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.event.countdown.help_join"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission(Subcommands.JOIN_PERMISSION)) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
