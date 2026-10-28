package dev.ua.ikeepcalm.bedwars.net.smp.listener;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.item.service.SandboxItems;
import dev.ua.ikeepcalm.bedwars.domain.reward.service.RewardRedeemer;
import dev.ua.ikeepcalm.bedwars.net.smp.service.ReturnGreeter;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

@RequiredArgsConstructor
public class SmpEventListener implements Listener {

    private final MythicBedwars plugin;
    private final RewardRedeemer redeemer;
    private final ReturnGreeter greeter;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        reclaimMatchItems(player);
        greeter.onJoin(player);
        redeemer.redeemOnJoin(player);
    }

    private void reclaimMatchItems(Player player) {
        int stripped = SandboxItems.strip(player);
        if (stripped > 0) {
            plugin.getLogger().warning("Removed " + stripped + " match-issued item stack(s) from " + player.getName()
                                       + " on arrival; check for inventory syncing between backends.");
        }
    }
}
