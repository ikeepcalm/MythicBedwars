package dev.ua.ikeepcalm.bedwars.domain.stats.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Getter
@Setter
@NoArgsConstructor
public class PathwayStats implements ConfigurationSerializable {

    private volatile int wins;
    private volatile int losses;
    private volatile int totalGames;
    private volatile double totalDamageDealt;
    private final List<Integer> sequencesReached = new CopyOnWriteArrayList<>();
    private final List<Long> gameDurations = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> abilityUsage = new ConcurrentHashMap<>();

    public PathwayStats(Map<String, Object> map) {
        wins = ((Number) map.getOrDefault("wins", 0)).intValue();
        losses = ((Number) map.getOrDefault("losses", 0)).intValue();
        totalGames = ((Number) map.getOrDefault("totalGames", 0)).intValue();
        totalDamageDealt = ((Number) map.getOrDefault("totalDamageDealt", 0.0)).doubleValue();
        if (map.get("sequencesReached") instanceof List<?> sequences) {
            sequences.forEach(value -> sequencesReached.add(((Number) value).intValue()));
        }
        if (map.get("gameDurations") instanceof List<?> durations) {
            durations.forEach(value -> gameDurations.add(((Number) value).longValue()));
        }
        if (map.get("abilityUsage") instanceof Map<?, ?> usage) {
            usage.forEach((ability, count) -> abilityUsage.put(String.valueOf(ability), ((Number) count).intValue()));
        }
    }

    public void recordOutcome(boolean won) {
        if (won) {
            wins++;
        } else {
            losses++;
        }
        totalGames++;
    }

    public void addDamage(double damage) {
        totalDamageDealt += damage;
    }

    public void addAbilityUse(String ability) {
        abilityUsage.merge(ability, 1, Integer::sum);
    }

    public double getWinRate() {
        return totalGames > 0 ? (double) wins / totalGames : 0.0;
    }

    public double getAverageSequence() {
        return sequencesReached.stream().mapToInt(Integer::intValue).average().orElse(9.0);
    }

    public String getMostUsedAbility() {
        return abilityUsage.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("None");
    }

    @Override
    public @NotNull Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("wins", wins);
        map.put("losses", losses);
        map.put("totalGames", totalGames);
        map.put("totalDamageDealt", totalDamageDealt);
        map.put("sequencesReached", List.copyOf(sequencesReached));
        map.put("gameDurations", List.copyOf(gameDurations));
        map.put("abilityUsage", new HashMap<>(abilityUsage));
        return map;
    }
}
