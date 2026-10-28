package dev.ua.ikeepcalm.bedwars.net.task;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.event.model.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.service.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.type.EventParticipant;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class EventSyncTask extends BukkitRunnable {

    private static final int MISSES_BEFORE_ABANDON = 3;

    private final MythicBedwars plugin;
    private final EventStore store;
    private final Map<String, Integer> misses = new ConcurrentHashMap<>();

    @Override
    public void run() {
        try {
            sweep();
        } catch (RuntimeException exception) {
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
        for (String eventId : local) {
            reconcile(participant, eventId);
        }
    }

    private void reconcile(EventParticipant participant, String eventId) {
        Optional<EventRecord> stored = store.read(eventId);
        if (stored.isPresent()) {
            misses.remove(eventId);
            if (stored.get().getState().isTerminal()) {
                abandon(participant, eventId, stored.get().getCancelReason());
            }
            return;
        }

        // An empty read is indistinguishable from a failed one, so wait for several in a row.
        if (plugin.isNetworkAvailable() && misses.merge(eventId, 1, Integer::sum) >= MISSES_BEFORE_ABANDON) {
            abandon(participant, eventId, CancelReason.ADMIN);
        }
    }

    private void abandon(EventParticipant participant, String eventId, CancelReason reason) {
        CancelReason resolved = reason == null ? CancelReason.ADMIN : reason;
        misses.remove(eventId);
        plugin.log("Reconciled event {} from Redis: no longer in flight ({}).", eventId, resolved);
        Bukkit.getScheduler().runTask(plugin, () -> participant.abandonLocally(eventId, resolved));
    }
}
