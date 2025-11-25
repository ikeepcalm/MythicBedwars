package dev.ua.ikeepcalm.mythicBedwars.cmd.impls;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.voting.model.VotingSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class VotingDebugCommand {

    private final MythicBedwars plugin;

    public VotingDebugCommand(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "status" -> showVotingStatus(sender);
            case "force" -> handleForce(sender, args);
            case "test" -> handleTest(sender);
            case "clear" -> handleClear(sender, args);
            default -> sendUsage(sender);
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("=== Voting Debug Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/mb voting status - Show voting status for all arenas", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb voting force <arena> <enable/disable> - Force magic state", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb voting test - Test voting in current arena", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb voting clear <arena> - Clear voting data", NamedTextColor.YELLOW));
    }

    private void showVotingStatus(CommandSender sender) {
        sender.sendMessage(Component.text("=== Voting Status ===", NamedTextColor.GOLD));

        boolean votingEnabled = plugin.getConfigManager().isVotingEnabled();
        sender.sendMessage(Component.text("Voting System: " + (votingEnabled ? "ENABLED" : "DISABLED"),
                votingEnabled ? NamedTextColor.GREEN : NamedTextColor.RED));

        for (Arena arena : BedwarsAPI.getGameAPI().getArenas()) {
            String arenaName = arena.getName();
            boolean hasVoting = plugin.getVotingManager().hasActiveVoting(arenaName);
            boolean magicEnabled = plugin.getVotingManager().isMagicEnabled(arenaName);
            VotingSession session = plugin.getVotingManager().getVotingSession(arenaName);

            sender.sendMessage(Component.text("\n" + arenaName + ":", NamedTextColor.AQUA));
            sender.sendMessage(Component.text("  Status: " + arena.getStatus(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Active Voting: " + (hasVoting ? "YES" : "NO"),
                    hasVoting ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Magic Enabled: " + (magicEnabled ? "YES" : "NO"),
                    magicEnabled ? NamedTextColor.GREEN : NamedTextColor.RED));

            if (session != null) {
                sender.sendMessage(Component.text("  Yes Votes: " + session.getYesVotes(), NamedTextColor.GREEN));
                sender.sendMessage(Component.text("  No Votes: " + session.getNoVotes(), NamedTextColor.RED));
            }
        }
    }

    private void handleForce(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /mb voting force <arena> <enable/disable>", NamedTextColor.RED));
            return;
        }

        String arenaName = args[2];
        boolean enable = args[3].equalsIgnoreCase("enable");

        Arena arena = BedwarsAPI.getGameAPI().getArenaByName(arenaName);
        if (arena == null) {
            sender.sendMessage(Component.text("Arena not found: " + arenaName, NamedTextColor.RED));
            return;
        }

        plugin.getVotingManager().cleanupArena(arenaName);
        plugin.getVotingManager().setMagicEnabled(arenaName, enable);

        sender.sendMessage(Component.text("Force set magic to " + (enable ? "ENABLED" : "DISABLED") +
                                          " for arena " + arenaName, enable ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void handleTest(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command must be run by a player", NamedTextColor.RED));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            sender.sendMessage(Component.text("You must be in an arena", NamedTextColor.RED));
            return;
        }

        plugin.getVotingManager().startVoting(arena);
        sender.sendMessage(Component.text("Started voting test for arena: " + arena.getName(), NamedTextColor.GREEN));
    }

    private void handleClear(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /mb voting clear <arena>", NamedTextColor.RED));
            return;
        }

        String arenaName = args[2];
        plugin.getVotingManager().cleanupArena(arenaName);
        sender.sendMessage(Component.text("Cleared voting data for arena: " + arenaName, NamedTextColor.GREEN));
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return List.of("status", "force", "test", "clear");
        }

        if (args.length == 3) {
            if ("force".equals(args[1]) || "clear".equals(args[1])) {
                return plugin.getArenaNames();
            }
        }

        if (args.length == 4 && "force".equals(args[1])) {
            return List.of("enable", "disable");
        }

        return List.of();
    }
}