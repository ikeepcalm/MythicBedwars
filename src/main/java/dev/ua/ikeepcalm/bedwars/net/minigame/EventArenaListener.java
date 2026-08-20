package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaBedBreakEvent;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.player.PlayerKillPlayerEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.RewardService;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ties the Bedwars match itself to the event lifecycle: seating arrivals, tracking who did what,
 * paying out, and sending everybody home.
 *
 * <p>Registered only in the minigame role.
 */
public class EventArenaListener implements Listener {

    private final MythicBedwars plugin;
    private final EventOrchestrator orchestrator;
    private final EventReturnService returns;
    private final RewardService rewards;

    public EventArenaListener(MythicBedwars plugin, EventOrchestrator orchestrator,
                              EventReturnService returns, RewardService rewards) {
        this.plugin = plugin;
        this.orchestrator = orchestrator;
        this.returns = returns;
        this.rewards = rewards;
    }

    /**
     * A recruit landing on this server. Seat them in the arena that is being held for them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // A tick of grace: MBedwars and COI both do their own join handling, and adding somebody to
        // an arena from inside their login is asking for trouble.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                orchestrator.handleArrival(event.getPlayer());
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(PlayerKillPlayerEvent event) {
        Arena arena = event.getArena();
        if (!orchestrator.isEventArena(arena.getName()) || event.getKiller() == null) {
            return;
        }

        rewards.tracker().recordKill(arena.getName(), event.getKiller().getUniqueId(), event.isFatalDeath());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedBreak(ArenaBedBreakEvent event) {
        Arena arena = event.getArena();
        if (!orchestrator.isEventArena(arena.getName()) || event.getPlayer() == null) {
            return;
        }

        rewards.tracker().recordBedBreak(arena.getName(), event.getPlayer().getUniqueId());
    }

    /**
     * Somebody left the arena. Eliminated players get offered a ride home; rage quitters get
     * recorded as such so they do not collect.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitArena(PlayerQuitArenaEvent event) {
        Arena arena = event.getArena();
        EventReservation reservation = orchestrator.getReservation(arena.getName());
        if (reservation == null) {
            return;
        }

        Player player = event.getPlayer();
        KickReason reason = event.getReason();
        rewards.tracker().markQuit(arena.getName(), player.getUniqueId(), reason);

        if (!player.isOnline()) {
            // Disconnected rather than eliminated; their outcome is settled at RoundEndEvent.
            return;
        }

        if (reason.isRageQuit()) {
            returns.recordOnly(player.getUniqueId(), reservation, ReturnOutcome.QUIT);
            return;
        }

        if (reason == KickReason.GAME_LOSE) {
            returns.offerReturn(player, reservation, ReturnOutcome.LOSE,
                    plugin.getConfigManager().getEventAutoReturnSeconds());
        }
    }

    /**
     * The authoritative end of the match.
     *
     * <p>Everything that needs Beyonder context happens here, because the
     * {@code PlayerQuitArenaEvent} cascade that follows tears the sandbox loadouts down.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(RoundEndEvent event) {
        Arena arena = event.getArena();
        EventReservation reservation = orchestrator.getReservation(arena.getName());
        if (reservation == null) {
            return;
        }

        Team winningTeam = event.getWinnerTeam();
        List<Player> winners = new ArrayList<>(event.getWinners());
        List<Player> losers = new ArrayList<>(event.getLosers());

        List<UUID> quitWinners = event.getQuitWinners().stream().map(memory -> memory.getUniqueId()).toList();
        List<UUID> quitLosers = event.getQuitLosers().stream().map(memory -> memory.getUniqueId()).toList();

        plugin.log("Event {} finished on {} - {} winner(s), {} loser(s).",
                reservation.eventId(), arena.getName(), winners.size(), losers.size());

        rewards.payOut(reservation, arena, winners, losers, quitWinners, quitLosers, event.isTie());
        orchestrator.publishFinished(reservation, arena, winningTeam, event.isTie(), winners, losers);

        // Let the celebration play before moving anybody.
        int delay = plugin.getConfigManager().getEventWinnerReturnDelaySeconds();
        List<UUID> present = new ArrayList<>();
        winners.forEach(player -> present.add(player.getUniqueId()));
        losers.forEach(player -> present.add(player.getUniqueId()));

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> returns.returnAll(present, reservation,
                        event.isTie() ? ReturnOutcome.TIE : ReturnOutcome.WIN),
                Math.max(1, delay) * 20L);

        // Nobody is left to move, but their results still have to reach the SMP.
        quitWinners.forEach(id -> returns.recordOnly(id, reservation, ReturnOutcome.WIN));
        quitLosers.forEach(id -> returns.recordOnly(id, reservation, ReturnOutcome.LOSE));
    }

    /**
     * Arena went idle: give it back and forget the match.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArenaStatusChange(ArenaStatusChangeEvent event) {
        if (event.getNewStatus() != ArenaStatus.STOPPED) {
            return;
        }

        EventReservation reservation = orchestrator.getReservation(event.getArena().getName());
        if (reservation == null) {
            return;
        }

        rewards.tracker().clear(event.getArena().getName());
        returns.clear(reservation.roster());
        orchestrator.finish(reservation);
    }
}
