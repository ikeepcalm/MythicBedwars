package dev.ua.ikeepcalm.bedwars.domain.voting.service;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.VotingSession;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.source.MagicMode;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VotingManager {

    private static final int[] SLOTS_THREE = {2, 4, 6};
    private static final int[] SLOTS_TWO = {3, 5};

    private final MythicBedwars plugin;
    private final NamespacedKey ballotKey;
    private final Map<String, VotingSession> arenaSessions = new ConcurrentHashMap<>();
    private final Map<String, MagicMode> votingResults = new ConcurrentHashMap<>();

    public VotingManager(MythicBedwars plugin) {
        this.plugin = plugin;
        this.ballotKey = new NamespacedKey(plugin, "vote_mode");
    }

    public void startVoting(Arena arena) {
        String name = arena.getName();
        if (plugin.isEventArena(name)) {
            // An event arena keeps the mode seeded at reservation; resolving again would re-roll RANDOM.
            votingResults.computeIfAbsent(name, ignored -> plugin.getConfigManager().resolveEventMagicMode());
            return;
        }

        if (!plugin.getConfigManager().isGloballyEnabled()
            || !plugin.getConfigManager().isArenaEnabled(name)
            || !plugin.getConfigManager().isVotingEnabled()) {
            votingResults.put(name, plugin.getConfigManager().getDefaultMagicMode());
            return;
        }

        if (arenaSessions.containsKey(name)) {
            return;
        }

        VotingSession session = new VotingSession(arena, plugin);
        votingResults.remove(name);
        arenaSessions.put(name, session);
        session.start();

        for (Player player : arena.getPlayers()) {
            giveVotingItems(player, arena);
        }
    }

    public void giveVotingItems(Player player, Arena arena) {
        VotingSession session = arenaSessions.get(arena.getName());
        if (session == null || !session.isActive()) {
            return;
        }

        List<ItemStack> items = session.ballot().stream().map(mode -> createVotingItem(player, mode)).toList();
        int[] slots = items.size() >= 3 ? SLOTS_THREE : SLOTS_TWO;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Arena current = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
            if (!player.isOnline() || current == null || !current.getName().equals(arena.getName())
                || !hasActiveVoting(arena.getName())) {
                return;
            }

            for (int i = 0; i < items.size() && i < slots.length; i++) {
                player.getInventory().setItem(slots[i], items.get(i));
            }
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.voting.instructions"));
        }, plugin.getConfigManager().getVotingItemDelay() * 20L);
    }

    private ItemStack createVotingItem(Player player, MagicMode mode) {
        ItemStack item = new ItemStack(mode.getBallotMaterial());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plugin.getLocaleManager()
                .formatMessage(player, "magic.voting.option." + mode.key() + ".name")
                .color(mode.getColor())
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(plugin.getLocaleManager()
                .formatMessage(player, "magic.voting.option." + mode.key() + ".description")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(ballotKey, PersistentDataType.STRING, mode.name());

        item.setItemMeta(meta);
        return item;
    }

    public void removeVotingItems(Player player) {
        for (int slot : SLOTS_THREE) {
            clearBallotSlot(player, slot);
        }
        for (int slot : SLOTS_TWO) {
            clearBallotSlot(player, slot);
        }
    }

    private void clearBallotSlot(Player player, int slot) {
        if (readBallot(player.getInventory().getItem(slot)) != null) {
            player.getInventory().setItem(slot, null);
        }
    }

    public MagicMode readBallot(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String raw = item.getPersistentDataContainer().get(ballotKey, PersistentDataType.STRING);
        return MagicMode.fromId(raw, null);
    }

    public void handleVoteClick(Player player, MagicMode mode) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;

        VotingSession session = arenaSessions.get(arena.getName());
        if (session == null || !session.isActive()) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.voting.not_active"));
            return;
        }
        if (!session.ballot().contains(mode)) {
            return;
        }

        session.castVote(player.getUniqueId(), mode);
        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.voting.voted." + mode.key()));
    }

    public boolean hasActiveVoting(String arenaName) {
        VotingSession session = arenaSessions.get(arenaName);
        return session != null && session.isActive();
    }

    public MagicMode getMagicMode(String arenaName) {
        MagicMode result = votingResults.get(arenaName);
        if (result != null) {
            return result;
        }
        VotingSession session = arenaSessions.get(arenaName);
        return session != null ? session.getResult() : plugin.getConfigManager().getDefaultMagicMode();
    }

    public boolean isMagicEnabled(String arenaName) {
        return getMagicMode(arenaName).isMagicEnabled();
    }

    public boolean isPerPlayerPathways(String arenaName) {
        return getMagicMode(arenaName).isPerPlayer();
    }

    public void endVoting(Arena arena) {
        VotingSession session = arenaSessions.remove(arena.getName());
        if (session == null) {
            return;
        }

        session.end();
        votingResults.put(arena.getName(), session.getResult());
        plugin.log("Voting ended for arena {} - magic mode {}", arena.getName(), session.getResult());

        for (Player player : arena.getPlayers()) {
            removeVotingItems(player);
        }
    }

    public VotingSession getVotingSession(String arenaName) {
        return arenaSessions.get(arenaName);
    }

    public void cleanupArena(String arenaName) {
        VotingSession session = arenaSessions.remove(arenaName);
        if (session != null) {
            session.cancel();
        }
        votingResults.remove(arenaName);
    }

    public void setMagicMode(String arenaName, MagicMode mode) {
        votingResults.put(arenaName, mode);
    }
}
