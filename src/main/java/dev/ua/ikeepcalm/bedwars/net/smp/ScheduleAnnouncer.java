package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells the survival server when the next Bedwars attempt is coming, and warns when it looks like
 * it will not happen.
 *
 * <p>The schedule has always known both of these things; until now the only way to ask was
 * {@code /mb event status}, which is admin-gated, so to a player events simply materialised out of
 * nowhere every few hours. Two consequences: nobody could plan around one, and nobody knew that the
 * reason none had run all evening was a population one short of the threshold — the one problem the
 * players themselves could actually fix.
 *
 * <p>Announces at configured minute marks rather than on a fixed period. A countdown that repeats
 * every ten minutes regardless of how far off the event is becomes wallpaper; one that appears at
 * an hour, then thirty minutes, then ten, reads as something approaching.
 */
public class ScheduleAnnouncer {

    /**
     * How often the marks are checked. Frequent enough that a mark is never missed by more than a
     * few seconds, cheap enough to be irrelevant: it reads two ints and a long off local state.
     */
    private static final long CHECK_PERIOD_TICKS = 20L * 20L;

    private final MythicBedwars plugin;
    private final RecruitmentManager recruitment;

    /**
     * Marks already announced for the window currently running. Cleared whenever the window
     * changes, which is what stops a single window announcing "30 minutes" twice and what lets the
     * next one announce it again.
     */
    private final Set<Integer> firedMarks = new HashSet<>();

    /**
     * The window the fired marks belong to — the timestamp of the last offer. A new offer moves it,
     * which is the signal to start counting down again.
     */
    private long trackedWindow = Long.MIN_VALUE;

    private volatile BukkitTask task;

    public ScheduleAnnouncer(MythicBedwars plugin, RecruitmentManager recruitment) {
        this.plugin = plugin;
        this.recruitment = recruitment;
    }

    /**
     * Starts, restarts or stops the countdown to match the current config. Idempotent, so
     * {@code /mb reload} can turn it on or off without a restart.
     */
    public void start() {
        stop();

        if (!plugin.getConfigManager().isEventCountdownEnabled()) {
            return;
        }

        if (plugin.getConfigManager().getEventCountdownMarks().isEmpty()) {
            plugin.log("Event countdown has no broadcast marks configured; nothing will be announced.");
            return;
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
    }

    public void stop() {
        BukkitTask existing = task;
        if (existing != null) {
            existing.cancel();
            task = null;
        }
    }

    private void tick() {
        RecruitmentManager.ScheduleOutlook outlook = recruitment.outlook();

        if (!outlook.enabled()) {
            return;
        }

        long window = recruitment.windowStamp();
        if (window != trackedWindow) {
            trackedWindow = window;
            firedMarks.clear();
        }

        // A drive already under way is louder than any countdown to one, and the recruitment
        // announcer is already talking to the same people.
        if (outlook.eventInFlight()) {
            return;
        }

        long minutes = outlook.minutesUntilDue();

        // Due now, or overdue and waiting on players. The marks have nothing left to count down to;
        // the shortfall warning below is what still matters, and it rides on the smallest mark.
        if (minutes <= 0L) {
            return;
        }

        Integer mark = dueMark(minutes);
        if (mark == null) {
            return;
        }

        firedMarks.add(mark);
        broadcast(outlook, minutes);
    }

    /**
     * @return the largest unfired mark the countdown has now reached, or {@code null} when none has
     * been reached. Largest first so a server that was empty across several marks announces the one
     * that is actually closest rather than working through the backlog one tick at a time.
     */
    private Integer dueMark(long minutesRemaining) {
        Integer best = null;

        for (int mark : plugin.getConfigManager().getEventCountdownMarks()) {
            if (minutesRemaining > mark || firedMarks.contains(mark)) {
                continue;
            }

            if (best == null || mark > best) {
                best = mark;
            }
        }

        // Everything larger than the mark we are about to announce is also behind us; recording
        // them now is what stops the next few ticks walking down the list.
        if (best != null) {
            for (int mark : plugin.getConfigManager().getEventCountdownMarks()) {
                if (mark >= best) {
                    firedMarks.add(mark);
                }
            }
        }

        return best;
    }

    private void broadcast(RecruitmentManager.ScheduleOutlook outlook, long minutes) {
        boolean warn = outlook.shortfall()
                && minutes <= plugin.getConfigManager().getEventCountdownWarnMinutes();

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Anyone opted out of recruitment broadcasts is opted out of being told one is coming.
            if (player.hasPermission("mythicbedwars.event.exempt")) {
                continue;
            }

            player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                    "magic.event.countdown.upcoming", "minutes", minutes));

            if (warn) {
                player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                        "magic.event.countdown.shortfall",
                        "online", outlook.onlineEligible(),
                        "needed", outlook.requiredPlayers(),
                        "missing", outlook.missing()));
            }

            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.6f);
        }

        plugin.log("Announced the next event in {} minute(s){}.", minutes,
                warn ? " with a player shortfall warning" : "");
    }

    /**
     * Renders the countdown to one recipient, for {@code /mb event next}.
     *
     * @return the lines to send, already localised for this player
     */
    public List<Component> describeFor(Player player) {
        RecruitmentManager.ScheduleOutlook outlook = recruitment.outlook();

        if (!outlook.enabled()) {
            return List.of(plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.disabled"));
        }

        if (outlook.eventInFlight()) {
            return List.of(plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.in_flight"));
        }

        Component headline = outlook.millisUntilDue() <= 0L
                ? plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.due_now")
                : plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.upcoming",
                "minutes", outlook.minutesUntilDue());

        Component population = plugin.getLocaleManager().formatMessage(player,
                "magic.event.countdown.population",
                "online", outlook.onlineEligible(),
                "needed", outlook.requiredPlayers());

        if (!outlook.shortfall()) {
            return List.of(headline, population);
        }

        return List.of(headline, population, plugin.getLocaleManager().formatMessage(player,
                "magic.event.countdown.shortfall",
                "online", outlook.onlineEligible(),
                "needed", outlook.requiredPlayers(),
                "missing", outlook.missing()));
    }
}
