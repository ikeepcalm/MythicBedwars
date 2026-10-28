package dev.ua.ikeepcalm.bedwars.net.event.service;

import dev.ua.ikeepcalm.bedwars.net.event.model.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class EventStore {

    private static final int RETIRED_RECORD_TTL_SECONDS = 120;

    private final RedisClient client;
    private final RedisKeys keys;
    private final int eventTtlSeconds;

    public boolean claimActiveSlot(String eventId) {
        return client.setIfAbsent(keys.activeEvent(), eventId, eventTtlSeconds);
    }

    public Optional<String> activeEventId() {
        return client.get(keys.activeEvent());
    }

    public void releaseActiveSlot(String eventId) {
        client.deleteIfEquals(keys.activeEvent(), eventId);
    }

    public boolean claimHost(String eventId, String serverId, int ttlSeconds) {
        return client.setIfAbsent(keys.eventHost(eventId), serverId, ttlSeconds);
    }

    public void write(EventRecord record) {
        client.hset(keys.event(record.getEventId()), record.toMap(), eventTtlSeconds);
    }

    public Optional<EventRecord> read(String eventId) {
        return Optional.ofNullable(EventRecord.fromMap(client.hgetAll(keys.event(eventId))));
    }

    public void retire(String eventId) {
        // Expire rather than delete: a peer that missed the cancellation still reconciles against the record.
        client.expire(keys.event(eventId), RETIRED_RECORD_TTL_SECONDS);
        deleteWorkingKeys(eventId);
    }

    public void purge(String eventId) {
        client.delete(keys.event(eventId));
        deleteWorkingKeys(eventId);
    }

    private void deleteWorkingKeys(String eventId) {
        client.delete(keys.eventHost(eventId));
        client.delete(keys.eventRoster(eventId));
        client.delete(keys.eventArrived(eventId));
        releaseActiveSlot(eventId);
    }

    public void startCooldown(int seconds) {
        if (seconds > 0) {
            client.setWithTtl(keys.cooldown(), Long.toString(System.currentTimeMillis()), seconds);
        }
    }

    public boolean isOnCooldown() {
        return client.get(keys.cooldown()).isPresent();
    }
}
