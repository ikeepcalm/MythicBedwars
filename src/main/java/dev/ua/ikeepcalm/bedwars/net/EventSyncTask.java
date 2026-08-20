package dev.ua.ikeepcalm.bedwars.net;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.event.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;
import java.util.Set;

/**
 * Reconciles this server's view of an in-flight event against the durable record in Redis.
 *
 * <p>This is the other half of the design's central guarantee. Every transition writes the event
 * hash <em>before</em> publishing, precisely because pub/sub has no replay: a subscriber that was
 * reconnecting when a message went out never hears it, and would otherwise hold a reservation, or
 * refuse to start another event, indefinitely. Polling the hash turns that from a wedged event into
 * a delay of a couple of seconds.
 *
 * <p>Only ever reconciles in the giving-up direction. Advancing a state machine forwards from a
 * snapshot is guesswork — a half-applied transition is worse than a late one — so anything that is
 * genuinely stuck moving forward is left to {@link EventReaperTask}, which has the deadlines.
 *
 * <p>Runs asynchronously and touches nothing but Redis; the reconciliation itself hops to the main
 * thread.
 */
public class EventSyncTask extends BukkitRunnable {

    /**
     * How many consecutive passes must find nothing before an event is abandoned.
     *
     * <p>{@code EventStore.read} cannot distinguish "no such event" from "Redis did not answer" —
     * every client method returns a safe default on failure — and the availability flag only flips
     * <em>after</em> an operation has already failed. Acting on a single empty read would let one
     * hiccup release a healthy arena and send everybody home.
     */
    private static final int MISSES_BEFORE_ABANDON = 3;

    private final MythicBedwars plugin;
    private final EventStore store;

    /** Consecutive empty reads per event. */
    private final java.util.Map<String, Integer> misses = new java.util.concurrent.ConcurrentHashMap<>();

    public EventSyncTask(MythicBedwars plugin, EventStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public void run() {
        try {
            sweep();
        } catch (RuntimeException exception) {
            // An escaping exception would cancel this repeating task permanently, silently removing
            // the very safety net it exists to be.
            plugin.log("Event sync pass failed: {}", String.valueOf(exception.getMessage()));
        }
    }

    private void sweep() {
        EventParticipant participant = plugin.getEventParticipant();
        if (participant == null || !plugin.isNetworkAvailable()) {
            return;
        }

        Set<String> local = participant.localEventIds();
        misses.keySet().retainAll(local);

        if (local.isEmpty()) {
            return;
        }

        for (String eventId : local) {
            Optional<EventRecord> stored = store.read(eventId);

            if (stored.isEmpty()) {
                if (!plugin.isNetworkAvailable()) {
                    // Redis answered nothing because it is not there, not because the event is gone.
                    continue;
                }

                int seen = misses.merge(eventId, 1, Integer::sum);
                if (seen < MISSES_BEFORE_ABANDON) {
                    continue;
                }

                // Consistently absent while the connection is healthy: reaped elsewhere or expired.
                // Nothing is coming, and holding state would block every future event.
                abandon(participant, eventId, CancelReason.ADMIN);
                continue;
            }

            misses.remove(eventId);

            EventRecord record = stored.get();
            if (record.state().isTerminal()) {
                abandon(participant, eventId, record.cancelReason());
            }
        }
    }

    private void abandon(EventParticipant participant, String eventId, CancelReason reason) {
        CancelReason resolved = reason == null ? CancelReason.ADMIN : reason;

        misses.remove(eventId);
        plugin.log("Reconciled event {} from Redis: no longer in flight ({}).", eventId, resolved);
        Bukkit.getScheduler().runTask(plugin, () -> participant.abandonLocally(eventId, resolved));
    }
}
