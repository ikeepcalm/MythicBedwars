package dev.ua.ikeepcalm.bedwars.domain.core;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.item.service.SandboxItems;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hands out the Beyonder crafting inputs a Bedwars round has no other source for.
 *
 * <p>Several pathways — Paragon above all, and Moon after it — are built around <i>making</i>
 * things. Paragon's artifact crafting wants a characteristic plus beyonder ingredients; Moon's
 * apothecary and perfume work wants ingredients. On the survival server those come from mining
 * nodes, foundables and drops accumulated over days. A twenty-minute Bedwars match has none of
 * that, so a team that drew a crafting pathway drew a pathway with its main verb removed.
 *
 * <p>This is the missing source: purchasable from the shop, and dropped by kills. Everything it
 * produces is tagged by {@link SandboxItems}, because these are real progression items and the
 * match is played on a server players are transferred home from.
 */
public class MaterialGrantService {

    private final MythicBedwars plugin;
    private final CircleOfImaginationAPI api;

    public MaterialGrantService(MythicBedwars plugin) {
        this.plugin = plugin;
        this.api = plugin.getCircleOfImaginationAPI();
    }

    /**
     * Rolls a characteristic from a random pathway, no stronger than {@code sequenceFloor}.
     *
     * <p>Random pathway rather than the buyer's own: for Paragon the characteristic decides which
     * abilities the crafted artifact can carry, so which one you drew is the whole interest of the
     * purchase. A characteristic of your own pathway would make every craft the same craft.
     *
     * @param sequenceFloor the strongest sequence this tier may produce; the roll runs from here
     *                      up to 9, remembering that a lower number is stronger
     * @return the tagged item, or {@code null} when COI could not produce one
     */
    public ItemStack rollCharacteristic(int sequenceFloor) {
        List<String> pathways = allowedPathways();
        if (pathways.isEmpty()) {
            return null;
        }

        // Shuffled rather than one random pick: a pathway can legitimately have no characteristic
        // registered at the sequence we rolled, and giving up on the first miss would make those
        // purchases silently fail.
        Collections.shuffle(pathways);

        for (String pathway : pathways) {
            ItemStack rolled = rollCharacteristicFrom(pathway, sequenceFloor);
            if (rolled != null) {
                return rolled;
            }
        }

        return null;
    }

    private ItemStack rollCharacteristicFrom(String pathway, int sequenceFloor) {
        for (int sequence : shuffledSequences(sequenceFloor)) {
            ItemStack item = api.getChar(pathway, sequence);
            if (item != null && !item.getType().isAir()) {
                return SandboxItems.tag(item);
            }
        }

        return null;
    }

    /**
     * Rolls an ingredient from {@code pathway}, no stronger than {@code sequenceFloor}.
     *
     * <p>The buyer's own pathway here, unlike characteristics: an ingredient is only useful to the
     * recipe that calls for it, and a Moon ingredient in a Paragon's hands is litter.
     *
     * @return the tagged item, or {@code null} when the pathway has no ingredient in range
     */
    public ItemStack rollIngredient(String pathway, int sequenceFloor) {
        if (pathway == null || pathway.isBlank()) {
            return null;
        }

        for (int sequence : shuffledSequences(sequenceFloor)) {
            List<ItemStack> candidates = api.getIngredientsForSequence(pathway, sequence);
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }

            List<ItemStack> usable = new ArrayList<>(candidates.size());
            for (ItemStack candidate : candidates) {
                if (candidate != null && !candidate.getType().isAir()) {
                    usable.add(candidate);
                }
            }

            if (usable.isEmpty()) {
                continue;
            }

            ItemStack picked = usable.get(ThreadLocalRandom.current().nextInt(usable.size())).clone();
            return SandboxItems.tag(picked);
        }

        return null;
    }

    /**
     * The sequences a roll may land on, weakest-first order destroyed.
     *
     * <p>Remember the inversion: {@code 9} is the weakest Beyonder and {@code 0} the strongest, so
     * a floor of {@code 6} means "sequence 6, 7, 8 or 9" — nothing stronger than 6.
     */
    private List<Integer> shuffledSequences(int sequenceFloor) {
        int floor = Math.max(0, Math.min(9, sequenceFloor));

        List<Integer> sequences = new ArrayList<>(10 - floor);
        for (int sequence = floor; sequence <= 9; sequence++) {
            sequences.add(sequence);
        }

        Collections.shuffle(sequences);
        return sequences;
    }

    /**
     * Gives the item to the player, dropping whatever will not fit at their feet rather than
     * destroying it.
     */
    public void give(Player player, ItemStack item) {
        if (item == null) {
            return;
        }

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) {
            return;
        }

        Location at = player.getLocation();
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(at, leftover);
        }
    }

    /**
     * @return whether a player on this pathway may buy crafting inputs. An empty
     * {@code shop.materials.pathways} means anyone may, which is the shipped default: an ingredient
     * is useful to more pathways than it is useless to, and narrowing it is an operator's call.
     */
    public boolean mayBuyMaterials(String pathway) {
        if (pathway == null) {
            return false;
        }

        List<String> configured = plugin.getConfigManager().getMaterialShopPathways();
        if (configured.isEmpty()) {
            return true;
        }

        return configured.stream().anyMatch(pathway::equalsIgnoreCase);
    }

    /**
     * @return whether this pathway is one of the ones that can actually build something, and so
     * one the kill drop should feed. An empty config list means every pathway qualifies.
     */
    public boolean isCraftingPathway(String pathway) {
        if (pathway == null) {
            return false;
        }

        List<String> configured = plugin.getConfigManager().getCraftingPathways();
        if (configured.isEmpty()) {
            return true;
        }

        return configured.stream().anyMatch(pathway::equalsIgnoreCase);
    }

    private List<String> allowedPathways() {
        List<String> names = api.getAllPathwayNames();
        if (names == null || names.isEmpty()) {
            return List.of();
        }

        List<String> allowed = new ArrayList<>(names.size());
        for (String name : names) {
            if (plugin.getConfigManager().isPathwayAllowed(name)) {
                allowed.add(name);
            }
        }

        // A config that disabled everything should not also break the shop; fall back to the full
        // list rather than selling an item that can never be produced.
        return allowed.isEmpty() ? new ArrayList<>(names) : allowed;
    }
}
