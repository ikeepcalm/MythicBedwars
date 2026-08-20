package dev.ua.ikeepcalm.bedwars.net.transport;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Hard ceiling on SCAN cursor iterations; this Redis is shared with other plugins.
     */
    private static final int MAX_SCAN_ITERATIONS = 16;

    private final MythicBedwars plugin;
    private final Map<String, Consumer<String>> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile JedisPool pool;
    private volatile boolean connected;
    private volatile Thread subscriberThread;
    private volatile JedisPubSub activePubSub;

    /**
     * The subscriber's own connection, kept outside the pool. {@code SUBSCRIBE} blocks for the life
     * of the subscription, so borrowing a pooled connection for it would permanently consume one of
     * {@code max-total} - and with a small pool that starves every ordinary command.
     */
    private volatile Jedis subscriberConnection;

    private volatile HostAndPort address;
    private volatile JedisClientConfig clientConfig;

    /**
     * Throttles the "Redis is down" log to once per outage rather than once per operation.
     */
    private volatile boolean outageLogged;

    /**
     * Throttles the "Redis rejected a command" log, which would otherwise fire per call.
     */
    private volatile boolean commandErrorLogged;

    public JedisRedisClient(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * @return whether an error reply means the server cannot serve us at all, as opposed to having
     * refused one malformed request
     */
    private static boolean isFatalReply(String message) {
        if (message == null) {
            return false;
        }

        String upper = message.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("LOADING")
                || upper.startsWith("MISCONF")
                || upper.startsWith("OOM")
                || upper.startsWith("READONLY")
                || upper.startsWith("CLUSTERDOWN")
                || upper.startsWith("NOAUTH")
                || upper.startsWith("NOPERM")
                || upper.startsWith("MASTERDOWN");
    }

    @Override
    public void subscribe(String channel, Consumer<String> handler) {
        if (running.get()) {
            // The subscriber thread snapshots the channel list when it starts and then blocks, so a
            // late registration would silently never receive anything.
            plugin.log("Ignoring subscription to '{}' registered after the client started.", channel);
            return;
        }

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
        // A brief wait rides out a burst without turning it into a failure, but it is capped well
        // below the socket timeout: a blocked main-thread tick is worse than a dropped message.
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(java.time.Duration.ofMillis(Math.min(250, config.getRedisTimeoutMs())));

        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.getRedisTimeoutMs())
                .socketTimeoutMillis(config.getRedisTimeoutMs())
                .database(config.getRedisDatabase())
                .ssl(config.isRedisSsl());

        String password = config.getRedisPassword();
        if (password != null && !password.isEmpty()) {
            clientConfig.password(password);
        }

        this.clientConfig = clientConfig.build();
        this.address = new HostAndPort(config.getRedisHost(), config.getRedisPort());
        this.pool = new JedisPool(poolConfig, this.address, this.clientConfig);

        String host = config.getRedisHost();
        int port = config.getRedisPort();
        String namespace = config.getRedisNamespace();

        // Off the main thread: an unreachable-but-not-refusing host (dropped packets rather than a
        // closed port) would otherwise stall onEnable for the connect plus socket timeout.
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (ping()) {
                plugin.log("Redis connected ({}:{}, namespace '{}')", host, port, namespace);
            } else {
                plugin.log("Redis unreachable at {}:{} - cross-server events are offline until it returns.",
                        host, port);
            }
        });

        if (!subscriptions.isEmpty()) {
            startSubscriber();
        }
    }

    /**
     * Re-tests a connection that previously failed.
     *
     * <p>This is the only way out of the disconnected state for the command path: {@link #withRedis}
     * short-circuits while {@code connected} is false, so without an explicit probe a single slow
     * command would pause cross-server events until the next restart. Driven by the heartbeat, off
     * the main thread.
     */
    @Override
    public void probeIfDisconnected() {
        if (!running.get() || connected || pool == null) {
            return;
        }

        ping();
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

        // interrupt() does not break a thread parked in a blocking socket read, so close the
        // subscriber's own connection underneath it and let the read fail.
        Jedis subscriber = this.subscriberConnection;
        if (subscriber != null) {
            try {
                subscriber.close();
            } catch (RuntimeException ignored) {
                // Best-effort on shutdown.
            }
        }

        Thread thread = this.subscriberThread;
        if (thread != null) {
            thread.interrupt();
            try {
                // Bounded: a lingering daemon thread is survivable, a hung shutdown is not.
                thread.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
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
    public Map<String, String> hgetAll(String key) {
        return withRedis(jedis -> jedis.hgetAll(key), Map.of());
    }

    @Override
    public boolean hset(String key, Map<String, String> values, int ttlSeconds) {
        if (values.isEmpty()) {
            return false;
        }

        return withRedis(jedis -> {
            // One script rather than HSET-then-EXPIRE: a connection lost between the two commands
            // would leave the hash with no TTL at all, and nothing else ever cleans it up.
            List<String> args = new java.util.ArrayList<>(values.size() * 2 + 1);
            args.add(Integer.toString(ttlSeconds));
            values.forEach((field, value) -> {
                args.add(field);
                args.add(value);
            });

            jedis.eval(LuaScripts.HSET_WITH_TTL, List.of(key), args);
            return true;
        }, false);
    }

    @Override
    public Optional<String> lpop(String key) {
        return withRedis(jedis -> Optional.ofNullable(jedis.lpop(key)), Optional.empty());
    }

    @Override
    public boolean hdel(String key, String field) {
        return withRedis(jedis -> jedis.hdel(key, field) > 0, false);
    }

    @Override
    public boolean rpushCapped(String key, String value, int maxLength) {
        return withRedis(jedis -> {
            jedis.rpush(key, value);
            jedis.ltrim(key, -maxLength, -1);
            return true;
        }, false);
    }

    @Override
    public Set<String> smembers(String key) {
        return withRedis(jedis -> jedis.smembers(key), Set.of());
    }

    @Override
    public boolean lpush(String key, String value, int ttlSeconds) {
        return withRedis(jedis -> {
            // One script, for the same reason as hset: a connection lost between the push and the
            // expire would leave a reward queue that never ages out.
            jedis.eval(LuaScripts.LPUSH_WITH_TTL, List.of(key),
                    List.of(value, Integer.toString(ttlSeconds)));
            return true;
        }, false);
    }

    @Override
    public boolean sadd(String key, String member, int ttlSeconds) {
        return withRedis(jedis -> {
            jedis.eval(LuaScripts.SADD_WITH_TTL, List.of(key),
                    List.of(member, Integer.toString(ttlSeconds)));
            return true;
        }, false);
    }

    @Override
    public boolean srem(String key, String member) {
        return withRedis(jedis -> jedis.srem(key, member) > 0, false);
    }

    @Override
    public long evalLong(String script, List<String> keys, List<String> args, long fallback) {
        return withRedis(jedis -> {
            Object reply = jedis.eval(script, keys, args);
            return reply instanceof Long value ? value : fallback;
        }, fallback);
    }

    @Override
    public boolean expire(String key, int ttlSeconds) {
        return withRedis(jedis -> jedis.expire(key, ttlSeconds) > 0, false);
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
     * Bounded prefix scan.
     *
     * <p>Hard-capped in cursor iterations as well as results. This Redis is shared with other
     * plugins, so a match pattern that happens to be selective would otherwise walk the entire
     * keyspace — thousands of round trips, holding a pooled connection, under a socket timeout whose
     * expiry gets read as an outage.
     */
    @Override
    public Set<String> scan(String matchPattern, int limit) {
        return withRedis(jedis -> {
            Set<String> found = new HashSet<>();
            ScanParams params = new ScanParams().match(matchPattern).count(512);
            String cursor = ScanParams.SCAN_POINTER_START;
            int iterations = 0;

            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                found.addAll(result.getResult());
                cursor = result.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor)
                    && found.size() < limit
                    && ++iterations < MAX_SCAN_ITERATIONS);

            return found;
        }, Set.of());
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
        } catch (redis.clients.jedis.exceptions.JedisDataException exception) {
            // A Lua error or a WRONGTYPE is a fault in the request we just sent; pausing the whole
            // feature over one would be disproportionate. But Redis also reports genuinely unusable
            // states through the same exception, and treating those as "fine" would leave
            // isAvailable() claiming the feature works while every write silently fails.
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

    private void markConnected() {
        if (!connected) {
            connected = true;
            commandErrorLogged = false;
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
                HostAndPort target = this.address;
                JedisClientConfig settings = this.clientConfig;
                if (target == null || settings == null) {
                    break;
                }

                boolean subscribed = false;

                // Deliberately not pooled: this connection blocks for the life of the subscription.
                try (Jedis jedis = new Jedis(target, settings)) {
                    this.subscriberConnection = jedis;

                    boolean[] established = {false};
                    JedisPubSub pubSub = new JedisPubSub() {
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

                    this.activePubSub = pubSub;

                    // Blocks until unsubscribed or the connection drops.
                    jedis.subscribe(pubSub, channels.toArray(new String[0]));
                    subscribed = established[0];
                } catch (RuntimeException exception) {
                    if (!running.get()) {
                        break;
                    }
                    markDisconnected(exception);
                } finally {
                    this.activePubSub = null;
                    this.subscriberConnection = null;
                }

                // Reset only once a subscription genuinely came up. Resetting before subscribe()
                // would make an instant failure (maxclients, NOAUTH) spin at 1 Hz forever.
                if (subscribed) {
                    backoff = RECONNECT_MIN_MILLIS;
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
