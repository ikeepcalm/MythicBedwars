package dev.ua.ikeepcalm.mythicBedwars.domain.runnable;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.voting.model.VotingSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.UUID;

public class VotingReminderTask extends BukkitRunnable {

    private final MythicBedwars plugin;
    private final Arena arena;
    private final VotingSession session;
    private final int maxReminders;
    private int reminderCount = 0;

    public VotingReminderTask(MythicBedwars plugin, Arena arena, VotingSession session) {
        this.plugin = plugin;
        this.arena = arena;
        this.session = session;
        this.maxReminders = plugin.getConfigManager().getMaxVotingReminders();
    }

    @Override
    public void run() {
        if (!session.isActive() || arena.getPlayers().isEmpty()) {
            this.cancel();
            return;
        }

        reminderCount++;

        for (Player player : arena.getPlayers()) {
            UUID playerId = player.getUniqueId();
            if (!session.hasVoted(playerId)) {
                sendVotingReminder(player);
            }
        }

        if (reminderCount >= maxReminders) {
            this.cancel();
        }
    }

    private void sendVotingReminder(Player player) {
        if (reminderCount % 2 == 0) {
            Component titleComponent = plugin.getLocaleManager().formatMessage("magic.voting.reminder_title");
            Component subtitleComponent = plugin.getLocaleManager().formatMessage("magic.voting.reminder_subtitle");

            Title title = Title.title(
                    titleComponent,
                    subtitleComponent,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))
            );
            player.showTitle(title);
        } else {
            player.sendMessage(plugin.getLocaleManager().formatMessage("magic.voting.reminder_message"));
        }

        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);
    }
}