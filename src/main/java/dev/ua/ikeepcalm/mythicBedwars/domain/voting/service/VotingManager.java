package dev.ua.ikeepcalm.mythicBedwars.domain.voting.service;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.voting.model.VotingSession;
import dev.ua.ikeepcalm.mythicBedwars.gui.VotingGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VotingManager {

    private static final Logger log = LoggerFactory.getLogger(VotingManager.class);
    private final MythicBedwars plugin;
    private final Map<String, VotingSession> arenaSessions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> votingResults = new ConcurrentHashMap<>();

    public VotingManager(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    public void startVoting(Arena arena) {
        if (!plugin.getConfigManager().isGloballyEnabled() ||
            !plugin.getConfigManager().isArenaEnabled(arena.getName()) ||
            !plugin.getConfigManager().isVotingEnabled()) {
            log.info("Voting skipped for arena {} - voting or plugin disabled", arena.getName());
            votingResults.put(arena.getName(), true);
            return;
        }

        if (arenaSessions.containsKey(arena.getName())) {
            log.warn("Voting already active for arena: {}", arena.getName());
            return;
        }

        VotingSession session = new VotingSession(arena, plugin);
        arenaSessions.put(arena.getName(), session);
        session.start();

        log.info("Voting started for arena: {} with {} players", arena.getName(), arena.getPlayers().size());

        for (Player player : arena.getPlayers()) {
            giveVotingItems(player, arena);
        }
    }

    public void giveVotingItems(Player player, Arena arena) {
        if (arena == null || !hasActiveVoting(arena.getName())) {
            log.debug("Cannot give voting items to {} - no active voting", player.getName());
            return;
        }

        ItemStack yesItem = createVotingItem(Material.LIME_DYE,
                plugin.getLocaleManager().getMessage("magic.voting.yes_item"),
                plugin.getLocaleManager().getMessage("magic.voting.yes_description"));

        ItemStack noItem = createVotingItem(Material.RED_DYE,
                plugin.getLocaleManager().getMessage("magic.voting.no_item"),
                plugin.getLocaleManager().getMessage("magic.voting.no_description"));

        int delayTicks = plugin.getConfigManager().getVotingItemDelay() * 20;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                Arena currentArena = de.marcely.bedwars.api.BedwarsAPI.getGameAPI().getArenaByPlayer(player);
                if (currentArena == null || !currentArena.getName().equals(arena.getName())) {
                    log.debug("Player {} no longer in arena, skipping voting items", player.getName());
                    return;
                }

                if (!hasActiveVoting(arena.getName())) {
                    log.debug("Voting no longer active for arena {}, skipping items for {}", arena.getName(), player.getName());
                    return;
                }

                player.getInventory().setItem(3, yesItem);
                player.getInventory().setItem(5, noItem);
                log.debug("Gave voting items to player: {}", player.getName());

                player.sendMessage(Component.text(plugin.getLocaleManager().getMessage("magic.voting.instructions"), NamedTextColor.GRAY));
            }
        }.runTaskLater(plugin, delayTicks);
    }

    private ItemStack createVotingItem(Material material, String name, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(Component.text(description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    public void removeVotingItems(Player player) {
        player.getInventory().setItem(3, null);
        player.getInventory().setItem(5, null);
    }

    public void handleVoteClick(Player player, Material material) {
        Arena arena = de.marcely.bedwars.api.BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;

        VotingSession session = arenaSessions.get(arena.getName());
        if (session == null || !session.isActive()) return;

        boolean vote = material == Material.LIME_DYE;
        session.castVote(player.getUniqueId(), vote);

        String messageKey = vote ? "magic.voting.voted_yes" : "magic.voting.voted_no";
        player.sendMessage(Component.text(plugin.getLocaleManager().getMessage(messageKey),
                vote ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    public boolean hasActiveVoting(String arenaName) {
        VotingSession session = arenaSessions.get(arenaName);
        return session != null && session.isActive();
    }

    public boolean isMagicEnabled(String arenaName) {
        Boolean result = votingResults.get(arenaName);
        if (result != null) {
            return result;
        }

        VotingSession session = arenaSessions.get(arenaName);
        if (session != null) {
            return session.isMagicEnabled();
        }

        return true;
    }

    public void endVoting(Arena arena) {
        VotingSession session = arenaSessions.get(arena.getName());
        if (session != null) {
            session.end();

            boolean magicEnabled = session.isMagicEnabled();
            votingResults.put(arena.getName(), magicEnabled);

            log.info("Voting ended for arena: {} - Magic {}", arena.getName(),
                    magicEnabled ? "ENABLED" : "DISABLED");

            for (Player player : arena.getPlayers()) {
                removeVotingItems(player);
            }

            arenaSessions.remove(arena.getName());
        }
    }

    public VotingSession getVotingSession(String arenaName) {
        return arenaSessions.get(arenaName);
    }

    public void cleanupArena(String arenaName) {
        arenaSessions.remove(arenaName);
        votingResults.remove(arenaName);
        log.debug("Cleaned up voting data for arena: {}", arenaName);
    }

    public void setMagicEnabled(String arenaName, boolean enabled) {
        votingResults.put(arenaName, enabled);
        log.info("Force set magic {} for arena: {}", enabled ? "ENABLED" : "DISABLED", arenaName);
    }

    public VotingGUI getVotingGUI() {
        return new VotingGUI(plugin);
    }
}