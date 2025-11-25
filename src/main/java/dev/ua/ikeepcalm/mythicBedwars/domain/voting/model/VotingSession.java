package dev.ua.ikeepcalm.mythicBedwars.domain.voting.model;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.runnable.VotingReminderTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VotingSession {

    private final Arena arena;
    private final MythicBedwars plugin;
    private final Map<UUID, Boolean> votes = new ConcurrentHashMap<>();
    private final VotingReminderTask reminderTask;
    private boolean active = false;
    private boolean magicEnabled = true;

    public VotingSession(Arena arena, MythicBedwars plugin) {
        this.arena = arena;
        this.plugin = plugin;
        this.reminderTask = new VotingReminderTask(plugin, arena, this);
    }

    public void start() {
        active = true;
        reminderTask.runTaskTimerAsynchronously(plugin, 20L, 200L);
        broadcastMessage("magic.voting.started", NamedTextColor.YELLOW);
        broadcastMessage("magic.voting.instructions", NamedTextColor.GRAY);
    }

    public void castVote(UUID playerId, boolean voteYes) {
        if (!active) return;

        votes.put(playerId, voteYes);
        updateVoteStatus();
    }

    private void updateVoteStatus() {
        int totalPlayers = arena.getPlayers().size();
        int yesVotes = (int) votes.values().stream().filter(vote -> vote).count();
        int noVotes = votes.size() - yesVotes;

        Component statusMessage = plugin.getLocaleManager().formatMessage("magic.voting.status",
                "yes", yesVotes, "no", noVotes, "total", totalPlayers);

        for (Player player : arena.getPlayers()) {
            player.sendMessage(statusMessage.color(NamedTextColor.AQUA));
        }
    }

    public void end() {
        if (!active) return;
        active = false;
        this.reminderTask.cancel();

        int totalPlayers = arena.getPlayers().size();
        int yesVotes = (int) votes.values().stream().filter(vote -> vote).count();
        int noVotes = votes.size() - yesVotes;
        int totalVotes = votes.size();

        if (totalVotes == 0) {
            magicEnabled = true;
            broadcastMessage("magic.voting.no_votes_at_all", NamedTextColor.YELLOW);
            return;
        }

        double yesPercentageOfAll = (double) yesVotes / totalPlayers;
        double noPercentageOfAll = (double) noVotes / totalPlayers;

        if (yesPercentageOfAll >= 0.5) {
            magicEnabled = true;
        } else if (noPercentageOfAll > 0.5) {
            magicEnabled = false;
        } else {
            double yesPercentageOfVoters = (double) yesVotes / totalVotes;
            magicEnabled = yesPercentageOfVoters >= 0.5;
        }

        if (magicEnabled) {
            broadcastMessage("magic.voting.magic_enabled", NamedTextColor.GREEN);
        } else {
            broadcastMessage("magic.voting.magic_disabled", NamedTextColor.RED);
        }

        Component resultMessage = plugin.getLocaleManager().formatMessage("magic.voting.final_result",
                "yes", yesVotes, "no", noVotes);

        for (Player player : arena.getPlayers()) {
            player.sendMessage(resultMessage.color(NamedTextColor.GOLD));
        }

        Component participationMessage = plugin.getLocaleManager().formatMessage("magic.voting.participation",
                "voted", totalVotes, "total", totalPlayers);
        for (Player player : arena.getPlayers()) {
            player.sendMessage(participationMessage.color(NamedTextColor.AQUA));
        }
    }

    private void broadcastMessage(String key, NamedTextColor color) {
        Component message = plugin.getLocaleManager().formatMessage(key);
        for (Player player : arena.getPlayers()) {
            player.sendMessage(message.color(color));
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean isMagicEnabled() {
        return magicEnabled;
    }

    public int getYesVotes() {
        return (int) votes.values().stream().filter(vote -> vote).count();
    }

    public int getNoVotes() {
        return votes.size() - getYesVotes();
    }

    public boolean hasVoted(UUID playerId) {
        return votes.containsKey(playerId);
    }

    public Boolean getVote(UUID playerId) {
        return votes.get(playerId);
    }
}