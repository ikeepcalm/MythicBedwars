package dev.ua.ikeepcalm.bedwars.net.protocol.payload;

import dev.ua.ikeepcalm.bedwars.net.protocol.CancelReason;

/**
 * The typed bodies carried inside {@link dev.ua.ikeepcalm.bedwars.net.protocol.Envelope#data()}.
 *
 * <p>Grouped in one file because each is a handful of fields and they only make sense as a set —
 * the protocol reads better in one place than spread across a dozen one-record files.
 *
 * <p>All fields are plain JSON-friendly types: these cross a version boundary, so nothing here may
 * depend on Bukkit or on class identity.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * SMP → minigame: a request for somebody to host.
     *
     * @param smpServerId   who to answer
     * @param smpServerName the proposer's Velocity name, so the host knows where to send players back
     * @param minPlayers    below this the match is not worth running
     * @param maxPlayers    the roster cap the SMP intends to advertise
     * @param onlinePlayers how many are on the SMP right now, as a hint at likely turnout
     */
    public record Propose(
            String smpServerId,
            String smpServerName,
            int minPlayers,
            int maxPlayers,
            int onlinePlayers
    ) {
    }

    /**
     * Minigame → SMP: claimed, with an arena reserved and held.
     *
     * @param minigameServerId   who is hosting
     * @param minigameServerName the host's Velocity name, i.e. where to transfer recruits
     * @param arenaName          the reserved arena
     * @param arenaCapacity      the arena's real capacity, which overrides the proposed cap when lower
     * @param signupDeadline     epoch millis after which signups must close
     */
    public record Accept(
            String minigameServerId,
            String minigameServerName,
            String arenaName,
            int arenaCapacity,
            long signupDeadline
    ) {
    }

    /**
     * Minigame → SMP: cannot host, and why.
     */
    public record Reject(
            String minigameServerId,
            String reason
    ) {
    }

    /**
     * Either side: the event is over before it produced a match.
     */
    public record Cancelled(
            CancelReason reason,
            String byServerId
    ) {
    }
}
