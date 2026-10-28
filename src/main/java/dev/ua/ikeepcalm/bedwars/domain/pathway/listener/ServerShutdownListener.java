package dev.ua.ikeepcalm.bedwars.domain.pathway.listener;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

@RequiredArgsConstructor
public class ServerShutdownListener implements Listener {

    private final MythicBedwars plugin;

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin || event.getPlugin().getName().equals("MBedwars")) {
            plugin.getArenaPathwayManager().cleanupAll();
        }
    }
}
