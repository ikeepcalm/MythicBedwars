package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Keeps a reserved arena's lobby countdown pushed out while recruits are still arriving.
 *
 * <p>MBedwars starts a match on its own once its minimum is met, which for an event would mean
 * kicking off before half the roster has crossed the proxy. Rather than fight that with arena
 * config — which persists — this simply keeps resetting the countdown until the orchestrator is
 * ready, then cancels itself and hands control back.
 */
public class LobbyHoldTask extends BukkitRunnable {

    private final String arenaName;
    private final int holdSeconds;

    public LobbyHoldTask(String arenaName, int holdSeconds) {
        this.arenaName = arenaName;
        this.holdSeconds = holdSeconds;
    }

    @Override
    public void run() {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(arenaName);
        if (arena == null || arena.getStatus() != ArenaStatus.LOBBY) {
            cancel();
            return;
        }

        // Non-instant so the lobby scoreboard does not visibly jitter every second.
        if (arena.getLobbyTimeRemaining() < holdSeconds) {
            arena.setLobbyTimeRemaining(holdSeconds, false);
        }
    }
}
