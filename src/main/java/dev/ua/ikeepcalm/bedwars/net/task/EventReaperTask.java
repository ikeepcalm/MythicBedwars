package dev.ua.ikeepcalm.bedwars.net.task;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import dev.ua.ikeepcalm.bedwars.net.event.model.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.service.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.CancelledPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.Heartbeat;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import dev.ua.ikeepcalm.bedwars.net.service.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.type.EventParticipant;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class EventReaperTask extends BukkitRunnable {

    private static final long DEADLINE_GRACE_MILLIS = 30_000L;
    private static final long MIN_STALL_MILLIS = 180_000L;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;
    private final long proposeTimeoutMillis;
    private final long stallMillis;

    public EventReaperTask(MythicBedwars plugin, NetworkService network, EventStore store) {
        ConfigLoader config = plugin.getConfigManager();
        this.plugin = plugin;
        this.network = network;
        this.store = store;
        this.proposeTimeoutMillis = config.getEventProposeTimeoutSeconds() * 1000L;
        long happyPathMillis = (config.getEventSignupSeconds() + config.getEventArrivalGraceSeconds()
                + config.getEventFillWindowSeconds() + config.getEventLobbyHoldSeconds()) * 1000L;
        this.stallMillis = Math.max(MIN_STALL_MILLIS, happyPathMillis * 2);
    }

    @Override
    public void run() {
        try {
            sweep();
        } catch (RuntimeException exception) {
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
            if (network.isAvailable()) {
                plugin.log("Reaping event slot {} - its record is gone.", eventId);
                store.releaseActiveSlot(eventId);
            }
            return;
        }

        EventRecord record = stored.get();
        if (record.getState().isTerminal()) {
            store.retire(eventId);
            return;
        }

        CancelReason reason = record.getState() == EventState.RUNNING ? null : diagnose(record);
        if (reason != null) {
            reap(record, reason);
        }
    }

    private void reap(EventRecord record, CancelReason reason) {
        String eventId = record.getEventId();
        plugin.log("Reaping event {} in state {}: {}", eventId, record.getState(), reason);
        store.write(record.cancelled(reason));
        network.getBus().broadcast(network.counterpartRole(), MessageType.EVENT_CANCELLED, eventId,
                new CancelledPayload(reason, network.getServerId()));

        EventParticipant participant = plugin.getEventParticipant();
        if (participant != null) {
            Bukkit.getScheduler().runTask(plugin, () -> participant.abandonLocally(eventId, reason));
        }

        store.retire(eventId);
    }

    private CancelReason diagnose(EventRecord record) {
        Set<String> alive = network.getRegistry().alive().stream()
                .map(Heartbeat::getServerId)
                .collect(Collectors.toSet());

        // Our own heartbeat missing means the read failed, not that every peer died.
        if (alive.contains(network.getServerId())) {
            if (isGone(alive, record.getMinigameServerId())) {
                return CancelReason.HOST_GONE;
            }
            if (isGone(alive, record.getSmpServerId())) {
                return CancelReason.PROPOSER_GONE;
            }
        }

        long now = System.currentTimeMillis();
        if (record.getState() == EventState.ANNOUNCED && record.getSignupDeadline() > 0
                && now > record.getSignupDeadline() + DEADLINE_GRACE_MILLIS) {
            return CancelReason.TIMEOUT_SIGNUP;
        }

        if (record.getState() == EventState.PROPOSED
                && now > record.getCreatedAt() + proposeTimeoutMillis + DEADLINE_GRACE_MILLIS) {
            return CancelReason.NO_HOST;
        }

        return now > record.getCreatedAt() + stallMillis ? CancelReason.TIMEOUT_SIGNUP : null;
    }

    private boolean isGone(Set<String> alive, String serverId) {
        return serverId != null && !alive.contains(serverId);
    }
}
