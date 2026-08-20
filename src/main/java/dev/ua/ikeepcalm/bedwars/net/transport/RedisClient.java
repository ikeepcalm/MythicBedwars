package dev.ua.ikeepcalm.bedwars.net.transport;

import java.util.*;
import java.util.function.Consumer;

/**
 * The seam between this plugin and Redis.
 *
 * <p>Deliberately expresses only the handful of operations the event system needs, in plain Java
 * types: {@code redis.clients.*} must not appear anywhere outside {@link JedisRedisClient}, so the
 * driver can be swapped (or borrowed from another plugin) by writing one class.
 *
 * <p>Every method is safe to call when Redis is down. Reads return empty, writes return
 * {@code false}; nothing throws, because a Redis outage must never take gameplay with it.
 */
public interface RedisClient {

    /**
     * Connects the pool and starts the subscriber thread. Non-blocking: if Redis is unreachable the
     * client simply stays {@linkplain #isAvailable() unavailable} and keeps retrying in the
     * background.
     */
    void start();

    /**
     * Closes the pool and stops the subscriber thread. Safe to call more than once.
     */
    void shutdown();

    /**
     * @return whether commands issued right now have a reasonable chance of succeeding
     */
    boolean isAvailable();

    /**
     * Registers interest in a channel. Must be called before {@link #start()}; the subscriber
     * thread subscribes to everything registered at once.
     *
     * <p>The handler runs on the subscriber thread — callers that touch Bukkit must hop to the main
     * thread themselves. {@link RedisBus} already does this for its consumers.
     */
    void subscribe(String channel, Consumer<String> handler);

    /**
     * @return {@code true} if the message was handed to Redis
     */
    boolean publish(String channel, String payload);

    /**
     * Unconditional write with an expiry.
     */
    boolean setWithTtl(String key, String value, int ttlSeconds);

    /**
     * Write only if the key is absent — the primitive behind both distributed locks.
     *
     * @return {@code true} if this caller won
     */
    boolean setIfAbsent(String key, String value, int ttlSeconds);

    Optional<String> get(String key);

    /**
     * @return the values for {@code keys}, in order, with {@code null} for any that are missing.
     * Empty when Redis is unavailable.
     */
    List<String> getAll(Collection<String> keys);

    boolean delete(String key);

    /**
     * Deletes {@code key} only if it still holds {@code expectedValue}.
     *
     * <p>The safe way to release a lock: a plain delete could drop a lock that had already expired
     * and been re-acquired by somebody else.
     */
    boolean deleteIfEquals(String key, String expectedValue);

    /**
     * Writes the given fields into a hash and refreshes its expiry. Fields not mentioned are left
     * alone, so two sides can update different parts of the same record without clobbering.
     */
    boolean hset(String key, Map<String, String> values, int ttlSeconds);

    /**
     * @return every field of the hash, or an empty map when it is absent or Redis is unavailable
     */
    Map<String, String> hgetAll(String key);

    /**
     * @return the members of a set, or empty when it is absent or Redis is unavailable
     */
    Set<String> smembers(String key);

    /**
     * Removes and returns the head of a list.
     */
    Optional<String> lpop(String key);

    /**
     * Pushes to the head of a list and refreshes its expiry, preserving order on a retry.
     */
    boolean lpush(String key, String value, int ttlSeconds);

    /**
     * Appends to a list, trimming it to {@code maxLength} so a runaway producer cannot grow it
     * without bound.
     */
    boolean rpushCapped(String key, String value, int maxLength);

    /**
     * Runs a Lua script server-side.
     *
     * <p>The point of exposing this is atomicity: a check-then-write done from Java is two round
     * trips with a race in between, which for signups would mean going over the cap.
     *
     * @return the script's integer reply, or {@code fallback} if it could not be run
     */
    long evalLong(String script, List<String> keys, List<String> args, long fallback);

    /**
     * Cursor-based key search. Never {@code KEYS} — that blocks the whole Redis server, and this
     * runs on a timer.
     */
    Set<String> scan(String matchPattern, int limit);
}
