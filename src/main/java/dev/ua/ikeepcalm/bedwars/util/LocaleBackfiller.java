package dev.ua.ikeepcalm.bedwars.util;

import dev.ua.ikeepcalm.bedwars.config.LocaleLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes language keys a shipped {@code lang-*.yml} has gained into the operator's copy on disk.
 *
 * <p>{@link LocaleLoader} already calls {@code setDefaults} with the bundled file, so a key added
 * by a new release <i>resolves</i> correctly the moment the jar is dropped in — nothing is ever
 * missing at runtime. What it does not do is put the key in the file, and the file is the whole
 * point of shipping translations: a string an operator cannot see is a string they cannot
 * translate, reword or switch off. On a server that has been running since the first release, that
 * silently accumulates until most of the plugin's copy is invisible.
 *
 * <p>The counterpart to {@link ConfigBackfiller}, and the same rules apply: only missing keys are
 * written, an existing value is never touched however much it differs from the shipped one, and
 * the file is only rewritten when something actually changed.
 *
 * <p>Retired keys are deliberately <b>not</b> removed. A stale entry in {@code config.yml} is a lie
 * about what the plugin will do, which is worth correcting; a stale entry in a language file is
 * just a line nothing reads, and deleting an operator's translation because a key was renamed is a
 * worse outcome than leaving it there.
 */
public class LocaleBackfiller {

    private LocaleBackfiller() {
    }

    /**
     * Brings one language file up to date against the version bundled in the jar.
     *
     * @param plugin   used for the data folder and to read the bundled resource
     * @param fileName e.g. {@code lang-en.yml}
     * @return the keys that were added, empty when the file was already current or has no bundled
     * counterpart to compare against
     */
    public static List<String> apply(JavaPlugin plugin, String fileName) {
        File onDisk = new File(new File(plugin.getDataFolder(), "lang"), fileName);

        // Nothing to merge into: LocaleLoader writes the file from the jar when it is missing, and
        // a fresh copy is current by definition.
        if (!onDisk.exists()) {
            return List.of();
        }

        YamlConfiguration bundled = loadBundled(plugin, fileName);
        if (bundled == null) {
            // An operator's own lang-xx.yml with no shipped counterpart. Theirs entirely.
            return List.of();
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(onDisk);

        List<String> added = new ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            // Sections are created implicitly by writing their children; writing them explicitly
            // would replace a populated branch with an empty map.
            if (bundled.isConfigurationSection(key)) {
                continue;
            }

            // isSet consults defaults, which are not set on this handle - so this is a true
            // "is it in the file" test rather than "can it be resolved".
            if (current.contains(key)) {
                continue;
            }

            current.set(key, bundled.get(key));
            added.add(key);
        }

        if (added.isEmpty()) {
            return List.of();
        }

        try {
            current.save(onDisk);
        } catch (Exception exception) {
            // Never fatal. The keys still resolve through LocaleLoader's defaults, so a read-only
            // data folder costs the operator visibility and nothing else.
            plugin.getLogger().warning("Could not write " + fileName + ": " + exception.getMessage());
            return List.of();
        }

        return added;
    }

    private static YamlConfiguration loadBundled(JavaPlugin plugin, String fileName) {
        try (InputStream stream = plugin.getResource("lang/" + fileName)) {
            if (stream == null) {
                return null;
            }

            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not read the bundled " + fileName + ": " + exception.getMessage());
            return null;
        }
    }

    /**
     * @return a one-line summary of what was added to a file, for the startup log
     */
    public static String describe(String fileName, List<String> added) {
        if (added.size() <= 4) {
            return fileName + ": added " + String.join(", ", added);
        }

        return fileName + ": added " + added.size() + " keys, including "
                + String.join(", ", added.subList(0, 4)) + " ...";
    }
}
