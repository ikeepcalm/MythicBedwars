package dev.ua.ikeepcalm.bedwars.net.minigame.service;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.AddPlayerCause;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.source.MagicMode;
import dev.ua.ikeepcalm.bedwars.net.event.model.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.service.EventStore;
import dev.ua.ikeepcalm.bedwars.net.minigame.model.EventReservation;
import dev.ua.ikeepcalm.bedwars.net.minigame.task.LobbyHoldTask;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.AcceptPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.ArenaReadyPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.CancelledPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.EventFinishedPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.EventStartedPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.PlayerArrivedPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.PlayerReturnPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.ProposePayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.RejectPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.RosterClosedPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import dev.ua.ikeepcalm.bedwars.net.service.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.transport.LuaScripts;
import dev.ua.ikeepcalm.bedwars.net.type.EventParticipant;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class EventOrchestrator implements EventParticipant {

    private static final int HOST_CLAIM_TTL_SECONDS = 60;
    private static final long ROSTER_WATCHDOG_GRACE_MILLIS = 45_000L;
    private static final long START_WATCHDOG_MILLIS = 40_000L;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;
    private final ArenaSelector selector;

    private final Map<String, EventReservation> reservationsByArena = new ConcurrentHashMap<>();
    private final Set<UUID> forcedJoins = ConcurrentHashMap.newKeySet();
    private final Map<String, BukkitTask> holdTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> arrivalTasks = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> watchdogs = new ConcurrentHashMap<>();

    public EventOrchestrator(MythicBedwars plugin, NetworkService network, EventStore store) {
        this.plugin = plugin;
        this.network = network;
        this.store = store;
        this.selector = new ArenaSelector(plugin);
    }

    public void registerHandlers() {
        network.getBus().on(MessageType.EVENT_PROPOSE, this::onPropose);
        network.getBus().on(MessageType.ROSTER_CLOSED, this::onRosterClosed);
        network.getBus().on(MessageType.EVENT_CANCELLED, this::onCancelled);
        network.getBus().on(MessageType.PLAYER_RETURN, this::onPlayerReturn);
    }

    public boolean isEventArena(String arenaName) {
        return reservationsByArena.containsKey(arenaName);
    }

    public EventReservation getReservation(String arenaName) {
        return reservationsByArena.get(arenaName);
    }

    public Optional<EventReservation> reservationForEvent(String eventId) {
        return reservationsByArena.values().stream()
                .filter(reservation -> reservation.getEventId().equals(eventId))
                .findFirst();
    }

    public Optional<EventReservation> reservationForPlayer(UUID playerId) {
        return reservationsByArena.values().stream()
                .filter(reservation -> reservation.getRoster().contains(playerId))
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
                .map(EventReservation::getEventId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void abandonLocally(String eventId, CancelReason reason) {
        reservationForEvent(eventId).ifPresent(reservation -> {
            if (isMatchUnderway(reservation)) {
                plugin.log("Ignoring reconciliation for event {}: the match is still running.", eventId);
                return;
            }
            abandon(reservation, reason);
        });
    }

    public boolean forceJoin(Player player, Arena arena) {
        forcedJoins.add(player.getUniqueId());
        try {
            return arena.addPlayer(player, null, AddPlayerCause.PLUGIN) == null;
        } catch (IllegalStateException notInLobby) {
            plugin.log("Cannot add {} to {}: {}", player.getName(), arena.getName(), notInLobby.getMessage());
            return false;
        } finally {
            forcedJoins.remove(player.getUniqueId());
        }
    }

    private void onPlayerReturn(Envelope envelope) {
        PlayerReturnPayload returning = network.getBus().payload(envelope, PlayerReturnPayload.class);
        if (returning == null || returning.getOutcome() != ReturnOutcome.NO_SHOW) {
            return;
        }

        UUID playerId = parseUuid(returning.getUuid());
        if (playerId == null) {
            return;
        }

        reservationForEvent(envelope.getEventId()).ifPresent(reservation -> {
            if (reservation.isStarting() || !reservation.getRoster().remove(playerId)) {
                return;
            }

            plugin.log("Event {}: {} is not coming; roster down to {}.",
                    reservation.getEventId(), playerId, reservation.getRoster().size());

            if (!reservation.getRoster().isEmpty() && presentCount(reservation) >= reservation.getRoster().size()) {
                beginCountdown(reservation);
            }
        });
    }

    private void onPropose(Envelope envelope) {
        ProposePayload propose = network.getBus().payload(envelope, ProposePayload.class);
        String eventId = envelope.getEventId();
        if (propose == null || eventId == null) {
            return;
        }

        if (!plugin.getConfigManager().isEventEnabled()) {
            reject(eventId, propose, "events are disabled on this server");
            return;
        }

        if (!reservationsByArena.isEmpty()) {
            reject(eventId, propose, "already hosting an event");
            return;
        }

        Optional<Arena> candidate = pickArena(propose);
        if (candidate.isEmpty()) {
            reject(eventId, propose, "no usable arena for the expected turnout");
            return;
        }

        Arena arena = candidate.get();
        int capacity = arena.getMaxPlayers();
        long signupDeadline = System.currentTimeMillis() + plugin.getConfigManager().getEventSignupSeconds() * 1000L;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // the loser of the claim stays silent; a reject would race the winner's accept
            if (!store.claimHost(eventId, network.getServerId(), HOST_CLAIM_TTL_SECONDS)) {
                plugin.log("Another server claimed event {} first; standing down.", eventId);
                return;
            }

            store.read(eventId)
                    .map(record -> record.accepted(network.getServerId(), plugin.getConfigManager().getThisVelocityServer(),
                            arena.getName(), capacity, signupDeadline))
                    .ifPresent(store::write);

            Bukkit.getScheduler().runTask(plugin, () -> completeAccept(eventId, arena, propose, capacity, signupDeadline));
        });
    }

    private Optional<Arena> pickArena(ProposePayload propose) {
        int floor = Math.max(propose.getMinPlayers(), plugin.getConfigManager().getEventMinPlayers());
        int ceiling = Math.min(propose.getMaxPlayers() > 0 ? propose.getMaxPlayers() : Integer.MAX_VALUE,
                plugin.getConfigManager().getEventMaxPlayers());
        int expected = Math.max(floor, Math.min(ceiling, propose.getOnlinePlayers()));

        Optional<Arena> candidate = selector.select(expected, arena -> !isEventArena(arena.getName()));
        if (candidate.isEmpty() && expected > floor) {
            candidate = selector.select(floor, arena -> !isEventArena(arena.getName()));
        }
        return candidate;
    }

    private void completeAccept(String eventId, Arena arena, ProposePayload propose, int capacity, long signupDeadline) {
        if (!arena.exists() || isEventArena(arena.getName())) {
            plugin.log("Arena {} was taken before event {} could reserve it.", arena.getName(), eventId);
            plugin.offMainThread(() -> network.getClient().delete(network.getKeys().eventHost(eventId)));
            reject(eventId, propose, "arena was taken before it could be reserved");
            return;
        }

        // resolved once: magic-mode RANDOM rolls on every read, and this value is seeded three times
        MagicMode magicMode = plugin.getConfigManager().resolveEventMagicMode();

        EventReservation reservation = new EventReservation(eventId, propose.getSmpServerId(), magicMode,
                arena.getName(), arena.getMinPlayers());
        reservationsByArena.put(arena.getName(), reservation);
        plugin.getVotingManager().setMagicMode(arena.getName(), magicMode);

        network.getBus().send(NetworkRole.SMP, MessageType.EVENT_ACCEPT, eventId, propose.getSmpServerId(),
                new AcceptPayload(network.getServerId(), plugin.getConfigManager().getThisVelocityServer(),
                        arena.getName(), capacity, signupDeadline));

        long wait = Math.max(5_000L, signupDeadline - System.currentTimeMillis() + ROSTER_WATCHDOG_GRACE_MILLIS);
        watch(eventId, wait / 50L, held -> {
            if (held.getRoster().isEmpty()) {
                plugin.log("Event {}: no roster ever arrived from the SMP; releasing {}.", eventId, held.getArenaName());
                cancel(eventId, CancelReason.TIMEOUT_SIGNUP);
            }
        });

        plugin.log("Accepted event {} on arena {} (capacity {}).", eventId, arena.getName(), capacity);
    }

    private void reject(String eventId, ProposePayload propose, String reason) {
        plugin.log("Rejecting event {}: {}", eventId, reason);
        network.getBus().send(NetworkRole.SMP, MessageType.EVENT_REJECT, eventId, propose.getSmpServerId(),
                new RejectPayload(network.getServerId(), reason));
    }

    private void onRosterClosed(Envelope envelope) {
        String eventId = envelope.getEventId();
        RosterClosedPayload closed = network.getBus().payload(envelope, RosterClosedPayload.class);
        if (closed == null || eventId == null) {
            return;
        }

        EventReservation reservation = reservationForEvent(eventId).orElse(null);
        if (reservation == null) {
            plugin.log("Roster for unknown event {} - ignoring.", eventId);
            return;
        }

        if (!reservation.getRoster().isEmpty() || reservation.isStarting()) {
            plugin.log("Ignoring duplicate roster for event {}.", eventId);
            return;
        }

        cancelWatchdog(eventId);
        closed.getRoster().stream()
                .map(EventOrchestrator::parseUuid)
                .filter(Objects::nonNull)
                .forEach(reservation.getRoster()::add);

        int turnout = Math.max(closed.getCount(), reservation.getRoster().size());
        Arena arena = resolveArenaForRoster(reservation, turnout);
        if (arena != null) {
            holdArena(reservation, arena, turnout);
        }
    }

    private void holdArena(EventReservation reservation, Arena arena, int turnout) {
        String eventId = reservation.getEventId();

        if (arena.getStatus() == ArenaStatus.STOPPED) {
            arena.setStatus(ArenaStatus.LOBBY);
        }
        applyMinPlayers(arena, turnout);

        holdTasks.put(arena.getName(), new LobbyHoldTask(arena.getName(),
                plugin.getConfigManager().getEventLobbyHoldSeconds()).runTaskTimer(plugin, 0L, 20L));

        int graceSeconds = plugin.getConfigManager().getEventArrivalGraceSeconds();
        long transferDeadline = System.currentTimeMillis() + graceSeconds * 1000L;
        reservation.setTransferDeadline(transferDeadline);

        writeState(eventId, EventState.ARENA_READY);
        network.getBus().send(NetworkRole.SMP, MessageType.ARENA_READY, eventId, reservation.getSmpServerId(),
                new ArenaReadyPayload(network.getServerId(), plugin.getConfigManager().getThisVelocityServer(),
                        arena.getName(), transferDeadline));

        arrivalTasks.put(eventId, Bukkit.getScheduler().runTaskTimer(plugin, () -> tickArrivals(eventId), 20L, 20L));

        plugin.log("Event {} holding arena {} for {} player(s); arrivals close in {}s.",
                eventId, arena.getName(), reservation.getRoster().size(), graceSeconds);
    }

    private Arena resolveArenaForRoster(EventReservation reservation, int turnout) {
        String eventId = reservation.getEventId();
        Arena current = arenaOf(reservation);

        if (current != null && selector.isStillUsable(current, turnout)) {
            Optional<Arena> better = selector.findBetterFit(current, turnout, arena -> !isEventArena(arena.getName()));
            if (better.isEmpty()) {
                return current;
            }

            plugin.log("Event {}: {} players turned up, moving from {} ({} slots) to {} ({} slots).",
                    eventId, turnout, current.getName(), current.getMaxPlayers(),
                    better.get().getName(), better.get().getMaxPlayers());
            switchArena(reservation, current, better.get());
            return better.get();
        }

        Optional<Arena> replacement = selector.select(turnout,
                arena -> !isEventArena(arena.getName()) || arena.getName().equals(reservation.getArenaName()));
        if (replacement.isEmpty()) {
            plugin.log("Arena {} is no longer usable for event {} and nothing else fits.", reservation.getArenaName(), eventId);
            cancel(eventId, CancelReason.ARENA_LOST);
            return null;
        }

        plugin.log("Event {}: {} became unusable, switching to {}.", eventId, reservation.getArenaName(), replacement.get().getName());
        switchArena(reservation, current, replacement.get());
        return replacement.get();
    }

    private void switchArena(EventReservation reservation, Arena from, Arena to) {
        String previousName = reservation.getArenaName();

        stopTask(holdTasks, previousName);
        reservationsByArena.remove(previousName);

        if (from != null) {
            from.setMinPlayers(reservation.getOriginalMinPlayers());
            plugin.getVotingManager().cleanupArena(previousName);
        }

        reservation.reassign(to.getName(), to.getMinPlayers());
        reservationsByArena.put(to.getName(), reservation);
        plugin.getVotingManager().setMagicMode(to.getName(), reservation.getMagicMode());
    }

    private void applyMinPlayers(Arena arena, int turnout) {
        int target = Math.max(2, Math.min(arena.getMinPlayers(), Math.max(2, turnout)));
        if (arena.getMinPlayers() != target) {
            arena.setMinPlayers(target);
        }
    }

    public boolean handleArrival(Player player) {
        EventReservation reservation = reservationForPlayer(player.getUniqueId()).orElse(null);
        if (reservation == null) {
            return false;
        }

        Arena arena = arenaOf(reservation);
        if (arena == null || !forceJoin(player, arena)) {
            plugin.log("Could not seat arriving player {} for event {}; sending them back.",
                    player.getName(), reservation.getEventId());
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.seat_lost"));
            plugin.getReturnService().returnNow(player, reservation, ReturnOutcome.NO_SHOW);
            return true;
        }

        reservation.getArrived().add(player.getUniqueId());
        recordArrival(reservation, player);

        if (presentCount(reservation) >= reservation.getRoster().size()) {
            plugin.log("Event {}: everybody arrived, starting.", reservation.getEventId());
            beginCountdown(reservation);
        }
        return true;
    }

    private void recordArrival(EventReservation reservation, Player player) {
        String eventId = reservation.getEventId();
        String playerId = player.getUniqueId().toString();
        String ttl = Integer.toString(plugin.getConfigManager().getEventTtlSeconds());

        plugin.offMainThread(() -> network.getClient().evalLong(LuaScripts.SADD_WITH_TTL,
                List.of(network.getKeys().eventArrived(eventId)), List.of(playerId, ttl), -1L));

        int arrived = reservation.getArrived().size();
        int expected = reservation.getRoster().size();
        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.arena_welcome",
                "arrived", arrived, "expected", expected));
        network.getBus().send(NetworkRole.SMP, MessageType.PLAYER_ARRIVED, eventId, reservation.getSmpServerId(),
                new PlayerArrivedPayload(playerId, arrived, expected));
    }

    public void forgetArrival(EventReservation reservation, UUID playerId) {
        if (!reservation.isStarting()) {
            reservation.getArrived().remove(playerId);
        }
    }

    private int presentCount(EventReservation reservation) {
        Arena arena = arenaOf(reservation);
        if (arena == null) {
            return reservation.getArrived().size();
        }

        return (int) arena.getPlayers().stream()
                .filter(player -> reservation.getArrived().contains(player.getUniqueId()))
                .count();
    }

    private void tickArrivals(String eventId) {
        EventReservation reservation = reservationForEvent(eventId).orElse(null);
        if (reservation == null) {
            stopTask(arrivalTasks, eventId);
            return;
        }

        if (System.currentTimeMillis() < reservation.getTransferDeadline()) {
            return;
        }

        stopTask(arrivalTasks, eventId);

        int arrived = presentCount(reservation);
        if (arrived < plugin.getConfigManager().getEventMinArrivals()) {
            plugin.log("Event {}: only {} arrived, calling it off.", eventId, arrived);
            cancel(eventId, CancelReason.TOO_FEW_ARRIVALS);
            return;
        }

        recordNoShows(reservation);
        openFill(reservation, Math.max(0, reservation.getRoster().size() - arrived));
    }

    private void openFill(EventReservation reservation, int spare) {
        reservation.setFillOpen(true);
        int fillSeconds = plugin.getConfigManager().getEventFillWindowSeconds();
        if (fillSeconds <= 0) {
            beginCountdown(reservation);
            return;
        }

        plugin.log("Event {}: opening {} spare slot(s) to local players for {}s.",
                reservation.getEventId(), spare, fillSeconds);

        if (plugin.getConfigManager().isEventAnnouncedLocally() && spare > 0) {
            announceFillLocally(reservation, spare, fillSeconds);
        }

        watch(reservation.getEventId(), fillSeconds * 20L, this::beginCountdown);
    }

    private void announceFillLocally(EventReservation reservation, int spare, int seconds) {
        Arena arena = arenaOf(reservation);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (arena == null || !arena.getPlayers().contains(player)) {
                player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.local_fill_open",
                        "arena", reservation.getArenaName(), "slots", spare, "seconds", seconds));
            }
        }
    }

    private void recordNoShows(EventReservation reservation) {
        for (UUID playerId : reservation.getRoster()) {
            if (!reservation.getArrived().contains(playerId)) {
                plugin.getReturnService().recordOnly(playerId, reservation, ReturnOutcome.NO_SHOW);
            }
        }
    }

    private void beginCountdown(EventReservation reservation) {
        if (reservation.isStarting()) {
            return;
        }

        String eventId = reservation.getEventId();
        stopTask(arrivalTasks, eventId);
        cancelWatchdog(eventId);

        Arena arena = arenaOf(reservation);
        if (arena == null || arena.getStatus() != ArenaStatus.LOBBY) {
            cancel(eventId, CancelReason.ARENA_LOST);
            return;
        }

        reservation.setStarting(true);
        stopTask(holdTasks, reservation.getArenaName());
        plugin.getVotingManager().setMagicMode(arena.getName(), reservation.getMagicMode());
        applyMinPlayers(arena, arena.getPlayers().size());

        if (!arena.setLobbyTimeRemaining(plugin.getConfigManager().getEventStartCountdownSeconds(), true)) {
            plugin.log("Event {}: {} refused a countdown ({} player(s), minimum {}).",
                    eventId, arena.getName(), arena.getPlayers().size(), arena.getMinPlayers());
            cancel(eventId, CancelReason.ARENA_LOST);
            return;
        }

        writeState(eventId, EventState.RUNNING);
        network.getBus().send(NetworkRole.SMP, MessageType.EVENT_STARTED, eventId, reservation.getSmpServerId(),
                new EventStartedPayload(arena.getName(), arena.getPlayers().size()));

        watch(eventId, START_WATCHDOG_MILLIS / 50L, held -> {
            Arena still = arenaOf(held);
            if (still == null || still.getStatus() != ArenaStatus.RUNNING) {
                plugin.log("Event {} never started; releasing {}.", eventId, held.getArenaName());
                cancel(eventId, CancelReason.ARENA_LOST);
            }
        });

        plugin.log("Event {} starting on {} with {} player(s).", eventId, arena.getName(), arena.getPlayers().size());
    }

    public void publishFinished(EventReservation reservation, Arena arena, Team winnerTeam, boolean tie,
                                List<Player> winners, List<Player> losers) {
        String eventId = reservation.getEventId();
        cancelWatchdog(eventId);

        EventFinishedPayload payload = new EventFinishedPayload(arena.getName(), tie,
                winnerTeam == null ? null : winnerTeam.name(),
                winners.stream().map(player -> player.getUniqueId().toString()).toList(),
                losers.stream().map(player -> player.getUniqueId().toString()).toList(),
                arena.getRunningTime() == null ? 0L : arena.getRunningTime().toMillis());

        network.getBus().send(NetworkRole.SMP, MessageType.EVENT_FINISHED, eventId, reservation.getSmpServerId(), payload);
        writeState(eventId, EventState.FINISHED);
    }

    public void finish(EventReservation reservation) {
        teardown(reservation);

        String eventId = reservation.getEventId();
        int cooldownSeconds = plugin.getConfigManager().getEventCooldownMinutes() * 60;
        plugin.offMainThread(() -> {
            store.retire(eventId);
            store.startCooldown(cooldownSeconds);
        });

        plugin.log("Event {} complete; arena {} released.", eventId, reservation.getArenaName());
    }

    private void onCancelled(Envelope envelope) {
        if (envelope.getEventId() == null) {
            return;
        }

        CancelledPayload cancelled = network.getBus().payload(envelope, CancelledPayload.class);
        // Gson maps an unknown enum constant to null rather than throwing
        CancelReason reason = cancelled == null || cancelled.getReason() == null ? CancelReason.ADMIN : cancelled.getReason();

        reservationForEvent(envelope.getEventId()).ifPresent(reservation -> {
            if (isMatchUnderway(reservation)) {
                plugin.log("Ignoring remote cancel for event {}: the match is already running.", envelope.getEventId());
                return;
            }
            abandon(reservation, reason);
        });
    }

    public void cancel(String eventId, CancelReason reason) {
        reservationForEvent(eventId).ifPresent(reservation -> {
            network.getBus().broadcast(NetworkRole.SMP, MessageType.EVENT_CANCELLED, eventId,
                    new CancelledPayload(reason, network.getServerId()));
            sendEveryoneHome(reservation, reason);
            teardown(reservation);
            plugin.offMainThread(() -> {
                store.read(eventId).map(record -> record.cancelled(reason)).ifPresent(store::write);
                store.retire(eventId);
            });
            plugin.log("Released arena {} from event {} ({}).", reservation.getArenaName(), eventId, reason);
        });
    }

    private void abandon(EventReservation reservation, CancelReason reason) {
        sendEveryoneHome(reservation, reason);
        teardown(reservation);
        String eventId = reservation.getEventId();
        plugin.offMainThread(() -> store.retire(eventId));
        plugin.log("Released arena {} from event {} ({}).", reservation.getArenaName(), eventId, reason);
    }

    private boolean isMatchUnderway(EventReservation reservation) {
        Arena arena = arenaOf(reservation);
        return arena != null && (arena.getStatus() == ArenaStatus.RUNNING || arena.getStatus() == ArenaStatus.END_LOBBY);
    }

    private void teardown(EventReservation reservation) {
        reservationsByArena.remove(reservation.getArenaName());
        stopTask(holdTasks, reservation.getArenaName());
        stopTask(arrivalTasks, reservation.getEventId());
        cancelWatchdog(reservation.getEventId());

        Arena arena = arenaOf(reservation);
        if (arena != null) {
            arena.setMinPlayers(reservation.getOriginalMinPlayers());
        }

        plugin.getVotingManager().cleanupArena(reservation.getArenaName());
    }

    private void sendEveryoneHome(EventReservation reservation, CancelReason reason) {
        Arena arena = arenaOf(reservation);
        if (arena != null) {
            List<Player> present = new ArrayList<>(arena.getPlayers());
            arena.getSpectators().stream().filter(spectator -> !present.contains(spectator)).forEach(present::add);

            List<UUID> travelling = new ArrayList<>();
            for (Player player : present) {
                arena.kickPlayer(player, KickReason.PLUGIN_STOP);
                player.sendMessage(plugin.getLocaleManager().formatMessage(player, reason.localeKey()));
                if (reservation.getRoster().contains(player.getUniqueId())) {
                    travelling.add(player.getUniqueId());
                }
            }

            if (!travelling.isEmpty()) {
                plugin.getReturnService().returnAll(travelling, reservation, ReturnOutcome.CANCELLED);
            }
        }
        plugin.getReturnService().clear(reservation.getEventId());
    }

    private Arena arenaOf(EventReservation reservation) {
        return BedwarsAPI.getGameAPI().getArenaByExactName(reservation.getArenaName());
    }

    private void writeState(String eventId, EventState state) {
        plugin.offMainThread(() -> store.read(eventId).map(record -> record.withState(state)).ifPresent(store::write));
    }

    private void watch(String eventId, long delayTicks, Consumer<EventReservation> check) {
        cancelWatchdog(eventId);
        watchdogs.put(eventId, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            watchdogs.remove(eventId);
            reservationForEvent(eventId).ifPresent(check);
        }, delayTicks));
    }

    private void cancelWatchdog(String eventId) {
        stopTask(watchdogs, eventId);
    }

    private static void stopTask(Map<String, BukkitTask> tasks, String key) {
        BukkitTask task = tasks.remove(key);
        if (task != null) {
            task.cancel();
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public void recoverOnBoot() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<EventRecord> stored = store.activeEventId().flatMap(store::read);
            if (stored.isEmpty()) {
                return;
            }

            EventRecord record = stored.get();
            if (!network.getServerId().equals(record.getMinigameServerId()) || record.getState().isTerminal()) {
                return;
            }

            plugin.log("Abandoning event {} left over from a previous run.", record.getEventId());
            store.write(record.cancelled(CancelReason.HOST_RESTARTED));
            network.getBus().broadcast(NetworkRole.SMP, MessageType.EVENT_CANCELLED, record.getEventId(),
                    new CancelledPayload(CancelReason.HOST_RESTARTED, network.getServerId()));
            store.retire(record.getEventId());
        });
    }

    public void shutdown() {
        for (EventReservation reservation : Map.copyOf(reservationsByArena).values()) {
            String eventId = reservation.getEventId();

            store.read(eventId).map(record -> record.cancelled(CancelReason.HOST_RESTARTED)).ifPresent(store::write);
            network.getBus().broadcast(NetworkRole.SMP, MessageType.EVENT_CANCELLED, eventId,
                    new CancelledPayload(CancelReason.HOST_RESTARTED, network.getServerId()));

            // recorded so a mid-match player is still greeted and paid on the SMP
            Arena arena = arenaOf(reservation);
            if (arena != null) {
                arena.getPlayers().stream()
                        .filter(player -> reservation.getRoster().contains(player.getUniqueId()))
                        .forEach(player -> plugin.getReturnService().recordOnly(player.getUniqueId(), reservation, ReturnOutcome.CANCELLED));
            }

            teardown(reservation);
            store.retire(eventId);
        }
    }
}
