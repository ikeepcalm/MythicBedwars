package dev.ua.ikeepcalm.bedwars.net.event;

import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;

import java.util.Optional;

/**
 * Durable event state, and the two locks that keep the network to one event at a time.
 *
 * <p>Every method here does Redis I/O and returns a safe default when Redis is down, so callers can
 * treat an outage as "no event in flight" rather than having to handle failure separately.
 */
public class EventStore {

    /**
     * How long a terminal event stays readable for peers still catching up.
     */
    private static final int RETIRED_RECORD_TTL_SECONDS = 120;

    private final RedisClient client;
    private final RedisKeys keys;
    private final int eventTtlSeconds;

    public EventStore(RedisClient client, RedisKeys keys, int eventTtlSeconds) {
        this.client = client;
        this.keys = keys;
        this.eventTtlSeconds = eventTtlSeconds;
    }

    /**
     * Claims the network-wide "one event at a time" slot.
     *
     * <p>The TTL is the backstop for a proposer that dies mid-flight: the slot frees itself rather
     * than blocking events forever.
     *
     * @return {@code true} if this caller now owns the slot
     */
    public boolean claimActiveSlot(String eventId) {
        return client.setIfAbsent(keys.activeEvent(), eventId, eventTtlSeconds);
    }

    /**
     * @return the event currently in flight anywhere on the network
     */
    public Optional<String> activeEventId() {
        return client.get(keys.activeEvent());
    }

    /**
     * Frees the slot, but only if it is still ours — a plain delete could release an event that had
     * already expired and been replaced.
     */
    public void releaseActiveSlot(String eventId) {
        client.deleteIfEquals(keys.activeEvent(), eventId);
    }

    /**
     * Decides which Bedwars server hosts, when several could.
     *
     * <p>Short TTL because it only has to outlive the accept handshake.
     *
     * @return {@code true} if this server won and should now reserve an arena
     */
    public boolean claimHost(String eventId, String serverId, int ttlSeconds) {
        return client.setIfAbsent(keys.eventHost(eventId), serverId, ttlSeconds);
    }

    public void write(EventRecord record) {
        client.hset(keys.event(record.eventId()), record.toMap(), eventTtlSeconds);
    }

    /**
     * @return the stored record, or empty when the event is unknown or already reaped
     */
    public Optional<EventRecord> read(String eventId) {
        return Optional.ofNullable(EventRecord.fromMap(client.hgetAll(keys.event(eventId))));
    }

    /**
     * Retires a finished event.
     *
     * <p>Frees the network slot and the working sets immediately, but only shortens the event hash's
     * life rather than deleting it. The hash is what a peer reconciles against, and deleting it in
     * the same breath as publishing the cancellation would take away the durable record exactly when
     * a peer that missed the message needs it. The remaining window is long enough for the sync pass
     * to notice and short enough that nothing accumulates.
     *
     * <p>Note the pending-return hash is deliberately untouched: it outlives the event on purpose, so
     * a player who is still travelling can be greeted correctly whenever they arrive.
     */
    public void retire(String eventId) {
        client.expire(keys.event(eventId), RETIRED_RECORD_TTL_SECONDS);
        client.delete(keys.eventHost(eventId));
        client.delete(keys.eventRoster(eventId));
        client.delete(keys.eventArrived(eventId));
        releaseActiveSlot(eventId);
    }

    /**
     * Removes every key belonging to an event, including the record itself. For an event nobody can
     * still be reconciling against — one whose record was already unreadable, or a boot-time cleanup.
     */
    public void purge(String eventId) {
        client.delete(keys.event(eventId));
        client.delete(keys.eventHost(eventId));
        client.delete(keys.eventRoster(eventId));
        client.delete(keys.eventArrived(eventId));
        releaseActiveSlot(eventId);
    }

    /**
     * Blocks new proposals for a while after an event ends, so a quiet server does not spam its
     * players with offers.
     */
    public void startCooldown(int seconds) {
        if (seconds > 0) {
            client.setWithTtl(keys.cooldown(), Long.toString(System.currentTimeMillis()), seconds);
        }
    }

    public boolean isOnCooldown() {
        return client.get(keys.cooldown()).isPresent();
    }
}
