package dev.ua.ikeepcalm.bedwars.config;

import dev.ua.ikeepcalm.bedwars.domain.voting.model.MagicMode;
import dev.ua.ikeepcalm.bedwars.util.ConfigBackfiller;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class ConfigLoader {

    private final JavaPlugin plugin;
    private volatile FileConfiguration config;

    public ConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        // Writes anything this version added and clears out what it retired, so the file on disk is
        // the whole truth about what the plugin will do. A no-op once it has run.
        for (String change : ConfigBackfiller.apply(plugin, config)) {
            plugin.getLogger().info("config.yml: " + change);
        }
    }

    /**
     * @return whether this instance takes part in the cross-server event system at all. Off by
     * default, so a standalone Bedwars server never reads any of the network settings.
     */
    public boolean isNetworkEnabled() {
        return config.getBoolean("network.enabled", false);
    }

    /**
     * @return the configured role, falling back to {@link NetworkRole#MINIGAME} when unset or
     * misspelled - a typo should not strip a live Bedwars server of its features
     */
    public NetworkRole getNetworkRole() {
        return NetworkRole.fromId(config.getString("network.role"), NetworkRole.MINIGAME);
    }

    /**
     * @return the literal {@code network.role} value, for reporting one that could not be read
     */
    public String getRawNetworkRole() {
        return config.getString("network.role", "");
    }

    /**
     * @return whether {@code network.role} is set to something that is neither role. Worth a warning:
     * silently defaulting a survival server to MINIGAME disables the whole plugin there.
     */
    public boolean hasUnparseableNetworkRole() {
        String raw = config.getString("network.role");
        return raw != null && !raw.isBlank() && NetworkRole.fromId(raw, null) == null;
    }

    /**
     * @return this instance's unique identity on the network, or an empty string when unset. Two
     * servers sharing an id overwrite each other's heartbeat and each accepts messages addressed to
     * the other, so there is deliberately no usable default.
     */
    public String getServerId() {
        return config.getString("network.server-id", "");
    }

    /**
     * @return this backend's name in {@code velocity.toml} - i.e. where the proxy sends players to
     * reach this server
     */
    public String getThisVelocityServer() {
        return config.getString("network.velocity.this-server", "");
    }

    /**
     * @return whether this instance takes part in event matches at all, separately from whether the
     * network transport is up
     */
    public boolean isEventEnabled() {
        return config.getBoolean("network.event.enabled", true);
    }

    public int getEventMinPlayers() {
        return config.getInt("network.event.min-players", 4);
    }

    public int getEventMaxPlayers() {
        return config.getInt("network.event.max-players", 16);
    }

    public int getEventSignupSeconds() {
        return config.getInt("network.event.signup-seconds", 120);
    }

    /**
     * @return seconds-remaining thresholds at which to re-broadcast the offer, sorted high to low so
     * the tick loop fires them in order regardless of how they were written in the config
     */
    public List<Integer> getEventSignupReminders() {
        List<Integer> configured = config.getIntegerList("network.event.signup-reminders");
        if (configured.isEmpty()) {
            return List.of(90, 60, 30, 10);
        }
        return configured.stream().sorted(java.util.Comparator.reverseOrder()).toList();
    }

    /**
     * @return how long to wait for a host to answer before treating the proposal as dead
     */
    public int getEventProposeTimeoutSeconds() {
        return config.getInt("network.event.propose-timeout-seconds", 10);
    }

    /**
     * @return how long the host holds its arena open waiting for recruits to arrive
     */
    public int getEventArrivalGraceSeconds() {
        return config.getInt("network.event.arrival-grace-seconds", 60);
    }

    /**
     * @return how long leftover slots stay open to players already on the Bedwars server, once the
     * arrival window has closed
     */
    public int getEventFillWindowSeconds() {
        return config.getInt("network.event.fill-window-seconds", 20);
    }

    /**
     * @return the lobby countdown pushed out each tick so MBedwars never auto-starts an event arena
     * out from under us
     */
    public int getEventLobbyHoldSeconds() {
        return config.getInt("network.event.lobby-hold-seconds", 120);
    }

    /**
     * @return the visible countdown handed to MBedwars once we decide to start
     */
    public int getEventStartCountdownSeconds() {
        return config.getInt("network.event.start-countdown-seconds", 5);
    }

    /**
     * @return the fewest arrivals worth starting a match with, regardless of how many signed up
     */
    public int getEventMinArrivals() {
        return config.getInt("network.event.min-arrivals", 4);
    }

    /**
     * @return how long an eliminated player may linger before being sent home automatically
     */
    public int getEventAutoReturnSeconds() {
        return config.getInt("network.event.auto-return-seconds", 30);
    }

    /**
     * @return the pause after a win before winners are moved, so the celebration is not cut short
     */
    public int getEventWinnerReturnDelaySeconds() {
        return config.getInt("network.event.winner-return-delay-seconds", 15);
    }

    /**
     * @return how often stale or orphaned events are swept up
     */
    public int getEventReapIntervalSeconds() {
        return config.getInt("network.event.reap-interval-seconds", 30);
    }

    /**
     * @return whether players already on the Bedwars server are told about a forming event
     */
    public boolean isEventAnnouncedLocally() {
        return config.getBoolean("network.event.announce-locally", true);
    }

    public int getEventCooldownMinutes() {
        return config.getInt("network.event.cooldown-minutes", 60);
    }

    /**
     * @return how often an in-flight event reconciles its local state against the durable Redis hash.
     * Pub/sub is not replayable, so this is what turns a dropped message into a short delay rather
     * than a wedged event.
     */
    public int getEventSyncIntervalSeconds() {
        return config.getInt("network.event.sync-interval-seconds", 2);
    }

    /**
     * @return whether spectators may watch an event match
     */
    public boolean isEventSpectatorsAllowed() {
        return config.getBoolean("network.event.allow-spectators", true);
    }

    /**
     * @return whether the SMP runs events on a schedule, rather than waiting for an admin to run
     * {@code /mb event start}
     */
    public boolean isEventScheduleEnabled() {
        return config.getBoolean("network.event.schedule.enabled", true);
    }

    /**
     * @return how many hours must pass between scheduled events. This is the whole rule: one event
     * per window, network-wide, whenever the population is there for it.
     */
    public double getEventScheduleIntervalHours() {
        return Math.max(0.25, config.getDouble("network.event.schedule.interval-hours", 5.0));
    }

    /**
     * @return the fewest players who must be online for a scheduled event to be offered
     */
    public int getEventScheduleMinPlayers() {
        return config.getInt("network.event.schedule.min-players", 40);
    }

    /**
     * @return how often the schedule is re-checked. Only the interval decides how often an event
     * actually runs; this is just how promptly the window is noticed once the players are there.
     */
    public int getEventScheduleCheckSeconds() {
        return config.getInt("network.event.schedule.check-seconds", 120);
    }

    /**
     * @return how long an event's Redis keys survive without an update, so a half-finished event
     * cannot block the network forever
     */
    public int getEventTtlSeconds() {
        return config.getInt("network.event.event-ttl-seconds", 900);
    }

    /**
     * @return whether the survival server announces the countdown to the next event attempt
     */
    public boolean isEventCountdownEnabled() {
        return config.getBoolean("network.event.schedule.countdown.enabled", true);
    }

    /**
     * Minute marks at which the countdown is broadcast.
     *
     * <p>Sorted descending and de-duplicated here rather than at each use: the announcer wants the
     * largest mark it has reached, and an operator writing them in whatever order reads naturally
     * should not change which one fires.
     *
     * @return the marks, largest first; empty switches the broadcast off
     */
    public List<Integer> getEventCountdownMarks() {
        List<Integer> configured = config.getIntegerList("network.event.schedule.countdown.broadcast-minutes");

        if (configured.isEmpty() && !config.isSet("network.event.schedule.countdown.broadcast-minutes")) {
            return List.of(60, 30, 10);
        }

        return configured.stream()
                .filter(minutes -> minutes > 0)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    /**
     * @return how close the next attempt must be before a player shortfall is worth warning about.
     * Warning about it six hours out is noise; warning about it in the last hour is actionable.
     */
    public int getEventCountdownWarnMinutes() {
        return config.getInt("network.event.schedule.countdown.shortfall-warn-minutes", 60);
    }

    /**
     * @return whether event matches always run with magic on, bypassing the usual vote
     */
    public boolean isEventForceMagic() {
        return config.getBoolean("network.event.force-magic", true);
    }

    /**
     * The value {@code network.event.magic-mode} takes to mean "pick one per event".
     *
     * <p>Not a {@link MagicMode} constant, because it is not a mode a round can be in — it is a
     * policy for choosing one. Making it an enum constant would force every {@code switch} over
     * the real modes to handle a case that can never reach them.
     */
    private static final String EVENT_MAGIC_MODE_RANDOM = "RANDOM";

    /**
     * The mode event matches run in, since they never hold a vote.
     *
     * <p>{@code force-magic: false} still wins outright — it is the switch an operator reaches for
     * to run events without magic at all, and a mode set alongside it must not quietly turn it back
     * on.
     *
     * <p><b>Rolls on every call when set to {@code RANDOM}.</b> Callers must resolve it once and
     * carry the answer; an event that asked twice could accept as one mode and start as another.
     * {@link dev.ua.ikeepcalm.bedwars.net.minigame.EventReservation} is where the host keeps it.
     */
    public MagicMode resolveEventMagicMode() {
        if (!isEventForceMagic()) {
            return MagicMode.OFF;
        }

        String raw = config.getString("network.event.magic-mode");

        if (EVENT_MAGIC_MODE_RANDOM.equalsIgnoreCase(raw == null ? null : raw.trim())) {
            // Between the two enabled modes only: RANDOM answers "which kind of magic", not
            // "whether", which force-magic above has already settled.
            return ThreadLocalRandom.current().nextBoolean() ? MagicMode.TEAM : MagicMode.INDIVIDUAL;
        }

        MagicMode configured = MagicMode.fromId(raw, getDefaultMagicMode());

        return configured.isMagicEnabled() ? configured : MagicMode.TEAM;
    }

    /**
     * @return arenas events may use; empty means any eligible arena
     */
    public List<String> getEventArenaWhitelist() {
        return config.getStringList("network.event.arena-whitelist");
    }

    public List<String> getEventArenaBlacklist() {
        return config.getStringList("network.event.arena-blacklist");
    }

    /**
     * @return the team count to favour when several arenas hold the turnout equally well. Four is the
     * classic Bedwars shape, and it is what makes sixteen players land on a 4x4 rather than an 8x2
     * and four players on a 1v1v1v1 rather than a 2v2.
     */
    public int getEventPreferredTeamCount() {
        return Math.max(2, config.getInt("network.event.preferred-team-count", 4));
    }

    /**
     * @return arenas to favour when several fit equally well
     */
    public List<String> getPreferredEventArenas() {
        return config.getStringList("network.event.prefer-arenas");
    }

    public String getSmpVelocityServer() {
        return config.getString("network.velocity.smp-server", "survival");
    }

    /**
     * @return the Velocity name of the Bedwars server, i.e. where recruits are sent
     */
    public String getMinigameVelocityServer() {
        return config.getString("network.velocity.minigame-server", "bedwars");
    }

    /**
     * @return ticks to leave between consecutive transfers, so a whole roster moving at once does
     * not hit the destination as a login storm
     */
    public int getTransferStaggerTicks() {
        return config.getInt("network.velocity.transfer-stagger-ticks", 5);
    }

    public String getRedisHost() {
        return config.getString("network.redis.host", "127.0.0.1");
    }

    public int getRedisPort() {
        return config.getInt("network.redis.port", 6379);
    }

    public String getRedisPassword() {
        return config.getString("network.redis.password", "");
    }

    public int getRedisDatabase() {
        return config.getInt("network.redis.database", 0);
    }

    public boolean isRedisSsl() {
        return config.getBoolean("network.redis.ssl", false);
    }

    public int getRedisTimeoutMs() {
        return config.getInt("network.redis.timeout-ms", 2000);
    }

    /**
     * @return the key prefix for everything this plugin writes. Distinct from Circle of
     * Imagination's own {@code coi} prefix so both can share one Redis instance.
     */
    public String getRedisNamespace() {
        return config.getString("network.redis.namespace", "mythicbedwars");
    }

    public int getRedisPoolMaxTotal() {
        return config.getInt("network.redis.pool.max-total", 8);
    }

    public int getRedisPoolMaxIdle() {
        return config.getInt("network.redis.pool.max-idle", 4);
    }

    public int getRedisPoolMinIdle() {
        return config.getInt("network.redis.pool.min-idle", 1);
    }

    public int getHeartbeatIntervalSeconds() {
        return config.getInt("network.heartbeat.interval-seconds", 5);
    }

    public int getHeartbeatTtlSeconds() {
        return config.getInt("network.heartbeat.ttl-seconds", 15);
    }

    /**
     * @return how far behind a heartbeat's own timestamp may be before the server is treated as
     * gone. Slightly above the TTL, so clock skew alone cannot hide a healthy peer.
     */
    public int getHeartbeatStaleAfterSeconds() {
        return config.getInt("network.heartbeat.stale-after-seconds", 20);
    }

    public double getPassiveActingMultiplier() {
        return config.getDouble("acting.passive-multiplier", 1.0);
    }

    public double getKillActingMultiplier() {
        return config.getDouble("acting.kill-multiplier", 5.0);
    }

    public double getBedBreakActingMultiplier() {
        return config.getDouble("acting.bed-break-multiplier", 10.0);
    }

    public double getFinalKillActingMultiplier() {
        return config.getDouble("acting.final-kill-multiplier", 7.0);
    }

    public int getPassiveActingAmount() {
        return config.getInt("acting.passive-amount", 10);
    }

    public int getAutoSaveInterval() {
        return config.getInt("statistics.save-interval-seconds", 300);
    }

    public void setArenaEnabled(String arenaName, boolean enabled) {
        config.set("arenas." + arenaName + ".enabled", enabled);
        plugin.saveConfig();
    }

    public List<String> getDisabledPathways() {
        return config.getStringList("pathways.disabled");
    }

    public List<String> getBlockedAbilities() {
        return config.getStringList("pathways.blocked-abilities");
    }

    public boolean isPathwayBalancingEnabled() {
        return config.getBoolean("pathways.auto-balance", true);
    }

    public double getDeathActingPenalty() {
        return config.getDouble("acting.death-penalty", 0.15);
    }

    public double getSequenceMultiplier(int sequence) {
        double fallback = switch (sequence) {
            case 9 -> 3.5;
            case 8 -> 3.0;
            case 7 -> 2.5;
            case 6 -> 1.3;
            case 5 -> 1.1;
            case 4 -> 0.4;
            case 3 -> 0.3;
            case 2 -> 0.2;
            case 1 -> 0.1;
            default -> 0.05;
        };
        return config.getDouble("acting.sequence-multipliers." + sequence, fallback);
    }

    public int getMaxSequencePurchases(int sequence) {
        return config.getInt("shop.max-purchases.sequence-" + sequence, -1);
    }

    /**
     * @return whether the shop sells Beyonder crafting inputs at all
     */
    public boolean isShopMaterialsEnabled() {
        return config.getBoolean("shop.materials.enabled", true);
    }

    /**
     * @return the pathways allowed to buy crafting inputs; empty means all of them
     */
    public List<String> getMaterialShopPathways() {
        return config.getStringList("shop.materials.pathways");
    }

    /**
     * @return how many crafting inputs one player may buy in a match, or {@code -1} for no limit
     */
    public int getMaxMaterialPurchases() {
        return config.getInt("shop.materials.max-per-match", 8);
    }

    /**
     * The pathways whose players earn a crafting ingredient for a kill.
     *
     * <p>Defaults to the two the players actually named. An empty list means every pathway, which
     * is deliberately <b>not</b> the default: the drop exists to fix pathways that cannot otherwise
     * build anything, and handing it to combat pathways as well is a straight power increase.
     */
    public List<String> getCraftingPathways() {
        if (!config.isSet("pathways.crafting.pathways")) {
            return List.of("paragon", "moon");
        }

        return config.getStringList("pathways.crafting.pathways");
    }

    /**
     * @return whether killing somebody drops a crafting ingredient for crafting pathways
     */
    public boolean isCraftingKillDropEnabled() {
        return config.getBoolean("pathways.crafting.kill-drops", true);
    }

    /**
     * @return the chance, 0.0 to 1.0, that an ordinary kill yields an ingredient
     */
    public double getCraftingKillDropChance() {
        return clampChance(config.getDouble("pathways.crafting.kill-drop-chance", 1.0));
    }

    /**
     * @return the chance for a final kill, which is worth more because it is harder and rarer
     */
    public double getCraftingFinalKillDropChance() {
        return clampChance(config.getDouble("pathways.crafting.final-kill-drop-chance", 1.0));
    }

    private static double clampChance(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * How long a player must wait between uses of one ordinary projectile.
     *
     * <p>Keyed by a short name — {@code bow}, {@code crossbow}, {@code egg}, {@code ender_pearl} —
     * or, for an MBedwars special item, by its {@code special-id} exactly as {@code shop.yml}
     * spells it, so {@code fireball} gates the shop's fireball.
     *
     * @return the cooldown in seconds; {@code 0} or less means no limit, which is the default for
     * anything not named in config
     */
    public double getProjectileCooldownSeconds(String key) {
        if (key == null || !config.getBoolean("combat.projectile-cooldowns.enabled", true)) {
            return 0.0;
        }

        return config.getDouble("combat.projectile-cooldowns.items." + key, 0.0);
    }

    public boolean isGloballyEnabled() {
        return config.getBoolean("global.enabled", true);
    }

    public boolean toggleGlobalEnabled() {
        boolean current = isGloballyEnabled();
        config.set("global.enabled", !current);
        plugin.saveConfig();
        return !current;
    }

    public boolean isArenaEnabled(String arenaName) {
        if (!isGloballyEnabled()) return false;
        return config.getBoolean("arenas." + arenaName + ".enabled", true);
    }

    public boolean isPathwayAllowed(String pathway) {
        return !getDisabledPathways().contains(pathway);
    }

    public double getBalanceThreshold() {
        return config.getDouble("pathways.balance-threshold", 0.1);
    }

    public int getMinGamesForBalance() {
        return config.getInt("pathways.min-games-for-balance", 3);
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public boolean isSpectatorFeaturesEnabled() {
        return config.getBoolean("spectator.enabled", true);
    }

    public boolean isSpectatorHudDefaultEnabled() {
        return config.getBoolean("spectator.hud-default", true);
    }

    public boolean isSpectatorActionBarDefaultEnabled() {
        return config.getBoolean("spectator.actionbar-default", true);
    }

    public boolean isSpectatorDetailedModeDefaultEnabled() {
        return config.getBoolean("spectator.detailed-default", false);
    }

    public int getSpectatorUpdateInterval() {
        return config.getInt("spectator.update-interval-ticks", 20);
    }

    public boolean isVotingEnabled() {
        return config.getBoolean("voting.enabled", true);
    }

    /**
     * The mode used when nobody votes, when voting is switched off, and as the tie-break floor.
     *
     * <p>Defaults to {@link MagicMode#TEAM}: it is what every existing install has always done, and
     * a config upgrade must not change how a server plays without anybody asking for it.
     */
    public MagicMode getDefaultMagicMode() {
        MagicMode configured = MagicMode.fromId(config.getString("voting.default-mode"), MagicMode.TEAM);

        // OFF is a legitimate thing to want here - an operator who wants magic opt-in rather than
        // opt-out - so it is deliberately not corrected away.
        if (configured == MagicMode.INDIVIDUAL && !isIndividualMagicModeEnabled()) {
            return MagicMode.TEAM;
        }

        return configured;
    }

    /**
     * @return whether the per-player pathway mode is offered on the ballot at all
     */
    public boolean isIndividualMagicModeEnabled() {
        return config.getBoolean("voting.individual-enabled", true);
    }

    public int getVotingItemDelay() {
        return config.getInt("voting.item-delay", 3);
    }

    public boolean isVotingRemindersEnabled() {
        return config.getBoolean("voting.reminders-enabled", true);
    }

    public int getVotingReminderInterval() {
        return config.getInt("voting.reminder-interval", 10);
    }

    public int getMaxVotingReminders() {
        return config.getInt("voting.max-reminders", 5);
    }

}