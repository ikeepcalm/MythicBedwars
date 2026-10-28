package dev.ua.ikeepcalm.bedwars.net.transport;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import lombok.RequiredArgsConstructor;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.bukkit.Bukkit;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

@RequiredArgsConstructor
public class RedisClient {

    private static final long RECONNECT_MIN_MILLIS = 1_000L;
    private static final long RECONNECT_MAX_MILLIS = 30_000L;
    private static final int MAX_SCAN_ITERATIONS = 16;
    private static final List<String> FATAL_REPLY_PREFIXES = List.of(
            "LOADING", "MISCONF", "OOM", "READONLY", "CLUSTERDOWN", "NOAUTH", "NOPERM", "MASTERDOWN");

    private final MythicBedwars plugin;
    private final Map<String, Consumer<String>> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile JedisPool pool;
    private volatile boolean connected;
    private volatile Thread subscriberThread;
    private volatile JedisPubSub activePubSub;
    private volatile Jedis subscriberConnection;
    private volatile HostAndPort address;
    private volatile JedisClientConfig clientConfig;
    private volatile boolean outageLogged;
    private volatile boolean commandErrorLogged;

    public void subscribe(String channel, Consumer<String> handler) {
        if (running.get()) {
            plugin.log("Ignoring subscription to '{}' registered after the client started.", channel);
            return;
        }

        subscriptions.put(channel, handler);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        ConfigLoader config = plugin.getConfigManager();
        clientConfig = buildClientConfig(config);
        address = new HostAndPort(config.getRedisHost(), config.getRedisPort());
        pool = new JedisPool(buildPoolConfig(config), address, clientConfig);

        String host = config.getRedisHost();
        int port = config.getRedisPort();
        String namespace = config.getRedisNamespace();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (ping()) {
                plugin.log("Redis connected ({}:{}, namespace '{}')", host, port, namespace);
            } else {
                plugin.log("Redis unreachable at {}:{} - cross-server events are offline until it returns.", host, port);
            }
        });

        if (!subscriptions.isEmpty()) {
            startSubscriber();
        }
    }

    private GenericObjectPoolConfig<Jedis> buildPoolConfig(ConfigLoader config) {
        GenericObjectPoolConfig<Jedis> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(config.getRedisPoolMaxTotal());
        poolConfig.setMaxIdle(config.getRedisPoolMaxIdle());
        poolConfig.setMinIdle(config.getRedisPoolMinIdle());
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(Duration.ofMillis(Math.min(250, config.getRedisTimeoutMs())));
        return poolConfig;
    }

    private JedisClientConfig buildClientConfig(ConfigLoader config) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.getRedisTimeoutMs())
                .socketTimeoutMillis(config.getRedisTimeoutMs())
                .database(config.getRedisDatabase())
                .ssl(config.isRedisSsl());

        String password = config.getRedisPassword();
        if (password != null && !password.isEmpty()) {
            builder.password(password);
        }
        return builder.build();
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        connected = false;
        JedisPubSub pubSub = activePubSub;
        Jedis subscriber = subscriberConnection;
        closeQuietly(pubSub == null ? null : pubSub::unsubscribe);
        // interrupt() cannot break a blocking socket read, so close the connection underneath it.
        closeQuietly(subscriber == null ? null : subscriber::close);

        Thread thread = subscriberThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        JedisPool current = pool;
        pool = null;
        closeQuietly(current == null ? null : current::close);
    }

    private void closeQuietly(Runnable close) {
        if (close == null) {
            return;
        }

        try {
            close.run();
        } catch (RuntimeException ignored) {
        }
    }

    public boolean isAvailable() {
        return running.get() && connected && pool != null;
    }

    public void probeIfDisconnected() {
        if (running.get() && !connected && pool != null) {
            ping();
        }
    }

    public boolean publish(String channel, String payload) {
        return withRedis(jedis -> {
            jedis.publish(channel, payload);
            return true;
        }, false);
    }

    public boolean setWithTtl(String key, String value, int ttlSeconds) {
        return withRedis(jedis -> {
            jedis.setex(key, ttlSeconds, value);
            return true;
        }, false);
    }

    public boolean setIfAbsent(String key, String value, int ttlSeconds) {
        return withRedis(jedis -> "OK".equals(jedis.set(key, value, SetParams.setParams().nx().ex(ttlSeconds))), false);
    }

    public Optional<String> get(String key) {
        return withRedis(jedis -> Optional.ofNullable(jedis.get(key)), Optional.empty());
    }

    public List<String> getAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }

        String[] array = keys.toArray(new String[0]);
        return withRedis(jedis -> jedis.mget(array), List.of());
    }

    public boolean delete(String key) {
        return withRedis(jedis -> jedis.del(key) > 0, false);
    }

    public boolean deleteIfEquals(String key, String expectedValue) {
        return withRedis(jedis -> jedis.eval(LuaScripts.DELETE_IF_EQUALS, List.of(key), List.of(expectedValue))
                instanceof Long deleted && deleted > 0, false);
    }

    public boolean hset(String key, Map<String, String> values, int ttlSeconds) {
        if (values.isEmpty()) {
            return false;
        }

        List<String> args = new ArrayList<>(values.size() * 2 + 1);
        args.add(Integer.toString(ttlSeconds));
        values.forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });

        return withRedis(jedis -> {
            jedis.eval(LuaScripts.HSET_WITH_TTL, List.of(key), args);
            return true;
        }, false);
    }

    public Map<String, String> hgetAll(String key) {
        return withRedis(jedis -> jedis.hgetAll(key), Map.of());
    }

    public boolean hdel(String key, String field) {
        return withRedis(jedis -> jedis.hdel(key, field) > 0, false);
    }

    public Set<String> smembers(String key) {
        return withRedis(jedis -> jedis.smembers(key), Set.of());
    }

    public boolean sadd(String key, String member, int ttlSeconds) {
        return withRedis(jedis -> {
            jedis.eval(LuaScripts.SADD_WITH_TTL, List.of(key), List.of(member, Integer.toString(ttlSeconds)));
            return true;
        }, false);
    }

    public boolean srem(String key, String member) {
        return withRedis(jedis -> jedis.srem(key, member) > 0, false);
    }

    public boolean expire(String key, int ttlSeconds) {
        return withRedis(jedis -> jedis.expire(key, ttlSeconds) > 0, false);
    }

    public Optional<String> lpop(String key) {
        return withRedis(jedis -> Optional.ofNullable(jedis.lpop(key)), Optional.empty());
    }

    public boolean lpush(String key, String value, int ttlSeconds) {
        return withRedis(jedis -> {
            jedis.eval(LuaScripts.LPUSH_WITH_TTL, List.of(key), List.of(value, Integer.toString(ttlSeconds)));
            return true;
        }, false);
    }

    public boolean rpushCapped(String key, String value, int maxLength) {
        return withRedis(jedis -> {
            jedis.rpush(key, value);
            jedis.ltrim(key, -maxLength, -1);
            return true;
        }, false);
    }

    public long evalLong(String script, List<String> keys, List<String> args, long fallback) {
        return withRedis(jedis -> jedis.eval(script, keys, args) instanceof Long value ? value : fallback, fallback);
    }

    public Set<String> scan(String matchPattern, int limit) {
        return withRedis(jedis -> {
            Set<String> found = new HashSet<>();
            ScanParams params = new ScanParams().match(matchPattern).count(512);
            String cursor = ScanParams.SCAN_POINTER_START;
            int iterations = 0;

            do {
                ScanResult<String> page = jedis.scan(cursor, params);
                found.addAll(page.getResult());
                cursor = page.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor)
                    && found.size() < limit
                    && ++iterations < MAX_SCAN_ITERATIONS);

            return found;
        }, Set.of());
    }

    private boolean ping() {
        return withRedis(jedis -> {
            jedis.ping();
            return true;
        }, false, true);
    }

    private <T> T withRedis(Function<Jedis, T> operation, T fallback) {
        return withRedis(operation, fallback, false);
    }

    private <T> T withRedis(Function<Jedis, T> operation, T fallback, boolean probing) {
        JedisPool current = pool;
        if (current == null || !running.get() || (!probing && !connected)) {
            return fallback;
        }

        try (Jedis jedis = current.getResource()) {
            T reply = operation.apply(jedis);
            markConnected();
            return reply;
        } catch (JedisDataException exception) {
            if (isFatalReply(exception.getMessage())) {
                markDisconnected(exception);
            } else if (!commandErrorLogged) {
                commandErrorLogged = true;
                plugin.log("Redis rejected a command: {}", String.valueOf(exception.getMessage()));
            }
            return fallback;
        } catch (RuntimeException exception) {
            markDisconnected(exception);
            return fallback;
        }
    }

    private static boolean isFatalReply(String message) {
        if (message == null) {
            return false;
        }

        String upper = message.toUpperCase(Locale.ROOT);
        return FATAL_REPLY_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    private void markConnected() {
        if (connected) {
            return;
        }

        connected = true;
        commandErrorLogged = false;
        if (outageLogged) {
            outageLogged = false;
            plugin.log("Redis connection restored.");
        }
    }

    private void markDisconnected(RuntimeException exception) {
        connected = false;
        if (!outageLogged) {
            outageLogged = true;
            plugin.log("Redis unavailable ({}: {}) - cross-server events are paused.",
                    exception.getClass().getSimpleName(), String.valueOf(exception.getMessage()));
        }
    }

    private void startSubscriber() {
        String[] channels = subscriptions.keySet().toArray(new String[0]);
        Thread thread = new Thread(() -> runSubscriber(channels), "MythicBedwars-Redis-Subscriber");
        thread.setDaemon(true);
        subscriberThread = thread;
        thread.start();
    }

    private void runSubscriber(String[] channels) {
        long backoff = RECONNECT_MIN_MILLIS;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            if (subscribeUntilDropped(channels)) {
                backoff = RECONNECT_MIN_MILLIS;
            }

            if (!running.get()) {
                return;
            }

            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, RECONNECT_MAX_MILLIS);
        }
    }

    private boolean subscribeUntilDropped(String[] channels) {
        boolean[] established = {false};

        // Not pooled: SUBSCRIBE holds its connection for life and would starve the pool.
        try (Jedis jedis = new Jedis(address, clientConfig)) {
            subscriberConnection = jedis;
            activePubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    dispatch(channel, message);
                }

                @Override
                public void onSubscribe(String channel, int subscribedChannels) {
                    established[0] = true;
                    markConnected();
                }
            };
            jedis.subscribe(activePubSub, channels);
        } catch (RuntimeException exception) {
            if (running.get()) {
                markDisconnected(exception);
            }
        } finally {
            activePubSub = null;
            subscriberConnection = null;
        }

        return established[0];
    }

    private void dispatch(String channel, String message) {
        Consumer<String> handler = subscriptions.get(channel);
        if (handler == null) {
            return;
        }

        try {
            handler.accept(message);
        } catch (RuntimeException exception) {
            plugin.log("Failed to handle Redis message on {}: {}", channel, String.valueOf(exception.getMessage()));
        }
    }
}
