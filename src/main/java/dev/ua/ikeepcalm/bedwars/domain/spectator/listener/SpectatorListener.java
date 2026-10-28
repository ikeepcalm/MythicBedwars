package dev.ua.ikeepcalm.bedwars.domain.spectator.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.player.SpectatorJoinArenaEvent;
import de.marcely.bedwars.api.event.player.SpectatorQuitArenaEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.spectator.model.SpectatorData;
import dev.ua.ikeepcalm.bedwars.domain.spectator.service.SpectatorManager;
import lombok.RequiredArgsConstructor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

@RequiredArgsConstructor
public class SpectatorListener implements Listener {

    private final MythicBedwars plugin;

    @EventHandler
    public void onPlayerJoinArena(SpectatorJoinArenaEvent event) {
        if (!plugin.getSpectatorManager().isSpectating(event.getPlayer())) {
            plugin.getSpectatorManager().addSpectator(event.getPlayer(), event.getArena(), event.getReason());
        }
    }

    @EventHandler
    public void onPlayerQuitArena(SpectatorQuitArenaEvent event) {
        plugin.getSpectatorManager().removeSpectator(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getSpectatorManager().removeSpectator(event.getPlayer());
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player spectator = event.getPlayer();
        SpectatorManager spectators = plugin.getSpectatorManager();
        if (spectator.getGameMode() != GameMode.SPECTATOR || !spectators.isSpectating(spectator)) return;
        if (!(event.getRightClicked() instanceof Player target) || target.getGameMode() == GameMode.SPECTATOR) return;

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(spectator);
        if (arena == null || !arena.getPlayers().contains(target)) return;
        if (!spectators.canUseSpectatorFeatures(spectator, arena)) return;

        event.setCancelled(true);
        spectators.showPlayerDetails(spectator, target);

        SpectatorData data = spectators.getSpectatorData(spectator);
        if (data != null && data.isActionBarEnabled()) {
            data.setTargetPlayer(target);
            spectator.sendMessage(plugin.getLocaleManager().formatMessage(spectator, "magic.spectator.target_set",
                    "player", target.getName()));
        }
    }
}
