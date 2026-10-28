package dev.ua.ikeepcalm.bedwars.net.minigame.task;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import lombok.RequiredArgsConstructor;
import org.bukkit.scheduler.BukkitRunnable;

@RequiredArgsConstructor
public class LobbyHoldTask extends BukkitRunnable {

    private final String arenaName;
    private final int holdSeconds;

    @Override
    public void run() {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(arenaName);
        if (arena == null || arena.getStatus() != ArenaStatus.LOBBY) {
            cancel();
            return;
        }

        if (arena.getLobbyTimeRemaining() < holdSeconds) {
            arena.setLobbyTimeRemaining(holdSeconds, false);
        }
    }
}
