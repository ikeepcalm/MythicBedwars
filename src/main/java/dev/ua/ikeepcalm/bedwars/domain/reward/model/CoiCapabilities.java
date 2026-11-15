package dev.ua.ikeepcalm.bedwars.domain.reward.model;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationApi;
import lombok.Data;

import java.util.UUID;

@Data
public class CoiCapabilities {
    public static final String REWARD_SOURCE = "bedwars:event-reward";
    public static final String SPEED_BOOST_KEY = "mythicbedwars:event-reward";

    private final boolean cooldownCredit;

    public static CoiCapabilities probe(CircleOfImaginationApi api) {
        return new CoiCapabilities(probeCooldownCredit(api));
    }

    private static boolean probeCooldownCredit(CircleOfImaginationApi api) {
        try {
            api.acting().getClass().getMethod("creditCooldown", UUID.class, String.class, long.class);
            return true;
        } catch (NoSuchMethodException | LinkageError e) {
            return false;
        }
    }

    public boolean isDegraded() {
        return !cooldownCredit;
    }

    public String describe() {
        return "cooldown-credit=" + (cooldownCredit ? "yes" : "no") + ", acting-source=" + REWARD_SOURCE;
    }
}
