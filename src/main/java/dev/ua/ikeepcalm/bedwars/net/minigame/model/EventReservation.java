package dev.ua.ikeepcalm.bedwars.net.minigame.model;

import dev.ua.ikeepcalm.bedwars.domain.voting.model.source.MagicMode;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class EventReservation {

    private final String eventId;
    private final String smpServerId;
    private final MagicMode magicMode;
    private final Set<UUID> roster = ConcurrentHashMap.newKeySet();
    private final Set<UUID> arrived = ConcurrentHashMap.newKeySet();

    private volatile String arenaName;
    private volatile int originalMinPlayers;

    @Setter
    private volatile long transferDeadline;
    @Setter
    private volatile boolean fillOpen;
    @Setter
    private volatile boolean starting;

    public EventReservation(String eventId, String smpServerId, MagicMode magicMode, String arenaName, int originalMinPlayers) {
        this.eventId = eventId;
        this.smpServerId = smpServerId;
        this.magicMode = magicMode;
        this.arenaName = arenaName;
        this.originalMinPlayers = originalMinPlayers;
    }

    public void reassign(String arenaName, int originalMinPlayers) {
        this.arenaName = arenaName;
        this.originalMinPlayers = originalMinPlayers;
    }
}
