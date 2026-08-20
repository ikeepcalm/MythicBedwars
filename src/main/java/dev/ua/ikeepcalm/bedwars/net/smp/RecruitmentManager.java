package dev.ua.ikeepcalm.bedwars.net.smp;

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

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The survival-server half: decide when to run an event, ask for a host, and hold the result.
 *
 * <p>The rule that matters most here is that <b>nothing is ever announced to players before a host
 * has accepted</b>. Advertising a match the network cannot actually run is worse than not
 * advertising one at all.
 */
public class RecruitmentManager {

    /** Guards the decide-and-propose critical section against a second SMP node. */
    private static final int PROPOSE_LOCK_TTL_SECONDS = 10;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;

    private volatile String currentEventId;
    private volatile EventState currentState;
    private volatile String hostServerId;
    private volatile String hostServerName;
    private volatile String arenaName;

    public RecruitmentManager(MythicBedwars plugin, NetworkService network, EventStore store) {
        this.plugin = plugin;
        this.network = network;
        this.store = store;
    }

    public void registerHandlers() {
        network.bus().on(MessageType.EVENT_ACCEPT, this::onAccept);
        network.bus().on(MessageType.EVENT_REJECT, this::onReject);
        network.bus().on(MessageType.EVENT_CANCELLED, this::onCancelled);
    }

    public Optional<String> currentEventId() {
        return Optional.ofNullable(currentEventId);
    }

    public EventState currentState() {
        return currentState;
    }

    public Optional<String> arena() {
        return Optional.ofNullable(arenaName);
    }

    public Optional<String> host() {
        return Optional.ofNullable(hostServerId);
    }

    /**
     * Attempts to start an event.
     *
     * <p>Everything up to and including the publish happens off the main thread, because each
     * precondition is a Redis round trip. {@code feedback} is invoked back on the main thread so the
     * caller can safely message a command sender.
     *
     * @param ignoreCooldown for the admin command, which should not be blocked by the quiet period
     */
    public void propose(boolean ignoreCooldown, Consumer<String> feedback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String outcome = tryPropose(ignoreCooldown);
            Bukkit.getScheduler().runTask(plugin, () -> feedback.accept(outcome));
        });
    }

    /**
     * @return {@code null} on success, otherwise a human-readable reason it did not happen
     */
    private String tryPropose(boolean ignoreCooldown) {
        if (!plugin.getConfigManager().isEventEnabled()) {
            return "Events are disabled in the config.";
        }

        if (!network.isAvailable()) {
            return "Redis is unavailable - cannot reach the Bedwars server.";
        }

        if (currentEventId != null) {
            return "An event is already in flight (" + currentEventId + ", " + currentState + ").";
        }

        if (!ignoreCooldown && store.isOnCooldown()) {
            return "An event ran recently; still on cooldown.";
        }

        // Pre-flight: never advertise a game nobody can host.
        network.registry().invalidate();
        if (!network.registry().hasLiveMinigameServer()) {
            return "No Bedwars server is online to host.";
        }

        String lockToken = UUID.randomUUID().toString();
        if (!network.client().setIfAbsent(network.keys().proposeLock(), lockToken, PROPOSE_LOCK_TTL_SECONDS)) {
            return "Another server is proposing an event right now.";
        }

        try {
            if (store.activeEventId().isPresent()) {
                return "An event is already active on the network.";
            }

            String eventId = UUID.randomUUID().toString();
            if (!store.claimActiveSlot(eventId)) {
                return "Could not claim the network event slot.";
            }

            int minPlayers = plugin.getConfigManager().getEventMinPlayers();
            int maxPlayers = plugin.getConfigManager().getEventMaxPlayers();

            EventRecord record = new EventRecord(
                    eventId, EventState.PROPOSED,
                    network.serverId(), plugin.getConfigManager().getThisVelocityServer(),
                    null, null, null,
                    minPlayers, maxPlayers,
                    System.currentTimeMillis(), 0L, null);
            store.write(record);

            currentEventId = eventId;
            currentState = EventState.PROPOSED;

            network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_PROPOSE, eventId,
                    new Payloads.Propose(network.serverId(), plugin.getConfigManager().getThisVelocityServer(),
                            minPlayers, maxPlayers, Bukkit.getOnlinePlayers().size()));

            plugin.log("Proposed event {} (min {}, max {}).", eventId, minPlayers, maxPlayers);
            return null;
        } finally {
            network.client().deleteIfEquals(network.keys().proposeLock(), lockToken);
        }
    }

    private void onAccept(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.Accept accept = network.bus().payload(envelope, Payloads.Accept.class);
        if (accept == null) {
            return;
        }

        hostServerId = accept.minigameServerId();
        hostServerName = accept.minigameServerName();
        arenaName = accept.arenaName();
        currentState = EventState.ACCEPTED;

        // Persist our own view of the transition too. The host writes the record before it
        // publishes, but if that write is the thing that failed we would otherwise be left with a
        // durable record that disagrees with reality - and a later reconcile would drag us back to
        // PROPOSED. Writing the same record from here is idempotent and makes it self-healing.
        String eventId = envelope.eventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> store.read(eventId)
                .map(record -> record.accepted(accept.minigameServerId(), accept.minigameServerName(),
                        accept.arenaName(), accept.arenaCapacity(), accept.signupDeadline()))
                .ifPresent(store::write));

        plugin.log("Event {} accepted by {} on arena {} (capacity {}).",
                envelope.eventId(), accept.minigameServerId(), accept.arenaName(), accept.arenaCapacity());
        plugin.log("Recruitment announcement is not wired up yet - use /mb event cancel to release it.");
    }

    private void onReject(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.Reject reject = network.bus().payload(envelope, Payloads.Reject.class);
        String reason = reject == null ? "unspecified" : reject.reason();

        plugin.log("Event {} rejected by {}: {}", envelope.eventId(),
                reject == null ? "?" : reject.minigameServerId(), reason);

        // A reject is only final when nobody else could pick it up; with one Bedwars server that is
        // immediately true, so release rather than leaving the slot claimed.
        clearAndRelease(envelope.eventId());
    }

    private void onCancelled(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.Cancelled cancelled = network.bus().payload(envelope, Payloads.Cancelled.class);
        CancelReason reason = cancelled == null ? CancelReason.ADMIN : cancelled.reason();

        plugin.log("Event {} cancelled ({}).", envelope.eventId(), reason.display());
        clearLocal();
    }

    /**
     * Cancels the in-flight event from this side.
     */
    public void cancel(CancelReason reason, Consumer<String> feedback) {
        String eventId = currentEventId;
        if (eventId == null) {
            feedback.accept("No event is in flight.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.read(eventId).map(record -> record.cancelled(reason)).ifPresent(store::write);
            network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, eventId,
                    new Payloads.Cancelled(reason, network.serverId()));
            store.purge(eventId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                clearLocal();
                feedback.accept("Cancelled event " + eventId + ".");
            });
        });
    }

    private void clearAndRelease(String eventId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.purge(eventId);
            Bukkit.getScheduler().runTask(plugin, this::clearLocal);
        });
    }

    private void clearLocal() {
        currentEventId = null;
        currentState = null;
        hostServerId = null;
        hostServerName = null;
        arenaName = null;
    }

    private boolean isCurrent(String eventId) {
        return eventId != null && eventId.equals(currentEventId);
    }

    /**
     * Picks up an event this server proposed before a restart, or drops it if it is past saving.
     */
    public void recoverOnBoot() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<String> active = store.activeEventId();
            if (active.isEmpty()) {
                return;
            }

            Optional<EventRecord> stored = store.read(active.get());
            if (stored.isEmpty()) {
                // Slot claimed but the record is gone: nothing can be recovered from it.
                store.releaseActiveSlot(active.get());
                return;
            }

            EventRecord record = stored.get();
            if (!network.serverId().equals(record.smpServerId()) || record.state().isTerminal()) {
                return;
            }

            plugin.log("Abandoning event {} left over from a previous run.", record.eventId());
            store.write(record.cancelled(CancelReason.PROPOSER_GONE));
            network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, record.eventId(),
                    new Payloads.Cancelled(CancelReason.PROPOSER_GONE, network.serverId()));
            store.purge(record.eventId());
        });
    }

    public Optional<String> hostServerName() {
        return Optional.ofNullable(hostServerName);
    }
}
