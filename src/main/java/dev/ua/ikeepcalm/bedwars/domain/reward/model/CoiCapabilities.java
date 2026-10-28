package dev.ua.ikeepcalm.bedwars.domain.reward.model;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.ActingSourceCategory;
import lombok.Data;
import org.bukkit.entity.Player;

import java.util.Locale;

@Data
public class CoiCapabilities {
    private static final ActingSourceCategory FALLBACK_SOURCE = ActingSourceCategory.PLAYER_INTERACTION;

    private final boolean cooldownCredit;
    private final ActingSourceCategory rewardSource;

    public static CoiCapabilities probe(CircleOfImaginationAPI api, String sourceName) {
        return new CoiCapabilities(probeCooldownCredit(api), probeRewardSource(sourceName));
    }

    private static boolean probeCooldownCredit(CircleOfImaginationAPI api) {
        try {
            api.getClass().getMethod("creditActingCooldown", Player.class, String.class, long.class);
            return true;
        } catch (NoSuchMethodException | LinkageError e) {
            return false;
        }
    }

    private static ActingSourceCategory probeRewardSource(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return FALLBACK_SOURCE;
        }
        try {
            return ActingSourceCategory.valueOf(sourceName.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return FALLBACK_SOURCE;
        }
    }

    public boolean isDegraded() {
        return !cooldownCredit || rewardSource == FALLBACK_SOURCE;
    }

    public String describe() {
        return "cooldown-credit=" + (cooldownCredit ? "yes" : "no") + ", acting-source=" + rewardSource.id();
    }
}
