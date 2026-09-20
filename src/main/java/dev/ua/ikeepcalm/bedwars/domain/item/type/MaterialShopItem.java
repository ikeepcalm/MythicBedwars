package dev.ua.ikeepcalm.bedwars.domain.item.type;

import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseHandler;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseSession;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.item.model.MaterialItemSession;
import dev.ua.ikeepcalm.bedwars.domain.item.model.source.MaterialKind;
import org.bukkit.plugin.Plugin;

/**
 * The shop handler behind one {@code magic_char_*} / {@code magic_ingredient_*} entry.
 */
public class MaterialShopItem implements SpecialItemUseHandler {

    private final MaterialKind kind;
    private final int sequenceFloor;

    public MaterialShopItem(MaterialKind kind, int sequenceFloor) {
        this.kind = kind;
        this.sequenceFloor = sequenceFloor;
    }

    @Override
    public Plugin getPlugin() {
        return MythicBedwars.getInstance();
    }

    @Override
    public SpecialItemUseSession openSession(PlayerUseSpecialItemEvent event) {
        MaterialItemSession session = new MaterialItemSession(event, kind, sequenceFloor);
        session.run();
        return session;
    }
}
