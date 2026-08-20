package dev.ua.ikeepcalm.bedwars.net.transport;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one class in this plugin that knows Redis exists.
 *
 * <p>Jedis arrives at runtime through the {@code libraries:} block in {@code plugin.yml}, into this
 * plugin's own classloader — so a different Jedis version in another plugin cannot clash with ours.
 *
 * <p>Failure policy throughout: log, degrade, keep going. A Redis outage disables cross-server
 * events and nothing else.
 */
public class JedisRedisClient implements RedisClient {

    private static final long RECONNECT_MIN_MILLIS = 1_000L;
    private static final long RECONNECT_MAX_MILLIS = 30_000L;

    private final MythicBedwars plugin;
    private final Map<String, Consumer<String>> subscriptions = new LinkedHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile JedisPool pool;
    private volatile boolean connected;
    private volatile Thread subscriberThread;
    private volatile JedisPubSub activePubSub;

    /**
     * Throttles the "Redis is down" log to once per outage rather than once per operation.
     */
    private volatile boolean outageLogged;

    public JedisRedisClient(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    @Override
    public void subscribe(String channel, Consumer<String> handler) {
        subscriptions.put(channel, handler);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        ConfigLoader config = plugin.getConfigManager();

        GenericObjectPoolConfig<Jedis> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(config.getRedisPoolMaxTotal());
        poolConfig.setMaxIdle(config.getRedisPoolMaxIdle());
        poolConfig.setMinIdle(config.getRedisPoolMinIdle());
        // A blocked event tick is worse than a failed one: never let a borrow hang the caller.
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(java.time.Duration.ofMillis(config.getRedisTimeoutMs()));

        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.getRedisTimeoutMs())
                .socketTimeoutMillis(config.getRedisTimeoutMs())
                .database(config.getRedisDatabase())
                .ssl(config.isRedisSsl());

        String password = config.getRedisPassword();
        if (password != null && !password.isEmpty()) {
            clientConfig.password(password);
        }

        JedisClientConfig built = clientConfig.build();
        HostAndPort address = new HostAndPort(config.getRedisHost(), config.getRedisPort());

        this.pool = new JedisPool(poolConfig, address, built);

        if (ping()) {
            plugin.log("Redis connected ({}:{}, namespace '{}')",
                    config.getRedisHost(), config.getRedisPort(), config.getRedisNamespace());
        } else {
            plugin.log("Redis unreachable at {}:{} - cross-server events are offline until it returns.",
                    config.getRedisHost(), config.getRedisPort());
        }

        if (!subscriptions.isEmpty()) {
            startSubscriber();
        }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        connected = false;

        JedisPubSub pubSub = this.activePubSub;
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (RuntimeException ignored) {
                // Already torn down, or the connection died first - nothing useful to do.
            }
        }

        Thread thread = this.subscriberThread;
        if (thread != null) {
            thread.interrupt();
        }

        JedisPool current = this.pool;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ignored) {
                // Best-effort on shutdown.
            }
            this.pool = null;
        }
    }

    @Override
    public boolean isAvailable() {
        return running.get() && connected && pool != null;
    }

    @Override
    public boolean publish(String channel, String payload) {
        return withRedis(jedis -> {
            jedis.publish(channel, payload);
            return true;
        }, false);
    }

    @Override
    public boolean setWithTtl(String key, String value, int ttlSeconds) {
        return withRedis(jedis -> {
            jedis.setex(key, ttlSeconds, value);
            return true;
        }, false);
    }

    @Override
    public boolean setIfAbsent(String key, String value, int ttlSeconds) {
        return withRedis(jedis -> "OK".equals(jedis.set(key, value, SetParams.setParams().nx().ex(ttlSeconds))), false);
    }

    @Override
    public Optional<String> get(String key) {
        return withRedis(jedis -> Optional.ofNullable(jedis.get(key)), Optional.empty());
    }

    @Override
    public List<String> getAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }

        String[] array = keys.toArray(new String[0]);
        return withRedis(jedis -> jedis.mget(array), List.of());
    }

    @Override
    public boolean delete(String key) {
        return withRedis(jedis -> jedis.del(key) > 0, false);
    }

    @Override
    public boolean deleteIfEquals(String key, String expectedValue) {
        // Read-then-delete would race with expiry; the compare has to happen inside Redis.
        return withRedis(jedis -> {
            Object result = jedis.eval(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
                    List.of(key), List.of(expectedValue));
            return result instanceof Long deleted && deleted > 0;
        }, false);
    }

    @Override
    public boolean hset(String key, Map<String, String> values, int ttlSeconds) {
        if (values.isEmpty()) {
            return false;
        }

        return withRedis(jedis -> {
            jedis.hset(key, values);
            jedis.expire(key, ttlSeconds);
            return true;
        }, false);
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        return withRedis(jedis -> jedis.hgetAll(key), Map.of());
    }

    @Override
    public Set<String> scan(String matchPattern, int limit) {
        return withRedis(jedis -> {
            Set<String> found = new HashSet<>();
            ScanParams params = new ScanParams().match(matchPattern).count(Math.min(limit, 100));
            String cursor = ScanParams.SCAN_POINTER_START;

            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                found.addAll(result.getResult());
                cursor = result.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor) && found.size() < limit);

            return found;
        }, Set.of());
    }

    private boolean ping() {
        Boolean result = withRedis(jedis -> {
            jedis.ping();
            return true;
        }, false, true);
        return Boolean.TRUE.equals(result);
    }

    private <T> T withRedis(Function<Jedis, T> operation, T fallback) {
        return withRedis(operation, fallback, false);
    }

    /**
     * Runs {@code operation} against a pooled connection, translating any failure into
     * {@code fallback}.
     *
     * @param probing when {@code true} this call is establishing connectivity rather than relying on
     *                it, so the {@link #connected} short-circuit is skipped
     */
    private <T> T withRedis(Function<Jedis, T> operation, T fallback, boolean probing) {
        JedisPool current = this.pool;
        if (current == null || !running.get() || (!probing && !connected)) {
            return fallback;
        }

        try (Jedis jedis = current.getResource()) {
            T result = operation.apply(jedis);
            markConnected();
            return result;
        } catch (RuntimeException exception) {
            markDisconnected(exception);
            return fallback;
        }
    }

    private void markConnected() {
        if (!connected) {
            connected = true;
            if (outageLogged) {
                plugin.log("Redis connection restored.");
                outageLogged = false;
            }
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

    /**
     * Runs the blocking {@code SUBSCRIBE} on its own daemon thread, reconnecting with a capped
     * backoff for as long as the client is running.
     */
    private void startSubscriber() {
        List<String> channels = new ArrayList<>(subscriptions.keySet());

        Thread thread = new Thread(() -> {
            long backoff = RECONNECT_MIN_MILLIS;

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                JedisPool current = this.pool;
                if (current == null) {
                    break;
                }

                try (Jedis jedis = current.getResource()) {
                    JedisPubSub pubSub = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            dispatch(channel, message);
                        }

                        @Override
                        public void onSubscribe(String channel, int subscribedChannels) {
                            markConnected();
                        }
                    };

                    this.activePubSub = pubSub;
                    backoff = RECONNECT_MIN_MILLIS;

                    // Blocks until unsubscribed or the connection drops.
                    jedis.subscribe(pubSub, channels.toArray(new String[0]));
                } catch (RuntimeException exception) {
                    if (!running.get()) {
                        break;
                    }
                    markDisconnected(exception);
                } finally {
                    this.activePubSub = null;
                }

                if (!running.get()) {
                    break;
                }

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, RECONNECT_MAX_MILLIS);
            }
        }, "MythicBedwars-Redis-Subscriber");

        thread.setDaemon(true);
        this.subscriberThread = thread;
        thread.start();
    }

    private void dispatch(String channel, String message) {
        Consumer<String> handler = subscriptions.get(channel);
        if (handler == null) {
            return;
        }

        try {
            handler.accept(message);
        } catch (RuntimeException exception) {
            // One malformed message must not kill the subscriber thread and with it every future one.
            plugin.log("Failed to handle Redis message on {}: {}", channel, String.valueOf(exception.getMessage()));
        }
    }
}
