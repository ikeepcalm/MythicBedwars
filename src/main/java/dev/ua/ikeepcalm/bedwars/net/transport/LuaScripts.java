package dev.ua.ikeepcalm.bedwars.net.transport;

/**
 * Server-side scripts, kept together so the atomicity guarantees are readable in one place.
 */
public final class LuaScripts {

    /**
     * Adds one player to an event roster, or explains why not — in a single atomic step.
     *
     * <p>Doing this from Java would be check-then-write across several round trips, and two players
     * clicking at the same moment could both pass the cap check. Inside Redis the whole thing is
     * one operation, so the cap is exact no matter how many people click at once or how many SMP
     * nodes are running.
     *
     * <p>{@code KEYS[1]} roster set, {@code KEYS[2]} event hash.
     * {@code ARGV[1]} player uuid, {@code ARGV[2]} cap, {@code ARGV[3]} roster ttl seconds.
     *
     * <p>Returns the new roster size on success, or one of the negative codes below.
     */
    public static final String SIGNUP = """
            if redis.call('HGET', KEYS[2], 'state') ~= 'ANNOUNCED' then return -1 end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -2 end
            if redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[2]) then return -3 end
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return redis.call('SCARD', KEYS[1])
            """;

    /** Signups are not open (yet, or any more). */
    public static final long SIGNUP_CLOSED = -1L;

    /** This player is already on the roster. */
    public static final long SIGNUP_DUPLICATE = -2L;

    /** The roster is at capacity. */
    public static final long SIGNUP_FULL = -3L;

    private LuaScripts() {
    }
}
