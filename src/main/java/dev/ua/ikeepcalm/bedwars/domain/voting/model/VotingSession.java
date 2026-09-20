package dev.ua.ikeepcalm.bedwars.domain.voting.model;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.runnable.VotingReminderTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VotingSession {

    private final Arena arena;
    private final MythicBedwars plugin;
    private final Map<UUID, MagicMode> votes = new ConcurrentHashMap<>();
    private final VotingReminderTask reminderTask;

    /**
     * Whether the reminder task was actually scheduled. Needed because {@code BukkitRunnable.cancel()}
     * throws for a runnable that was never scheduled, and reminders are now optional.
     */
    private boolean remindersRunning = false;
    private boolean active = false;
    private volatile MagicMode result = MagicMode.TEAM;

    public VotingSession(Arena arena, MythicBedwars plugin) {
        this.arena = arena;
        this.plugin = plugin;
        this.reminderTask = new VotingReminderTask(plugin, arena, this);
        this.result = plugin.getConfigManager().getDefaultMagicMode();
    }

    public void start() {
        active = true;

        if (plugin.getConfigManager().isVotingRemindersEnabled()) {
            long period = Math.max(1L, plugin.getConfigManager().getVotingReminderInterval()) * 20L;
            reminderTask.runTaskTimerAsynchronously(plugin, period, period);
            remindersRunning = true;
        }

        broadcastMessage("magic.voting.started", NamedTextColor.YELLOW);
        broadcastMessage("magic.voting.instructions", NamedTextColor.GRAY);
    }

    public void castVote(UUID playerId, MagicMode mode) {
        if (!active) return;

        votes.put(playerId, mode);
        updateVoteStatus();
    }

    /**
     * @return the modes a player may actually vote for, in ballot order. Individual mode can be
     * switched off in config, in which case it is not offered and not tallied.
     */
    public List<MagicMode> ballot() {
        if (plugin.getConfigManager().isIndividualMagicModeEnabled()) {
            return List.of(MagicMode.TEAM, MagicMode.INDIVIDUAL, MagicMode.OFF);
        }

        return List.of(MagicMode.TEAM, MagicMode.OFF);
    }

    private void updateVoteStatus() {
        Component statusMessage = plugin.getLocaleManager().formatMessage("magic.voting.status",
                "team", countVotes(MagicMode.TEAM),
                "individual", countVotes(MagicMode.INDIVIDUAL),
                "off", countVotes(MagicMode.OFF),
                "total", arena.getPlayers().size());

        for (Player player : arena.getPlayers()) {
            player.sendMessage(statusMessage.color(NamedTextColor.AQUA));
        }
    }

    public void end() {
        if (!active) return;
        active = false;

        if (remindersRunning) {
            this.reminderTask.cancel();
            remindersRunning = false;
        }

        int totalPlayers = arena.getPlayers().size();
        int totalVotes = votes.size();

        if (totalVotes == 0) {
            result = plugin.getConfigManager().getDefaultMagicMode();
            broadcastMessage("magic.voting.no_votes_at_all", NamedTextColor.YELLOW);
            announceResult();
            return;
        }

        result = tally();
        announceResult();

        Component resultMessage = plugin.getLocaleManager().formatMessage("magic.voting.final_result",
                "team", countVotes(MagicMode.TEAM),
                "individual", countVotes(MagicMode.INDIVIDUAL),
                "off", countVotes(MagicMode.OFF));

        for (Player player : arena.getPlayers()) {
            player.sendMessage(resultMessage.color(NamedTextColor.GOLD));
        }

        Component participationMessage = plugin.getLocaleManager().formatMessage("magic.voting.participation",
                "voted", totalVotes, "total", totalPlayers);
        for (Player player : arena.getPlayers()) {
            player.sendMessage(participationMessage.color(NamedTextColor.AQUA));
        }
    }

    /**
     * Decides the round's mode from the ballots cast.
     *
     * <p>A straight plurality, which is the only tally that stays fair once there are three
     * options: the old two-option rule demanded an absolute majority of <i>everyone present</i> to
     * turn magic off, and carrying that forward would let a third of the lobby impose a mode the
     * other two thirds each voted against.
     *
     * <p>Ties break towards the earlier entry on the ballot — {@code TEAM}, then {@code INDIVIDUAL},
     * then {@code OFF} — so a deadlocked lobby lands on the configured norm rather than on whichever
     * enum constant happened to be iterated first.
     */
    private MagicMode tally() {
        Map<MagicMode, Integer> counts = new EnumMap<>(MagicMode.class);
        for (MagicMode mode : votes.values()) {
            counts.merge(mode, 1, Integer::sum);
        }

        MagicMode winner = null;
        int best = -1;

        for (MagicMode mode : ballot()) {
            int count = counts.getOrDefault(mode, 0);
            if (count > best) {
                best = count;
                winner = mode;
            }
        }

        return winner == null ? plugin.getConfigManager().getDefaultMagicMode() : winner;
    }

    private void announceResult() {
        NamedTextColor color = switch (result) {
            case OFF -> NamedTextColor.RED;
            case TEAM -> NamedTextColor.GREEN;
            case INDIVIDUAL -> NamedTextColor.LIGHT_PURPLE;
        };

        broadcastMessage("magic.voting.result." + result.key(), color);
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

    public MagicMode getResult() {
        return result;
    }

    public boolean isMagicEnabled() {
        return result.isMagicEnabled();
    }

    public int countVotes(MagicMode mode) {
        return (int) votes.values().stream().filter(vote -> vote == mode).count();
    }

    public boolean hasVoted(UUID playerId) {
        return votes.containsKey(playerId);
    }

    public MagicMode getVote(UUID playerId) {
        return votes.get(playerId);
    }
}
