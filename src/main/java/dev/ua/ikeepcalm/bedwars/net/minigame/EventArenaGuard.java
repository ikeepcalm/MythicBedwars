package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.arena.AddPlayerCause;
import de.marcely.bedwars.api.arena.AddPlayerIssue;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Keeps a reserved arena for the people the SMP promised it to.
 *
 * <p>MBedwars has no private-arena concept and {@link PlayerJoinArenaEvent} is not cancellable, so
 * a join is refused by attaching an {@link AddPlayerIssue} — which also gives the player a reason
 * rather than a silent failure.
 */
public class EventArenaGuard implements Listener {

    private static final String ISSUE_ID = "mythicbedwars:event_locked";

    private final MythicBedwars plugin;
    private final EventOrchestrator orchestrator;

    /** Built lazily so the locale file is loaded by the time we read the hint out of it. */
    private AddPlayerIssue lockedIssue;

    public EventArenaGuard(MythicBedwars plugin, EventOrchestrator orchestrator) {
        this.plugin = plugin;
        this.orchestrator = orchestrator;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoinArena(PlayerJoinArenaEvent event) {
        EventReservation reservation = orchestrator.getReservation(event.getArena().getName());
        if (reservation == null) {
            return;
        }

        // Our own forced joins carry AddPlayerCause.PLUGIN and are pre-registered, so they pass
        // even though the roster check below would also let them through.
        if (event.getCause() == AddPlayerCause.PLUGIN
            && orchestrator.isForcedJoin(event.getPlayer().getUniqueId())) {
            return;
        }

        if (reservation.roster().contains(event.getPlayer().getUniqueId())) {
            return;
        }

        // Once the arrival window has closed, leftover slots are fair game for locals.
        if (reservation.isFillOpen()) {
            return;
        }

        event.addIssue(lockedIssue());
    }

    private AddPlayerIssue lockedIssue() {
        if (lockedIssue == null) {
            String hint = plugin.getLocaleManager().getMessage("magic.event.arena_locked");
            lockedIssue = AddPlayerIssue.construct(ISSUE_ID, hint);
        }
        return lockedIssue;
    }
}
