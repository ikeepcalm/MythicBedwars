package dev.ua.ikeepcalm.bedwars.net.minigame;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.protocol.payload.Payloads;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gets players back to the survival server once their event is over.
 *
 * <p>The ordering in {@link #returnNow} is load-bearing: the outcome is written to Redis
 * <em>before</em> anything is published or anybody is moved. A transfer can fail, a message can be
 * dropped, a player can pull the plug mid-flight — but the durable record is already there, so the
 * SMP still knows what happened to them whenever they next log in.
 *
 * <p>"Recorded" and "transferred" are tracked separately, per event. Conflating them is a trap: a
 * player eliminated in the final seconds of a match is recorded by {@code RoundEndEvent} while their
 * own return offer is still counting down, and a single set would make that recording cancel the
 * transfer — stranding them on the Bedwars server with a button that silently does nothing.
 */
public class EventReturnService {

    /** How long a [RETURN HOME] prompt stays clickable. */
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(10);

    private final MythicBedwars plugin;
    private final NetworkService network;

    /**
     * Whose outcome has been written, per event. Guards against publishing twice.
     */
    private final Map<String, Set<UUID>> recorded = new ConcurrentHashMap<>();

    /**
     * Who has actually been sent (or is scheduled), per event. Guards against moving twice.
     */
    private final Map<String, Set<UUID>> transferred = new ConcurrentHashMap<>();

    public EventReturnService(MythicBedwars plugin, NetworkService network) {
        this.plugin = plugin;
        this.network = network;
    }

    private static boolean mark(Map<String, Set<UUID>> tracker, String eventId, UUID playerId) {
        return tracker.computeIfAbsent(eventId, key -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    /**
     * Offers the player a way home and sends them anyway once {@code autoAfterSeconds} elapses.
     *
     * <p>Both routes exist because a click callback dies with the connection that rendered it, and
     * because somebody who has stopped paying attention should not be stranded on a server they did
     * not mean to stay on.
     */
    public void offerReturn(Player player, EventReservation reservation, ReturnOutcome outcome, int autoAfterSeconds) {
        UUID playerId = player.getUniqueId();
        String eventId = reservation.eventId();

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

    /**
     * Records the outcome, tells the SMP, then moves the player — in that order.
     */
    public void returnNow(Player player, EventReservation reservation, ReturnOutcome outcome) {
        UUID playerId = player.getUniqueId();
        String eventId = reservation.eventId();

        if (!mark(transferred, eventId, playerId)) {
            return;
        }

        recordAndPublish(playerId, reservation, outcome);

        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.return.transferring",
                "server", plugin.getConfigManager().getSmpVelocityServer()));
        plugin.getTransferService().transfer(player, plugin.getConfigManager().getSmpVelocityServer());
    }

    /**
     * Sends a group home, staggered so the SMP does not take the whole roster in one tick.
     */
    public void returnAll(Collection<UUID> playerIds, EventReservation reservation, ReturnOutcome outcome) {
        String eventId = reservation.eventId();
        List<UUID> moving = new ArrayList<>();

        for (UUID playerId : playerIds) {
            if (!mark(transferred, eventId, playerId)) {
                continue;
            }
            recordAndPublish(playerId, reservation, outcome);
            moving.add(playerId);
        }

        if (moving.isEmpty()) {
            return;
        }

        plugin.getTransferService().transferStaggered(moving,
                plugin.getConfigManager().getSmpVelocityServer(),
                missed -> plugin.log("Could not send {} home; their outcome is stored for next login.", missed));
    }

    /**
     * Records an outcome for somebody who is not here to be moved — a quitter, or a player who
     * never showed up. They will be greeted correctly whenever they next appear on the SMP.
     *
     * <p>Deliberately does not touch the transferred set, so a player who is still mid-return keeps
     * their ride home.
     */
    public void recordOnly(UUID playerId, EventReservation reservation, ReturnOutcome outcome) {
        recordAndPublish(playerId, reservation, outcome);
    }

    private void recordAndPublish(UUID playerId, EventReservation reservation, ReturnOutcome outcome) {
        String eventId = reservation.eventId();
        if (!mark(recorded, eventId, playerId)) {
            return;
        }

        String key = network.keys().eventPendingReturn(eventId);
        int ttl = plugin.getConfigManager().getEventTtlSeconds();
        Map<String, String> field = Map.of(playerId.toString(), outcome.name());

        String pointer = network.keys().playerReturn(playerId);
        String pointerValue = eventId + ":" + outcome.name();

        // Off the main thread: this is called once per player from inside RoundEndEvent, and a
        // sixteen-player match with quitters would otherwise be dozens of round trips in one tick.
        plugin.offMainThread(() -> {
            network.client().hset(key, field, ttl);
            // The per-event hash cannot be found from a player alone, and the survival server may
            // have restarted since. This is what makes the outcome discoverable at their next login.
            network.client().setWithTtl(pointer, pointerValue, ttl);
        });

        network.bus().send(NetworkRole.SMP, MessageType.PLAYER_RETURN, eventId,
                reservation.smpServerId(),
                new Payloads.PlayerReturn(playerId.toString(), outcome));
    }

    /**
     * Forgets an event's bookkeeping, so a later event on the same arena starts clean.
     *
     * <p>Keyed on the event rather than on a roster: a local player who took a spare slot is in
     * neither roster, and would otherwise stay in these sets for the life of the process — silently
     * losing their outcome if they ever joined a later event.
     */
    public void clear(String eventId) {
        recorded.remove(eventId);
        transferred.remove(eventId);
    }

    private boolean hasTransferred(String eventId, UUID playerId) {
        Set<UUID> set = transferred.get(eventId);
        return set != null && set.contains(playerId);
    }
}
