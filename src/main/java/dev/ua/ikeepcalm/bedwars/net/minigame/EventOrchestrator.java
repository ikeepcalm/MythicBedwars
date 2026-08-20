package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.event.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.payload.Payloads;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Bedwars-server half of the event lifecycle: decide whether we can host, reserve an arena, and
 * hold it.
 *
 * <p>Only ever constructed in the {@link NetworkRole#MINIGAME} role — it reaches MBedwars
 * throughout.
 */
public class EventOrchestrator {

    /**
     * How long the host claim survives. It only has to outlive the accept handshake; if we die
     * mid-handshake another server should be able to take over promptly.
     */
    private static final int HOST_CLAIM_TTL_SECONDS = 60;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;
    private final ArenaSelector selector;

    private final Map<String, EventReservation> reservationsByArena = new ConcurrentHashMap<>();
    private final Set<UUID> forcedJoins = ConcurrentHashMap.newKeySet();
    private final Map<String, BukkitTask> holdTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> arrivalTasks = new ConcurrentHashMap<>();

    public EventOrchestrator(MythicBedwars plugin, NetworkService network, EventStore store) {
        this.plugin = plugin;
        this.network = network;
        this.store = store;
        this.selector = new ArenaSelector(plugin);
    }

    public void registerHandlers() {
        network.bus().on(MessageType.EVENT_PROPOSE, this::onPropose);
        network.bus().on(MessageType.ROSTER_CLOSED, this::onRosterClosed);
        network.bus().on(MessageType.EVENT_CANCELLED, this::onCancelled);
    }

    /**
     * @return whether this arena is currently held for an event, which suppresses the magic vote and
     * locks out non-roster players
     */
    public boolean isEventArena(String arenaName) {
        return reservationsByArena.containsKey(arenaName);
    }

    public EventReservation getReservation(String arenaName) {
        return reservationsByArena.get(arenaName);
    }

    public Optional<EventReservation> reservationForEvent(String eventId) {
        return reservationsByArena.values().stream()
                .filter(reservation -> reservation.eventId().equals(eventId))
                .findFirst();
    }

    public boolean isForcedJoin(UUID playerId) {
        return forcedJoins.contains(playerId);
    }

    /**
     * Adds a roster member to their reserved arena.
     *
     * <p>{@link Arena#addPlayer} throws unless the arena is in its lobby, and our own guard would
     * otherwise refuse the join, so both are handled here rather than at each call site.
     */
    public boolean forceJoin(org.bukkit.entity.Player player, Arena arena) {
        forcedJoins.add(player.getUniqueId());
        try {
            return arena.addPlayer(player, null, de.marcely.bedwars.api.arena.AddPlayerCause.PLUGIN) == null;
        } catch (IllegalStateException notInLobby) {
            plugin.log("Cannot add {} to {}: {}", player.getName(), arena.getName(), notInLobby.getMessage());
            return false;
        } finally {
            forcedJoins.remove(player.getUniqueId());
        }
    }

    /**
     * Called when an SMP asks for a host.
     */
    private void onPropose(Envelope envelope) {
        Payloads.Propose propose = network.bus().payload(envelope, Payloads.Propose.class);
        if (propose == null || envelope.eventId() == null) {
            return;
        }

        String eventId = envelope.eventId();

        if (!plugin.getConfigManager().isEventEnabled()) {
            reject(envelope, propose, "events are disabled on this server");
            return;
        }

        if (!reservationsByArena.isEmpty()) {
            reject(envelope, propose, "already hosting an event");
            return;
        }

        int expected = Math.max(propose.minPlayers(), plugin.getConfigManager().getEventMinPlayers());
        Optional<Arena> candidate = selector.select(expected, arena -> !isEventArena(arena.getName()));
        if (candidate.isEmpty()) {
            reject(envelope, propose, "no usable arena for " + expected + " players");
            return;
        }

        // Several Bedwars servers may be able to host; exactly one may answer.
        if (!store.claimHost(eventId, network.serverId(), HOST_CLAIM_TTL_SECONDS)) {
            plugin.log("Another server claimed event {} first; standing down.", eventId);
            return;
        }

        Arena arena = candidate.get();
        long signupDeadline = System.currentTimeMillis()
                              + plugin.getConfigManager().getEventSignupSeconds() * 1000L;

        EventReservation reservation = new EventReservation(
                eventId, arena.getName(), propose.smpServerId(), propose.smpServerName(),
                arena.getMinPlayers(), signupDeadline);
        reservationsByArena.put(arena.getName(), reservation);

        // Pre-seed the vote result: an event match always has magic on, and no VotingSession is
        // ever created for this arena (see MythicBedwars#isEventArena), so nothing can overwrite it.
        plugin.getVotingManager().setMagicEnabled(arena.getName(), plugin.getConfigManager().isEventForceMagic());

        // The hash is written before the reply so a dropped ACCEPT costs latency, not the event.
        store.read(eventId)
                .map(record -> record.accepted(network.serverId(),
                        plugin.getConfigManager().getThisVelocityServer(),
                        arena.getName(), arena.getMaxPlayers(), signupDeadline))
                .ifPresent(store::write);

        network.bus().send(NetworkRole.SMP, MessageType.EVENT_ACCEPT, eventId, propose.smpServerId(),
                new Payloads.Accept(network.serverId(), plugin.getConfigManager().getThisVelocityServer(),
                        arena.getName(), arena.getMaxPlayers(), signupDeadline));

        plugin.log("Accepted event {} on arena {} (capacity {}).", eventId, arena.getName(), arena.getMaxPlayers());
    }

    private void reject(Envelope envelope, Payloads.Propose propose, String reason) {
        plugin.log("Rejecting event {}: {}", envelope.eventId(), reason);
        network.bus().send(NetworkRole.SMP, MessageType.EVENT_REJECT, envelope.eventId(), propose.smpServerId(),
                new Payloads.Reject(network.serverId(), reason));
    }

    /**
     * Signups are closed. Hold the arena open and tell the SMP to start moving people.
     */
    private void onRosterClosed(Envelope envelope) {
        String eventId = envelope.eventId();
        Payloads.RosterClosed closed = network.bus().payload(envelope, Payloads.RosterClosed.class);
        if (closed == null || eventId == null) {
            return;
        }

        EventReservation reservation = reservationForEvent(eventId).orElse(null);
        if (reservation == null) {
            plugin.log("Roster for unknown event {} - ignoring.", eventId);
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());

        // Anything that made this arena eligible can have stopped being true while signups ran.
        if (arena == null || !selector.isStillUsable(arena, closed.count())) {
            plugin.log("Arena {} is no longer usable for event {}.", reservation.arenaName(), eventId);
            cancel(eventId, CancelReason.ARENA_LOST);
            return;
        }

        for (String raw : closed.roster()) {
            try {
                reservation.roster().add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // One malformed id is not worth failing the whole roster over.
            }
        }

        // addPlayer() throws outside the lobby, so promote an idle arena before anybody arrives.
        if (arena.getStatus() == ArenaStatus.STOPPED) {
            arena.setStatus(ArenaStatus.LOBBY);
        }

        int holdSeconds = plugin.getConfigManager().getEventLobbyHoldSeconds();
        LobbyHoldTask hold = new LobbyHoldTask(arena.getName(), holdSeconds);
        holdTasks.put(arena.getName(), hold.runTaskTimer(plugin, 0L, 20L));

        long transferDeadline = System.currentTimeMillis()
                + plugin.getConfigManager().getEventArrivalGraceSeconds() * 1000L;
        reservation.transferDeadline(transferDeadline);

        store.read(eventId).map(record -> record.withState(EventState.ARENA_READY)).ifPresent(store::write);
        network.bus().send(NetworkRole.SMP, MessageType.ARENA_READY, eventId, reservation.smpServerId(),
                new Payloads.ArenaReady(network.serverId(),
                        plugin.getConfigManager().getThisVelocityServer(),
                        arena.getName(), transferDeadline));

        arrivalTasks.put(eventId, Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickArrivals(eventId), 20L, 20L));

        plugin.log("Event {} holding arena {} for {} player(s); arrivals close in {}s.",
                eventId, arena.getName(), reservation.roster().size(),
                plugin.getConfigManager().getEventArrivalGraceSeconds());
    }

    /**
     * Called on the Bedwars server when a recruited player connects.
     *
     * @return whether the player belonged to an event and was handled here
     */
    public boolean handleArrival(Player player) {
        EventReservation reservation = reservationForPlayer(player.getUniqueId()).orElse(null);
        if (reservation == null) {
            return false;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena == null) {
            return false;
        }

        if (!forceJoin(player, arena)) {
            plugin.log("Could not seat arriving player {} in {}.", player.getName(), arena.getName());
            return false;
        }

        reservation.arrived().add(player.getUniqueId());
        network.client().evalLong("redis.call('SADD', KEYS[1], ARGV[1]) return redis.call('SCARD', KEYS[1])",
                List.of(network.keys().eventArrived(reservation.eventId())),
                List.of(player.getUniqueId().toString()), -1L);

        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.arena_welcome",
                "arrived", reservation.arrived().size(), "expected", reservation.roster().size()));

        network.bus().send(NetworkRole.SMP, MessageType.PLAYER_ARRIVED, reservation.eventId(),
                reservation.smpServerId(),
                new Payloads.PlayerArrived(player.getUniqueId().toString(),
                        reservation.arrived().size(), reservation.roster().size()));

        if (reservation.arrived().size() >= reservation.roster().size()) {
            plugin.log("Event {}: everybody arrived, starting.", reservation.eventId());
            beginCountdown(reservation);
        }

        return true;
    }

    private void tickArrivals(String eventId) {
        EventReservation reservation = reservationForEvent(eventId).orElse(null);
        if (reservation == null) {
            stopArrivalTask(eventId);
            return;
        }

        if (System.currentTimeMillis() < reservation.transferDeadline()) {
            return;
        }

        stopArrivalTask(eventId);

        int arrived = reservation.arrived().size();
        if (arrived < plugin.getConfigManager().getEventMinArrivals()) {
            plugin.log("Event {}: only {} arrived, calling it off.", eventId, arrived);
            returnEveryone(reservation, CancelReason.TOO_FEW_ARRIVALS);
            cancel(eventId, CancelReason.TOO_FEW_ARRIVALS);
            return;
        }

        // Late arrivals are out of luck, but locals can take the empty slots for a fuller game.
        reservation.openFill();
        int fillSeconds = plugin.getConfigManager().getEventFillWindowSeconds();
        if (fillSeconds > 0) {
            plugin.log("Event {}: opening {} spare slot(s) to local players for {}s.",
                    eventId, Math.max(0, reservation.roster().size() - arrived), fillSeconds);
            Bukkit.getScheduler().runTaskLater(plugin, () -> beginCountdown(reservation), fillSeconds * 20L);
        } else {
            beginCountdown(reservation);
        }
    }

    /**
     * Hands the arena back to MBedwars with a short countdown.
     *
     * <p>Deliberately not {@code setStatus(RUNNING)}: that would skip MBedwars' own start pipeline
     * (team balancing, spawns), and the existing ArenaListener relies on the normal transition to
     * assign pathways.
     */
    private void beginCountdown(EventReservation reservation) {
        if (reservation.isStarting()) {
            return;
        }
        reservation.markStarting();

        stopArrivalTask(reservation.eventId());
        stopHoldTask(reservation.arenaName());

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena == null || arena.getStatus() != ArenaStatus.LOBBY) {
            cancel(reservation.eventId(), CancelReason.ARENA_LOST);
            return;
        }

        plugin.getVotingManager().setMagicEnabled(arena.getName(),
                plugin.getConfigManager().isEventForceMagic());

        int countdown = plugin.getConfigManager().getEventStartCountdownSeconds();
        arena.setLobbyTimeRemaining(countdown, true);

        store.read(reservation.eventId()).map(record -> record.withState(EventState.RUNNING))
                .ifPresent(store::write);
        network.bus().send(NetworkRole.SMP, MessageType.EVENT_STARTED, reservation.eventId(),
                reservation.smpServerId(),
                new Payloads.EventStarted(arena.getName(), arena.getPlayers().size()));

        plugin.log("Event {} starting on {} with {} player(s).",
                reservation.eventId(), arena.getName(), arena.getPlayers().size());
    }

    /**
     * Kicks everybody out of a reserved arena and sends them home.
     */
    private void returnEveryone(EventReservation reservation, CancelReason reason) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena == null) {
            return;
        }

        List<Player> present = new ArrayList<>(arena.getPlayers());
        for (Player player : present) {
            arena.kickPlayer(player, KickReason.PLUGIN_STOP);
            player.sendMessage(plugin.getLocaleManager().formatMessage(
                    "magic.event.cancelled.generic", "reason", reason.display()));
        }

        plugin.getReturnService().returnAll(
                present.stream().map(Player::getUniqueId).toList(),
                reservation, ReturnOutcome.CANCELLED);
    }

    /**
     * Publishes the match result. Called before any transfer, so the SMP has the outcome even if
     * every subsequent move fails.
     */
    public void publishFinished(EventReservation reservation, Arena arena,
                                de.marcely.bedwars.api.arena.Team winnerTeam, boolean tie,
                                List<Player> winners, List<Player> losers) {
        store.read(reservation.eventId()).map(record -> record.withState(EventState.FINISHED))
                .ifPresent(store::write);

        network.bus().send(NetworkRole.SMP, MessageType.EVENT_FINISHED, reservation.eventId(),
                reservation.smpServerId(),
                new Payloads.EventFinished(
                        arena.getName(), tie,
                        winnerTeam == null ? null : winnerTeam.name(),
                        winners.stream().map(p -> p.getUniqueId().toString()).toList(),
                        losers.stream().map(p -> p.getUniqueId().toString()).toList(),
                        arena.getRunningTime() == null ? 0L : arena.getRunningTime().toMillis()));
    }

    /**
     * Retires a reservation after a match that actually ran, as opposed to one that was called off.
     */
    public void finish(EventReservation reservation) {
        reservationsByArena.remove(reservation.arenaName());
        stopHoldTask(reservation.arenaName());
        stopArrivalTask(reservation.eventId());

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena != null) {
            arena.setMinPlayers(reservation.originalMinPlayers());
        }

        plugin.getVotingManager().cleanupArena(reservation.arenaName());
        store.purge(reservation.eventId());
        store.startCooldown(plugin.getConfigManager().getEventCooldownMinutes() * 60);

        plugin.log("Event {} complete; arena {} released.", reservation.eventId(), reservation.arenaName());
    }

    public Optional<EventReservation> reservationForPlayer(UUID playerId) {
        return reservationsByArena.values().stream()
                .filter(reservation -> reservation.roster().contains(playerId))
                .findFirst();
    }

    private void stopHoldTask(String arenaName) {
        BukkitTask task = holdTasks.remove(arenaName);
        if (task != null) {
            task.cancel();
        }
    }

    private void stopArrivalTask(String eventId) {
        BukkitTask task = arrivalTasks.remove(eventId);
        if (task != null) {
            task.cancel();
        }
    }

    private void onCancelled(Envelope envelope) {
        if (envelope.eventId() == null) {
            return;
        }

        Payloads.Cancelled cancelled = network.bus().payload(envelope, Payloads.Cancelled.class);
        CancelReason reason = cancelled == null ? CancelReason.ADMIN : cancelled.reason();

        reservationForEvent(envelope.eventId())
                .ifPresent(reservation -> release(reservation, reason));
    }

    /**
     * Cancels an event this server is hosting and tells the network.
     */
    public void cancel(String eventId, CancelReason reason) {
        reservationForEvent(eventId).ifPresent(reservation -> {
            store.read(eventId).map(record -> record.cancelled(reason)).ifPresent(store::write);
            network.bus().broadcast(NetworkRole.SMP, MessageType.EVENT_CANCELLED, eventId,
                    new Payloads.Cancelled(reason, network.serverId()));
            release(reservation, reason);
        });
    }

    /**
     * Frees the arena and undoes everything the reservation changed.
     */
    private void release(EventReservation reservation, CancelReason reason) {
        reservationsByArena.remove(reservation.arenaName());
        stopHoldTask(reservation.arenaName());
        stopArrivalTask(reservation.eventId());

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena != null) {
            // Put the arena's own tuning back; an event must not permanently change a map.
            arena.setMinPlayers(reservation.originalMinPlayers());
        }

        plugin.getVotingManager().cleanupArena(reservation.arenaName());
        store.purge(reservation.eventId());

        plugin.log("Released arena {} from event {} ({}).",
                reservation.arenaName(), reservation.eventId(), reason.display());
    }

    /**
     * Drops any event this server was hosting when it went down. Arena state cannot survive a
     * restart, so there is nothing to resume — the only correct move is to abandon it cleanly.
     */
    public void recoverOnBoot() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<String> active = store.activeEventId();
            if (active.isEmpty()) {
                return;
            }

            String eventId = active.get();
            Optional<EventRecord> record = store.read(eventId);
            if (record.isEmpty()) {
                return;
            }

            EventRecord event = record.get();
            if (!network.serverId().equals(event.minigameServerId()) || event.state().isTerminal()) {
                return;
            }

            plugin.log("Abandoning event {} left over from a previous run.", eventId);
            store.write(event.cancelled(CancelReason.HOST_RESTARTED));
            network.bus().broadcast(NetworkRole.SMP, MessageType.EVENT_CANCELLED, eventId,
                    new Payloads.Cancelled(CancelReason.HOST_RESTARTED, network.serverId()));
            store.purge(eventId);
        });
    }

    /**
     * @return a diagnostic line per held arena
     */
    public Map<String, EventReservation> reservations() {
        return Map.copyOf(reservationsByArena);
    }

    /**
     * Called on shutdown: give up anything we are holding so the network is not left waiting.
     */
    public void shutdown() {
        for (EventReservation reservation : Map.copyOf(reservationsByArena).values()) {
            if (EventState.RUNNING != stateOf(reservation)) {
                cancel(reservation.eventId(), CancelReason.HOST_RESTARTED);
            }
        }
    }

    private EventState stateOf(EventReservation reservation) {
        return store.read(reservation.eventId()).map(EventRecord::state).orElse(EventState.CANCELLED);
    }
}
