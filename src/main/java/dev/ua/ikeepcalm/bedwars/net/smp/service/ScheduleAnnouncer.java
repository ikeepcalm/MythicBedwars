package dev.ua.ikeepcalm.bedwars.net.smp.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.smp.model.ScheduleOutlook;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ScheduleAnnouncer {

    private static final long CHECK_PERIOD_TICKS = 20L * 20L;

    private final MythicBedwars plugin;
    private final RecruitmentManager recruitment;
    private final Set<Integer> firedMarks = new HashSet<>();

    private long trackedWindow = Long.MIN_VALUE;
    private volatile BukkitTask task;

    public ScheduleAnnouncer(MythicBedwars plugin, RecruitmentManager recruitment) {
        this.plugin = plugin;
        this.recruitment = recruitment;
    }

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
        ScheduleOutlook outlook = recruitment.outlook();
        if (!outlook.isEnabled()) {
            return;
        }

        long window = recruitment.windowStamp();
        if (window != trackedWindow) {
            trackedWindow = window;
            firedMarks.clear();
        }

        long minutes = outlook.getMinutesUntilDue();
        if (outlook.isEventInFlight() || minutes <= 0L) {
            return;
        }

        if (dueMark(minutes) != null) {
            broadcast(outlook, minutes);
        }
    }

    private Integer dueMark(long minutesRemaining) {
        List<Integer> marks = plugin.getConfigManager().getEventCountdownMarks();
        Integer best = marks.stream()
                .filter(mark -> minutesRemaining <= mark && !firedMarks.contains(mark))
                .max(Integer::compare)
                .orElse(null);

        // every mark at or above the one announced is behind us too; do not walk down them tick by tick
        if (best != null) {
            marks.stream().filter(mark -> mark >= best).forEach(firedMarks::add);
        }
        return best;
    }

    private void broadcast(ScheduleOutlook outlook, long minutes) {
        boolean warn = outlook.isShortfall() && minutes <= plugin.getConfigManager().getEventCountdownWarnMinutes();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("mythicbedwars.event.exempt")) {
                continue;
            }

            player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                    "magic.event.countdown.upcoming", "minutes", minutes));
            if (warn) {
                player.sendMessage(shortfall(player, outlook));
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.6f);
        }

        plugin.log("Announced the next event in {} minute(s){}.", minutes, warn ? " with a player shortfall warning" : "");
    }

    public List<Component> describeFor(Player player) {
        ScheduleOutlook outlook = recruitment.outlook();

        if (!outlook.isEnabled()) {
            return List.of(plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.disabled"));
        }

        if (outlook.isEventInFlight()) {
            return List.of(plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.in_flight"));
        }

        Component headline = outlook.getMillisUntilDue() <= 0L
                ? plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.due_now")
                : plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.upcoming",
                "minutes", outlook.getMinutesUntilDue());

        Component population = plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.population",
                "online", outlook.getOnlineEligible(), "needed", outlook.getRequiredPlayers());

        return outlook.isShortfall()
                ? List.of(headline, population, shortfall(player, outlook))
                : List.of(headline, population);
    }

    private Component shortfall(Player player, ScheduleOutlook outlook) {
        return plugin.getLocaleManager().formatMessage(player, "magic.event.countdown.shortfall",
                "online", outlook.getOnlineEligible(),
                "needed", outlook.getRequiredPlayers(),
                "missing", outlook.getMissing());
    }
}
