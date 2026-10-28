package dev.ua.ikeepcalm.bedwars.domain.item.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class SandboxItems {

    private static NamespacedKey key() {
        return new NamespacedKey(MythicBedwars.getInstance(), "sandbox_item");
    }

    public static ItemStack tag(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isTagged(ItemStack item) {
        return item != null && !item.getType().isAir()
               && item.getPersistentDataContainer().has(key(), PersistentDataType.BYTE);
    }

    public static int strip(Player player) {
        Inventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int removed = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            if (isTagged(contents[slot])) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }
}
