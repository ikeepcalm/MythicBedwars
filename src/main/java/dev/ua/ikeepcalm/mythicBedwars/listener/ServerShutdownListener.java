package dev.ua.ikeepcalm.mythicBedwars.listener;

import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

public class ServerShutdownListener implements Listener {

    private final MythicBedwars plugin;

    public ServerShutdownListener(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin || event.getPlugin().getName().equals("MBedwars")) {
            plugin.getArenaPathwayManager().cleanupAll();
        }
    }
}