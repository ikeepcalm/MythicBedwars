package dev.ua.ikeepcalm.mythicBedwars.config;

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