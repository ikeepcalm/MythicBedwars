package dev.ua.ikeepcalm.bedwars.net;

import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;

import java.util.Set;

/**
 * The half of an event this server is responsible for, as far as reconciliation is concerned.
 *
 * <p>Exists because pub/sub is not replayable and cannot be used to tell a server about itself.
 * {@code RedisBus} registers each outgoing message id in its own dedup ring before publishing — so
 * that a Redis which echoes a publish back cannot double-apply a transition — which also means a
 * message a server sends to its <em>own</em> role's channel never comes back. Anything that has to
 * inform the local half therefore has to call it directly, through this.
 *
 * <p>Implemented by both roles so {@link EventSyncTask} and {@link EventReaperTask} can be
 * role-neutral.
 */
public interface EventParticipant {

    /**
     * @return the events this server currently believes are in flight. Empty when it is idle.
     */
    Set<String> localEventIds();

    /**
     * Gives up on an event locally, releasing whatever is being held for it.
     *
     * <p>Called on the main thread, because for the minigame role this releases an arena reservation
     * and sends players home.
     *
     * @param eventId the event to let go of; ignored if this server never had it
     * @param reason  why, for the player-facing message
     */
    void abandonLocally(String eventId, CancelReason reason);
}
