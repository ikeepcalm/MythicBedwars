package dev.ua.ikeepcalm.bedwars.domain.item.model.source;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;

import java.util.Locale;

@RequiredArgsConstructor
public enum MaterialKind {

    CHARACTERISTIC("magic_char", Material.PLAYER_HEAD),
    INGREDIENT("magic_ingredient", Material.GLOW_BERRIES);

    private final String idPrefix;
    @Getter
    private final Material icon;

    public String specialItemId(int tier) {
        return idPrefix + "_" + tier;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
