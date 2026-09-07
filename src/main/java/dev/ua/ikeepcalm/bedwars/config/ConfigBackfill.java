package dev.ua.ikeepcalm.bedwars.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes settings that a config file predating them is missing, and clears out the ones that no
 * longer mean anything.
 *
 * <p>The plugin does not strictly need this: {@code JavaPlugin#reloadConfig} backs the on-disk file
 * with the bundled one, so an absent key already resolves to its default. It exists because an
 * invisible default is not a setting anybody can tune — an admin looking for the event schedule in
 * their own {@code config.yml} should find it there, with its comments, rather than having to be
 * told it exists. Stale keys are removed for the same reason in reverse: a key that is still in the
 * file but no longer read is a lie about what the plugin will do.
 *
 * <p>Runs on every load, and writes the file only when something actually changed, so it is a no-op
 * from the second boot onwards.
 */
public final class ConfigBackfill {

    private ConfigBackfill() {
    }

    /**
     * Keys the idle-driven auto-propose used. Nothing is carried over from them: they measured how
     * many players looked AFK, which the schedule deliberately does not care about.
     */
    private static final List<String> RETIRED = List.of(
            "network.event.auto-propose",
            "network.event.auto-propose-interval-seconds",
            "network.event.auto-propose-min-idle-players",
            "network.event.idle-threshold-seconds");

    /**
     * Brings {@code config.yml} up to date in place.
     *
     * @return a description of every change made, empty when the file was already current
     */
    public static List<String> apply(JavaPlugin plugin, FileConfiguration config) {
        List<String> changes = new ArrayList<>();

        for (String retired : RETIRED) {
            if (config.isSet(retired)) {
                config.set(retired, null);
                changes.add("removed " + retired);
            }
        }

        backfillSchedule(config, changes);

        if (changes.isEmpty()) {
            return List.of();
        }

        plugin.saveConfig();
        return changes;
    }

    private static void backfillSchedule(FileConfiguration config, List<String> changes) {
        // Only worth commenting the section itself when we are the ones creating it; overwriting the
        // comments on a section the admin already has would be rude, and possibly wrong.
        boolean fresh = !config.isSet("network.event.schedule");

        if (set(config, changes, "network.event.schedule.enabled", true) && fresh) {
            comment(config, "network.event.schedule",
                    "",
                    "--- SMP role: offering events without being asked -----------------------",
                    "One match every interval-hours, whenever min-players are online. The last offer",
                    "is remembered in schedule.yml as well as in Redis, so the interval survives a",
                    "restart of either. A window is only spent on an event that was actually offered:",
                    "if no Bedwars server answers, the next check tries again.");
        }

        if (set(config, changes, "network.event.schedule.interval-hours", 5)) {
            comment(config, "network.event.schedule.interval-hours",
                    "Hours between events. Fractions are allowed (0.5 = every 30 minutes).");
        }

        if (set(config, changes, "network.event.schedule.min-players", 40)) {
            comment(config, "network.event.schedule.min-players",
                    "Players who must be online for the event to be worth offering. Anyone holding",
                    "mythicbedwars.event.exempt is not counted, since they will never be asked.");
        }

        if (set(config, changes, "network.event.schedule.check-seconds", 120)) {
            comment(config, "network.event.schedule.check-seconds",
                    "How promptly a due window is noticed once the players are there. Not how often",
                    "events run - that is interval-hours alone.");
        }
    }

    /**
     * @return whether the key was missing and has now been written
     */
    private static boolean set(FileConfiguration config, List<String> changes, String path, Object value) {
        if (config.isSet(path)) {
            return false;
        }

        config.set(path, value);
        changes.add("added " + path + ": " + value);
        return true;
    }

    /**
     * Attaches comments to a key we just wrote. Never called for a key that was already on disk, so
     * it cannot overwrite anything an admin put there.
     */
    private static void comment(FileConfiguration config, String path, String... lines) {
        config.setComments(path, List.of(lines));
    }
}
