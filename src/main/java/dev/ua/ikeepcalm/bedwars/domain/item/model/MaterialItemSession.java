package dev.ua.ikeepcalm.bedwars.domain.item.model;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseSession;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.core.MaterialGrantService;
import dev.ua.ikeepcalm.bedwars.domain.core.PathwayManager;
import dev.ua.ikeepcalm.bedwars.domain.item.model.source.MaterialKind;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Redeeming one purchased crafting input.
 *
 * <p>The shop hands the player a voucher rather than the material itself, for the same reason the
 * sequence potions do: what the item resolves to depends on who is holding it and what the round
 * has assigned them, neither of which MBedwars knows when it builds the shop page at boot.
 *
 * <p>The item is only taken once something was actually produced. A roll that comes back empty —
 * a pathway with nothing registered in range, a COI that failed to answer — leaves the player
 * holding what they paid for.
 */
public class MaterialItemSession extends SpecialItemUseSession {

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
        MythicBedwars plugin = MythicBedwars.getInstance();
        Player player = getEvent().getPlayer();

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            stop();
            return;
        }

        if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.shop.material.no_magic"));
            stop();
            return;
        }

        if (!plugin.getConfigManager().isShopMaterialsEnabled()) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.shop.material.disabled"));
            stop();
            return;
        }

        String pathway = plugin.getArenaPathwayManager().getPlayerPathway(player);
        if (pathway == null) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.shop.material.no_pathway"));
            stop();
            return;
        }

        MaterialGrantService materials = plugin.getMaterialGrantService();

        if (!materials.mayBuyMaterials(pathway)) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                    "magic.shop.material.wrong_pathway"));
            stop();
            return;
        }

        PathwayManager.PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
        int cap = plugin.getConfigManager().getMaxMaterialPurchases();
        if (data != null && cap >= 0 && data.getMaterialPurchaseCount() >= cap) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                    "magic.shop.material.limit_reached", "limit", cap));
            stop();
            return;
        }

        ItemStack granted = switch (kind) {
            case CHARACTERISTIC -> materials.rollCharacteristic(sequenceFloor);
            case INGREDIENT -> materials.rollIngredient(pathway, sequenceFloor);
        };

        if (granted == null) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.shop.material.unavailable"));
            stop();
            return;
        }

        materials.give(player, granted);

        if (data != null) {
            data.incrementMaterialPurchase();
        }

        player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                "magic.shop.material.received." + kind.key()));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.3f);

        takeItem();
        stop();
    }
}
