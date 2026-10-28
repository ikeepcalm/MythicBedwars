package dev.ua.ikeepcalm.bedwars.util.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LocaleBackfiller {

    public static List<String> apply(JavaPlugin plugin, String fileName) {
        File onDisk = new File(new File(plugin.getDataFolder(), "lang"), fileName);
        if (!onDisk.exists()) {
            return List.of();
        }

        YamlConfiguration bundled = loadBundled(plugin, fileName);
        if (bundled == null) {
            return List.of();
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(onDisk);
        List<String> added = new ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            if (!bundled.isConfigurationSection(key) && !current.contains(key)) {
                current.set(key, bundled.get(key));
                added.add(key);
            }
        }

        if (added.isEmpty()) {
            return List.of();
        }

        try {
            current.save(onDisk);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not write " + fileName + ": " + exception.getMessage());
            return List.of();
        }
        return added;
    }

    private static YamlConfiguration loadBundled(JavaPlugin plugin, String fileName) {
        try (InputStream stream = plugin.getResource("lang/" + fileName)) {
            return stream == null ? null
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read the bundled " + fileName + ": " + exception.getMessage());
            return null;
        }
    }

    public static String describe(String fileName, List<String> added) {
        if (added.size() <= 4) {
            return fileName + ": added " + String.join(", ", added);
        }
        return fileName + ": added " + added.size() + " keys, including "
                + String.join(", ", added.subList(0, 4)) + " ...";
    }
}
