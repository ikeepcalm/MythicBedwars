package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Welcomes players back from an event and tells them how it went.
 *
 * <p>Two routes in, because neither alone is reliable. The {@code PLAYER_RETURN} message usually
 * arrives while the player is still crossing the proxy, so it is held until they appear; but pub/sub
 * has no replay, and a message lost to a reconnect would leave the player arriving to silence. The
 * durable pointer written alongside it covers that case, and also covers a survival server that was
 * restarted in between — which is the situation the in-memory route cannot help with at all.
 *
 * <p>Rewards are applied separately by {@code RewardRedeemer}; this is only the greeting. Keeping
 * them apart means a lost outcome costs a nice message, never a reward.
 */
public class ReturnGreeter {

    private final MythicBedwars plugin;
    private final NetworkService network;

    /**
     * Outcomes that arrived before their player did.
     *
     * <p>Bounded: a player who never comes back would otherwise sit here for the life of the process.
     * The durable Redis pointer is the real backstop, so dropping the oldest costs nothing.
     */
    private final Map<UUID, ReturnOutcome> expected =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, ReturnOutcome> eldest) {
                    return size() > 256;
                }
            });

    public ReturnGreeter(MythicBedwars plugin, NetworkService network) {
        this.plugin = plugin;
        this.network = network;
    }

    /**
     * Called when the Bedwars server reports somebody on their way.
     */
    public void greetWhenReady(UUID playerId, String eventId, ReturnOutcome outcome) {
        if (outcome == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            deliver(player, outcome);
            consumeDurable(playerId, eventId);
            return;
        }

        // They are still travelling. Their login will pick this up.
        expected.put(playerId, outcome);
    }

    /**
     * Called on join. Delivers whatever is waiting, from memory or from Redis.
     */
    public void onJoin(Player player) {
        UUID playerId = player.getUniqueId();

        ReturnOutcome pending = expected.remove(playerId);
        if (pending != null) {
            deliver(player, pending);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> clearDurable(playerId, null));
            return;
        }

        // Nothing in memory: either the message never arrived, or this server has restarted since.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<String> stored = network.client().get(network.keys().playerReturn(playerId));
            if (stored.isEmpty()) {
                return;
            }

            String raw = stored.get();
            int split = raw.lastIndexOf(':');
            String eventId = split > 0 ? raw.substring(0, split) : null;
            String outcomeName = split > 0 ? raw.substring(split + 1) : raw;

            ReturnOutcome outcome;
            try {
                outcome = ReturnOutcome.valueOf(outcomeName.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // Written by a newer build. Not worth guessing at, and not worth keeping.
                clearDurable(playerId, eventId);
                return;
            }

            clearDurable(playerId, eventId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    deliver(player, outcome);
                }
            });
        });
    }

    private void deliver(Player player, ReturnOutcome outcome) {
        player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                "magic.event.welcome_back." + outcome.name().toLowerCase(Locale.ROOT)));
    }

    private void consumeDurable(UUID playerId, String eventId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> clearDurable(playerId, eventId));
    }

    /**
     * Removes the delivered record. Does Redis I/O — call off the main thread.
     */
    private void clearDurable(UUID playerId, String eventId) {
        network.client().delete(network.keys().playerReturn(playerId));

        if (eventId != null) {
            network.client().hdel(network.keys().eventPendingReturn(eventId), playerId.toString());
        }
    }
}
