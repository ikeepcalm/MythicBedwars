package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.RewardRedeemer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The survival server's side of a player coming home.
 *
 * <p>Contains no MBedwars types, and is only registered in the SMP role.
 */
public class SmpEventListener implements Listener {

    private final MythicBedwars plugin;
    private final RewardRedeemer redeemer;

    public SmpEventListener(MythicBedwars plugin, RewardRedeemer redeemer) {
        this.plugin = plugin;
        this.redeemer = redeemer;
    }

    /**
     * Pays out whatever is waiting.
     *
     * <p>Deliberately keyed off login rather than off the return message: a player who was
     * disconnected, transferred while the bus was down, or simply never came back still gets paid
     * the next time they appear.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        redeemer.redeemOnJoin(event.getPlayer());
    }
}
