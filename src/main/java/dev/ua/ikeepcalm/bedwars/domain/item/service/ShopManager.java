package dev.ua.ikeepcalm.bedwars.domain.item.service;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.game.specialitem.SpecialItem;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseHandler;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.item.model.source.MaterialKind;
import dev.ua.ikeepcalm.bedwars.domain.item.type.MaterialShopItem;
import dev.ua.ikeepcalm.bedwars.domain.item.type.PotionShopItem;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

@RequiredArgsConstructor
public class ShopManager {

    private final MythicBedwars plugin;

    public void scheduleRegistration() {
        BedwarsAPI.onReady(() -> {
            for (int tier = 9; tier >= 0; tier--) {
                registerPotion(tier);
                for (MaterialKind kind : MaterialKind.values()) {
                    registerMaterial(kind, tier);
                }
            }
        });
    }

    private void registerPotion(int sequence) {
        ItemStack display = displayItem(Material.POTION,
                line("magic.shop.potion.name", NamedTextColor.LIGHT_PURPLE, sequence),
                line("magic.shop.potion.lore.0", NamedTextColor.GRAY, sequence),
                line("magic.shop.potion.lore.1", NamedTextColor.GRAY, sequence),
                Component.empty(),
                line("magic.shop.potion.lore.2", NamedTextColor.YELLOW, sequence));

        register("magic_potion_" + sequence, "magic.shop.potion.name", sequence, display, new PotionShopItem(sequence));
    }

    private void registerMaterial(MaterialKind kind, int tier) {
        String prefix = "magic.shop.material." + kind.key();
        ItemStack display = displayItem(kind.getIcon(),
                line(prefix + ".name", NamedTextColor.LIGHT_PURPLE, tier),
                line(prefix + ".lore", NamedTextColor.GRAY, tier),
                Component.empty(),
                line("magic.shop.material.bound", NamedTextColor.DARK_GRAY, tier));

        register(kind.specialItemId(tier), prefix + ".name", tier, display, new MaterialShopItem(kind, tier));
    }

    private void register(String id, String nameKey, int sequence, ItemStack display, SpecialItemUseHandler handler) {
        String name = plugin.getLocaleManager().getMessage(nameKey).replace("{sequence}", String.valueOf(sequence));
        SpecialItem created = BedwarsAPI.getGameAPI().registerSpecialItem(id, plugin, name, display);
        if (created == null) {
            plugin.log("Failed to register special item {}", id);
            return;
        }
        created.setHandler(handler);
    }

    private Component line(String key, NamedTextColor color, int sequence) {
        return plugin.getLocaleManager().formatMessage(key, "sequence", String.valueOf(sequence))
                .color(color)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack displayItem(Material material, Component name, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }
}
