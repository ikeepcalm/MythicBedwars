package dev.ua.ikeepcalm.bedwars.net.smp.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class ScheduleJournal {

    private static final DateTimeFormatter READABLE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final MythicBedwars plugin;
    private final File file;
    private final Object writeLock = new Object();

    @Getter
    private volatile long lastOfferedAt;
    private volatile long lastStartedAt;
    private volatile String lastEventId;

    public ScheduleJournal(MythicBedwars plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "schedule.yml");
    }

    public void load() {
        if (!file.exists()) {
            write();
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        lastOfferedAt = Math.max(0L, yaml.getLong("last-offered-at"));
        lastStartedAt = Math.max(0L, yaml.getLong("last-started-at"));
        lastEventId = yaml.getString("last-event-id");

        if (lastOfferedAt > System.currentTimeMillis()) {
            plugin.log("schedule.yml records an event in the future; treating the schedule as due.");
            lastOfferedAt = 0L;
        }

        if (lastOfferedAt > 0L) {
            plugin.log("Last event was offered {}; the schedule resumes from there.", stamp(lastOfferedAt));
        }
    }

    public boolean isDue(long intervalMillis) {
        return millisUntilDue(intervalMillis) <= 0L;
    }

    public long millisUntilDue(long intervalMillis) {
        long offered = lastOfferedAt;
        return offered <= 0L ? 0L : Math.max(0L, nextDueAfter(offered, intervalMillis) - System.currentTimeMillis());
    }

    public static long nextDueAfter(long offeredAt, long intervalMillis) {
        return Math.max((offeredAt / intervalMillis + 1) * intervalMillis, offeredAt + intervalMillis / 2);
    }

    public void recordOffered(String eventId) {
        lastOfferedAt = System.currentTimeMillis();
        lastEventId = eventId;
        persist();
    }

    public void recordStarted(String eventId) {
        lastStartedAt = System.currentTimeMillis();
        lastEventId = eventId;
        persist();
    }

    public String describeLastOffered() {
        return stamp(lastOfferedAt);
    }

    public static String describeDuration(long millis) {
        if (millis <= 0L) {
            return null;
        }

        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return minutes > 0L ? minutes + "m" : "under a minute";
    }

    private void persist() {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::write);
        } else {
            write();
        }
    }

    private void write() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("last-offered-at", lastOfferedAt);
        yaml.set("last-offered", stamp(lastOfferedAt));
        yaml.set("last-started-at", lastStartedAt);
        yaml.set("last-started", stamp(lastStartedAt));
        yaml.set("last-event-id", lastEventId);

        synchronized (writeLock) {
            try {
                yaml.save(file);
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not write schedule.yml: " + exception.getMessage());
            }
        }
    }

    private static String stamp(long millis) {
        return millis <= 0L ? "never" : READABLE.format(Instant.ofEpochMilli(millis));
    }
}
