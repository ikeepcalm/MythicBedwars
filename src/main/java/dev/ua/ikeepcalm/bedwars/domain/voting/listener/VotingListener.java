package dev.ua.ikeepcalm.bedwars.domain.voting.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.source.MagicMode;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

@RequiredArgsConstructor
public class VotingListener implements Listener {

    private final MythicBedwars plugin;

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        MagicMode mode = plugin.getVotingManager().readBallot(event.getItem());
        if (mode == null) return;

        Player player = event.getPlayer();
        if (BedwarsAPI.getGameAPI().getArenaByPlayer(player) == null) return;

        event.setCancelled(true);
        plugin.getVotingManager().handleVoteClick(player, mode);
    }
}
