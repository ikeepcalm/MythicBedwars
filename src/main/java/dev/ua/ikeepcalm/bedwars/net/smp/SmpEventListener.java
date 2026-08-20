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
    private final ReturnGreeter greeter;

    public SmpEventListener(MythicBedwars plugin, RewardRedeemer redeemer, ReturnGreeter greeter) {
        this.plugin = plugin;
        this.redeemer = redeemer;
        this.greeter = greeter;
    }

    /**
     * Greets them, then pays out whatever is waiting.
     *
     * <p>Deliberately keyed off login rather than off the return message: a player who was
     * disconnected, transferred while the bus was down, or simply never came back still gets paid
     * the next time they appear.
     *
     * <p>The greeting and the payout are independent on purpose. Losing an outcome should cost a nice
     * message and nothing else — the rewards are guarded separately, by their own queue.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        greeter.onJoin(event.getPlayer());
        redeemer.redeemOnJoin(event.getPlayer());
    }
}
