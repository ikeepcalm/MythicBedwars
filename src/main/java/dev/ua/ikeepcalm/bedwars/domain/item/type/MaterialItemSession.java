package dev.ua.ikeepcalm.bedwars.domain.item.type;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseSession;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.LocaleLoader;
import dev.ua.ikeepcalm.bedwars.domain.item.model.source.MaterialKind;
import dev.ua.ikeepcalm.bedwars.domain.item.service.MaterialGrantService;
import dev.ua.ikeepcalm.bedwars.domain.pathway.model.PlayerMagicData;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class MaterialItemSession extends SpecialItemUseSession {

    private final MythicBedwars plugin = MythicBedwars.getInstance();
    private final MaterialKind kind;
    private final int sequenceFloor;

    public MaterialItemSession(PlayerUseSpecialItemEvent event, MaterialKind kind, int sequenceFloor) {
        super(event);
        this.kind = kind;
        this.sequenceFloor = sequenceFloor;
    }

    @Override
    protected void handleStop() {
    }

    public void run() {
        if (redeem(getEvent().getPlayer())) {
            takeItem();
        }
        stop();
    }

    private boolean redeem(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            return false;
        }

        LocaleLoader locale = plugin.getLocaleManager();
        String pathway = plugin.getArenaPathwayManager().getPlayerPathway(player);
        String refusal = refusal(arena, pathway);
        if (refusal != null) {
            player.sendMessage(locale.formatMessage(player, "magic.shop.material." + refusal));
            return false;
        }

        PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
        int cap = plugin.getConfigManager().getMaxMaterialPurchases();
        if (data != null && cap >= 0 && data.getMaterialPurchaseCount() >= cap) {
            player.sendMessage(locale.formatMessage(player, "magic.shop.material.limit_reached", "limit", cap));
            return false;
        }

        MaterialGrantService materials = plugin.getMaterialGrantService();
        ItemStack granted = switch (kind) {
            case CHARACTERISTIC -> materials.rollCharacteristic(sequenceFloor);
            case INGREDIENT -> materials.rollIngredient(pathway, sequenceFloor);
        };
        if (granted == null) {
            player.sendMessage(locale.formatMessage(player, "magic.shop.material.unavailable"));
            return false;
        }

        materials.give(player, granted);
        if (data != null) {
            data.incrementMaterialPurchase();
        }
        player.sendMessage(locale.formatMessage(player, "magic.shop.material.received." + kind.key()));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.3f);
        return true;
    }

    private String refusal(Arena arena, String pathway) {
        if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            return "no_magic";
        }
        if (!plugin.getConfigManager().isShopMaterialsEnabled()) {
            return "disabled";
        }
        if (pathway == null) {
            return "no_pathway";
        }
        if (!plugin.getMaterialGrantService().mayBuyMaterials(pathway)) {
            return "wrong_pathway";
        }
        return null;
    }
}
