package dev.ua.ikeepcalm.bedwars.net.minigame;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import dev.ua.ikeepcalm.bedwars.net.protocol.payload.Payloads;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gets players back to the survival server once their event is over.
 *
 * <p>The ordering in {@link #returnNow} is load-bearing: the outcome is written to Redis
 * <em>before</em> anything is published or anybody is moved. A transfer can fail, a message can be
 * dropped, a player can pull the plug mid-flight — but the durable record is already there, so the
 * SMP still knows what happened to them whenever they next log in.
 */
public class EventReturnService {

    /** How long a [RETURN HOME] prompt stays clickable. */
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(10);

    private final MythicBedwars plugin;
    private final NetworkService network;

    /** Players already sent (or scheduled), so a click and the auto-timer cannot double-fire. */
    private final Set<UUID> departing = ConcurrentHashMap.newKeySet();

    public EventReturnService(MythicBedwars plugin, NetworkService network) {
        this.plugin = plugin;
        this.network = network;
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

        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(CALLBACK_LIFETIME)
                .build();

        Component button = plugin.getLocaleManager().formatMessage("magic.event.return.button")
                .clickEvent(ClickEvent.callback(audience -> {
                    if (audience instanceof Player clicker) {
                        returnNow(clicker, reservation, outcome);
                    }
                }, options));

        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.return.offer"));
        player.sendMessage(button);
        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.return.auto",
                "seconds", autoAfterSeconds));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player still = Bukkit.getPlayer(playerId);
            if (still != null && still.isOnline() && !departing.contains(playerId)) {
                returnNow(still, reservation, outcome);
            }
        }, Math.max(1, autoAfterSeconds) * 20L);
    }

    /**
     * Records the outcome, tells the SMP, then moves the player — in that order.
     */
    public void returnNow(Player player, EventReservation reservation, ReturnOutcome outcome) {
        UUID playerId = player.getUniqueId();
        if (!departing.add(playerId)) {
            return;
        }

        recordAndPublish(playerId, reservation, outcome);

        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.return.transferring",
                "server", plugin.getConfigManager().getSmpVelocityServer()));
        plugin.getTransferService().transfer(player, plugin.getConfigManager().getSmpVelocityServer());
    }

    /**
     * Sends a group home, staggered so the SMP does not take the whole roster in one tick.
     */
    public void returnAll(Collection<UUID> playerIds, EventReservation reservation, ReturnOutcome outcome) {
        for (UUID playerId : playerIds) {
            if (!departing.add(playerId)) {
                continue;
            }
            recordAndPublish(playerId, reservation, outcome);
        }

        plugin.getTransferService().transferStaggered(playerIds,
                plugin.getConfigManager().getSmpVelocityServer(),
                missed -> plugin.log("Could not send {} home; their outcome is stored for next login.", missed));
    }

    /**
     * Records an outcome for somebody who is not here to be moved — a quitter, or a player who
     * never showed up. They will be greeted correctly whenever they next appear on the SMP.
     */
    public void recordOnly(UUID playerId, EventReservation reservation, ReturnOutcome outcome) {
        if (departing.add(playerId)) {
            recordAndPublish(playerId, reservation, outcome);
        }
    }

    private void recordAndPublish(UUID playerId, EventReservation reservation, ReturnOutcome outcome) {
        network.client().hset(network.keys().eventPendingReturn(reservation.eventId()),
                Map.of(playerId.toString(), outcome.name()),
                plugin.getConfigManager().getEventTtlSeconds());

        network.bus().send(NetworkRole.SMP, MessageType.PLAYER_RETURN, reservation.eventId(),
                reservation.smpServerId(),
                new Payloads.PlayerReturn(playerId.toString(), outcome));
    }

    /**
     * Forgets who has already left, so a later event on the same arena starts clean.
     */
    public void clear(Collection<UUID> playerIds) {
        departing.removeAll(playerIds);
    }
}
