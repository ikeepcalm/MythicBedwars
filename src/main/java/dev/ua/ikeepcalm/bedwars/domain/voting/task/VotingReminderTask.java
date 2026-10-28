package dev.ua.ikeepcalm.bedwars.domain.voting.task;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.LocaleLoader;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.VotingSession;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;

public class VotingReminderTask extends BukkitRunnable {

    private static final Title.Times TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500));

    private final MythicBedwars plugin;
    private final Arena arena;
    private final VotingSession session;
    private final int maxReminders;
    private int reminderCount;

    public VotingReminderTask(MythicBedwars plugin, Arena arena, VotingSession session) {
        this.plugin = plugin;
        this.arena = arena;
        this.session = session;
        this.maxReminders = plugin.getConfigManager().getMaxVotingReminders();
    }

    @Override
    public void run() {
        if (!session.isActive()) {
            cancel();
            return;
        }
        if (arena.getPlayers().isEmpty()) {
            return;
        }

        reminderCount++;
        for (Player player : arena.getPlayers()) {
            if (!session.hasVoted(player.getUniqueId())) {
                remind(player);
            }
        }

        if (reminderCount >= maxReminders) {
            cancel();
        }
    }

    private void remind(Player player) {
        LocaleLoader locale = plugin.getLocaleManager();
        if (reminderCount % 2 == 0) {
            player.showTitle(Title.title(
                    locale.formatMessage(player, "magic.voting.reminder_title"),
                    locale.formatMessage(player, "magic.voting.reminder_subtitle"),
                    TITLE_TIMES));
        } else {
            player.sendMessage(locale.formatMessage(player, "magic.voting.reminder_message"));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);
    }
}
