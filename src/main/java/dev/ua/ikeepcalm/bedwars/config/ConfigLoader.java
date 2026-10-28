package dev.ua.ikeepcalm.bedwars.config;

import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.source.MagicMode;
import dev.ua.ikeepcalm.bedwars.util.config.ConfigBackfiller;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
public class ConfigLoader {

    private final JavaPlugin plugin;
    private volatile FileConfiguration config;

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        for (String change : ConfigBackfiller.apply(plugin, config)) {
            plugin.getLogger().info("config.yml: " + change);
        }
    }

    public boolean isNetworkEnabled() {
        return config.getBoolean("network.enabled", false);
    }

    public NetworkRole getNetworkRole() {
        return NetworkRole.fromId(config.getString("network.role"), NetworkRole.MINIGAME);
    }

    public String getRawNetworkRole() {
        return config.getString("network.role", "");
    }

    public boolean hasUnparseableNetworkRole() {
        String raw = config.getString("network.role");
        return raw != null && !raw.isBlank() && NetworkRole.fromId(raw, null) == null;
    }

    public String getServerId() {
        return config.getString("network.server-id", "");
    }

    public String getThisVelocityServer() {
        return config.getString("network.velocity.this-server", "");
    }

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

    public List<Integer> getEventSignupReminders() {
        List<Integer> configured = config.getIntegerList("network.event.signup-reminders");
        if (configured.isEmpty()) {
            return List.of(90, 60, 30, 10);
        }
        return configured.stream().sorted(Comparator.reverseOrder()).toList();
    }

    public int getEventProposeTimeoutSeconds() {
        return config.getInt("network.event.propose-timeout-seconds", 10);
    }

    public int getEventArrivalGraceSeconds() {
        return config.getInt("network.event.arrival-grace-seconds", 60);
    }

    public int getEventFillWindowSeconds() {
        return config.getInt("network.event.fill-window-seconds", 20);
    }

    public int getEventLobbyHoldSeconds() {
        return config.getInt("network.event.lobby-hold-seconds", 120);
    }

    public int getEventStartCountdownSeconds() {
        return config.getInt("network.event.start-countdown-seconds", 5);
    }

    public int getEventMinArrivals() {
        return config.getInt("network.event.min-arrivals", 4);
    }

    public int getEventAutoReturnSeconds() {
        return config.getInt("network.event.auto-return-seconds", 30);
    }

    public int getEventWinnerReturnDelaySeconds() {
        return config.getInt("network.event.winner-return-delay-seconds", 15);
    }

    public int getEventReapIntervalSeconds() {
        return config.getInt("network.event.reap-interval-seconds", 30);
    }

    public boolean isEventAnnouncedLocally() {
        return config.getBoolean("network.event.announce-locally", true);
    }

    public int getEventCooldownMinutes() {
        return config.getInt("network.event.cooldown-minutes", 60);
    }

    public int getEventSyncIntervalSeconds() {
        return config.getInt("network.event.sync-interval-seconds", 2);
    }

    public boolean isEventSpectatorsAllowed() {
        return config.getBoolean("network.event.allow-spectators", true);
    }

    public boolean isEventScheduleEnabled() {
        return config.getBoolean("network.event.schedule.enabled", true);
    }

    public double getEventScheduleIntervalHours() {
        return Math.max(0.25, config.getDouble("network.event.schedule.interval-hours", 5.0));
    }

    public int getEventScheduleMinPlayers() {
        return config.getInt("network.event.schedule.min-players", 40);
    }

    public int getEventScheduleCheckSeconds() {
        return config.getInt("network.event.schedule.check-seconds", 120);
    }

    public int getEventTtlSeconds() {
        return config.getInt("network.event.event-ttl-seconds", 900);
    }

    public boolean isEventCountdownEnabled() {
        return config.getBoolean("network.event.schedule.countdown.enabled", true);
    }

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

    public int getEventCountdownWarnMinutes() {
        return config.getInt("network.event.schedule.countdown.shortfall-warn-minutes", 60);
    }

    public boolean isEventForceMagic() {
        return config.getBoolean("network.event.force-magic", true);
    }

    public MagicMode resolveEventMagicMode() {
        if (!isEventForceMagic()) {
            return MagicMode.OFF;
        }

        String raw = config.getString("network.event.magic-mode", "RANDOM");

        if (raw != null && "RANDOM".equalsIgnoreCase(raw.trim())) {
            return ThreadLocalRandom.current().nextBoolean() ? MagicMode.TEAM : MagicMode.INDIVIDUAL;
        }

        MagicMode configured = MagicMode.fromId(raw, getDefaultMagicMode());

        return configured.isMagicEnabled() ? configured : MagicMode.TEAM;
    }

    public List<String> getEventArenaWhitelist() {
        return config.getStringList("network.event.arena-whitelist");
    }

    public List<String> getEventArenaBlacklist() {
        return config.getStringList("network.event.arena-blacklist");
    }

    public int getEventPreferredTeamCount() {
        return Math.max(2, config.getInt("network.event.preferred-team-count", 4));
    }

    public List<String> getPreferredEventArenas() {
        return config.getStringList("network.event.prefer-arenas");
    }

    public String getSmpVelocityServer() {
        return config.getString("network.velocity.smp-server", "survival");
    }

    public String getMinigameVelocityServer() {
        return config.getString("network.velocity.minigame-server", "bedwars");
    }

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

    public void setDisabledPathways(List<String> pathways) {
        config.set("pathways.disabled", pathways);
        plugin.saveConfig();
    }

    public void setPathwayBalancingEnabled(boolean enabled) {
        config.set("pathways.auto-balance", enabled);
        plugin.saveConfig();
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

    public boolean isShopMaterialsEnabled() {
        return config.getBoolean("shop.materials.enabled", true);
    }

    public List<String> getMaterialShopPathways() {
        return config.getStringList("shop.materials.pathways");
    }

    public int getMaxMaterialPurchases() {
        return config.getInt("shop.materials.max-per-match", 8);
    }

    public List<String> getCraftingPathways() {
        if (!config.isSet("pathways.crafting.pathways")) {
            return List.of("paragon", "moon");
        }

        return config.getStringList("pathways.crafting.pathways");
    }

    public boolean isCraftingKillDropEnabled() {
        return config.getBoolean("pathways.crafting.kill-drops", true);
    }

    public double getCraftingKillDropChance() {
        return Math.clamp(config.getDouble("pathways.crafting.kill-drop-chance", 0.7), 0.0, 1.0);
    }

    public double getCraftingFinalKillDropChance() {
        return Math.clamp(config.getDouble("pathways.crafting.final-kill-drop-chance", 1.0), 0.0, 1.0);
    }

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
        return isGloballyEnabled() && config.getBoolean("arenas." + arenaName + ".enabled", true);
    }

    public boolean isPathwayAllowed(String pathway) {
        return !getDisabledPathways().contains(pathway);
    }

    public double getBalanceThreshold() {
        return config.getDouble("pathways.balance-threshold", 0.1);
    }

    public int getMinGamesForBalance() {
        return config.getInt("pathways.min-games-for-balance", 20);
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

    public MagicMode getDefaultMagicMode() {
        MagicMode configured = MagicMode.fromId(config.getString("voting.default-mode"), MagicMode.TEAM);

        if (configured == MagicMode.INDIVIDUAL && !isIndividualMagicModeEnabled()) {
            return MagicMode.TEAM;
        }

        return configured;
    }

    public boolean isIndividualMagicModeEnabled() {
        return config.getBoolean("voting.individual-enabled", true);
    }

    public double getVotingMinParticipation() {
        return Math.clamp(config.getDouble("voting.min-participation", 0.1), 0.0, 1.0);
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
