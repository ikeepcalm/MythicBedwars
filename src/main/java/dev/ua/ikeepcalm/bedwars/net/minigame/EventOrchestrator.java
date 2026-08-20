package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.event.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.MessageType;
import dev.ua.ikeepcalm.bedwars.net.protocol.payload.Payloads;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    public EventOrchestrator(MythicBedwars plugin, NetworkService network, EventStore store) {
        this.plugin = plugin;
        this.network = network;
        this.store = store;
        this.selector = new ArenaSelector(plugin);
    }

    public void registerHandlers() {
        network.bus().on(MessageType.EVENT_PROPOSE, this::onPropose);
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
