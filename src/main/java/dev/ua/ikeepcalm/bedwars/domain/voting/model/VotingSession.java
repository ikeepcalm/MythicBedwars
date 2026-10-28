package dev.ua.ikeepcalm.bedwars.domain.voting.model;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.source.MagicMode;
import dev.ua.ikeepcalm.bedwars.domain.voting.task.VotingReminderTask;
import lombok.Getter;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class VotingSession {

    private final Arena arena;
    private final MythicBedwars plugin;
    private final Map<UUID, MagicMode> votes = new ConcurrentHashMap<>();
    private VotingReminderTask reminderTask;
    @Getter
    private volatile boolean active;
    @Getter
    private volatile MagicMode result;

    public VotingSession(Arena arena, MythicBedwars plugin) {
        this.arena = arena;
        this.plugin = plugin;
        this.result = plugin.getConfigManager().getDefaultMagicMode();
    }

    public void start() {
        active = true;

        if (plugin.getConfigManager().isVotingRemindersEnabled()) {
            long period = Math.max(1L, plugin.getConfigManager().getVotingReminderInterval()) * 20L;
            reminderTask = new VotingReminderTask(plugin, arena, this);
            reminderTask.runTaskTimer(plugin, period, period);
        }

        broadcast("magic.voting.started", NamedTextColor.YELLOW);
        broadcast("magic.voting.instructions", NamedTextColor.GRAY);
    }

    public void castVote(UUID playerId, MagicMode mode) {
        if (!active) return;

        votes.put(playerId, mode);
        broadcast("magic.voting.status", NamedTextColor.AQUA,
                "team", countVotes(MagicMode.TEAM),
                "individual", countVotes(MagicMode.INDIVIDUAL),
                "off", countVotes(MagicMode.OFF),
                "total", arena.getPlayers().size());
    }

    public List<MagicMode> ballot() {
        if (plugin.getConfigManager().isIndividualMagicModeEnabled()) {
            return List.of(MagicMode.TEAM, MagicMode.INDIVIDUAL, MagicMode.OFF);
        }
        return List.of(MagicMode.TEAM, MagicMode.OFF);
    }

    public void cancel() {
        active = false;
        if (reminderTask != null && !reminderTask.isCancelled()) {
            reminderTask.cancel();
        }
    }

    public void end() {
        if (!active) return;
        cancel();

        Set<UUID> present = arena.getPlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());
        votes.keySet().retainAll(present);

        int totalPlayers = present.size();
        int totalVotes = votes.size();
        if (totalVotes == 0 || (double) totalVotes / Math.max(1, totalPlayers)
                               < plugin.getConfigManager().getVotingMinParticipation()) {
            settleLowTurnout(totalVotes, totalPlayers);
            return;
        }

        result = tally();
        announceResult();
        broadcast("magic.voting.final_result", NamedTextColor.GOLD,
                "team", countVotes(MagicMode.TEAM),
                "individual", countVotes(MagicMode.INDIVIDUAL),
                "off", countVotes(MagicMode.OFF));
        broadcast("magic.voting.participation", NamedTextColor.AQUA, "voted", totalVotes, "total", totalPlayers);
    }

    private void settleLowTurnout(int totalVotes, int totalPlayers) {
        MagicMode fallback = plugin.getConfigManager().getDefaultMagicMode();
        if (fallback.isMagicEnabled()) {
            result = rollMagicMode();
            broadcast("magic.voting.low_turnout", NamedTextColor.YELLOW, "voted", totalVotes, "total", totalPlayers);
        } else {
            result = fallback;
            broadcast("magic.voting.no_votes_at_all", NamedTextColor.YELLOW);
        }
        announceResult();
    }

    private MagicMode tally() {
        int off = countVotes(MagicMode.OFF);
        int team = countVotes(MagicMode.TEAM);
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

    private MagicMode rollMagicMode() {
        List<MagicMode> options = ballot().stream().filter(MagicMode::isMagicEnabled).toList();
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private void announceResult() {
        broadcast("magic.voting.result." + result.key(), result.getColor());
    }

    private void broadcast(String key, TextColor color, Object... args) {
        for (Player player : arena.getPlayers()) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, key, args).color(color));
        }
    }

    public int countVotes(MagicMode mode) {
        return (int) votes.values().stream().filter(vote -> vote == mode).count();
    }

    public boolean hasVoted(UUID playerId) {
        return votes.containsKey(playerId);
    }
}
