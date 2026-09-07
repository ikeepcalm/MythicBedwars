package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * When the last event happened, kept in {@code schedule.yml} so the answer survives a restart.
 *
 * <p>The interval is also held as a Redis key with a matching TTL, and that key is still what stops
 * two survival nodes offering an event in the same instant. It is not enough on its own: a Redis
 * restart, an eviction, a namespace change or a `FLUSHALL` all forget it silently, and the schedule
 * would then fire immediately on the next tick — which, for something that broadcasts to everybody
 * online, is the one failure mode worth spending a file on. On boot the file is authoritative: if it
 * says an event went out an hour ago, no event goes out for another four.
 *
 * <p>Deliberately plain: two timestamps and an id, written whole. There is nothing here worth a
 * database, and a file an admin can read and edit is a feature — setting {@code last-offered-at} to
 * {@code 0} makes an event due at the next check.
 */
public class ScheduleJournal {

    private static final DateTimeFormatter READABLE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final MythicBedwars plugin;
    private final File file;

    /** Serialises writes; two of them interleaving would produce a half-written file. */
    private final Object writeLock = new Object();

    private volatile long lastOfferedAt;
    private volatile long lastStartedAt;
    private volatile String lastEventId;

    public ScheduleJournal(MythicBedwars plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "schedule.yml");
    }

    /**
     * Reads the record. Synchronous on purpose: this happens once, during enable, and every decision
     * the schedule makes afterwards depends on the answer.
     */
    public void load() {
        if (!file.exists()) {
            // Write the empty record now rather than after the first event, so the file - and the
            // header explaining how to reset the schedule - is there for an admin to find. Inline:
            // this is boot, it is a few hundred bytes, and there is nothing to race with yet.
            write();
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        lastOfferedAt = Math.max(0L, yaml.getLong("last-offered-at"));
        lastStartedAt = Math.max(0L, yaml.getLong("last-started-at"));
        lastEventId = yaml.getString("last-event-id");

        if (lastOfferedAt > System.currentTimeMillis()) {
            // A clock that moved backwards, or a hand-edited file. Trusting it would lock the
            // schedule out until the timestamp came round again, which could be years.
            plugin.log("schedule.yml records an event in the future; treating the schedule as due.");
            lastOfferedAt = 0L;
        }

        if (lastOfferedAt > 0L) {
            plugin.log("Last event was offered {}; the schedule resumes from there.",
                    READABLE.format(Instant.ofEpochMilli(lastOfferedAt)));
        }
    }

    /**
     * @return whether enough time has passed since the last offer for another one to be due
     */
    public boolean isDue(long intervalMillis) {
        return millisUntilDue(intervalMillis) <= 0L;
    }

    /**
     * @return how long until the next event is due, or {@code 0} if one is due now
     */
    public long millisUntilDue(long intervalMillis) {
        long offered = lastOfferedAt;
        if (offered <= 0L) {
            return 0L;
        }

        return Math.max(0L, offered + intervalMillis - System.currentTimeMillis());
    }

    public long lastOfferedAt() {
        return lastOfferedAt;
    }

    /**
     * Records that an event has just been offered, which is what starts the next interval running.
     * Written at the offer rather than at the finish: the broadcast is the part players see, and an
     * event that was advertised has spent its window whether or not anybody signed up.
     */
    public void recordOffered(String eventId) {
        lastOfferedAt = System.currentTimeMillis();
        lastEventId = eventId;
        persist();
    }

    /**
     * Records that the match actually began. Diagnostic only - the schedule runs off the offer - but
     * it is the difference between "we advertise events" and "events happen" when reading the file.
     */
    public void recordStarted(String eventId) {
        lastStartedAt = System.currentTimeMillis();
        lastEventId = eventId;
        persist();
    }

    /**
     * @return a human-readable stamp for the last offer, for {@code /mb event status}
     */
    public String describeLastOffered() {
        return stamp(lastOfferedAt);
    }

    /**
     * @return {@code 1h 12m}-style text for a duration, or {@code null} for nothing worth showing
     */
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

    /**
     * Writes the record out. File I/O, so it never happens on the main thread — the timestamps in
     * memory are already authoritative, and a write that lands a tick later costs nothing.
     */
    private void persist() {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::write);
            return;
        }

        write();
    }

    private void write() {
        synchronized (writeLock) {
            try {
                writeFile();
            } catch (Exception exception) {
                // Not fatal, and deliberately catching everything: this is called from the middle of
                // proposing an event, and a failure to write a timestamp must not be what stops the
                // event going out. The in-memory schedule keeps working until the next restart.
                plugin.getLogger().warning("Could not write schedule.yml: " + exception.getMessage());
            }
        }
    }

    private void writeFile() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Written by MythicBedwars. When the last event was offered, so the schedule in",
                "config.yml (network.event.schedule) survives a restart of this server or of Redis.",
                "",
                "Set last-offered-at to 0 to make an event due at the next check."));

        yaml.set("last-offered-at", lastOfferedAt);
        yaml.set("last-offered", stamp(lastOfferedAt));
        yaml.set("last-started-at", lastStartedAt);
        yaml.set("last-started", stamp(lastStartedAt));
        yaml.set("last-event-id", lastEventId);

        yaml.setComments("last-offered", List.of("Informational; last-offered-at is the one that is read."));
        yaml.setComments("last-started", List.of("Informational."));

        yaml.save(file);
    }

    private static String stamp(long millis) {
        return millis <= 0L ? "never" : READABLE.format(Instant.ofEpochMilli(millis));
    }
}
