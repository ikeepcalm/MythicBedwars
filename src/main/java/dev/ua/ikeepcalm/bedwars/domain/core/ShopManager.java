package dev.ua.ikeepcalm.bedwars.domain.core;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.game.specialitem.SpecialItem;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.item.model.source.MaterialKind;
import dev.ua.ikeepcalm.bedwars.domain.item.type.MaterialShopItem;
import dev.ua.ikeepcalm.bedwars.domain.item.type.PotionShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class ShopManager {

    private final MythicBedwars plugin;

    public ShopManager(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the shop items once MBedwars finishes loading.
     *
     * <p>Lives here rather than in the plugin bootstrap so that {@code BedwarsAPI} is only ever
     * referenced from a class the SMP role never constructs.
     */
     public void scheduleRegistration() {
        BedwarsAPI.onReady(() -> {
            registerPotionItems();
            registerMaterialItems();
        });
    }

    public void registerPotionItems() {
        for (int sequence = 9; sequence >= 0; sequence--) {
            ItemStack specialItem = createPotionSpecialItem(sequence);

            String id = "magic_potion_" + sequence;
            SpecialItem createdItem = BedwarsAPI.getGameAPI().registerSpecialItem(
                    id,
                    MythicBedwars.getInstance(),
                    plugin.getLocaleManager().getMessage("magic.shop.potion.name").replace("{sequence}", String.valueOf(sequence)),
                    specialItem
            );

            if (createdItem != null) {
                createdItem.setHandler(new PotionShopItem(id, specialItem, sequence));
                MythicBedwars.getInstance().log("Registered special magic potion item with ID: " + id);
            } else {
                MythicBedwars.getInstance().log("Failed to register special magic potion item!");
            }
        }
    }

    /**
     * Registers the crafting-input entries, one per kind per sequence tier.
     *
     * <p>Registered unconditionally, like the potions, even when {@code shop.materials.enabled} is
     * off: MBedwars resolves {@code special-id} once when it reads {@code shop.yml}, so an entry
     * that was not registered at boot is a broken shop page rather than a hidden one. The config
     * switch is enforced when the item is <i>used</i>, which also means flipping it takes effect on
     * a reload rather than a restart.
     */
    public void registerMaterialItems() {
        for (MaterialKind kind : MaterialKind.values()) {
            for (int tier = 9; tier >= 0; tier--) {
                registerMaterialItem(kind, tier);
            }
        }
    }

    private void registerMaterialItem(MaterialKind kind, int tier) {
        String id = kind.specialItemId(tier);

        SpecialItem created = BedwarsAPI.getGameAPI().registerSpecialItem(
                id,
                MythicBedwars.getInstance(),
                plugin.getLocaleManager().getMessage("magic.shop.material." + kind.key() + ".name")
                        .replace("{sequence}", String.valueOf(tier)),
                createMaterialSpecialItem(kind, tier));

        if (created == null) {
            MythicBedwars.getInstance().log("Failed to register special material item: {}", id);
            return;
        }

        created.setHandler(new MaterialShopItem(kind, tier));
        MythicBedwars.getInstance().log("Registered special material item with ID: {}", id);
    }

    private ItemStack createMaterialSpecialItem(MaterialKind kind, int tier) {
        ItemStack displayItem = new ItemStack(kind.icon());
        ItemMeta meta = displayItem.getItemMeta();

        meta.displayName(plugin.getLocaleManager()
                .formatMessage("magic.shop.material." + kind.key() + ".name", "sequence", String.valueOf(tier))
                .color(NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(plugin.getLocaleManager()
                .formatMessage("magic.shop.material." + kind.key() + ".lore", "sequence", String.valueOf(tier))
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(plugin.getLocaleManager().formatMessage("magic.shop.material.bound")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        displayItem.setItemMeta(meta);

        return displayItem;
    }

    private ItemStack createPotionSpecialItem(int sequence) {
        ItemStack displayItem = new ItemStack(Material.POTION);
        ItemMeta meta = displayItem.getItemMeta();

        Component itemName = plugin.getLocaleManager().formatMessage("magic.shop.potion.name", "sequence", String.valueOf(sequence));
        meta.displayName(itemName.color(NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        Component loreLine1 = plugin.getLocaleManager().formatMessage("magic.shop.potion.lore.0", "sequence", String.valueOf(sequence));
        Component loreLine2 = plugin.getLocaleManager().formatMessage("magic.shop.potion.lore.1");

        lore.add(loreLine1.color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(loreLine2.color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(plugin.getLocaleManager().formatMessage("magic.shop.potion.lore.2").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        displayItem.setItemMeta(meta);

        return displayItem;
    }
}
