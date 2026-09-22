package dev.ua.ikeepcalm.bedwars.domain.voting.model;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.runnable.VotingReminderTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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

        // Ballots from players who left the lobby would otherwise both decide the round and push
        // the participation ratio past 100%.
        Set<UUID> present = new HashSet<>();
        for (Player player : arena.getPlayers()) {
            present.add(player.getUniqueId());
        }
        votes.keySet().retainAll(present);

        int totalPlayers = present.size();
        int totalVotes = votes.size();

        if (totalVotes == 0 || (double) totalVotes / Math.max(1, totalPlayers)
                               < plugin.getConfigManager().getVotingMinParticipation()) {
            MagicMode fallback = plugin.getConfigManager().getDefaultMagicMode();

            // default-mode: OFF is the operator making magic opt-in, so a lobby that did not opt in
            // keeps ordinary Bedwars rather than being rolled into magic.
            if (!fallback.isMagicEnabled()) {
                result = fallback;
                broadcastMessage("magic.voting.no_votes_at_all", NamedTextColor.YELLOW);
            } else {
                result = rollMagicMode();
                Component message = plugin.getLocaleManager().formatMessage("magic.voting.low_turnout",
                        "voted", totalVotes, "total", totalPlayers);
                for (Player player : arena.getPlayers()) {
                    player.sendMessage(message.color(NamedTextColor.YELLOW));
                }
            }

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
     * Decides the round's mode from the ballots cast, in two rounds.
     *
     * <p>First whether magic runs at all: {@code TEAM} and {@code INDIVIDUAL} are both votes for
     * magic, and counting them apart would split that side. A straight three-way plurality let
     * 3 team + 3 individual lose to 4 off, turning magic off for a lobby six tenths of which asked
     * for it. Then, among the magic voters only, which kind.
     *
     * <p>Ties break towards magic, then towards the configured default mode (or {@code TEAM} when
     * that default is {@code OFF}).
     */
    private MagicMode tally() {
        int off = countVotes(MagicMode.OFF);
        int team = countVotes(MagicMode.TEAM);
        // Only while it is on the ballot: a reload that switched it off mid-lobby must not let
        // ballots already cast for it win.
        int individual = ballot().contains(MagicMode.INDIVIDUAL) ? countVotes(MagicMode.INDIVIDUAL) : 0;

        if (off > team + individual) {
            return MagicMode.OFF;
        }

        if (team == individual) {
            MagicMode fallback = plugin.getConfigManager().getDefaultMagicMode();
            return fallback.isMagicEnabled() ? fallback : MagicMode.TEAM;
        }

        return team > individual ? MagicMode.TEAM : MagicMode.INDIVIDUAL;
    }

    /**
     * @return one of the enabled modes on the ballot, at random
     */
    private MagicMode rollMagicMode() {
        List<MagicMode> options = ballot().stream().filter(MagicMode::isMagicEnabled).toList();
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
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
