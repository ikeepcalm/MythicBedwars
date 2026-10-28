package dev.ua.ikeepcalm.bedwars.config.models.source;

public enum NetworkRole {
    SMP,
    MINIGAME;

    public static NetworkRole fromId(String raw, NetworkRole fallback) {
        if (raw == null) {
            return fallback;
        }

        String trimmed = raw.trim();
        for (NetworkRole role : values()) {
            if (role.name().equalsIgnoreCase(trimmed)) {
                return role;
            }
        }
        return fallback;
    }
}
