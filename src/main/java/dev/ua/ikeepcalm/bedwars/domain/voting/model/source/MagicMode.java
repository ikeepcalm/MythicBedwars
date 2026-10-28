package dev.ua.ikeepcalm.bedwars.domain.voting.model.source;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.Locale;

@Getter
@RequiredArgsConstructor
public enum MagicMode {

    OFF(Material.RED_DYE, NamedTextColor.RED),
    TEAM(Material.LIME_DYE, NamedTextColor.GREEN),
    INDIVIDUAL(Material.PURPLE_DYE, NamedTextColor.LIGHT_PURPLE);

    private final Material ballotMaterial;
    private final NamedTextColor color;

    public boolean isMagicEnabled() {
        return this != OFF;
    }

    public boolean isPerPlayer() {
        return this == INDIVIDUAL;
    }

    public static MagicMode fromId(String id, MagicMode fallback) {
        if (id == null) {
            return fallback;
        }
        for (MagicMode mode : values()) {
            if (mode.name().equalsIgnoreCase(id.trim())) {
                return mode;
            }
        }
        return fallback;
    }

    public String key() {
        // YAML 1.1 parses an unquoted "off:" key as boolean false, so OFF must never be the lang key.
        return this == OFF ? "disabled" : name().toLowerCase(Locale.ROOT);
    }
}
