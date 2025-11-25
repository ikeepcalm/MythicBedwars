package dev.ua.ikeepcalm.mythicBedwars.cmd.impls;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.spectator.SpectatorManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SpectatorCommand implements CommandExecutor, TabCompleter {

    private final MythicBedwars plugin;

    public SpectatorCommand(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.player_only"));
            return true;
        }

        if (!player.hasPermission("mythicbedwars.spectator")) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.no_permission"));
            return true;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null || player.getGameMode() != GameMode.SPECTATOR) {
            player.sendMessage(Component.text("You must be spectating a Bedwars game to use this command!", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "toggle" -> handleToggle(player, Arrays.copyOfRange(args, 1, args.length));
            case "target" -> handleTarget(player, Arrays.copyOfRange(args, 1, args.length));
            case "teams" -> handleTeams(player);
            case "inspect" -> handleInspect(player, Arrays.copyOfRange(args, 1, args.length));
            case "settings" -> handleSettings(player);
            default -> sendHelpMessage(player);
        }

        return true;
    }

    private void handleToggle(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /mbspec toggle <hud|actionbar|detailed>", NamedTextColor.RED));
            return;
        }

        SpectatorManager.SpectatorData data = plugin.getSpectatorManager().getSpectatorData(player);
        if (data == null) {
            player.sendMessage(Component.text("Spectator data not found!", NamedTextColor.RED));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "hud" -> {
                data.setHudEnabled(!data.isHudEnabled());
                String status = data.isHudEnabled() ? "enabled" : "disabled";
                player.sendMessage(Component.text("Boss bar HUD " + status + "!",
                        data.isHudEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "actionbar" -> {
                data.setActionBarEnabled(!data.isActionBarEnabled());
                String status = data.isActionBarEnabled() ? "enabled" : "disabled";
                player.sendMessage(Component.text("Action bar " + status + "!",
                        data.isActionBarEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "detailed" -> {
                data.setDetailedMode(!data.isDetailedMode());
                String status = data.isDetailedMode() ? "enabled" : "disabled";
                player.sendMessage(Component.text("Detailed mode " + status + "!",
                        data.isDetailedMode() ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            default ->
                    player.sendMessage(Component.text("Invalid option! Use: hud, actionbar, or detailed", NamedTextColor.RED));
        }
    }

    private void handleTarget(Player player, String[] args) {
        if (args.length == 0) {
            SpectatorManager.SpectatorData data = plugin.getSpectatorManager().getSpectatorData(player);
            if (data != null) {
                data.setTargetPlayer(null);
                player.sendMessage(Component.text("Target cleared! Action bar will show nearest player.", NamedTextColor.GREEN));
            }
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Player not found!", NamedTextColor.RED));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null || !arena.getPlayers().contains(target)) {
            player.sendMessage(Component.text("Target player is not in this arena!", NamedTextColor.RED));
            return;
        }

        SpectatorManager.SpectatorData data = plugin.getSpectatorManager().getSpectatorData(player);
        if (data != null) {
            data.setTargetPlayer(target);
            player.sendMessage(Component.text("Now targeting " + target.getName() + "!", NamedTextColor.GREEN));
        }
    }

    private void handleTeams(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;

        player.sendMessage(Component.text("=== Team Overview ===", NamedTextColor.GOLD));

        for (var team : arena.getAliveTeams()) {
            String pathway = plugin.getArenaPathwayManager().getTeamPathway(arena, team);
            if (pathway == null) continue;

            Component teamHeader = Component.text("▶ " + team.getDisplayName(), getTeamColor(team.getDisplayName()))
                    .append(Component.text(" (" + pathway + ")", NamedTextColor.LIGHT_PURPLE));
            player.sendMessage(teamHeader);
        }
    }

    private void handleInspect(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /mbspec inspect <player>", NamedTextColor.RED));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Player not found!", NamedTextColor.RED));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null || !arena.getPlayers().contains(target)) {
            player.sendMessage(Component.text("Target player is not in this arena!", NamedTextColor.RED));
            return;
        }

        plugin.getSpectatorManager().showPlayerDetails(player, target);
    }

    private void handleSettings(Player player) {
        SpectatorManager.SpectatorData data = plugin.getSpectatorManager().getSpectatorData(player);
        if (data == null) {
            player.sendMessage(Component.text("Spectator data not found!", NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("=== Spectator Settings ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Boss Bar HUD: " + (data.isHudEnabled() ? "Enabled" : "Disabled"),
                data.isHudEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
        player.sendMessage(Component.text("Action Bar: " + (data.isActionBarEnabled() ? "Enabled" : "Disabled"),
                data.isActionBarEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
        player.sendMessage(Component.text("Detailed Mode: " + (data.isDetailedMode() ? "Enabled" : "Disabled"),
                data.isDetailedMode() ? NamedTextColor.GREEN : NamedTextColor.RED));

        if (data.getTargetPlayer() != null) {
            player.sendMessage(Component.text("Current Target: " + data.getTargetPlayer().getName(), NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("Current Target: Auto (nearest player)", NamedTextColor.GRAY));
        }
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(Component.text("=== MythicBedwars Spectator Commands ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/mbspec toggle <hud|actionbar|detailed> - Toggle display options", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/mbspec target [player] - Set target player (or clear)", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/mbspec teams - Show team overview", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/mbspec inspect <player> - Inspect player's magic", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/mbspec settings - View current settings", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Tip: Right-click players to quickly inspect them!", NamedTextColor.AQUA));
    }

    private NamedTextColor getTeamColor(String teamName) {
        return switch (teamName.toLowerCase()) {
            case "red" -> NamedTextColor.RED;
            case "blue" -> NamedTextColor.BLUE;
            case "green" -> NamedTextColor.GREEN;
            case "yellow" -> NamedTextColor.YELLOW;
            case "aqua", "cyan" -> NamedTextColor.AQUA;
            case "white" -> NamedTextColor.WHITE;
            case "pink" -> NamedTextColor.LIGHT_PURPLE;
            case "gray", "grey" -> NamedTextColor.GRAY;
            default -> NamedTextColor.WHITE;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("toggle", "target", "teams", "inspect", "settings")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "toggle" -> {
                    return Stream.of("hud", "actionbar", "detailed")
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "target", "inspect" -> {
                    Arena arena = null;
                    if (sender instanceof Player player) {
                        arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
                    }

                    if (arena != null) {
                        return arena.getPlayers().stream()
                                .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                                .map(Player::getName)
                                .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
            }
        }

        return List.of();
    }
}