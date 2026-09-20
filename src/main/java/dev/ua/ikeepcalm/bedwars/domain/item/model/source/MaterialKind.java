package dev.ua.ikeepcalm.bedwars.domain.item.model.source;

import org.bukkit.Material;

/**
 * The two kinds of crafting input the shop sells.
 *
 * <p>Split from the sequence tier because they answer different questions: the kind decides which
 * COI lookup runs and whose pathway it runs against, the tier decides how strong the result may be.
 */
public enum MaterialKind {

    /**
     * A Beyonder characteristic from a random pathway. Paragon's artifact crafting consumes one per
     * craft, and which pathway it came from is what decides the artifact's abilities.
     */
    CHARACTERISTIC("magic_char", Material.PLAYER_HEAD),

    /**
     * A Beyonder ingredient from the buyer's own pathway, for brewing and crafting recipes.
     */
    INGREDIENT("magic_ingredient", Material.GLOW_BERRIES);

    private final String idPrefix;
    private final Material icon;

    MaterialKind(String idPrefix, Material icon) {
        this.idPrefix = idPrefix;
        this.icon = icon;
    }

    /**
     * @return the special-item id for a tier, e.g. {@code magic_char_6}. This is the string an
     * operator writes as {@code special-id} in MBedwars' {@code shop.yml}.
     */
    public String specialItemId(int tier) {
        return idPrefix + "_" + tier;
    }

    /**
     * @return the display material for the shop entry
     */
    public Material icon() {
        return icon;
    }

    /**
     * @return the locale key fragment naming this kind
     */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
