package dev.ua.ikeepcalm.bedwars.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes settings that a config file predating them is missing, and clears out the ones that no
 * longer mean anything.
 *
 * <p>The plugin does not strictly need this: {@code JavaPlugin#reloadConfig} backs the on-disk file
 * with the bundled one, so an absent key already resolves to its default. It exists because an
 * invisible default is not a setting anybody can tune — an admin looking for the event schedule in
 * their own {@code config.yml} should find it there, with its comments, rather than having to be
 * told it exists. Stale keys are removed for the same reason in reverse: a key that is still in the
 * file but no longer read is a lie about what the plugin will do.
 *
 * <p>Runs on every load, and writes the file only when something actually changed, so it is a no-op
 * from the second boot onwards.
 */
public class ConfigBackfiller {

    private ConfigBackfiller() {
    }

    /**
     * Keys the idle-driven auto-propose used. Nothing is carried over from them: they measured how
     * many players looked AFK, which the schedule deliberately does not care about.
     */
    private static final List<String> RETIRED = List.of(
            "network.event.auto-propose",
            "network.event.auto-propose-interval-seconds",
            "network.event.auto-propose-min-idle-players",
            "network.event.idle-threshold-seconds");

    /**
     * Brings {@code config.yml} up to date in place.
     *
     * @return a description of every change made, empty when the file was already current
     */
    public static List<String> apply(JavaPlugin plugin, FileConfiguration config) {
        List<String> changes = new ArrayList<>();

        for (String retired : RETIRED) {
            if (config.isSet(retired)) {
                config.set(retired, null);
                changes.add("removed " + retired);
            }
        }

        backfillSchedule(config, changes);
        backfillMagicModes(config, changes);
        backfillCrafting(config, changes);
        backfillProjectileCooldowns(config, changes);

        if (changes.isEmpty()) {
            return List.of();
        }

        plugin.saveConfig();
        return changes;
    }

    private static void backfillSchedule(FileConfiguration config, List<String> changes) {
        // Only worth commenting the section itself when we are the ones creating it; overwriting the
        // comments on a section the admin already has would be rude, and possibly wrong.
        boolean fresh = !config.isSet("network.event.schedule");

        if (set(config, changes, "network.event.schedule.enabled", true) && fresh) {
            comment(config, "network.event.schedule",
                    "",
                    "--- SMP role: offering events without being asked -----------------------",
                    "One match every interval-hours, whenever min-players are online. The last offer",
                    "is remembered in schedule.yml as well as in Redis, so the interval survives a",
                    "restart of either. A window is only spent on an event that was actually offered:",
                    "if no Bedwars server answers, the next check tries again.");
        }

        if (set(config, changes, "network.event.schedule.interval-hours", 5)) {
            comment(config, "network.event.schedule.interval-hours",
                    "Hours between events. Fractions are allowed (0.5 = every 30 minutes).");
        }

        if (set(config, changes, "network.event.schedule.min-players", 40)) {
            comment(config, "network.event.schedule.min-players",
                    "Players who must be online for the event to be worth offering. Anyone holding",
                    "mythicbedwars.event.exempt is not counted, since they will never be asked.");
        }

        if (set(config, changes, "network.event.schedule.check-seconds", 120)) {
            comment(config, "network.event.schedule.check-seconds",
                    "How promptly a due window is noticed once the players are there. Not how often",
                    "events run - that is interval-hours alone.");
        }

        backfillCountdown(config, changes);
    }

    /**
     * The player-facing countdown, which the schedule had the data for long before it had a voice.
     */
    private static void backfillCountdown(FileConfiguration config, List<String> changes) {
        if (set(config, changes, "network.event.schedule.countdown.enabled", true)) {
            comment(config, "network.event.schedule.countdown",
                    "",
                    "Telling players when the next attempt is due, and when it looks like it will not",
                    "happen. Without it the schedule is invisible: events appear out of nowhere, and a",
                    "server sitting one player under min-players never learns that is why.");
        }

        if (set(config, changes, "network.event.schedule.countdown.broadcast-minutes", List.of(60, 30, 10))) {
            comment(config, "network.event.schedule.countdown.broadcast-minutes",
                    "Minutes-remaining marks at which to broadcast. Empty disables the broadcast,",
                    "leaving /mb event next as the only way to ask.");
        }

        if (set(config, changes, "network.event.schedule.countdown.shortfall-warn-minutes", 60)) {
            comment(config, "network.event.schedule.countdown.shortfall-warn-minutes",
                    "How close the next attempt must be before a player shortfall is worth mentioning.",
                    "Six hours out it is noise; in the last hour it is something players can act on.");
        }
    }

    /**
     * The three-way magic vote, which replaced a plain on/off one.
     *
     * <p>Both keys default to the pre-existing behaviour: an admin who upgrades and changes nothing
     * keeps the two-option ballot's outcome, with the third option merely available.
     */
    private static void backfillMagicModes(FileConfiguration config, List<String> changes) {
        if (set(config, changes, "voting.individual-enabled", true)) {
            comment(config, "voting.individual-enabled",
                    "",
                    "The lobby picks between three magic modes:",
                    "  TEAM       - one pathway per team, shared by everyone on it",
                    "  INDIVIDUAL - one pathway per player, drawn distinctly across the whole arena",
                    "  OFF        - ordinary Bedwars",
                    "",
                    "INDIVIDUAL exists because pathways are not equally suited to Bedwars: a team that",
                    "draws a non-combat pathway is behind before the round starts. Spreading the draw",
                    "over players means every team holds a mix, so none is carried by one unlucky roll.",
                    "",
                    "Whether INDIVIDUAL is offered on the ballot at all. With this off the vote is the",
                    "original two-option one, and a default-mode of INDIVIDUAL falls back to TEAM.");
        }

        if (set(config, changes, "voting.default-mode", "TEAM")) {
            comment(config, "voting.default-mode",
                    "Used when voting is disabled, and as the tie-break floor between TEAM and INDIVIDUAL.",
                    "TEAM keeps an upgraded server playing exactly as it did before. Set it to OFF to make magic",
                    "opt-in: a lobby that does not vote then plays ordinary Bedwars instead of rolling a mode.");
        }

        if (set(config, changes, "voting.min-participation", 0.1)) {
            comment(config, "voting.min-participation",
                    "Fraction of the lobby that must vote for the ballots to count (0.1 = 10%). Below it, and",
                    "when nobody votes at all, TEAM or INDIVIDUAL is rolled at random - a handful of voters in a",
                    "full lobby should not decide the round for everyone else.");
        }

        if (set(config, changes, "network.event.magic-mode", "TEAM")) {
            comment(config, "network.event.magic-mode",
                    "Which magic mode an event match runs in, since it never holds a vote.",
                    "  TEAM       - one pathway per team",
                    "  INDIVIDUAL - one pathway per player",
                    "  RANDOM     - one of the two above, rolled once per event",
                    "force-magic: false wins outright; this is only read when magic is on at all.");
        }
    }

    /**
     * The match's own supply of Beyonder crafting inputs, and the shop page that sells them.
     */
    private static void backfillCrafting(FileConfiguration config, List<String> changes) {
        if (set(config, changes, "pathways.crafting.kill-drops", true)) {
            comment(config, "pathways.crafting",
                    "",
                    "Pathways whose whole point is making things (Paragon, Moon) arrive in a match",
                    "with nothing to make anything from: ingredients normally come from mining nodes",
                    "and foundables accumulated over days of survival play. These are the match's",
                    "own supply. A kill yields an ingredient no STRONGER than the victim - remember",
                    "COI's inversion, where a lower sequence number is the stronger Beyonder.");
        }

        if (set(config, changes, "pathways.crafting.pathways", List.of("paragon", "moon"))) {
            comment(config, "pathways.crafting.pathways",
                    "Which pathways earn the drop. An EMPTY list means every pathway, which is",
                    "deliberately not the default: this exists to fix pathways that cannot otherwise",
                    "build anything, and giving it to combat pathways too is a straight power boost.");
        }

        set(config, changes, "pathways.crafting.kill-drop-chance", 1.0);
        set(config, changes, "pathways.crafting.final-kill-drop-chance", 1.0);

        if (set(config, changes, "shop.materials.enabled", true)) {
            comment(config, "shop.materials",
                    "",
                    "Beyonder crafting inputs, sold from the shop's Arcane Materials page.",
                    "Special-item ids are magic_char_<0-9> and magic_ingredient_<0-9>, where the",
                    "number is the STRONGEST sequence that tier may roll. A characteristic comes from",
                    "a RANDOM pathway; an ingredient always from the BUYER'S OWN pathway.",
                    "Everything bought here is reclaimed when the player leaves the arena.");
        }

        set(config, changes, "shop.materials.pathways", List.of());

        if (set(config, changes, "shop.materials.max-per-match", 8)) {
            comment(config, "shop.materials.max-per-match",
                    "How many one player may buy in a match. -1 for no limit.");
        }
    }

    /**
     * Rate limits on the ordinary projectiles.
     */
    private static void backfillProjectileCooldowns(FileConfiguration config, List<String> changes) {
        if (set(config, changes, "combat.projectile-cooldowns.enabled", true)) {
            comment(config, "combat.projectile-cooldowns",
                    "",
                    "Rate limits on the ordinary projectiles, so fireballs and bows cannot simply be",
                    "held down. Keyed on the LAUNCHING ITEM, never on the projectile: Circle of",
                    "Imagination casts its abilities as plain snowballs and arrows with nothing to",
                    "distinguish them, so a limit that watched the projectile would throttle Beyonder",
                    "abilities too. Nothing here can reach an ability.");
        }

        boolean freshItems = !config.isSet("combat.projectile-cooldowns.items");

        set(config, changes, "combat.projectile-cooldowns.items.bow", 1.5);
        set(config, changes, "combat.projectile-cooldowns.items.crossbow", 2.0);
        set(config, changes, "combat.projectile-cooldowns.items.fireball", 3.0);
        set(config, changes, "combat.projectile-cooldowns.items.egg", 1.0);
        set(config, changes, "combat.projectile-cooldowns.items.snowball", 0.5);
        set(config, changes, "combat.projectile-cooldowns.items.ender_pearl", 5.0);

        if (freshItems) {
            comment(config, "combat.projectile-cooldowns.items",
                    "Seconds between uses. Omit a key, or set it to 0, for no limit.",
                    "Vanilla keys: bow, crossbow, egg, snowball, ender_pearl, splash_potion,",
                    "lingering_potion. MBedwars keys: any special-id exactly as shop.yml spells it.");
        }
    }

    /**
     * @return whether the key was missing and has now been written
     */
    private static boolean set(FileConfiguration config, List<String> changes, String path, Object value) {
        if (config.isSet(path)) {
            return false;
        }

        config.set(path, value);
        changes.add("added " + path + ": " + value);
        return true;
    }

    /**
     * Attaches comments to a key we just wrote. Never called for a key that was already on disk, so
     * it cannot overwrite anything an admin put there.
     */
    private static void comment(FileConfiguration config, String path, String... lines) {
        config.setComments(path, List.of(lines));
    }
}
