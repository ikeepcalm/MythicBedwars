package dev.ua.ikeepcalm.bedwars.net.minigame;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One arena held for one event, and everything the host needs to remember about it.
 *
 * <p>Mutable and touched from both the main thread and Redis handlers, so the player sets are
 * concurrent.
 */
public class EventReservation {

    private final String eventId;
    private final String smpServerId;
    private final String smpServerName;

    /**
     * Not final: once signups close the real turnout is known, and that is the first moment a
     * right-sized arena can be chosen. See {@link #reassign}.
     */
    private volatile String arenaName;

    /** Restored when the reservation is released, so an event cannot permanently retune an arena. */
    private volatile int originalMinPlayers;

    private final Set<UUID> roster = ConcurrentHashMap.newKeySet();
    private final Set<UUID> arrived = ConcurrentHashMap.newKeySet();

    private volatile long signupDeadline;
    private volatile long transferDeadline;

    /** True once leftover slots are opened to players already on this server. */
    private volatile boolean fillOpen;

    /**
     * Guards against the countdown being triggered twice, e.g. last arrival racing the deadline.
     */
    private volatile boolean starting;

    public EventReservation(String eventId, String arenaName, String smpServerId, String smpServerName,
                            int originalMinPlayers, long signupDeadline) {
        this.eventId = eventId;
        this.arenaName = arenaName;
        this.smpServerId = smpServerId;
        this.smpServerName = smpServerName;
        this.originalMinPlayers = originalMinPlayers;
        this.signupDeadline = signupDeadline;
    }

    public String eventId() {
        return eventId;
    }

    public String arenaName() {
        return arenaName;
    }

    /**
     * Moves this reservation to a different arena, before anybody has arrived.
     *
     * @param originalMinPlayers the new arena's own setting, to be restored on release
     */
    public void reassign(String arenaName, int originalMinPlayers) {
        this.arenaName = arenaName;
        this.originalMinPlayers = originalMinPlayers;
    }

    public String smpServerId() {
        return smpServerId;
    }

    public String smpServerName() {
        return smpServerName;
    }

    public int originalMinPlayers() {
        return originalMinPlayers;
    }

    public Set<UUID> roster() {
        return roster;
    }

    public Set<UUID> arrived() {
        return arrived;
    }

    public long signupDeadline() {
        return signupDeadline;
    }

    public void signupDeadline(long value) {
        this.signupDeadline = value;
    }

    public long transferDeadline() {
        return transferDeadline;
    }

    public void transferDeadline(long value) {
        this.transferDeadline = value;
    }

    public boolean isFillOpen() {
        return fillOpen;
    }

    public void openFill() {
        this.fillOpen = true;
    }

    public boolean isStarting() {
        return starting;
    }

    public void markStarting() {
        this.starting = true;
    }
}
