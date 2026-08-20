package dev.ua.ikeepcalm.bedwars.net.transport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.protocol.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import org.bukkit.Bukkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Typed message bus over the Redis pub/sub channels.
 *
 * <p>Three things happen here that every consumer would otherwise have to remember:
 * <ul>
 *   <li>messages not addressed to this instance are dropped;</li>
 *   <li>duplicates are dropped — Redis pub/sub redelivers on reconnect;</li>
 *   <li><b>handlers are dispatched on the main thread.</b> Messages arrive on the Jedis subscriber
 *       thread, and touching Bukkit from there is the single most common way this kind of feature
 *       corrupts server state. Enforcing it once here means no handler can get it wrong.</li>
 * </ul>
 */
public class RedisBus {

    /**
     * How many recently-seen message ids to remember. Comfortably more than a reconnect can
     * redeliver, and small enough to stay free.
     */
    private static final int DEDUP_CAPACITY = 512;

    private final MythicBedwars plugin;
    private final RedisClient client;
    private final RedisKeys keys;
    private final String serverId;
    private final Gson gson = new Gson();

    private final Map<MessageType, Consumer<Envelope>> handlers = new ConcurrentHashMap<>();

    /**
     * Bounded LRU of message ids. Insertion-ordered so the oldest entry falls off once full.
     */
    private final Map<String, Boolean> recentMessageIds = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > DEDUP_CAPACITY;
                }
            });

    public RedisBus(MythicBedwars plugin, RedisClient client, RedisKeys keys, String serverId) {
        this.plugin = plugin;
        this.client = client;
        this.keys = keys;
        this.serverId = serverId;
    }

    /**
     * Subscribes to the channel this role consumes. Must run before {@link RedisClient#start()}.
     */
    public void listenAs(NetworkRole role) {
        client.subscribe(keys.channel(role), this::receive);
    }

    public void on(MessageType type, Consumer<Envelope> handler) {
        handlers.put(type, handler);
    }

    /**
     * Publishes to every instance in {@code consumer}'s role.
     */
    public boolean broadcast(NetworkRole consumer, MessageType type, String eventId, Object payload) {
        return send(consumer, type, eventId, Envelope.BROADCAST, payload);
    }

    /**
     * Publishes to one named instance. Others on the channel receive and discard it.
     */
    public boolean send(NetworkRole consumer, MessageType type, String eventId, String targetServerId, Object payload) {
        Envelope envelope = new Envelope(
                Envelope.VERSION,
                type,
                UUID.randomUUID().toString(),
                eventId,
                serverId,
                targetServerId,
                System.currentTimeMillis(),
                payload == null ? new JsonObject() : gson.toJsonTree(payload).getAsJsonObject()
        );

        // Our own id goes in before publishing: some Redis setups echo a publish back to the
        // publisher, and re-handling our own message would double-apply the transition.
        recentMessageIds.put(envelope.msgId(), Boolean.TRUE);

        String channel = keys.channel(consumer);
        String wire = gson.toJson(envelope);

        // PUBLISH is a blocking round trip. Most callers are main-thread event handlers - and
        // publishFinished runs inside RoundEndEvent, once per player - so a degraded Redis would
        // stall the tick for the socket timeout on each one.
        if (Bukkit.isPrimaryThread()) {
            plugin.offMainThread(() -> client.publish(channel, wire));
            // Optimistic: the send has been handed off, not confirmed. The only caller that needs a
            // real answer (the propose path) is already off the main thread and takes the branch below.
            return true;
        }

        return client.publish(channel, wire);
    }

    /**
     * Deserialises {@code data} into the payload record a handler expects.
     *
     * @return {@code null} when the payload does not fit, which the handler should treat as a
     * malformed message rather than an empty one
     */
    public <T> T payload(Envelope envelope, Class<T> type) {
        try {
            return gson.fromJson(envelope.data(), type);
        } catch (JsonSyntaxException exception) {
            plugin.log("Discarding {} with unreadable payload: {}", envelope.type(), String.valueOf(exception.getMessage()));
            return null;
        }
    }

    /**
     * Runs on the Jedis subscriber thread.
     */
    private void receive(String raw) {
        try {
            dispatchIncoming(raw);
        } catch (RuntimeException exception) {
            // Anything escaping here would climb the subscriber thread. JsonSyntaxException is the
            // obvious case, but a `data` field that is a JSON primitive rather than an object throws
            // ClassCastException instead, and relying on the transport's outer catch to absorb that
            // makes the bus's own robustness somebody else's problem.
            plugin.log("Discarding unusable control message: {}", String.valueOf(exception.getMessage()));
        }
    }

    private void dispatchIncoming(String raw) {
        Envelope envelope;
        try {
            envelope = gson.fromJson(raw, Envelope.class);
        } catch (JsonSyntaxException exception) {
            plugin.log("Discarding unparseable control message: {}", String.valueOf(exception.getMessage()));
            return;
        }

        if (envelope == null || envelope.type() == null || envelope.msgId() == null) {
            return;
        }

        if (envelope.v() != Envelope.VERSION) {
            plugin.log("Ignoring control message from {} with protocol v{} (this server speaks v{}).",
                    envelope.from(), envelope.v(), Envelope.VERSION);
            return;
        }

        if (!envelope.isAddressedTo(serverId)) {
            return;
        }

        Consumer<Envelope> handler = handlers.get(envelope.type());
        if (handler == null) {
            // Checked BEFORE the dedup ring on purpose. Burning the id here would suppress the
            // pub/sub redelivery of this exact message too, so a message that arrived a moment
            // before its handler was registered would be lost rather than merely early.
            return;
        }

        if (recentMessageIds.put(envelope.msgId(), Boolean.TRUE) != null) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                handler.accept(envelope);
            } catch (RuntimeException exception) {
                plugin.log("Handler for {} failed: {}", envelope.type(), String.valueOf(exception.getMessage()));
            }
        });
    }
}
