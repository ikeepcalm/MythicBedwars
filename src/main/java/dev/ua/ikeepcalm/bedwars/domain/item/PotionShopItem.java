package dev.ua.ikeepcalm.bedwars.domain.item;

import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseHandler;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseSession;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class PotionShopItem implements SpecialItemUseHandler {

    private final String id;
    private final ItemStack displayItem;
    private final int sequence;

    public PotionShopItem(String id, ItemStack displayItem, int sequence) {
        this.id = id;
        this.displayItem = displayItem;
        this.sequence = sequence;
    }

    @Override
    public Plugin getPlugin() {
        return MythicBedwars.getInstance();
    }

    @Override
    public SpecialItemUseSession openSession(PlayerUseSpecialItemEvent event) {
        PotionItemSession session = new PotionItemSession(event, sequence);
        session.run();
        return session;
    }

    public String getId() {
        return id;
    }

    public ItemStack getDisplayItem() {
        return displayItem;
    }
}