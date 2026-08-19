package dev.ua.ikeepcalm.bedwars.config;

/**
 * Which half of the network this instance is running as.
 *
 * <p>The same jar is deployed on every backend; this decides which subsystems boot. Only
 * {@link #MINIGAME} touches MBedwars, so an {@link #SMP} instance must never construct or register
 * anything that reaches the Bedwars API.
 */
public enum NetworkRole {

    /**
     * The survival server players are recruited from. Runs the recruitment and reward-redemption
     * side only; MBedwars is not expected to be installed.
     */
    SMP,

    /**
     * The Bedwars server that hosts the matches. Runs every existing MythicBedwars feature.
     */
    MINIGAME;

    /**
     * Parses a configured role name, tolerating case and surrounding whitespace.
     *
     * @return {@code fallback} when {@code raw} is absent or unrecognised, so a typo degrades to the
     * safe default rather than preventing startup
     */
    public static NetworkRole fromId(String raw, NetworkRole fallback) {
        if (raw == null) {
            return fallback;
        }

        String trimmed = raw.trim();
        for (NetworkRole role : values()) {
            if (role.name().equalsIgnoreCase(trimmed)) {
                return role;
            }
        }

        return fallback;
    }
}
