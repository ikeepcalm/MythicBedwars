package dev.ua.ikeepcalm.bedwars.domain.pathway.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.pathway.model.PlayerMagicData;
import dev.ua.ikeepcalm.coi.api.event.AbilityUsageEvent;
import dev.ua.ikeepcalm.coi.api.event.MagicBlockEvent;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;

public class PlayerListener implements Listener {

    private final MythicBedwars plugin;
    private final NamespacedKey potionKey;

    public PlayerListener(MythicBedwars plugin) {
        this.plugin = plugin;
        this.potionKey = new NamespacedKey(plugin, "sequence_potion");
    }

    @EventHandler
    public void onAbilityUsage(AbilityUsageEvent event) {
        Player player = event.getPlayer();
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            event.setCancelled(true);
            return;
        }

        if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.shop.material.no_magic"));
            return;
        }

        String abilityName = event.getAbility().plainName();
        if (player.getGameMode() == GameMode.SPECTATOR
            || !plugin.getArenaPathwayManager().hasPlayerMagic(player)
            || plugin.getConfigManager().getBlockedAbilities().stream().anyMatch(abilityName::equalsIgnoreCase)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMagicBlock(MagicBlockEvent event) {
        Collection<Arena> arenas = BedwarsAPI.getGameAPI().getArenaByLocation(event.getLocation());
        if (arenas != null && !arenas.isEmpty()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        ItemMeta meta = event.getItem().getItemMeta();
        if (arena == null || meta == null || !meta.getPersistentDataContainer().has(potionKey, PersistentDataType.INTEGER)) {
            return;
        }

        PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
        if (!plugin.getVotingManager().isMagicEnabled(arena.getName()) || data == null) {
            event.setCancelled(true);
            return;
        }

        BeyonderData beyonderData = plugin.getCircleOfImaginationAPI().getBeyonderData(player);
        if (beyonderData != null && !beyonderData.pathways().isEmpty()
            && !beyonderData.pathways().getFirst().name().equals(data.getPathway())) {
            event.setCancelled(true);
        }
    }
}
