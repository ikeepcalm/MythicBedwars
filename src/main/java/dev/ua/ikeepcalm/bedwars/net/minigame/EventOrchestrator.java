package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.EventParticipant;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.event.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.payload.Payloads;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import dev.ua.ikeepcalm.bedwars.net.transport.LuaScripts;
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
 *
 * <p><b>Threading.</b> Bus handlers arrive on the main thread (the bus hops for us), which is
 * required for MBedwars calls but wrong for Redis. Every Redis round trip here is therefore pushed
 * to an async task and any follow-up hops back. The pattern matters: {@code onPropose} alone is five
 * sequential round trips, and {@code publishFinished} runs inside {@code RoundEndEvent}.
 */
public class EventOrchestrator implements EventParticipant {

    /**
     * How long the host claim survives. It only has to outlive the accept handshake; if we die
     * mid-handshake another server should be able to take over promptly.
     */
    private static final int HOST_CLAIM_TTL_SECONDS = 60;

    /**
     * Grace on top of the signup window before a reservation nobody followed up on is dropped.
     */
    private static final long ROSTER_WATCHDOG_GRACE_MILLIS = 45_000L;

    /**
     * How long to wait for MBedwars to actually take an arena from LOBBY to RUNNING after we hand it
     * the countdown, before concluding it never will.
     */
    private static final long START_WATCHDOG_MILLIS = 40_000L;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;
    private final ArenaSelector selector;

    private final Map<String, EventReservation> reservationsByArena = new ConcurrentHashMap<>();
    private final Set<UUID> forcedJoins = ConcurrentHashMap.newKeySet();
    private final Map<String, BukkitTask> holdTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> arrivalTasks = new ConcurrentHashMap<>();

    /**
     * Watchdogs keyed by event id, so none of them outlive the event that scheduled them.
     */
    private final Map<String, BukkitTask> watchdogs = new ConcurrentHashMap<>();

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
        network.bus().on(MessageType.PLAYER_RETURN, this::onPlayerReturn);
    }

    /**
     * The SMP telling us somebody it had rostered is not coming.
     *
     * <p>Taking them off the roster is what lets the match start early: the gate is "everybody on the
     * roster has arrived", so one player who signed up and logged off would otherwise hold every
     * match open for its full arrival window.
     */
    private void onPlayerReturn(Envelope envelope) {
        Payloads.PlayerReturn returning = network.bus().payload(envelope, Payloads.PlayerReturn.class);
        if (returning == null || returning.outcome() != ReturnOutcome.NO_SHOW) {
            return;
        }

        UUID playerId;
        try {
            playerId = UUID.fromString(returning.uuid());
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return;
        }

        reservationForEvent(envelope.eventId()).ifPresent(reservation -> {
            if (reservation.isStarting() || !reservation.roster().remove(playerId)) {
                return;
            }

            plugin.log("Event {}: {} is not coming; roster down to {}.",
                    reservation.eventId(), playerId, reservation.roster().size());

            if (!reservation.roster().isEmpty() && presentCount(reservation) >= reservation.roster().size()) {
                beginCountdown(reservation);
            }
        });
    }

    // ---------------------------------------------------------------- state ----

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

    public Optional<EventReservation> reservationForPlayer(UUID playerId) {
        return reservationsByArena.values().stream()
                .filter(reservation -> reservation.roster().contains(playerId))
                .findFirst();
    }

    public boolean isForcedJoin(UUID playerId) {
        return forcedJoins.contains(playerId);
    }

    public Map<String, EventReservation> reservations() {
        return Map.copyOf(reservationsByArena);
    }

    @Override
    public Set<String> localEventIds() {
        return reservationsByArena.values().stream()
                .map(EventReservation::eventId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public void abandonLocally(String eventId, CancelReason reason) {
        reservationForEvent(eventId).ifPresent(reservation -> {
            // A match that is genuinely under way is not abandoned on somebody else's say-so; see
            // onCancelled for why.
            if (isMatchUnderway(reservation)) {
                plugin.log("Ignoring reconciliation for event {}: the match is still running.", eventId);
                return;
            }
            release(reservation, reason, true);
        });
    }

    /**
     * Adds a roster member to their reserved arena.
     *
     * <p>{@link Arena#addPlayer} throws unless the arena is in its lobby, and our own guard would
     * otherwise refuse the join, so both are handled here rather than at each call site.
     */
    public boolean forceJoin(Player player, Arena arena) {
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

    // -------------------------------------------------------------- propose ----

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

        // Size the arena to the turnout we actually expect, not to the bare minimum. Picking a
        // four-slot map because min-players is four would then cap signups at four, turning away
        // everyone else - the arena's capacity is what the SMP advertises.
        int floor = Math.max(propose.minPlayers(), plugin.getConfigManager().getEventMinPlayers());
        int ceiling = Math.min(
                propose.maxPlayers() > 0 ? propose.maxPlayers() : Integer.MAX_VALUE,
                plugin.getConfigManager().getEventMaxPlayers());
        int expected = Math.max(floor, Math.min(ceiling, Math.max(floor, propose.onlinePlayers())));

        Optional<Arena> candidate = selector.select(expected, arena -> !isEventArena(arena.getName()));
        if (candidate.isEmpty() && expected > floor) {
            // Nothing that big is free; a smaller game is better than no game.
            expected = floor;
            candidate = selector.select(expected, arena -> !isEventArena(arena.getName()));
        }

        if (candidate.isEmpty()) {
            reject(envelope, propose, "no usable arena for " + expected + " players");
            return;
        }

        Arena arena = candidate.get();
        int capacity = arena.getMaxPlayers();
        long signupDeadline = System.currentTimeMillis()
                + plugin.getConfigManager().getEventSignupSeconds() * 1000L;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Several Bedwars servers may be able to host; exactly one may answer. The loser stays
            // silent rather than publishing a reject that would race the winner's accept.
            if (!store.claimHost(eventId, network.serverId(), HOST_CLAIM_TTL_SECONDS)) {
                plugin.log("Another server claimed event {} first; standing down.", eventId);
                return;
            }

            // The hash is written before the reply, so a dropped ACCEPT costs latency, not the event.
            store.read(eventId)
                    .map(record -> record.accepted(network.serverId(),
                            plugin.getConfigManager().getThisVelocityServer(),
                            arena.getName(), capacity, signupDeadline))
                    .ifPresent(store::write);

            Bukkit.getScheduler().runTask(plugin, () -> completeAccept(
                    eventId, arena, propose, capacity, signupDeadline));
        });
    }

    /**
     * Back on the main thread with the host claim won: take the arena and answer.
     */
    private void completeAccept(String eventId, Arena arena, Payloads.Propose propose,
                                int capacity, long signupDeadline) {
        // Re-check: locals may have wandered into it while we were talking to Redis.
        if (!arena.exists() || isEventArena(arena.getName())) {
            plugin.log("Arena {} was taken before event {} could reserve it.", arena.getName(), eventId);

            // Give the claim back, or no other Bedwars server can host for its whole TTL and the
            // network is left with an ACCEPTED record naming an arena nobody reserved.
            plugin.offMainThread(() -> network.client().delete(network.keys().eventHost(eventId)));
            network.bus().send(NetworkRole.SMP, MessageType.EVENT_REJECT, eventId, propose.smpServerId(),
                    new Payloads.Reject(network.serverId(), "arena was taken before it could be reserved"));
            return;
        }

        EventReservation reservation = new EventReservation(
                eventId, arena.getName(), propose.smpServerId(), propose.smpServerName(),
                arena.getMinPlayers(), signupDeadline);
        reservationsByArena.put(arena.getName(), reservation);

        // Pre-seed the vote result: an event match always has magic on, and no VotingSession is ever
        // created for this arena (see MythicBedwars#isEventArena), so nothing can overwrite it.
        plugin.getVotingManager().setMagicEnabled(arena.getName(),
                plugin.getConfigManager().isEventForceMagic());

        network.bus().send(NetworkRole.SMP, MessageType.EVENT_ACCEPT, eventId, propose.smpServerId(),
                new Payloads.Accept(network.serverId(), plugin.getConfigManager().getThisVelocityServer(),
                        arena.getName(), capacity, signupDeadline));

        // Without this, an SMP that dies during its own signup window leaves us holding the arena
        // with no timer of any kind: locked to local players, rejecting every future event, forever.
        long wait = Math.max(5_000L, signupDeadline - System.currentTimeMillis() + ROSTER_WATCHDOG_GRACE_MILLIS);
        watchdogs.put(eventId, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            watchdogs.remove(eventId);
            reservationForEvent(eventId).ifPresent(held -> {
                if (held.roster().isEmpty()) {
                    plugin.log("Event {}: no roster ever arrived from the SMP; releasing {}.",
                            eventId, held.arenaName());
                    cancel(eventId, CancelReason.TIMEOUT_SIGNUP);
                }
            });
        }, wait / 50L));

        plugin.log("Accepted event {} on arena {} (capacity {}).", eventId, arena.getName(), capacity);
    }

    private void reject(Envelope envelope, Payloads.Propose propose, String reason) {
        plugin.log("Rejecting event {}: {}", envelope.eventId(), reason);
        network.bus().send(NetworkRole.SMP, MessageType.EVENT_REJECT, envelope.eventId(), propose.smpServerId(),
                new Payloads.Reject(network.serverId(), reason));
    }

    // --------------------------------------------------------- roster closed ----

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

        // Idempotent: a duplicate ROSTER_CLOSED must not start a second hold task and leak the first.
        if (!reservation.roster().isEmpty() || reservation.isStarting()) {
            plugin.log("Ignoring duplicate roster for event {}.", eventId);
            return;
        }

        cancelWatchdog(eventId);

        for (String raw : closed.roster()) {
            try {
                reservation.roster().add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // One malformed id is not worth failing the whole roster over.
            }
        }

        int turnout = Math.max(closed.count(), reservation.roster().size());
        Arena arena = resolveArenaForRoster(reservation, turnout);
        if (arena == null) {
            return;
        }

        // addPlayer() throws outside the lobby, so promote an idle arena before anybody arrives.
        if (arena.getStatus() == ArenaStatus.STOPPED) {
            arena.setStatus(ArenaStatus.LOBBY);
        }

        // MBedwars only runs its lobby countdown once the arena is above its own minimum, and we
        // force-start through that countdown. Lower it to what actually turned up, or the start
        // silently does nothing.
        applyMinPlayers(arena, turnout);

        int holdSeconds = plugin.getConfigManager().getEventLobbyHoldSeconds();
        LobbyHoldTask hold = new LobbyHoldTask(arena.getName(), holdSeconds);
        holdTasks.put(arena.getName(), hold.runTaskTimer(plugin, 0L, 20L));

        long transferDeadline = System.currentTimeMillis()
                + plugin.getConfigManager().getEventArrivalGraceSeconds() * 1000L;
        reservation.transferDeadline(transferDeadline);

        String arenaName = arena.getName();
        plugin.offMainThread(() ->
                store.read(eventId).map(record -> record.withState(EventState.ARENA_READY))
                        .ifPresent(store::write));

        network.bus().send(NetworkRole.SMP, MessageType.ARENA_READY, eventId, reservation.smpServerId(),
                new Payloads.ArenaReady(network.serverId(),
                        plugin.getConfigManager().getThisVelocityServer(),
                        arenaName, transferDeadline));

        arrivalTasks.put(eventId, Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickArrivals(eventId), 20L, 20L));

        plugin.log("Event {} holding arena {} for {} player(s); arrivals close in {}s.",
                eventId, arenaName, reservation.roster().size(),
                plugin.getConfigManager().getEventArrivalGraceSeconds());
    }

    /**
     * Confirms the reserved arena still works for the real turnout, and moves to a better-fitting one
     * where that is clearly worth doing.
     *
     * <p>This is the first point at which the turnout is actually known — the arena had to be
     * reserved before signups opened, on nothing but an estimate — so it is also the first chance to
     * right-size. Nobody has been transferred yet, which is what makes a swap safe here and nowhere
     * later.
     *
     * @return the arena to use, or {@code null} if the event has been cancelled
     */
    private Arena resolveArenaForRoster(EventReservation reservation, int turnout) {
        String eventId = reservation.eventId();
        Arena current = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());

        if (current != null && selector.isStillUsable(current, turnout)) {
            Optional<Arena> better = selector.findBetterFit(current, turnout,
                    arena -> !isEventArena(arena.getName()));

            if (better.isPresent()) {
                Arena replacement = better.get();
                plugin.log("Event {}: {} players turned up, moving from {} ({} slots) to {} ({} slots).",
                        eventId, turnout, current.getName(), current.getMaxPlayers(),
                        replacement.getName(), replacement.getMaxPlayers());
                switchArena(reservation, current, replacement);
                return replacement;
            }

            return current;
        }

        // The reserved arena is gone or broken. Try for a replacement before giving up: the roster is
        // already assembled and cancelling costs everybody their game.
        Optional<Arena> replacement = selector.select(turnout,
                arena -> !isEventArena(arena.getName())
                        || arena.getName().equals(reservation.arenaName()));

        if (replacement.isEmpty()) {
            plugin.log("Arena {} is no longer usable for event {} and nothing else fits.",
                    reservation.arenaName(), eventId);
            cancel(eventId, CancelReason.ARENA_LOST);
            return null;
        }

        Arena chosen = replacement.get();
        plugin.log("Event {}: {} became unusable, switching to {}.",
                eventId, reservation.arenaName(), chosen.getName());
        switchArena(reservation, current, chosen);
        return chosen;
    }

    private void switchArena(EventReservation reservation, Arena from, Arena to) {
        String previousName = reservation.arenaName();

        stopHoldTask(previousName);
        reservationsByArena.remove(previousName);

        if (from != null) {
            from.setMinPlayers(reservation.originalMinPlayers());
            plugin.getVotingManager().cleanupArena(previousName);
        }

        reservation.reassign(to.getName(), to.getMinPlayers());
        reservationsByArena.put(to.getName(), reservation);
        plugin.getVotingManager().setMagicEnabled(to.getName(),
                plugin.getConfigManager().isEventForceMagic());
    }

    /**
     * Brings the arena's own minimum down to what turned up, clamped at two — MBedwars will not run a
     * countdown below its minimum, and {@code setLobbyTimeRemaining} would silently do nothing.
     */
    private void applyMinPlayers(Arena arena, int turnout) {
        int target = Math.max(2, Math.min(arena.getMinPlayers(), Math.max(2, turnout)));
        if (arena.getMinPlayers() != target) {
            arena.setMinPlayers(target);
        }
    }

    // -------------------------------------------------------------- arrivals ----

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
        if (arena == null || !forceJoin(player, arena)) {
            // They crossed the proxy for a game they cannot be seated in - a full arena, one that
            // has already started, or a relog mid-match. Send them straight home rather than
            // leaving them standing on a server they never chose to visit.
            plugin.log("Could not seat arriving player {} for event {}; sending them back.",
                    player.getName(), reservation.eventId());
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.seat_lost"));
            plugin.getReturnService().returnNow(player, reservation, ReturnOutcome.NO_SHOW);
            return true;
        }

        reservation.arrived().add(player.getUniqueId());

        String eventId = reservation.eventId();
        String playerId = player.getUniqueId().toString();
        int ttl = plugin.getConfigManager().getEventTtlSeconds();

        plugin.offMainThread(() ->
                network.client().evalLong(LuaScripts.SADD_WITH_TTL,
                        List.of(network.keys().eventArrived(eventId)),
                        List.of(playerId, Integer.toString(ttl)), -1L));

        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.arena_welcome",
                "arrived", reservation.arrived().size(), "expected", reservation.roster().size()));

        network.bus().send(NetworkRole.SMP, MessageType.PLAYER_ARRIVED, eventId,
                reservation.smpServerId(),
                new Payloads.PlayerArrived(playerId,
                        reservation.arrived().size(), reservation.roster().size()));

        if (presentCount(reservation) >= reservation.roster().size()) {
            plugin.log("Event {}: everybody arrived, starting.", eventId);
            beginCountdown(reservation);
        }

        return true;
    }

    /**
     * Forgets somebody who arrived and then left again before the match began.
     *
     * <p>Without this the arrival gate can be cleared by players who are no longer there, and the
     * match starts under-populated — or, combined with the arena's own minimum, not at all.
     */
    public void forgetArrival(EventReservation reservation, UUID playerId) {
        if (reservation.isStarting()) {
            return;
        }

        reservation.arrived().remove(playerId);
    }

    /**
     * @return how many rostered players are genuinely in the arena right now
     */
    private int presentCount(EventReservation reservation) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena == null) {
            return reservation.arrived().size();
        }

        return (int) arena.getPlayers().stream()
                .filter(player -> reservation.arrived().contains(player.getUniqueId()))
                .count();
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

        int arrived = presentCount(reservation);
        if (arrived < plugin.getConfigManager().getEventMinArrivals()) {
            plugin.log("Event {}: only {} arrived, calling it off.", eventId, arrived);
            cancel(eventId, CancelReason.TOO_FEW_ARRIVALS);
            return;
        }

        recordNoShows(reservation);

        // Late arrivals are out of luck, but locals can take the empty slots for a fuller game.
        reservation.openFill();
        int fillSeconds = plugin.getConfigManager().getEventFillWindowSeconds();
        if (fillSeconds <= 0) {
            beginCountdown(reservation);
            return;
        }

        int spare = Math.max(0, reservation.roster().size() - arrived);
        plugin.log("Event {}: opening {} spare slot(s) to local players for {}s.",
                eventId, spare, fillSeconds);

        if (plugin.getConfigManager().isEventAnnouncedLocally() && spare > 0) {
            announceFillLocally(reservation, spare, fillSeconds);
        }

        // Tracked, or a cancellation during the fill window would still let this fire and start a
        // match for an event that no longer exists.
        watchdogs.put(eventId, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            watchdogs.remove(eventId);
            reservationForEvent(eventId).ifPresent(this::beginCountdown);
        }, fillSeconds * 20L));
    }

    /**
     * Tells players already here that an event match has room, which is the only way they would ever
     * find out — the recruitment broadcast goes out on the SMP.
     */
    private void announceFillLocally(EventReservation reservation, int spare, int seconds) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (arena == null || !arena.getPlayers().contains(player)) {
                player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                        "magic.event.local_fill_open",
                        "arena", reservation.arenaName(), "slots", spare, "seconds", seconds));
            }
        }
    }

    /**
     * Writes an outcome for everybody who signed up and never made it, so the SMP can tell them what
     * happened rather than leaving them wondering.
     */
    private void recordNoShows(EventReservation reservation) {
        for (UUID playerId : reservation.roster()) {
            if (!reservation.arrived().contains(playerId)) {
                plugin.getReturnService().recordOnly(playerId, reservation, ReturnOutcome.NO_SHOW);
            }
        }
    }

    // ----------------------------------------------------------------- start ----

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

        String eventId = reservation.eventId();

        stopArrivalTask(eventId);
        cancelWatchdog(eventId);

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena == null || arena.getStatus() != ArenaStatus.LOBBY) {
            cancel(eventId, CancelReason.ARENA_LOST);
            return;
        }

        reservation.markStarting();
        stopHoldTask(reservation.arenaName());

        plugin.getVotingManager().setMagicEnabled(arena.getName(),
                plugin.getConfigManager().isEventForceMagic());

        applyMinPlayers(arena, arena.getPlayers().size());

        int countdown = plugin.getConfigManager().getEventStartCountdownSeconds();
        if (!arena.setLobbyTimeRemaining(countdown, true)) {
            // MBedwars refused the countdown - almost always too few players for the arena's own
            // minimum. Silently returning here would leave the event with no timer at all.
            plugin.log("Event {}: {} refused a countdown ({} player(s), minimum {}).",
                    eventId, arena.getName(), arena.getPlayers().size(), arena.getMinPlayers());
            cancel(eventId, CancelReason.ARENA_LOST);
            return;
        }

        plugin.offMainThread(() ->
                store.read(eventId).map(record -> record.withState(EventState.RUNNING))
                        .ifPresent(store::write));

        network.bus().send(NetworkRole.SMP, MessageType.EVENT_STARTED, eventId,
                reservation.smpServerId(),
                new Payloads.EventStarted(arena.getName(), arena.getPlayers().size()));

        // Last line of defence: if the arena never actually reaches RUNNING, nothing else would ever
        // notice, and the reservation would hold the arena indefinitely.
        watchdogs.put(eventId, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            watchdogs.remove(eventId);
            reservationForEvent(eventId).ifPresent(held -> {
                Arena still = BedwarsAPI.getGameAPI().getArenaByExactName(held.arenaName());
                if (still == null || still.getStatus() != ArenaStatus.RUNNING) {
                    plugin.log("Event {} never started; releasing {}.", eventId, held.arenaName());
                    cancel(eventId, CancelReason.ARENA_LOST);
                }
            });
        }, START_WATCHDOG_MILLIS / 50L));

        plugin.log("Event {} starting on {} with {} player(s).",
                eventId, arena.getName(), arena.getPlayers().size());
    }

    // ---------------------------------------------------------------- finish ----

    /**
     * Publishes the match result. Called before any transfer, so the SMP has the outcome even if
     * every subsequent move fails.
     */
    public void publishFinished(EventReservation reservation, Arena arena,
                                de.marcely.bedwars.api.arena.Team winnerTeam, boolean tie,
                                List<Player> winners, List<Player> losers) {
        cancelWatchdog(reservation.eventId());

        String eventId = reservation.eventId();
        Payloads.EventFinished payload = new Payloads.EventFinished(
                arena.getName(), tie,
                winnerTeam == null ? null : winnerTeam.name(),
                winners.stream().map(p -> p.getUniqueId().toString()).toList(),
                losers.stream().map(p -> p.getUniqueId().toString()).toList(),
                arena.getRunningTime() == null ? 0L : arena.getRunningTime().toMillis());

        network.bus().send(NetworkRole.SMP, MessageType.EVENT_FINISHED, eventId,
                reservation.smpServerId(), payload);

        plugin.offMainThread(() ->
                store.read(eventId).map(record -> record.withState(EventState.FINISHED))
                        .ifPresent(store::write));
    }

    /**
     * Retires a reservation after a match that actually ran, as opposed to one that was called off.
     */
    public void finish(EventReservation reservation) {
        reservationsByArena.remove(reservation.arenaName());
        stopHoldTask(reservation.arenaName());
        stopArrivalTask(reservation.eventId());
        cancelWatchdog(reservation.eventId());

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena != null) {
            arena.setMinPlayers(reservation.originalMinPlayers());
        }

        plugin.getVotingManager().cleanupArena(reservation.arenaName());

        String eventId = reservation.eventId();
        int cooldownSeconds = plugin.getConfigManager().getEventCooldownMinutes() * 60;
        plugin.offMainThread(() -> {
            store.retire(eventId);
            store.startCooldown(cooldownSeconds);
        });

        plugin.log("Event {} complete; arena {} released.", eventId, reservation.arenaName());
    }

    // ---------------------------------------------------------------- cancel ----

    private void onCancelled(Envelope envelope) {
        if (envelope.eventId() == null) {
            return;
        }

        Payloads.Cancelled cancelled = network.bus().payload(envelope, Payloads.Cancelled.class);
        // Gson maps an unrecognised enum constant to null rather than throwing, so a peer running a
        // build with one extra reason would NPE here - and the release below would never run.
        CancelReason reason = cancelled == null || cancelled.reason() == null
                ? CancelReason.ADMIN
                : cancelled.reason();

        reservationForEvent(envelope.eventId()).ifPresent(reservation -> {
            // A live match is not torn down on a remote cancel. We physically hold the players; the
            // SMP does not, and dropping the reservation mid-match would strand everyone with no
            // rewards, no result, and no ride home.
            if (isMatchUnderway(reservation)) {
                plugin.log("Ignoring remote cancel for event {}: the match is already running.",
                        envelope.eventId());
                return;
            }

            release(reservation, reason, true);
        });
    }

    /**
     * Cancels an event this server is hosting and tells the network.
     */
    public void cancel(String eventId, CancelReason reason) {
        reservationForEvent(eventId).ifPresent(reservation -> {
            plugin.offMainThread(() ->
                    store.read(eventId).map(record -> record.cancelled(reason)).ifPresent(store::write));

            network.bus().broadcast(NetworkRole.SMP, MessageType.EVENT_CANCELLED, eventId,
                    new Payloads.Cancelled(reason, network.serverId()));
            release(reservation, reason, true);
        });
    }

    /**
     * @return whether MBedwars has actually taken this arena into a match
     */
    private boolean isMatchUnderway(EventReservation reservation) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena == null) {
            return false;
        }

        ArenaStatus status = arena.getStatus();
        return status == ArenaStatus.RUNNING || status == ArenaStatus.END_LOBBY;
    }

    /**
     * Frees the arena and undoes everything the reservation changed.
     *
     * @param sendPlayersHome whether anybody sitting in the arena should be returned to the SMP. Only
     *                        false on shutdown, where the proxy handles them and a transfer would not
     *                        complete anyway.
     */
    private void release(EventReservation reservation, CancelReason reason, boolean sendPlayersHome) {
        reservationsByArena.remove(reservation.arenaName());
        stopHoldTask(reservation.arenaName());
        stopArrivalTask(reservation.eventId());
        cancelWatchdog(reservation.eventId());

        if (sendPlayersHome) {
            // Every cancellation path runs through here, and any of them can happen after players
            // have already crossed the proxy. Releasing the arena without moving them would leave
            // them stranded on a server with nothing to do and no way back.
            returnEveryone(reservation, reason);
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena != null) {
            // Put the arena's own tuning back; an event must not permanently change a map.
            arena.setMinPlayers(reservation.originalMinPlayers());
        }

        plugin.getVotingManager().cleanupArena(reservation.arenaName());

        String eventId = reservation.eventId();
        plugin.offMainThread(() -> store.retire(eventId));

        plugin.log("Released arena {} from event {} ({}).",
                reservation.arenaName(), eventId, reason);
    }

    /**
     * Kicks everybody out of a reserved arena and sends the recruits home.
     */
    private void returnEveryone(EventReservation reservation, CancelReason reason) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
        if (arena == null) {
            return;
        }

        List<Player> present = new ArrayList<>(arena.getPlayers());
        arena.getSpectators().stream().filter(s -> !present.contains(s)).forEach(present::add);

        List<UUID> travelling = new ArrayList<>();
        for (Player player : present) {
            arena.kickPlayer(player, KickReason.PLUGIN_STOP);
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, reason.localeKey()));

            // Only the recruits go to the SMP. A local player who took a spare slot lives here and
            // would be baffled to find themselves on the survival server.
            if (reservation.roster().contains(player.getUniqueId())) {
                travelling.add(player.getUniqueId());
            }
        }

        if (!travelling.isEmpty()) {
            plugin.getReturnService().returnAll(travelling, reservation, ReturnOutcome.CANCELLED);
        }
    }

    // ------------------------------------------------------------- lifecycle ----

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

    private void cancelWatchdog(String eventId) {
        BukkitTask task = watchdogs.remove(eventId);
        if (task != null) {
            task.cancel();
        }
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
            store.retire(eventId);
        });
    }

    /**
     * Called on shutdown: give up everything we are holding so the network is not left waiting.
     *
     * <p>A running match is announced too. Its players are about to be scattered by the proxy's own
     * fallback, and leaving the record in place would have the SMP waiting for a result that is never
     * coming — until the event TTL, by which point a slow restart has blocked every other event.
     */
    public void shutdown() {
        for (EventReservation reservation : Map.copyOf(reservationsByArena).values()) {
            String eventId = reservation.eventId();

            store.read(eventId).map(record -> record.cancelled(CancelReason.HOST_RESTARTED))
                    .ifPresent(store::write);
            network.bus().broadcast(NetworkRole.SMP, MessageType.EVENT_CANCELLED, eventId,
                    new Payloads.Cancelled(CancelReason.HOST_RESTARTED, network.serverId()));

            // Recorded so anybody who was mid-match is still greeted, and still paid, when they turn
            // up on the survival server.
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(reservation.arenaName());
            if (arena != null) {
                for (Player player : arena.getPlayers()) {
                    if (reservation.roster().contains(player.getUniqueId())) {
                        plugin.getReturnService().recordOnly(
                                player.getUniqueId(), reservation, ReturnOutcome.CANCELLED);
                    }
                }
                arena.setMinPlayers(reservation.originalMinPlayers());
            }

            release(reservation, CancelReason.HOST_RESTARTED, false);
        }
    }
}
