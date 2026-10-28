package dev.ua.ikeepcalm.bedwars.domain.arena.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class ProjectileCooldownListener implements Listener {

    private static final Map<Material, EntityType> THROWABLES = Map.of(
            Material.EGG, EntityType.EGG,
            Material.SNOWBALL, EntityType.SNOWBALL,
            Material.ENDER_PEARL, EntityType.ENDER_PEARL,
            Material.SPLASH_POTION, EntityType.SPLASH_POTION,
            Material.LINGERING_POTION, EntityType.LINGERING_POTION);

    private final MythicBedwars plugin;
    private final Map<UUID, Map<String, Long>> expiries = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !inLiveArena(player)) {
            return;
        }

        ItemStack bow = event.getBow();
        String key = bow != null && bow.getType() == Material.CROSSBOW ? "crossbow" : "bow";
        if (!claim(player, key)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUseSpecialItem(PlayerUseSpecialItemEvent event) {
        String id = event.getSpecialItem() == null ? null : event.getSpecialItem().getId();
        if (id == null) {
            return;
        }

        if (!claim(event.getPlayer(), id.toLowerCase(Locale.ROOT))) {
            event.setTakingItem(false);
            event.setCancelled(true);
        }
    }

    // Gate on the item in hand, never on the projectile: COI casts abilities as plain Snowball/Arrow entities.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player) || !inLiveArena(player)) {
            return;
        }

        Material held = heldThrowable(player);
        if (held == null || THROWABLES.get(held) != projectile.getType()) {
            return;
        }

        if (!claim(player, held.name().toLowerCase(Locale.ROOT))) {
            event.setCancelled(true);
        }
    }

    private Material heldThrowable(Player player) {
        Material main = player.getInventory().getItemInMainHand().getType();
        if (THROWABLES.containsKey(main)) {
            return main;
        }

        Material off = player.getInventory().getItemInOffHand().getType();
        return THROWABLES.containsKey(off) ? off : null;
    }

    private boolean claim(Player player, String key) {
        double seconds = plugin.getConfigManager().getProjectileCooldownSeconds(key);
        if (seconds <= 0.0) {
            return true;
        }

        Map<String, Long> playerExpiries = expiries.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        Long expiry = playerExpiries.get(key);
        if (expiry != null && expiry > now) {
            player.sendActionBar(plugin.getLocaleManager().formatMessage(player, "magic.combat.projectile_cooldown",
                    "seconds", String.format(Locale.ROOT, "%.1f", (expiry - now) / 1000.0)));
            return false;
        }

        playerExpiries.put(key, now + (long) (seconds * 1000.0));
        return true;
    }

    private boolean inLiveArena(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        return arena != null && arena.getStatus() == ArenaStatus.RUNNING;
    }

    public void clear(Player player) {
        expiries.remove(player.getUniqueId());
    }
}
