package dev.ua.ikeepcalm.mythicBedwars.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collection;

public class SpectatorListener implements Listener {

    private final MythicBedwars plugin;

    public SpectatorListener(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoinArena(PlayerJoinArenaEvent event) {
        Player player = event.getPlayer();
        Arena arena = event.getArena();

        if (player.getGameMode() == GameMode.SPECTATOR) {
            plugin.getSpectatorManager().addSpectator(player, arena);
        }
    }

    @EventHandler
    public void onPlayerQuitArena(PlayerQuitArenaEvent event) {
        Player player = event.getPlayer();

        if (plugin.getSpectatorManager().isSpectating(player)) {
            plugin.getSpectatorManager().removeSpectator(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (plugin.getSpectatorManager().isSpectating(player)) {
            plugin.getSpectatorManager().removeSpectator(player);
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);

        if (arena == null) return;


        Collection<Player> spectatingPlayer = BedwarsAPI.getGameAPI().getSpectatingPlayers();

        if (spectatingPlayer.contains(player)) {
            if (event.getNewGameMode() == GameMode.SPECTATOR) {
                if (!plugin.getSpectatorManager().isSpectating(player)) {
                    plugin.getSpectatorManager().addSpectator(player, arena);
                }
            } else {
                if (plugin.getSpectatorManager().isSpectating(player)) {
                    plugin.getSpectatorManager().removeSpectator(player);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player spectator = event.getPlayer();

        if (spectator.getGameMode() != GameMode.SPECTATOR) return;
        if (!plugin.getSpectatorManager().isSpectating(spectator)) return;

        if (!(event.getRightClicked() instanceof Player target)) return;

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(spectator);
        if (arena == null || !arena.getPlayers().contains(target)) return;

        if (target.getGameMode() == GameMode.SPECTATOR) return;

        event.setCancelled(true);

        plugin.getSpectatorManager().showPlayerDetails(spectator, target);

        var spectatorData = plugin.getSpectatorManager().getSpectatorData(spectator);
        if (spectatorData != null && spectatorData.isActionBarEnabled()) {
            spectatorData.setTargetPlayer(target);
            spectator.sendMessage(Component.text("Now tracking " + target.getName() + " in action bar", NamedTextColor.GREEN));
        }
    }
}