package dev.ua.ikeepcalm.bedwars.net.minigame.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.minigame.model.EventReservation;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.PlayerReturnPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import dev.ua.ikeepcalm.bedwars.net.service.NetworkService;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class EventReturnService {

    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(10);

    private final MythicBedwars plugin;
    private final NetworkService network;

    private final Map<String, Set<UUID>> recorded = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> transferred = new ConcurrentHashMap<>();

    public void offerReturn(Player player, EventReservation reservation, ReturnOutcome outcome, int autoAfterSeconds) {
        UUID playerId = player.getUniqueId();
        String eventId = reservation.getEventId();

        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(CALLBACK_LIFETIME)
                .build();

        Component button = plugin.getLocaleManager().formatMessage(player, "magic.event.return.button")
                .clickEvent(ClickEvent.callback(audience -> {
                    if (audience instanceof Player clicker) {
                        returnNow(clicker, reservation, outcome);
                    }
                }, options));

        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.return.offer"));
        player.sendMessage(button);
        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.return.auto",
                "seconds", autoAfterSeconds));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player still = Bukkit.getPlayer(playerId);
            if (still != null && still.isOnline() && !hasTransferred(eventId, playerId)) {
                returnNow(still, reservation, outcome);
            }
        }, Math.max(1, autoAfterSeconds) * 20L);
    }

    public void returnNow(Player player, EventReservation reservation, ReturnOutcome outcome) {
        if (!mark(transferred, reservation.getEventId(), player.getUniqueId())) {
            return;
        }

        recordOnly(player.getUniqueId(), reservation, outcome);

        String server = plugin.getConfigManager().getSmpVelocityServer();
        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.return.transferring",
                "server", server));
        plugin.getTransferService().transfer(player, server);
    }

    public void returnAll(Collection<UUID> playerIds, EventReservation reservation, ReturnOutcome outcome) {
        List<UUID> moving = new ArrayList<>();
        for (UUID playerId : playerIds) {
            if (mark(transferred, reservation.getEventId(), playerId)) {
                recordOnly(playerId, reservation, outcome);
                moving.add(playerId);
            }
        }

        if (!moving.isEmpty()) {
            plugin.getTransferService().transferStaggered(moving, plugin.getConfigManager().getSmpVelocityServer(),
                    missed -> plugin.log("Could not send {} home; their outcome is stored for next login.", missed));
        }
    }

    public void recordOnly(UUID playerId, EventReservation reservation, ReturnOutcome outcome) {
        String eventId = reservation.getEventId();
        if (!mark(recorded, eventId, playerId)) {
            return;
        }

        String key = network.getKeys().eventPendingReturn(eventId);
        String pointer = network.getKeys().playerReturn(playerId);
        int ttl = plugin.getConfigManager().getEventTtlSeconds();

        plugin.offMainThread(() -> {
            network.getClient().hset(key, Map.of(playerId.toString(), outcome.name()), ttl);
            network.getClient().setWithTtl(pointer, eventId + ":" + outcome.name(), ttl);
        });

        network.getBus().send(NetworkRole.SMP, MessageType.PLAYER_RETURN, eventId, reservation.getSmpServerId(),
                new PlayerReturnPayload(playerId.toString(), outcome));
    }

    public void clear(String eventId) {
        recorded.remove(eventId);
        transferred.remove(eventId);
    }

    private boolean hasTransferred(String eventId, UUID playerId) {
        Set<UUID> moved = transferred.get(eventId);
        return moved != null && moved.contains(playerId);
    }

    private static boolean mark(Map<String, Set<UUID>> tracker, String eventId, UUID playerId) {
        return tracker.computeIfAbsent(eventId, key -> ConcurrentHashMap.newKeySet()).add(playerId);
    }
}
