package dev.ua.ikeepcalm.bedwars.util.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigBackfiller {

    private static final List<String> RETIRED = List.of(
            "network.event.auto-propose",
            "network.event.auto-propose-interval-seconds",
            "network.event.auto-propose-min-idle-players",
            "network.event.idle-threshold-seconds");

    private static final Map<String, Object> ADDED = new LinkedHashMap<>();

    static {
        ADDED.put("network.event.schedule.enabled", true);
        ADDED.put("network.event.schedule.interval-hours", 5);
        ADDED.put("network.event.schedule.min-players", 40);
        ADDED.put("network.event.schedule.check-seconds", 120);
        ADDED.put("network.event.schedule.countdown.enabled", true);
        ADDED.put("network.event.schedule.countdown.broadcast-minutes", List.of(60, 30, 10));
        ADDED.put("network.event.schedule.countdown.shortfall-warn-minutes", 60);
        ADDED.put("voting.individual-enabled", true);
        ADDED.put("voting.default-mode", "TEAM");
        ADDED.put("voting.min-participation", 0.1);
        ADDED.put("network.event.magic-mode", "RANDOM");
        ADDED.put("pathways.crafting.kill-drops", true);
        ADDED.put("pathways.crafting.pathways", List.of("paragon", "moon"));
        ADDED.put("pathways.crafting.kill-drop-chance", 0.7);
        ADDED.put("pathways.crafting.final-kill-drop-chance", 1.0);
        ADDED.put("shop.materials.enabled", true);
        ADDED.put("shop.materials.pathways", List.of());
        ADDED.put("shop.materials.max-per-match", 8);
        ADDED.put("combat.projectile-cooldowns.enabled", true);
        ADDED.put("combat.projectile-cooldowns.items", Map.of(
                "bow", 1.5, "crossbow", 2.0, "fireball", 3.0, "egg", 1.0, "snowball", 0.5, "ender_pearl", 5.0));
    }

    public static List<String> apply(JavaPlugin plugin, FileConfiguration config) {
        List<String> changes = new ArrayList<>();

        for (String retired : RETIRED) {
            if (config.isSet(retired)) {
                config.set(retired, null);
                changes.add("removed " + retired);
            }
        }

        ADDED.forEach((path, value) -> {
            if (!config.isSet(path)) {
                config.set(path, value);
                changes.add("added " + path + ": " + value);
            }
        });

        if (!changes.isEmpty()) {
            plugin.saveConfig();
        }
        return changes;
    }
}
