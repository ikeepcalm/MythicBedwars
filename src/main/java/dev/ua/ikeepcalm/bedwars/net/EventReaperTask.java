package dev.ua.ikeepcalm.bedwars.net;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.event.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.payload.Payloads;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;

/**
 * Sweeps up events that stopped making progress.
 *
 * <p>Pub/sub cannot be relied on for the sad paths — the whole reason a server needs reaping is
 * usually that it stopped talking. So this works purely from durable state: the event hash, and
 * whether each named participant still has a heartbeat.
 *
 * <p>Runs on both roles, and deliberately never touches a {@code RUNNING} event: a match in progress
 * is judged only by its host, which knows whether the arena is real.
 */
public class EventReaperTask extends BukkitRunnable {

    /** Grace beyond a deadline before acting, so a slow tick is not mistaken for a dead server. */
    private static final long DEADLINE_GRACE_MILLIS = 30_000L;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;

    /**
     * Snapshotted rather than read per pass: this runs on an async thread, and the backing
     * configuration object is swapped wholesale by {@code /mb reload} on the main one.
     */
    private final long proposeTimeoutMillis;
    private final long stallMillis;

    public EventReaperTask(MythicBedwars plugin, NetworkService network, EventStore store) {
        this.plugin = plugin;
        this.network = network;
        this.store = store;
        this.proposeTimeoutMillis = plugin.getConfigManager().getEventProposeTimeoutSeconds() * 1000L;
        // Derived from the lifecycle it has to outlast, not from the TTL: an operator who lowers
        // event-ttl-seconds would otherwise start reaping perfectly healthy events.
        long happyPath = (plugin.getConfigManager().getEventSignupSeconds()
                + plugin.getConfigManager().getEventArrivalGraceSeconds()
                + plugin.getConfigManager().getEventFillWindowSeconds()
                + plugin.getConfigManager().getEventLobbyHoldSeconds()) * 1000L;
        this.stallMillis = Math.max(180_000L, happyPath * 2);
    }

    @Override
    public void run() {
        try {
            sweep();
        } catch (RuntimeException exception) {
            // Letting this escape would cancel the repeating task for good, quietly disabling the
            // only thing that cleans up after a dead peer.
            plugin.log("Reaper pass failed: {}", String.valueOf(exception.getMessage()));
        }
    }

    private void sweep() {
        if (!network.isAvailable()) {
            return;
        }

        Optional<String> active = store.activeEventId();
        if (active.isEmpty()) {
            return;
        }

        String eventId = active.get();
        Optional<EventRecord> stored = store.read(eventId);

        if (stored.isEmpty()) {
            // The slot outlived its record; nothing can be recovered, so free it for the next event.
            plugin.log("Reaping event slot {} - its record is gone.", eventId);
            store.releaseActiveSlot(eventId);
            return;
        }

        EventRecord record = stored.get();
        if (record.state().isTerminal()) {
            store.retire(eventId);
            return;
        }

        if (record.state() == EventState.RUNNING) {
            return;
        }

        CancelReason reason = diagnose(record);
        if (reason == null) {
            return;
        }

        plugin.log("Reaping event {} in state {}: {}", eventId, record.state(), reason);
        store.write(record.cancelled(reason));

        // Only the other role's channel. A message published to our own role never comes back to
        // us: the bus registers each outgoing id in its dedup ring before publishing, so the local
        // half has to be told directly.
        network.bus().broadcast(network.counterpartRole(), MessageType.EVENT_CANCELLED, eventId,
                new Payloads.Cancelled(reason, network.serverId()));

        EventParticipant participant = plugin.getEventParticipant();
        if (participant != null) {
            CancelReason resolved = reason;
            Bukkit.getScheduler().runTask(plugin, () -> participant.abandonLocally(eventId, resolved));
        }

        store.retire(eventId);
    }

    /**
     * @return why this event should be reaped, or {@code null} if it is still healthy
     */
    private CancelReason diagnose(EventRecord record) {
        if (isGone(record.minigameServerId())) {
            return CancelReason.HOST_GONE;
        }

        if (isGone(record.smpServerId())) {
            return CancelReason.PROPOSER_GONE;
        }

        long now = System.currentTimeMillis();

        if (record.state() == EventState.ANNOUNCED
            && record.signupDeadline() > 0
            && now > record.signupDeadline() + DEADLINE_GRACE_MILLIS) {
            return CancelReason.TIMEOUT_SIGNUP;
        }

        // A proposal nobody answered. The host claim TTL has long since lapsed by this point.
        if (record.state() == EventState.PROPOSED
                && now > record.createdAt() + proposeTimeoutMillis + DEADLINE_GRACE_MILLIS) {
            return CancelReason.NO_HOST;
        }

        // Catch-all. ACCEPTED, SIGNUP_CLOSED, ARENA_READY and TRANSFERRING have no deadline of their
        // own, so without this an event whose state write landed but whose announcement did not
        // would hold the network's one event slot until its full TTL elapsed.
        if (now > record.createdAt() + stallMillis) {
            return CancelReason.TIMEOUT_SIGNUP;
        }

        return null;
    }

    /**
     * @return whether a named server has stopped heartbeating. An unnamed one has not been assigned
     * yet and cannot be missing.
     */
    private boolean isGone(String serverId) {
        if (serverId == null) {
            return false;
        }

        return network.registry().alive().stream()
                .noneMatch(heartbeat -> serverId.equals(heartbeat.serverId()));
    }

}
