package dev.ua.ikeepcalm.bedwars.net.transport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class RedisBus {

    private static final int DEDUP_CAPACITY = 512;

    private final MythicBedwars plugin;
    private final RedisClient client;
    private final RedisKeys keys;
    private final String serverId;
    private final Gson gson = new Gson();
    private final Map<MessageType, Consumer<Envelope>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> recentMessageIds = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DEDUP_CAPACITY;
        }
    });

    public void listenAs(NetworkRole role) {
        client.subscribe(keys.channel(role), this::receive);
    }

    public void on(MessageType type, Consumer<Envelope> handler) {
        handlers.put(type, handler);
    }

    public boolean broadcast(NetworkRole consumer, MessageType type, String eventId, Object payload) {
        return send(consumer, type, eventId, Envelope.BROADCAST, payload);
    }

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

        recentMessageIds.put(envelope.getMsgId(), Boolean.TRUE);

        String channel = keys.channel(consumer);
        String wire = gson.toJson(envelope);
        if (Bukkit.isPrimaryThread()) {
            plugin.offMainThread(() -> client.publish(channel, wire));
            return true;
        }

        return client.publish(channel, wire);
    }

    public <T> T payload(Envelope envelope, Class<T> type) {
        try {
            return gson.fromJson(envelope.getData(), type);
        } catch (JsonSyntaxException exception) {
            plugin.log("Discarding {} with unreadable payload: {}", envelope.getType(), String.valueOf(exception.getMessage()));
            return null;
        }
    }

    private void receive(String raw) {
        try {
            dispatchIncoming(raw);
        } catch (RuntimeException exception) {
            plugin.log("Discarding unusable control message: {}", String.valueOf(exception.getMessage()));
        }
    }

    private void dispatchIncoming(String raw) {
        Envelope envelope = gson.fromJson(raw, Envelope.class);
        if (envelope == null || envelope.getType() == null || envelope.getMsgId() == null) {
            return;
        }

        if (envelope.getV() != Envelope.VERSION) {
            plugin.log("Ignoring control message from {} with protocol v{} (this server speaks v{}).",
                    envelope.getFrom(), envelope.getV(), Envelope.VERSION);
            return;
        }

        Consumer<Envelope> handler = handlers.get(envelope.getType());
        // The handler check must precede the dedup ring, or an early message would burn its own redelivery.
        if (!envelope.isAddressedTo(serverId) || handler == null
                || recentMessageIds.put(envelope.getMsgId(), Boolean.TRUE) != null) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                handler.accept(envelope);
            } catch (RuntimeException exception) {
                plugin.log("Handler for {} failed: {}", envelope.getType(), String.valueOf(exception.getMessage()));
            }
        });
    }
}
