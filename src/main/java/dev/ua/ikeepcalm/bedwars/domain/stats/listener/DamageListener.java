package dev.ua.ikeepcalm.bedwars.domain.stats.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.coi.api.event.AbilityUsageEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

@RequiredArgsConstructor
public class DamageListener implements Listener {

    private final MythicBedwars plugin;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player damager)) return;

        String pathway = arenaPathway(damager);
        if (pathway != null && plugin.getCircleOfImaginationAPI().getBeyonderData(damager) != null) {
            plugin.getStatisticsManager().recordDamageDealt(pathway, event.getFinalDamage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityUse(AbilityUsageEvent event) {
        String pathway = arenaPathway(event.getPlayer());
        if (pathway != null) {
            plugin.getStatisticsManager().recordAbilityUse(pathway, event.getAbility().plainName());
        }
    }

    private String arenaPathway(Player player) {
        if (BedwarsAPI.getGameAPI().getArenaByPlayer(player) == null) {
            return null;
        }
        return plugin.getArenaPathwayManager().getPlayerPathway(player);
    }
}
