package dev.ua.ikeepcalm.bedwars.net.protocol;

/**
 * Every message that crosses the Redis control bus.
 *
 * <p>The happy path runs top to bottom; {@link #EVENT_CANCELLED} may be published by either side at
 * any point and always terminates the event.
 */
public enum MessageType {

    /** SMP → minigame: "I have players, can you host?" */
    EVENT_PROPOSE,

    /** Minigame → SMP: "yes, on this arena, signups close at this deadline". */
    EVENT_ACCEPT,

    /** Minigame → SMP: "no", with a reason. */
    EVENT_REJECT,

    /** Either → both: a player signed up. Notification only; the Redis roster set is authoritative. */
    ROSTER_ADD,

    /** SMP → minigame: signups are closed, here is the final roster. */
    ROSTER_CLOSED,

    /** Minigame → SMP: the arena is reserved and held open, start transferring. */
    ARENA_READY,

    /** Minigame → SMP: a recruited player reached the arena. Throttled. */
    PLAYER_ARRIVED,

    /** Minigame → SMP: the match has begun. */
    EVENT_STARTED,

    /** Either → both: the event is over before it started, or was abandoned. */
    EVENT_CANCELLED,

    /** Minigame → SMP: final result, including quitters. Published before any transfer. */
    EVENT_FINISHED,

    /** Minigame → SMP: this player is being sent home with this outcome. */
    PLAYER_RETURN
}
