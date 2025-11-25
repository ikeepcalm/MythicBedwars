package dev.ua.ikeepcalm.mythicBedwars.gui;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.voting.model.VotingSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

public class VotingGUI {

    private final MythicBedwars plugin;

    public VotingGUI(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    public void openVotingGUI(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;

        VotingSession session = plugin.getVotingManager().getVotingSession(arena.getName());
        if (session == null || !session.isActive()) {
            player.sendMessage(Component.text(plugin.getLocaleManager().getMessage("magic.voting.not_active"), NamedTextColor.RED));
            return;
        }

        String title = plugin.getLocaleManager().getMessage("magic.voting.gui_title");
        Inventory gui = Bukkit.createInventory(null, 27, Component.text(title));

        fillBorder(gui);

        ItemStack yesItem = createVoteItem(Material.LIME_WOOL,
                plugin.getLocaleManager().getMessage("magic.voting.enable_magic"),
                Arrays.asList(
                        plugin.getLocaleManager().getMessage("magic.voting.enable_description"),
                        "",
                        plugin.getLocaleManager().formatMessage("magic.voting.current_votes", "votes", session.getYesVotes())
                ),
                session.hasVoted(player.getUniqueId()) && session.getVote(player.getUniqueId())
        );

        ItemStack noItem = createVoteItem(Material.RED_WOOL,
                plugin.getLocaleManager().getMessage("magic.voting.disable_magic"),
                Arrays.asList(
                        plugin.getLocaleManager().getMessage("magic.voting.disable_description"),
                        "",
                        plugin.getLocaleManager().formatMessage("magic.voting.current_votes", "votes", session.getNoVotes())
                ),
                session.hasVoted(player.getUniqueId()) && !session.getVote(player.getUniqueId())
        );

        ItemStack infoItem = createInfoItem(session);

        gui.setItem(11, yesItem);
        gui.setItem(15, noItem);
        gui.setItem(13, infoItem);

        player.openInventory(gui);
    }

    private void fillBorder(Inventory gui) {
        ItemStack border = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = border.getItemMeta();
        meta.displayName(Component.text(" "));
        border.setItemMeta(meta);

        for (int i = 0; i < 9; i++) {
            gui.setItem(i, border);
        }
        for (int i = 18; i < 27; i++) {
            gui.setItem(i, border);
        }
        gui.setItem(9, border);
        gui.setItem(17, border);
    }

    private ItemStack createVoteItem(Material material, String name, List<String> lore, boolean selected) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        NamedTextColor nameColor = selected ? NamedTextColor.GREEN : NamedTextColor.WHITE;
        meta.displayName(Component.text(name, nameColor).decoration(TextDecoration.ITALIC, false));

        List<TextComponent> loreComponents = lore.stream()
                .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList();

        if (selected) {
            loreComponents = new java.util.ArrayList<>(loreComponents);
            loreComponents.add(Component.empty());
            loreComponents.add(Component.text(plugin.getLocaleManager().getMessage("magic.voting.selected"), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(loreComponents);
        item.setItemMeta(meta);

        return item;
    }

    private ItemStack createInfoItem(VotingSession session) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(plugin.getLocaleManager().getMessage("magic.voting.vote_info"), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = Arrays.asList(
                Component.text(plugin.getLocaleManager().formatMessage("magic.voting.yes_votes", "votes", session.getYesVotes()), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text(plugin.getLocaleManager().formatMessage("magic.voting.no_votes", "votes", session.getNoVotes()), NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text(plugin.getLocaleManager().getMessage("magic.voting.majority_wins"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        );

        meta.lore(lore);
        item.setItemMeta(meta);

        return item;
    }
}