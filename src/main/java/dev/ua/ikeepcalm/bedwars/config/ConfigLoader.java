package dev.ua.ikeepcalm.bedwars.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ConfigLoader {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    public ConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
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
     * @return this instance's unique identity on the network. Two servers sharing an id will
     * overwrite each other's heartbeat, so this must differ per backend.
     */
    public String getServerId() {
        return config.getString("network.server-id", "unknown");
    }

    /**
     * @return this backend's name in {@code velocity.toml} - i.e. where the proxy sends players to
     * reach this server
     */
    public String getThisVelocityServer() {
        return config.getString("network.velocity.this-server", "");
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
        return config.getBoolean("pathways.auto-balance", false);
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