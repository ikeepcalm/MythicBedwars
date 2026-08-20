package dev.ua.ikeepcalm.bedwars.cmd;

import java.util.List;
import java.util.Locale;

/**
 * The names of every {@code /mb} subcommand, and which permission each needs.
 *
 * <p>Deliberately free of any MBedwars reference. The router has to be able to ask "is this one of
 * the Bedwars subcommands?" on a survival server, where MBedwars is not installed — and asking that
 * question of the class that <i>implements</i> them would force it to load, dragging
 * {@code de.marcely.bedwars} types in with it. That happens to survive today only because of how
 * lazily HotSpot resolves the constant pool; one MBedwars-typed field or static initialiser away, it
 * becomes a {@code NoClassDefFoundError} on a mistyped command.
 */
public final class Subcommands {

    /** Available in both roles. */
    public static final List<String> SHARED = List.of("toggle", "reload", "event");

    /** Needs MBedwars, so minigame-role only. */
    public static final List<String> MINIGAME = List.of("stats", "arena", "balance", "pathways", "voting");

    public static final String ADMIN_PERMISSION = "mythicbedwars.admin";
    public static final String JOIN_PERMISSION = "mythicbedwars.event.join";

    private Subcommands() {
    }

    /**
     * @return whether {@code subcommand} belongs to the MBedwars-backed group, regardless of whether
     * the current role can actually serve it
     */
    public static boolean isMinigameOnly(String subcommand) {
        return subcommand != null && MINIGAME.contains(subcommand.toLowerCase(Locale.ROOT));
    }
}
