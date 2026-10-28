package dev.ua.ikeepcalm.bedwars.domain.arena.service;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.RequiredArgsConstructor;
import org.bukkit.World;

@RequiredArgsConstructor
public class ArenaEnvironmentService {

    private final MythicBedwars plugin;
    private volatile boolean supported = true;

    public void apply(Arena arena) {
        setNeutral(arena, true);
    }

    public void clear(Arena arena) {
        setNeutral(arena, false);
    }

    private void setNeutral(Arena arena, boolean neutral) {
        World world = arena.getGameWorld();
        if (!supported || world == null) {
            return;
        }

        try {
            plugin.getCircleOfImaginationAPI().setEnvironmentNeutral(world, neutral);
        } catch (LinkageError error) {
            supported = false;
            plugin.getLogger().warning("The loaded Circle of Imagination has no environmental-gate API ("
                                       + error.getMessage() + "). Shadow pathways will stay "
                                       + "disadvantaged in always-day arenas until it is updated.");
        }
    }
}
