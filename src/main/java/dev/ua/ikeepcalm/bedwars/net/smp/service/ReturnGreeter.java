package dev.ua.ikeepcalm.bedwars.net.smp.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import dev.ua.ikeepcalm.bedwars.net.service.NetworkService;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
public class ReturnGreeter {

    private static final int MAX_EXPECTED = 256;

    private final MythicBedwars plugin;
    private final NetworkService network;

    private final Map<UUID, ReturnOutcome> expected = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, ReturnOutcome> eldest) {
            return size() > MAX_EXPECTED;
        }
    });

    public void greetWhenReady(UUID playerId, String eventId, ReturnOutcome outcome) {
        if (outcome == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            expected.put(playerId, outcome);
            return;
        }

        deliver(player, outcome);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> clearDurable(playerId, eventId));
    }

    public void onJoin(Player player) {
        UUID playerId = player.getUniqueId();

        ReturnOutcome pending = expected.remove(playerId);
        if (pending != null) {
            deliver(player, pending);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> clearDurable(playerId, null));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<String> stored = network.getClient().get(network.getKeys().playerReturn(playerId));
            stored.ifPresent(raw -> redeemStored(player, raw));
        });
    }

    private void redeemStored(Player player, String raw) {
        int split = raw.lastIndexOf(':');
        String eventId = split > 0 ? raw.substring(0, split) : null;
        String outcomeName = split > 0 ? raw.substring(split + 1) : raw;

        clearDurable(player.getUniqueId(), eventId);

        ReturnOutcome outcome;
        try {
            outcome = ReturnOutcome.valueOf(outcomeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException writtenByNewerBuild) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                deliver(player, outcome);
            }
        });
    }

    private void deliver(Player player, ReturnOutcome outcome) {
        player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                "magic.event.welcome_back." + outcome.name().toLowerCase(Locale.ROOT)));
    }

    private void clearDurable(UUID playerId, String eventId) {
        network.getClient().delete(network.getKeys().playerReturn(playerId));
        if (eventId != null) {
            network.getClient().hdel(network.getKeys().eventPendingReturn(eventId), playerId.toString());
        }
    }
}
