package dev.ua.ikeepcalm.mythicBedwars.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.event.AbilityUsageEvent;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class DamageListener implements Listener {

    private final MythicBedwars plugin;
    private final CircleOfImaginationAPI circleOfImaginationAPI;

    public DamageListener(MythicBedwars plugin) {
        this.plugin = plugin;
        this.circleOfImaginationAPI = plugin.getCircleOfImaginationAPI();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player damager)) return;

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(damager);
        if (arena == null) return;

        Team team = arena.getPlayerTeam(damager);
        if (team == null) return;

        String pathway = plugin.getArenaPathwayManager().getTeamPathway(arena, team);
        if (pathway == null) return;

        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(damager);

        if (beyonderData != null && plugin.getStatisticsManager() != null) {
            plugin.getStatisticsManager().recordDamageDealt(pathway, event.getFinalDamage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityUse(AbilityUsageEvent event) {
        Player player = event.getPlayer();

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;

        Team team = arena.getPlayerTeam(player);
        if (team == null) return;

        String pathway = plugin.getArenaPathwayManager().getTeamPathway(arena, team);
        if (pathway == null) return;

        if (plugin.getStatisticsManager() != null) {
            plugin.getStatisticsManager().recordAbilityUse(pathway, event.getAbility().plainName());
        }
    }
}