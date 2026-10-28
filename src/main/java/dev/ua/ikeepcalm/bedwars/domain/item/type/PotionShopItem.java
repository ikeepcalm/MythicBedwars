package dev.ua.ikeepcalm.bedwars.domain.item.type;

import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseHandler;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseSession;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.Plugin;

@RequiredArgsConstructor
public class PotionShopItem implements SpecialItemUseHandler {

    private final int sequence;

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
}
