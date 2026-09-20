package dev.ua.ikeepcalm.bedwars.domain.core;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import org.bukkit.World;

/**
 * Switches off a Bedwars world's environmental gates for the duration of a match.
 *
 * <p>Arenas run frozen at noon on a fully lit map. That is the right call for Bedwars and the
 * wrong world for half the Beyonder roster: every Darkness, Demoness, Hanged and Abyss ability
 * that requires shadow simply never fires there, and Nocturnality actively applies slowness and
 * weakness for standing in light the arena gives players no way to leave. A team that drew one of
 * those pathways drew a pathway that does not work, which is not a balance problem the win-rate
 * balancer can see or fix.
 *
 * <p>Circle of Imagination answers this with a per-world neutral flag, and neutral means neutral:
 * shadow gates and sunlight gates both pass, so this does not privilege the dark pathways over the
 * bright ones — it stops the map deciding the matchup.
 *
 * <p>Bound directly against the COI API. The runtime guard is still here, in the shape of a
 * {@link LinkageError} catch: this jar is routinely dropped onto a server whose Circle of
 * Imagination is older than the API it was compiled against, and the first call would then throw
 * {@code NoSuchMethodError} out of an arena status change. Losing the feature is the right cost
 * there; losing the round is not.
 */
public class ArenaEnvironmentService {

    private final MythicBedwars plugin;
    private final CircleOfImaginationAPI api;

    /**
     * Cleared the first time the loaded COI turns out not to have the method, so a server running
     * an older jar logs once and then stops trying on every arena transition.
     */
    private volatile boolean supported = true;

    public ArenaEnvironmentService(MythicBedwars plugin) {
        this.plugin = plugin;
        this.api = plugin.getCircleOfImaginationAPI();
    }

    /**
     * @return whether the loaded COI has accepted a neutrality call so far
     */
    public boolean isSupported() {
        return supported && api != null;
    }

    /**
     * Neutralises the arena's world for the round.
     *
     * <p>Only called when magic is actually on: a no-magic round is ordinary Bedwars, and quietly
     * changing how COI reads that world for players who voted magic off would be a surprise.
     */
    public void apply(Arena arena) {
        setNeutral(arena, true);
    }

    /**
     * Restores the world's normal behaviour once the round is over.
     *
     * <p>Worth doing even though arena worlds are usually reset between matches: a flag that
     * suppresses drawbacks is one to clear eagerly, and the same world can be reused by an arena
     * whose next round has magic switched off.
     */
    public void clear(Arena arena) {
        setNeutral(arena, false);
    }

    private void setNeutral(Arena arena, boolean neutral) {
        if (!isSupported() || arena == null) {
            return;
        }

        World world = arena.getGameWorld();
        if (world == null) {
            return;
        }

        try {
            api.setEnvironmentNeutral(world, neutral);
            plugin.log("Environmental gates {} for arena {} (world {}).",
                    neutral ? "neutralised" : "restored", arena.getName(), world.getName());
        } catch (LinkageError error) {
            // An older Circle of Imagination than the API this was built against. Latch it off:
            // the alternative is the same error on every arena status change for the life of the
            // server, which buries the one log line that explains it.
            supported = false;
            plugin.getLogger().warning("The loaded Circle of Imagination has no environmental-gate API ("
                    + error.getMessage() + "). Shadow pathways will stay "
                    + "disadvantaged in always-day arenas until it is updated.");
        }
    }
}
