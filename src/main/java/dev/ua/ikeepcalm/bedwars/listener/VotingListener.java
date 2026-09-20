package dev.ua.ikeepcalm.bedwars.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.MagicMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class VotingListener implements Listener {

    private final MythicBedwars plugin;

    public VotingListener(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // The ballot is read off the item's own tag rather than its material: with three options
        // the material is no longer a unique key, and a player holding an ordinary dye in a lobby
        // would otherwise have their click swallowed as a vote.
        MagicMode mode = plugin.getVotingManager().readBallot(item);
        if (mode == null) return;

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;

        event.setCancelled(true);
        plugin.getVotingManager().handleVoteClick(player, mode);
    }
}
