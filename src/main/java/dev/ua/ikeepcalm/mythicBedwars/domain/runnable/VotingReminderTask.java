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

    private final Arena arena;
    private final VotingSession session;
    private final int maxReminders;
    private int reminderCount = 0;

    public VotingReminderTask(MythicBedwars plugin, Arena arena, VotingSession session) {
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
            Title title = Title.title(
                    Component.text("Голосування!", NamedTextColor.YELLOW),
                    Component.text("Клікніть на зелений або червоний барвник!", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))
            );
            player.showTitle(title);
        } else {
            player.sendMessage(Component.text("⚡ Не забудьте проголосувати за магію!", NamedTextColor.YELLOW));
        }

        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);
    }
}