package dev.ua.ikeepcalm.bedwars.command.spectator;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.spectator.model.SpectatorData;
import dev.ua.ikeepcalm.bedwars.util.command.Completions;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.Locale;

@RequiredArgsConstructor
public class SpectatorCommand implements CommandExecutor, TabCompleter {

    private final MythicBedwars plugin;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage(sender, "magic.commands.player_only"));
            return true;
        }

        if (!player.hasPermission("mythicbedwars.spectator")) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.commands.no_permission"));
            return true;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null || player.getGameMode() != GameMode.SPECTATOR
                || !plugin.getSpectatorManager().canUseSpectatorFeatures(player, arena)) {
            player.sendMessage(Component.text("You must be spectating a Bedwars game to use this command!", NamedTextColor.RED));
            return true;
        }

        String argument = args.length > 1 ? args[1] : null;
        switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
            case "toggle" -> handleToggle(player, argument);
            case "target" -> handleTarget(player, arena, argument);
            case "teams" -> handleTeams(player, arena);
            case "inspect" -> handleInspect(player, arena, argument);
            case "settings" -> handleSettings(player);
            default -> sendHelpMessage(player);
        }
        return true;
    }

    private SpectatorData spectatorData(Player player) {
        SpectatorData data = plugin.getSpectatorManager().getSpectatorData(player);
        if (data == null) {
            player.sendMessage(Component.text("Spectator data not found!", NamedTextColor.RED));
        }
        return data;
    }

    private void handleToggle(Player player, String option) {
        if (option == null) {
            player.sendMessage(Component.text("Usage: /mbspec toggle <hud|actionbar|detailed>", NamedTextColor.RED));
            return;
        }

        SpectatorData data = spectatorData(player);
        if (data == null) {
            return;
        }

        switch (option.toLowerCase(Locale.ROOT)) {
            case "hud" -> {
                data.setHudEnabled(!data.isHudEnabled());
                sendToggled(player, "Boss bar HUD", data.isHudEnabled());
            }
            case "actionbar" -> {
                data.setActionBarEnabled(!data.isActionBarEnabled());
                sendToggled(player, "Action bar", data.isActionBarEnabled());
            }
            case "detailed" -> {
                data.setDetailedMode(!data.isDetailedMode());
                sendToggled(player, "Detailed mode", data.isDetailedMode());
            }
            default -> player.sendMessage(Component.text("Invalid option! Use: hud, actionbar, or detailed", NamedTextColor.RED));
        }
    }

    private void sendToggled(Player player, String feature, boolean enabled) {
        player.sendMessage(Component.text(feature + (enabled ? " enabled!" : " disabled!"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void handleTarget(Player player, Arena arena, String targetName) {
        SpectatorData data = spectatorData(player);
        if (data == null) {
            return;
        }

        if (targetName == null) {
            data.setTargetPlayer(null);
            player.sendMessage(Component.text("Target cleared! Action bar will show nearest player.", NamedTextColor.GREEN));
            return;
        }

        Player target = findArenaPlayer(player, arena, targetName);
        if (target != null) {
            data.setTargetPlayer(target);
            player.sendMessage(Component.text("Now targeting " + target.getName() + "!", NamedTextColor.GREEN));
        }
    }

    private void handleInspect(Player player, Arena arena, String targetName) {
        if (targetName == null) {
            player.sendMessage(Component.text("Usage: /mbspec inspect <player>", NamedTextColor.RED));
            return;
        }

        Player target = findArenaPlayer(player, arena, targetName);
        if (target != null) {
            plugin.getSpectatorManager().showPlayerDetails(player, target);
        }
    }

    private Player findArenaPlayer(Player player, Arena arena, String name) {
        Player target = Bukkit.getPlayer(name);
        if (target == null) {
            player.sendMessage(Component.text("Player not found!", NamedTextColor.RED));
            return null;
        }
        if (!arena.getPlayers().contains(target)) {
            player.sendMessage(Component.text("Target player is not in this arena!", NamedTextColor.RED));
            return null;
        }
        return target;
    }

    private void handleTeams(Player player, Arena arena) {
        player.sendMessage(Component.text("=== Team Overview ===", NamedTextColor.GOLD));

        for (var team : arena.getAliveTeams()) {
            String pathway = plugin.getArenaPathwayManager().getTeamPathwayDisplay(arena, team);
            if (pathway != null) {
                player.sendMessage(Component.text("▶ " + team.getDisplayName(), teamColor(team.getDisplayName()))
                        .append(Component.text(" (" + pathway + ")", NamedTextColor.LIGHT_PURPLE)));
            }
        }
    }

    private void handleSettings(Player player) {
        SpectatorData data = spectatorData(player);
        if (data == null) {
            return;
        }

        player.sendMessage(Component.text("=== Spectator Settings ===", NamedTextColor.GOLD));
        sendSetting(player, "Boss Bar HUD", data.isHudEnabled());
        sendSetting(player, "Action Bar", data.isActionBarEnabled());
        sendSetting(player, "Detailed Mode", data.isDetailedMode());

        if (data.getTargetPlayer() != null) {
            player.sendMessage(Component.text("Current Target: " + data.getTargetPlayer().getName(), NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("Current Target: Auto (nearest player)", NamedTextColor.GRAY));
        }
    }

    private void sendSetting(Player player, String name, boolean enabled) {
        player.sendMessage(Component.text(name + ": " + (enabled ? "Enabled" : "Disabled"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
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

    private NamedTextColor teamColor(String teamName) {
        return switch (teamName.toLowerCase(Locale.ROOT)) {
            case "red" -> NamedTextColor.RED;
            case "blue" -> NamedTextColor.BLUE;
            case "green" -> NamedTextColor.GREEN;
            case "yellow" -> NamedTextColor.YELLOW;
            case "aqua", "cyan" -> NamedTextColor.AQUA;
            case "pink" -> NamedTextColor.LIGHT_PURPLE;
            case "gray", "grey" -> NamedTextColor.GRAY;
            default -> NamedTextColor.WHITE;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return Completions.matching(List.of("toggle", "target", "teams", "inspect", "settings"), args[0]);
        }
        if (args.length != 2 || !(sender instanceof Player player)) {
            return List.of();
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "toggle" -> Completions.matching(List.of("hud", "actionbar", "detailed"), args[1]);
            case "target", "inspect" -> {
                Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
                if (arena == null || !plugin.getSpectatorManager().canUseSpectatorFeatures(player, arena)) {
                    yield List.of();
                }
                yield Completions.matching(arena.getPlayers().stream()
                        .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                        .map(Player::getName)
                        .toList(), args[1]);
            }
            default -> List.of();
        };
    }
}
