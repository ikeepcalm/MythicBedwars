package dev.ua.ikeepcalm.bedwars.net.transport;

public class LuaScripts {

    public static final String SIGNUP = """
            if redis.call('HGET', KEYS[2], 'state') ~= 'ANNOUNCED' then return -1 end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -2 end
            if redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[2]) then return -3 end
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return redis.call('SCARD', KEYS[1])
            """;

    public static final String HSET_WITH_TTL = """
            for i = 2, #ARGV, 2 do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            return 1
            """;

    public static final String SADD_WITH_TTL = """
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return redis.call('SCARD', KEYS[1])
            """;

    public static final String LPUSH_WITH_TTL = """
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return redis.call('LLEN', KEYS[1])
            """;

    public static final String DELETE_IF_EQUALS = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """;

    public static final long SIGNUP_CLOSED = -1L;
    public static final long SIGNUP_DUPLICATE = -2L;
    public static final long SIGNUP_FULL = -3L;
}
