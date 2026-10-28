package dev.ua.ikeepcalm.bedwars.domain.reward.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.Contribution;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardEntry;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.TierConfig;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardTier;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RequiredArgsConstructor
public class RewardConfig {

    private static final List<String> ADDED = List.of(
            "rewards.eligibility.participation-scale-floor",
            "rewards.cooldown-substitute-ratio",
            "rewards.performance.acting-percent-per-point",
            "rewards.performance.max-acting-percent");

    private static final Map<String, String> RETIRED_DEFAULTS = Map.of(
            "rewards.eligibility.min-participation-ratio", "0.25",
            "rewards.tiers.winner.rolls", "2",
            "rewards.tiers.winner.guaranteed", "[{ kind: ACTING_PERCENT, min-percent: 1.5, max-percent: 3.0 }]",
            "rewards.tiers.mvp.min-score", "3.0",
            "rewards.tiers.mvp.score.kill", "1.0",
            "rewards.tiers.mvp.pool", "[{ kind: ITEM, weight: 50, item: ACTING_BOTTLE, min-percent: 2.0, max-percent: 4.0 }, "
                                      + "{ kind: ITEM, weight: 30, item: ANTI_CONTROL_CHARM, epic: true }, "
                                      + "{ kind: ITEM, weight: 20, item: RITUAL_BOOK, amount: 1, epic: true }]");

    private final MythicBedwars plugin;

    private volatile FileConfiguration config;
    private volatile Map<RewardTier, TierConfig> tiers = Map.of();
    private volatile Map<Integer, Double> tokenSequenceWeights = Map.of(9, 1.0);

    public void load() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        if (!file.exists()) {
            plugin.saveResource("rewards.yml", false);
        }

        FileConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream bundledStream = plugin.getResource("rewards.yml");
        if (bundledStream != null) {
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(bundledStream, StandardCharsets.UTF_8));
            backfill(loaded, bundled, file);
            loaded.setDefaults(bundled);
        }

        Map<RewardTier, TierConfig> parsed = new EnumMap<>(RewardTier.class);
        for (RewardTier tier : RewardTier.values()) {
            parsed.put(tier, parseTier(loaded, tier));
        }

        tokenSequenceWeights = Map.copyOf(parseTokenSequenceWeights(loaded));
        tiers = parsed;
        config = loaded;

        plugin.log("Loaded reward tiers: {}", parsed.entrySet().stream()
                .filter(e -> e.getValue().isEnabled())
                .map(e -> e.getKey().name().toLowerCase(Locale.ROOT))
                .toList());
    }

    private void backfill(FileConfiguration loaded, YamlConfiguration bundled, File file) {
        List<String> changes = new ArrayList<>();

        for (String path : ADDED) {
            if (!loaded.isSet(path)) {
                loaded.set(path, bundled.get(path));
                changes.add("added " + path);
            }
        }

        RETIRED_DEFAULTS.forEach((path, retired) -> {
            if (loaded.isSet(path) && retiredValue(retired).equals(loaded.get(path))) {
                loaded.set(path, bundled.get(path));
                changes.add("updated untouched default " + path);
            }
        });

        if (changes.isEmpty()) {
            return;
        }

        try {
            loaded.save(file);
            changes.forEach(change -> plugin.getLogger().info("rewards.yml: " + change));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not update rewards.yml: " + exception.getMessage());
        }
    }

    private static Object retiredValue(String yaml) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString("value: " + yaml);
        } catch (InvalidConfigurationException exception) {
            throw new IllegalStateException("Malformed retired default: " + yaml, exception);
        }
        return parsed.get("value");
    }

    private TierConfig parseTier(FileConfiguration loaded, RewardTier tier) {
        ConfigurationSection section = loaded.getConfigurationSection(
                "rewards.tiers." + tier.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (section == null) {
            return TierConfig.EMPTY;
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
                Math.max(1, num(raw, "count", 1).intValue()),
                bool(raw, "epic"),
                str(raw, "tier"));
    }

    private Map<Integer, Double> parseTokenSequenceWeights(FileConfiguration loaded) {
        Map<Integer, Double> weights = new LinkedHashMap<>();

        ConfigurationSection section = loaded.getConfigurationSection("rewards.token-sequences");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int sequence;
                try {
                    sequence = Integer.parseInt(key.trim());
                } catch (NumberFormatException e) {
                    plugin.log("Ignoring non-numeric token sequence key: {}", key);
                    continue;
                }

                if (sequence < 1 || sequence > 9) {
                    plugin.log("Ignoring out-of-range token sequence {} (expected 1-9).", sequence);
                    continue;
                }

                double weight = section.getDouble(key, 0.0);
                if (weight > 0) {
                    weights.put(sequence, weight);
                }
            }
        }

        if (weights.isEmpty()) {
            weights.put(9, 1.0);
        }
        return weights;
    }

    private static Number num(Map<?, ?> raw, String key, Number fallback) {
        Object value = raw.get(key);
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static String str(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
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
        return tiers.getOrDefault(tier, TierConfig.EMPTY);
    }

    public Map<Integer, Double> tokenSequenceWeights() {
        return tokenSequenceWeights;
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

    public String actingSourceName() {
        return config.getString("rewards.acting-source", "EVENT");
    }

    public int dailyDowngradeThreshold() {
        return config.getInt("rewards.max-bundles-per-day", 8);
    }

    public int dailyDropThreshold() {
        return config.getInt("rewards.max-bundles-per-day-hard", Math.max(1, dailyDowngradeThreshold()) * 2);
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

    public double cooldownSubstituteRatio() {
        return config.getDouble("rewards.cooldown-substitute-ratio", 0.1);
    }

    public double performanceActingPerPoint() {
        return config.getDouble("rewards.performance.acting-percent-per-point", 0.08);
    }

    public double performanceActingCap() {
        return config.getDouble("rewards.performance.max-acting-percent", 3.0);
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
        return config.getDouble("rewards.eligibility.min-participation-ratio", 0.10);
    }

    public double participationScaleFloor() {
        return Math.min(1.0, Math.max(0.0, config.getDouble("rewards.eligibility.participation-scale-floor", 0.5)));
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
        return config.getDouble("rewards.tiers.mvp.min-score", 8.0);
    }

    public double score(Contribution contribution) {
        return contribution.getKills() * config.getDouble("rewards.tiers.mvp.score.kill", 0.5)
               + contribution.getFinalKills() * config.getDouble("rewards.tiers.mvp.score.final-kill", 3.0)
               + contribution.getBedsBroken() * config.getDouble("rewards.tiers.mvp.score.bed-break", 5.0);
    }

    public double performanceActingPercent(Contribution contribution) {
        return Math.min(performanceActingCap(), score(contribution) * performanceActingPerPoint());
    }
}
