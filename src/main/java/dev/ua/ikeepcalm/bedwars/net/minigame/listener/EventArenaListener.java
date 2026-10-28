package dev.ua.ikeepcalm.bedwars.net.minigame.listener;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.event.arena.ArenaBedBreakEvent;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.ArenaUnloadEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.player.PlayerBuyInShopEvent;
import de.marcely.bedwars.api.event.player.PlayerKillPlayerEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.service.RewardService;
import dev.ua.ikeepcalm.bedwars.net.minigame.model.EventReservation;
import dev.ua.ikeepcalm.bedwars.net.minigame.service.EventOrchestrator;
import dev.ua.ikeepcalm.bedwars.net.minigame.service.EventReturnService;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
public class EventArenaListener implements Listener {

    private final MythicBedwars plugin;
    private final EventOrchestrator orchestrator;
    private final EventReturnService returns;
    private final RewardService rewards;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                orchestrator.handleArrival(player);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(PlayerKillPlayerEvent event) {
        Arena arena = event.getArena();
        if (orchestrator.isEventArena(arena.getName()) && event.getKiller() != null) {
            rewards.getTracker().recordKill(arena.getName(), event.getKiller().getUniqueId(), event.isFatalDeath());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedBreak(ArenaBedBreakEvent event) {
        Arena arena = event.getArena();
        if (orchestrator.isEventArena(arena.getName()) && event.getPlayer() != null) {
            rewards.getTracker().recordBedBreak(arena.getName(), event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShopPurchase(PlayerBuyInShopEvent event) {
        Arena arena = event.getArena();
        // not Cancellable: a purchase that failed carries problems instead
        if (arena != null && orchestrator.isEventArena(arena.getName()) && event.getProblems().isEmpty()) {
            rewards.getTracker().recordPurchase(arena.getName(), event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager) {
            markActive(damager);
        } else if (event.getDamager() instanceof Projectile projectile
                   && projectile.getShooter() instanceof Player shooter) {
            markActive(shooter);
        }

        if (event.getEntity() instanceof Player victim) {
            markActive(victim);
        }
    }

    private void markActive(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena != null && orchestrator.isEventArena(arena.getName())) {
            rewards.getTracker().recordActivity(arena.getName(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitArena(PlayerQuitArenaEvent event) {
        Arena arena = event.getArena();
        EventReservation reservation = orchestrator.getReservation(arena.getName());
        if (reservation == null) {
            return;
        }

        Player player = event.getPlayer();
        KickReason reason = event.getReason();
        boolean running = arena.getStatus() == ArenaStatus.RUNNING;

        rewards.getTracker().markQuit(arena.getName(), player.getUniqueId(), reason, running);

        if (!running) {
            orchestrator.forgetArrival(reservation, player.getUniqueId());
        }

        if (player.isOnline()) {
            settleLeaver(player, reservation, reason, running);
        }
    }

    private void settleLeaver(Player player, EventReservation reservation, KickReason reason, boolean running) {
        // KickReason#isRageQuit() also covers SPECTATE/TELEPORT/PLUGIN; only a real LEAVE is a quit
        if (reason == KickReason.LEAVE && running) {
            returns.recordOnly(player.getUniqueId(), reservation, ReturnOutcome.QUIT);
            return;
        }

        boolean spectating = reason == KickReason.SPECTATE;
        if (spectating && plugin.getConfigManager().isEventSpectatorsAllowed()) {
            return;
        }

        if (reason != KickReason.GAME_LOSE && !spectating) {
            return;
        }

        if (reservation.getRoster().contains(player.getUniqueId())) {
            returns.offerReturn(player, reservation, ReturnOutcome.LOSE, plugin.getConfigManager().getEventAutoReturnSeconds());
        } else {
            returns.recordOnly(player.getUniqueId(), reservation, ReturnOutcome.LOSE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(RoundEndEvent event) {
        Arena arena = event.getArena();
        EventReservation reservation = orchestrator.getReservation(arena.getName());
        if (reservation == null) {
            return;
        }

        boolean tie = event.isTie();
        List<Player> winners = new ArrayList<>(event.getWinners());
        // MBedwars reports every collection as empty on a tie
        List<Player> losers = tie ? everyoneIn(arena) : new ArrayList<>(event.getLosers());
        List<UUID> quitWinners = event.getQuitWinners().stream().map(memory -> memory.getUniqueId()).toList();
        List<UUID> quitLosers = event.getQuitLosers().stream().map(memory -> memory.getUniqueId()).toList();

        plugin.log("Event {} finished on {} - {} winner(s), {} loser(s){}.",
                reservation.getEventId(), arena.getName(), winners.size(), losers.size(), tie ? " (tie)" : "");

        rewards.payOut(reservation, arena, winners, losers, quitWinners, quitLosers, tie);
        orchestrator.publishFinished(reservation, arena, event.getWinnerTeam(), tie, winners, losers);

        ReturnOutcome winnerOutcome = tie ? ReturnOutcome.TIE : ReturnOutcome.WIN;
        ReturnOutcome loserOutcome = tie ? ReturnOutcome.TIE : ReturnOutcome.LOSE;
        scheduleReturns(arena, reservation, winners, losers, winnerOutcome, loserOutcome);

        quitWinners.forEach(id -> returns.recordOnly(id, reservation, winnerOutcome));
        quitLosers.forEach(id -> returns.recordOnly(id, reservation, loserOutcome));
    }

    private void scheduleReturns(Arena arena, EventReservation reservation, List<Player> winners, List<Player> losers,
                                 ReturnOutcome winnerOutcome, ReturnOutcome loserOutcome) {
        List<UUID> winnerIds = winners.stream().map(Player::getUniqueId).toList();
        List<UUID> loserIds = losers.stream().map(Player::getUniqueId).toList();
        List<UUID> spectators = everyoneIn(arena).stream()
                .map(Player::getUniqueId)
                .filter(id -> !winnerIds.contains(id) && !loserIds.contains(id))
                .toList();

        int delay = plugin.getConfigManager().getEventWinnerReturnDelaySeconds();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sendHome(winnerIds, reservation, winnerOutcome);
            sendHome(loserIds, reservation, loserOutcome);
            sendHome(spectators, reservation, loserOutcome);
            returns.clear(reservation.getEventId());
        }, Math.max(1, delay) * 20L);
    }

    private void sendHome(List<UUID> playerIds, EventReservation reservation, ReturnOutcome outcome) {
        List<UUID> travelling = new ArrayList<>();
        for (UUID playerId : playerIds) {
            if (reservation.getRoster().contains(playerId)) {
                travelling.add(playerId);
            } else {
                returns.recordOnly(playerId, reservation, outcome);
            }
        }

        if (!travelling.isEmpty()) {
            returns.returnAll(travelling, reservation, outcome);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArenaStatusChange(ArenaStatusChangeEvent event) {
        EventReservation reservation = orchestrator.getReservation(event.getArena().getName());
        if (reservation == null) {
            return;
        }

        // a non-cloned arena goes END_LOBBY -> RESETTING -> LOBBY after a match and never reaches STOPPED
        ArenaStatus status = event.getNewStatus();
        if (status == ArenaStatus.STOPPED || (status == ArenaStatus.RESETTING && reservation.isStarting())) {
            retire(event.getArena(), reservation);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArenaUnload(ArenaUnloadEvent event) {
        EventReservation reservation = orchestrator.getReservation(event.getArena().getName());
        if (reservation != null) {
            retire(event.getArena(), reservation);
        }
    }

    private void retire(Arena arena, EventReservation reservation) {
        rewards.getTracker().clear(arena.getName());
        orchestrator.finish(reservation);
    }

    private static List<Player> everyoneIn(Arena arena) {
        List<Player> present = new ArrayList<>(arena.getPlayers());
        arena.getSpectators().stream().filter(spectator -> !present.contains(spectator)).forEach(present::add);
        return present;
    }
}
