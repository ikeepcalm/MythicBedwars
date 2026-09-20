package dev.ua.ikeepcalm.bedwars.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
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

/**
 * Rate-limits the ordinary projectiles — bows, fireballs, eggs — without touching Beyonder
 * abilities.
 *
 * <p>The distinction is the whole design problem. Circle of Imagination casts its abilities as
 * plain {@code Snowball}, {@code Arrow}, {@code SmallFireball} and {@code ThrownPotion} entities
 * with no shared marker on them, so a listener that inspected the flying projectile could not tell
 * a Darkness ability from a bow shot and would end up throttling both.
 *
 * <p>So nothing here looks at the projectile. Every gate is on <b>what launched it</b>: a real bow
 * or crossbow in the shooter's hand, an MBedwars special item the player clicked, or a throwable
 * whose matching material is in the hand that threw it. An ability cast passes through all three
 * conditions untouched, because an ability is cast from a menu or a keybind and never from the
 * item the cooldown is keyed on. Abilities keep their own cooldowns, which COI already enforces.
 *
 * <p>Cooldowns are per player per key and live only as long as the server does; there is nothing
 * worth persisting in "you threw a fireball four seconds ago".
 */
public class ProjectileCooldownListener implements Listener {

    /**
     * Throwables worth gating, and the material that must be in hand for the throw to count as
     * coming from that item rather than from an ability.
     */
    private static final Map<Material, String> THROWABLES = Map.of(
            Material.EGG, "egg",
            Material.SNOWBALL, "snowball",
            Material.ENDER_PEARL, "ender_pearl",
            Material.SPLASH_POTION, "splash_potion",
            Material.LINGERING_POTION, "lingering_potion");

    private final MythicBedwars plugin;

    /**
     * When each player's cooldown for each key expires, in milliseconds.
     *
     * <p>Concurrent because the map is cleared from the arena lifecycle and read from event
     * handlers; both are main-thread today, and neither is where a future async path would be
     * obvious enough to notice.
     */
    private final Map<UUID, Map<String, Long>> expiries = new ConcurrentHashMap<>();

    public ProjectileCooldownListener(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * Bows and crossbows. Cancelling here consumes nothing — the arrow is simply never created and
     * the draw is wasted, which is exactly the cost a spam limit should impose.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!inLiveArena(player)) {
            return;
        }

        ItemStack bow = event.getBow();
        String key = bow != null && bow.getType() == Material.CROSSBOW ? "crossbow" : "bow";

        if (!claim(player, key)) {
            event.setCancelled(true);
        }
    }

    /**
     * MBedwars special items — fireball above all.
     *
     * <p>{@code setTakingItem(false)} matters: the default is to consume the item, and a player who
     * is refused should keep the fireball they paid five gold for. Cancelling alone does not
     * guarantee that.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUseSpecialItem(PlayerUseSpecialItemEvent event) {
        String id = event.getSpecialItem() == null ? null : event.getSpecialItem().getId();
        if (id == null) {
            return;
        }

        Player player = event.getPlayer();
        String key = id.toLowerCase(Locale.ROOT);

        if (plugin.getConfigManager().getProjectileCooldownSeconds(key) <= 0.0) {
            return;
        }

        if (!claim(player, key)) {
            event.setTakingItem(false);
            event.setCancelled(true);
        }
    }

    /**
     * Hand-thrown projectiles, gated only when the matching item is actually in one of the
     * shooter's hands.
     *
     * <p>That check is what keeps COI out of it: a Fool's Combat Grafting or a Tyrant's Destructive
     * Droplet launches a {@code Snowball} from a player who is holding a sword.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) {
            return;
        }

        if (!inLiveArena(player)) {
            return;
        }

        Material held = heldThrowable(player);
        if (held == null) {
            return;
        }

        String key = THROWABLES.get(held);
        if (key == null || !matches(projectile, held)) {
            return;
        }

        if (!claim(player, key)) {
            event.setCancelled(true);
        }
    }

    /**
     * @return whether the launched entity is plausibly the thing the held item throws. Guards the
     * case where a player happens to be holding an egg while an ability launches something else.
     */
    private boolean matches(Projectile projectile, Material held) {
        String type = projectile.getType().name();

        return switch (held) {
            case EGG -> type.equals("EGG");
            case SNOWBALL -> type.equals("SNOWBALL");
            case ENDER_PEARL -> type.equals("ENDER_PEARL");
            case SPLASH_POTION, LINGERING_POTION -> type.equals("SPLASH_POTION") || type.equals("POTION");
            default -> false;
        };
    }

    private Material heldThrowable(Player player) {
        Material main = player.getInventory().getItemInMainHand().getType();
        if (THROWABLES.containsKey(main)) {
            return main;
        }

        Material off = player.getInventory().getItemInOffHand().getType();
        return THROWABLES.containsKey(off) ? off : null;
    }

    /**
     * Takes the cooldown if it is free, and tells the player how long is left if it is not.
     *
     * @return {@code true} when the action may proceed
     */
    private boolean claim(Player player, String key) {
        double seconds = plugin.getConfigManager().getProjectileCooldownSeconds(key);
        if (seconds <= 0.0) {
            return true;
        }

        Map<String, Long> playerExpiries = expiries.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>());

        long now = System.currentTimeMillis();
        Long expiry = playerExpiries.get(key);

        if (expiry != null && expiry > now) {
            notifyOnCooldown(player, expiry - now);
            return false;
        }

        playerExpiries.put(key, now + (long) (seconds * 1000.0));
        return true;
    }

    private void notifyOnCooldown(Player player, long remainingMillis) {
        // Action bar rather than chat: this fires on exactly the input a spamming player is
        // repeating, and putting it in chat would replace one kind of spam with another.
        Component message = plugin.getLocaleManager().formatMessage(player, "magic.combat.projectile_cooldown",
                "seconds", String.format(Locale.ROOT, "%.1f", remainingMillis / 1000.0));

        player.sendActionBar(message);
    }

    /**
     * @return whether the player is in a running match. Lobby and spectator states are left alone —
     * there is nothing there worth rate-limiting, and MBedwars already blocks most of it.
     */
    private boolean inLiveArena(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        return arena != null && arena.getStatus() == ArenaStatus.RUNNING;
    }

    /**
     * Drops a player's cooldowns. Called when they leave an arena, so a new match never starts with
     * a timer left over from the last one.
     */
    public void clear(Player player) {
        expiries.remove(player.getUniqueId());
    }
}
