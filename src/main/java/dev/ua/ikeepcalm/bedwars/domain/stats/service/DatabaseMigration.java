package dev.ua.ikeepcalm.bedwars.domain.stats.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.stats.model.PathwayStats;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class DatabaseMigration {

    private final MythicBedwars plugin;
    private final SQLiteDatabase database;

    public CompletableFuture<Boolean> migrateFromYaml() {
        File yamlFile = new File(plugin.getDataFolder(), "statistics.yml");
        if (!yamlFile.exists()) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            Map<String, PathwayStats> stats = readYaml(yamlFile);
            if (stats.isEmpty()) {
                return false;
            }

            database.saveStatisticsNow(stats);
            if (!yamlFile.renameTo(new File(plugin.getDataFolder(), "statistics.yml.backup"))) {
                plugin.log("Migrated statistics.yml but could not rename it; it will be migrated again on the next start.");
            }
            return true;
        }).exceptionally(e -> {
            plugin.log("Failed to migrate statistics.yml: {}", e.getMessage());
            return false;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, PathwayStats> readYaml(File yamlFile) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(yamlFile);
        Map<String, PathwayStats> stats = new HashMap<>();
        for (String key : config.getKeys(false)) {
            switch (config.get(key)) {
                case PathwayStats loaded -> stats.put(key, loaded);
                case ConfigurationSection section -> stats.put(key, new PathwayStats(section.getValues(false)));
                case Map<?, ?> map -> stats.put(key, new PathwayStats((Map<String, Object>) map));
                case null, default -> {
                }
            }
        }
        return stats;
    }
}
