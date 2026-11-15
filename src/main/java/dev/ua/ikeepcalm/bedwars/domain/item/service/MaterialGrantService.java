package dev.ua.ikeepcalm.bedwars.domain.item.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.coi.api.item.ItemApi;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class MaterialGrantService {

    private final MythicBedwars plugin;

    public ItemStack rollCharacteristic(int sequenceFloor) {
        List<String> pathways = allowedPathways();
        Collections.shuffle(pathways);

        for (String pathway : pathways) {
            for (int sequence : shuffledSequences(sequenceFloor)) {
                Optional<ItemStack> item = plugin.getCircleOfImaginationApi().items()
                        .create("characteristic?pathway=" + pathway + "&seq=" + sequence, 1);
                if (item.isPresent()) {
                    return SandboxItems.tag(item.get());
                }
            }
        }
        return null;
    }

    public ItemStack rollIngredient(String pathway, int sequenceFloor) {
        for (int sequence : shuffledSequences(sequenceFloor)) {
            ItemApi items = plugin.getCircleOfImaginationApi().items();
            List<ItemStack> usable = Stream.concat(items.mainIngredients(pathway, sequence).stream(),
                            items.supplementaryIngredients(pathway, sequence).stream())
                    .filter(candidate -> candidate != null && !candidate.getType().isAir())
                    .toList();
            if (!usable.isEmpty()) {
                return SandboxItems.tag(usable.get(ThreadLocalRandom.current().nextInt(usable.size())).clone());
            }
        }
        return null;
    }

    private List<Integer> shuffledSequences(int sequenceFloor) {
        List<Integer> sequences = new ArrayList<>();
        for (int sequence = Math.clamp(sequenceFloor, 0, 9); sequence <= 9; sequence++) {
            sequences.add(sequence);
        }
        Collections.shuffle(sequences);
        return sequences;
    }

    public void give(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    public boolean mayBuyMaterials(String pathway) {
        return isListed(pathway, plugin.getConfigManager().getMaterialShopPathways());
    }

    public boolean isCraftingPathway(String pathway) {
        return isListed(pathway, plugin.getConfigManager().getCraftingPathways());
    }

    private boolean isListed(String pathway, List<String> configured) {
        return pathway != null && (configured.isEmpty() || configured.stream().anyMatch(pathway::equalsIgnoreCase));
    }

    private List<String> allowedPathways() {
        List<String> names = plugin.getCircleOfImaginationApi().progression().pathways();
        List<String> allowed = new ArrayList<>(names.stream().filter(plugin.getConfigManager()::isPathwayAllowed).toList());
        return allowed.isEmpty() ? new ArrayList<>(names) : allowed;
    }
}
