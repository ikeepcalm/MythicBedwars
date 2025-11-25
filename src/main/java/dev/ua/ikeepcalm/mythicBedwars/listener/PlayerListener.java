package dev.ua.ikeepcalm.mythicBedwars.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.event.AbilityUsageEvent;
import dev.ua.ikeepcalm.coi.api.event.MagicBlockEvent;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class PlayerListener implements Listener {

    private final MythicBedwars plugin;
    private final CircleOfImaginationAPI circleOfImaginationAPI;

    public PlayerListener(MythicBedwars plugin) {
        this.plugin = plugin;
        this.circleOfImaginationAPI = plugin.getCircleOfImaginationAPI();
    }

    @EventHandler
    public void onSpecificAbilityUsage(AbilityUsageEvent event) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(event.getPlayer());
        if (arena == null) {
            event.setCancelled(true);
            return;
        }

        if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Magic is disabled in this arena!", NamedTextColor.RED));
            return;
        }

        if (!plugin.getArenaPathwayManager().hasPlayerMagic(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }

        String abilityName = event.getAbility().plainName();

        List<String> blockedAbilities = List.of("thread-hands", "nightmare", "travelers-door");
        if (blockedAbilities.stream().anyMatch(abilityName::equalsIgnoreCase)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onNonArenaAbilityUsage(AbilityUsageEvent event) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(event.getPlayer());
        if (arena == null) {
            event.setCancelled(true);
        } else {
            if (event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(MagicBlockEvent event) {
        @Nullable Collection<Arena> arenas = BedwarsAPI.getGameAPI().getArenaByLocation(event.getLocation());
        if (arenas != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        Plugin coiPlugin = Bukkit.getPluginManager().getPlugin("CircleOfImagination");
        if (coiPlugin == null) return;

        NamespacedKey potionKey = new NamespacedKey(plugin, "sequence_potion");

        if (meta.getPersistentDataContainer().has(potionKey, PersistentDataType.INTEGER)) {
            if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) {
                event.setCancelled(true);
                return;
            }

            if (!plugin.getArenaPathwayManager().hasPlayerMagic(player)) {
                event.setCancelled(true);
                return;
            }

            BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);

            if (beyonderData != null && !beyonderData.pathways().isEmpty()) {
                PathwayData currentPathway = beyonderData.pathways().getFirst();

                if (!currentPathway.name().equals(plugin.getArenaPathwayManager().getPlayerData(player).getPathway())) {
                    event.setCancelled(true);
                }
            }
        }
    }
}