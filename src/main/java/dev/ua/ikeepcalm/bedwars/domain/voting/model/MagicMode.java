package dev.ua.ikeepcalm.bedwars.domain.voting.model;

import dev.ua.ikeepcalm.bedwars.util.LocaleBackfiller;

import java.util.Locale;

/**
 * What the lobby decided magic should look like this round.
 *
 * <p>Replaces the boolean the vote used to carry. The distinction that matters to almost every
 * caller is still "is magic on at all", which {@link #isMagicEnabled()} answers — only pathway
 * assignment and the round's statistics care which of the two enabled modes won.
 *
 * <p>Deliberately free of any MBedwars or COI reference: {@code ConfigLoader} returns one of these
 * and is read in both roles, so the enum has to be loadable on a server where neither plugin exists.
 */
public enum MagicMode {

    /**
     * No magic. Ordinary Bedwars.
     */
    OFF,

    /**
     * One pathway per team, shared by everyone on it. The original behaviour, and still the default:
     * it is what makes a team's identity legible to its opponents.
     */
    TEAM,

    /**
     * One pathway per player, drawn distinctly across the whole arena where the pool allows.
     *
     * <p>Asked for because pathways are not equally suited to Bedwars — a team that draws a
     * non-combat pathway is losing before the round starts. Spreading the draw over players rather
     * than teams means every team ends up holding a mix, so nobody's whole team is carried by one
     * unlucky roll.
     */
    INDIVIDUAL;

    /**
     * @return whether magic runs at all in this mode
     */
    public boolean isMagicEnabled() {
        return this != OFF;
    }

    /**
     * @return whether pathways are handed out per player rather than per team
     */
    public boolean isPerPlayer() {
        return this == INDIVIDUAL;
    }

    /**
     * @return the mode named by {@code id}, or {@code fallback} when it names none. Never throws:
     * a typo in {@code config.yml} must not be what stops an arena starting.
     */
    public static MagicMode fromId(String id, MagicMode fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }

        for (MagicMode mode : values()) {
            if (mode.name().equalsIgnoreCase(id.trim())) {
                return mode;
            }
        }

        return fallback;
    }

    /**
     * Bridges the old boolean form, which {@code network.event.force-magic} and
     * {@code /mb voting set} both still speak.
     */
    public static MagicMode ofBoolean(boolean magicEnabled, MagicMode enabledMode) {
        return magicEnabled ? enabledMode : OFF;
    }

    /**
     * The locale key suffix for this mode.
     *
     * <p>{@code OFF} deliberately reads {@code disabled}. In YAML 1.1 — which SnakeYAML, and so
     * Bukkit, implements — {@code off} is a boolean <i>even as a mapping key</i>, so an unquoted
     * {@code off:} parses as {@code false} and every lookup under it misses silently. Quoting it
     * works, but the lang files are rewritten by {@link
     * LocaleBackfiller} and hand-edited by operators, and a key whose
     * correctness depends on quotes surviving both is a trap waiting to be sprung. A word that is
     * not a boolean in any YAML version has none of that problem.
     */
    public String key() {
        return this == OFF ? "disabled" : name().toLowerCase(Locale.ROOT);
    }
}
