package dev.ua.ikeepcalm.bedwars.net.minigame.listener;

import de.marcely.bedwars.api.arena.AddPlayerCause;
import de.marcely.bedwars.api.arena.AddPlayerIssue;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.net.minigame.model.EventReservation;
import dev.ua.ikeepcalm.bedwars.net.minigame.service.EventOrchestrator;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

@RequiredArgsConstructor
public class EventArenaGuard implements Listener {

    private static final String ISSUE_ID = "mythicbedwars:event_locked";

    private final MythicBedwars plugin;
    private final EventOrchestrator orchestrator;

    private AddPlayerIssue lockedIssue;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoinArena(PlayerJoinArenaEvent event) {
        EventReservation reservation = orchestrator.getReservation(event.getArena().getName());
        if (reservation == null) {
            return;
        }

        if (event.getCause() == AddPlayerCause.PLUGIN && orchestrator.isForcedJoin(event.getPlayer().getUniqueId())) {
            return;
        }

        if (reservation.getRoster().contains(event.getPlayer().getUniqueId()) || reservation.isFillOpen()) {
            return;
        }

        event.addIssue(lockedIssue());
    }

    private AddPlayerIssue lockedIssue() {
        if (lockedIssue == null) {
            lockedIssue = AddPlayerIssue.construct(ISSUE_ID,
                    plugin.getLocaleManager().getMessage("magic.event.arena_locked"));
        }
        return lockedIssue;
    }
}
