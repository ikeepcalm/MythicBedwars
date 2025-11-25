package dev.ua.ikeepcalm.mythicBedwars.domain.core;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.game.specialitem.SpecialItem;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.item.PotionShopItem;
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
