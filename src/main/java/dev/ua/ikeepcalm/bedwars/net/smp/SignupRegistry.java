package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.net.transport.LuaScripts;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The event roster, with every mutation going through one atomic script.
 */
public class SignupRegistry {

    private final RedisClient client;
    private final RedisKeys keys;
    private final int rosterTtlSeconds;

    public SignupRegistry(RedisClient client, RedisKeys keys, int rosterTtlSeconds) {
        this.client = client;
        this.keys = keys;
        this.rosterTtlSeconds = rosterTtlSeconds;
    }

    /**
     * What happened to one signup attempt.
     *
     * @param outcome  how it went
     * @param position the roster size after the attempt; only meaningful on {@link Outcome#ADDED}
     */
    public record Result(Outcome outcome, int position) {
    }

    public enum Outcome {
        ADDED,
        ALREADY_SIGNED_UP,
        FULL,
        CLOSED,
        /** Redis could not be reached; the caller should apologise rather than claim success. */
        ERROR
    }

    /**
     * Takes a player off the roster.
     *
     * <p>Needed for the signed-up-then-vanished case. Leaving a no-show on the roster keeps the
     * "everybody has arrived" check from ever passing, so the match waits out its whole grace period
     * and can be cancelled for too few arrivals while the players who did turn up stand around.
     *
     * <p>Does Redis I/O — call off the main thread.
     */
    public void remove(String eventId, UUID playerId) {
        client.srem(keys.eventRoster(eventId), playerId.toString());
    }

    /**
     * Attempts to add {@code playerId} to the roster. Does Redis I/O — call off the main thread.
     */
    public Result signUp(String eventId, UUID playerId, int cap) {
        long reply = client.evalLong(
                LuaScripts.SIGNUP,
                List.of(keys.eventRoster(eventId), keys.event(eventId)),
                List.of(playerId.toString(), Integer.toString(cap), Integer.toString(rosterTtlSeconds)),
                Long.MIN_VALUE);

        if (reply == LuaScripts.SIGNUP_CLOSED) {
            return new Result(Outcome.CLOSED, 0);
        }
        if (reply == LuaScripts.SIGNUP_DUPLICATE) {
            return new Result(Outcome.ALREADY_SIGNED_UP, 0);
        }
        if (reply == LuaScripts.SIGNUP_FULL) {
            return new Result(Outcome.FULL, 0);
        }
        if (reply < 0) {
            return new Result(Outcome.ERROR, 0);
        }

        return new Result(Outcome.ADDED, (int) reply);
    }

    /**
     * @return the signed-up player ids, skipping anything unparseable rather than failing the whole
     * roster over one bad entry
     */
    public Set<UUID> roster(String eventId) {
        return client.smembers(keys.eventRoster(eventId)).stream()
                .map(SignupRegistry::parseUuid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public int size(String eventId) {
        return client.smembers(keys.eventRoster(eventId)).size();
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
