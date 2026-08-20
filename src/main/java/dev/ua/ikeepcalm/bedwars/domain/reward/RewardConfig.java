package dev.ua.ikeepcalm.bedwars.domain.reward;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardEntry;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardTier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@code rewards.yml} and parses the tier tree once, rather than re-reading it per match.
 *
 * <p>Unparseable entries are dropped with a warning instead of throwing: a typo in one loot line
 * should cost that line, not the whole reward system.
 */
public class RewardConfig {

    private final MythicBedwars plugin;
    private final Map<RewardTier, TierConfig> tiers = new EnumMap<>(RewardTier.class);

    private FileConfiguration config;

    public RewardConfig(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * One tier's payout: what is always given, and what is rolled on top.
     */
    public record TierConfig(boolean enabled, int rolls, List<RewardEntry> guaranteed, List<RewardEntry> pool) {

        static TierConfig empty() {
            return new TierConfig(false, 0, List.of(), List.of());
        }
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        if (!file.exists()) {
            plugin.saveResource("rewards.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);

        // Bundled defaults back the on-disk copy, so a config written before a new key existed still
        // resolves it rather than silently behaving as if the feature were switched off.
        java.io.InputStream bundled = plugin.getResource("rewards.yml");
        if (bundled != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(bundled, java.nio.charset.StandardCharsets.UTF_8)));
        }

        tiers.clear();
        for (RewardTier tier : RewardTier.values()) {
            tiers.put(tier, parseTier(tier));
        }

        plugin.log("Loaded reward tiers: {}", tiers.entrySet().stream()
                .filter(e -> e.getValue().enabled())
                .map(e -> e.getKey().name().toLowerCase(Locale.ROOT))
                .toList());
    }

    private TierConfig parseTier(RewardTier tier) {
        String path = "rewards.tiers." + tier.name().toLowerCase(Locale.ROOT).replace('_', '-');
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return TierConfig.empty();
        }

        return new TierConfig(
                section.getBoolean("enabled", true),
                section.getInt("rolls", 0),
                parseEntries(section, "guaranteed"),
                parseEntries(section, "pool"));
    }

    private List<RewardEntry> parseEntries(ConfigurationSection section, String key) {
        List<RewardEntry> entries = new ArrayList<>();

        for (Map<?, ?> raw : section.getMapList(key)) {
            RewardEntry entry = parseEntry(raw);
            if (entry != null) {
                entries.add(entry);
            }
        }

        return List.copyOf(entries);
    }

    private RewardEntry parseEntry(Map<?, ?> raw) {
        RewardKind kind = parseEnum(RewardKind.class, str(raw, "kind"));
        if (kind == null) {
            plugin.log("Ignoring reward entry with unknown kind: {}", raw);
            return null;
        }

        RewardItemKind item = parseEnum(RewardItemKind.class, str(raw, "item"));
        if (kind == RewardKind.ITEM && item == null) {
            plugin.log("Ignoring ITEM reward entry with unknown item: {}", raw);
            return null;
        }

        return new RewardEntry(
                kind,
                num(raw, "weight", 1).intValue(),
                item,
                num(raw, "min-percent", 0).doubleValue(),
                num(raw, "max-percent", 0).doubleValue(),
                num(raw, "min-value", 0).doubleValue(),
                num(raw, "max-value", 0).doubleValue(),
                num(raw, "duration-seconds", 0).intValue(),
                num(raw, "amount", 1).intValue(),
                str(raw, "tier"));
    }

    private static String str(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Number num(Map<?, ?> raw, String key, Number fallback) {
        Object value = raw.get(key);
        return value instanceof Number number ? number : fallback;
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public TierConfig tier(RewardTier tier) {
        return tiers.getOrDefault(tier, TierConfig.empty());
    }

    public boolean isEnabled() {
        return config != null && config.getBoolean("rewards.enabled", true);
    }

    public int queueTtlSeconds() {
        return config.getInt("rewards.queue-ttl-days", 30) * 86_400;
    }

    public int maxQueuedBundles() {
        return config.getInt("rewards.max-queued-bundles", 10);
    }

    public int maxBundlesPerDay() {
        return config.getInt("rewards.max-bundles-per-day", 8);
    }

    public String actingSourceName() {
        return config.getString("rewards.acting-source", "EVENT");
    }

    public boolean substituteForNonBeyonders() {
        return !"HOLD".equalsIgnoreCase(config.getString("rewards.non-beyonder-policy", "SUBSTITUTE"));
    }

    public int fallbackBottleActing() {
        return config.getInt("rewards.fallback-bottle-acting", 300);
    }

    public double cooldownSubstitutePercent() {
        return config.getDouble("rewards.cooldown-substitute-percent", 2.0);
    }

    public boolean requeueOverflow() {
        return !"DROP".equalsIgnoreCase(config.getString("rewards.item-overflow", "REQUEUE"));
    }

    public int minPlayTimeSeconds() {
        return config.getInt("rewards.eligibility.min-play-time-seconds", 120);
    }

    public int minActions() {
        return config.getInt("rewards.eligibility.min-actions", 1);
    }

    public int minPlayers() {
        return config.getInt("rewards.eligibility.min-players", 4);
    }

    public double minParticipationRatio() {
        return config.getDouble("rewards.eligibility.min-participation-ratio", 0.25);
    }

    public boolean denyRageQuit() {
        return config.getBoolean("rewards.eligibility.deny-rage-quit", true);
    }

    public int redeemDelayTicks() {
        return config.getInt("rewards.redeem.delay-ticks", 60);
    }

    public int maxBundlesPerJoin() {
        return config.getInt("rewards.redeem.max-bundles-per-join", 5);
    }

    public double mvpMinScore() {
        return config.getDouble("rewards.tiers.mvp.min-score", 3.0);
    }

    public double mvpScore(String key, double fallback) {
        return config.getDouble("rewards.tiers.mvp.score." + key, fallback);
    }
}
