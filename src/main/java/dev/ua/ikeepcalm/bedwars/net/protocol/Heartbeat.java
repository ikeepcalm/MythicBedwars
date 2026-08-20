package dev.ua.ikeepcalm.bedwars.net.protocol;

import dev.ua.ikeepcalm.bedwars.config.NetworkRole;

/**
 * What each instance publishes about itself, so the other side knows it is alive and reachable.
 *
 * <p>Written to a short-TTL key rather than broadcast, so liveness is decided by the key simply
 * expiring — no timeout bookkeeping, and a crashed server disappears on its own.
 *
 * @param serverId       this instance's {@code network.server-id}
 * @param role           which half of the network it is running as
 * @param velocityServer its name in {@code velocity.toml}, i.e. where to send players to reach it
 * @param onlinePlayers  current player count
 * @param pluginVersion  so a version skew across the network is visible in {@code /mb event status}
 * @param ts             publish time in epoch millis, used to age out a stale-but-not-yet-expired key
 */
public record Heartbeat(
        String serverId,
        NetworkRole role,
        String velocityServer,
        int onlinePlayers,
        String pluginVersion,
        long ts
) {

    /**
     * @param staleAfterMillis how far behind {@code now} the stamp may be before this is ignored
     * @return whether this heartbeat should still be treated as evidence the server is up
     */
    public boolean isFresh(long now, long staleAfterMillis) {
        return now - ts < staleAfterMillis;
    }
}
