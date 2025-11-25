package dev.ua.ikeepcalm.mythicBedwars.domain.stats.db;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PathwayStats implements Serializable, ConfigurationSerializable {
    public int wins = 0;
    public int losses = 0;
    public int totalGames = 0;
    public double totalDamageDealt = 0;
    public List<Integer> sequencesReached = new ArrayList<>();
    public List<Long> gameDurations = new ArrayList<>();
    public Map<String, Integer> abilityUsage = new HashMap<>();

    public PathwayStats() {
    }

    @SuppressWarnings("unchecked")
    public PathwayStats(Map<String, Object> map) {
        this.wins = (int) map.getOrDefault("wins", 0);
        this.losses = (int) map.getOrDefault("losses", 0);
        this.totalGames = (int) map.getOrDefault("totalGames", 0);
        this.totalDamageDealt = ((Number) map.getOrDefault("totalDamageDealt", 0.0)).doubleValue();
        this.sequencesReached = (List<Integer>) map.getOrDefault("sequencesReached", new ArrayList<>());
        this.gameDurations = (List<Long>) map.getOrDefault("gameDurations", new ArrayList<>());
        this.abilityUsage = (Map<String, Integer>) map.getOrDefault("abilityUsage", new HashMap<>());
    }

    public double getAverageSequence() {
        if (sequencesReached.isEmpty()) return 9.0;
        return 9.0 - sequencesReached.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
    }

    double getAverageGameDuration() {
        if (gameDurations.isEmpty()) return 0;
        return gameDurations.stream()
                       .mapToLong(Long::longValue)
                       .average()
                       .orElse(0.0) / 1000 / 60;
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
        map.put("sequencesReached", sequencesReached);
        map.put("gameDurations", gameDurations);
        map.put("abilityUsage", abilityUsage);
        return map;
    }
}