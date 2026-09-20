package dev.ua.ikeepcalm.bedwars.domain.voting.service;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.MagicMode;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.VotingSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VotingManager {

    /**
     * Stamped on each ballot item so the click handler reads the vote off the item itself.
     *
     * <p>The old handler inferred the vote from the item's material, which only worked while there
     * were exactly two of them and no other dye could reach a lobby hotbar. Three options make that
     * guess both longer and wronger; the item says what it is instead.
     */
    private static final String BALLOT_KEY = "vote_mode";

    /**
     * Hotbar slots the ballot occupies, laid out around the centre so a two-option and a
     * three-option ballot both read as deliberate rather than left-aligned.
     */
    private static final int[] SLOTS_THREE = {2, 4, 6};
    private static final int[] SLOTS_TWO = {3, 5};

    private final MythicBedwars plugin;
    private final NamespacedKey ballotKey;
    private final Map<String, VotingSession> arenaSessions = new ConcurrentHashMap<>();
    private final Map<String, MagicMode> votingResults = new ConcurrentHashMap<>();

    public VotingManager(MythicBedwars plugin) {
        this.plugin = plugin;
        this.ballotKey = new NamespacedKey(plugin, BALLOT_KEY);
    }

    public void startVoting(Arena arena) {
        // Defence in depth: an event arena must never get a session, because endVoting() would then
        // overwrite the pre-seeded result and could turn magic off mid-event.
        if (plugin.isEventArena(arena.getName())) {
            // Keep whatever the orchestrator already seeded when it reserved the arena. Re-reading
            // the config here would resolve magic-mode: RANDOM a second time and could start the
            // match in a different mode from the one the event was accepted as.
            MagicMode forced = votingResults.computeIfAbsent(arena.getName(),
                    // No seed only when the host never reserved this arena itself - a recovered or
                    // hand-forced event. Resolving now is then the only answer available.
                    name -> plugin.getConfigManager().resolveEventMagicMode());

            MythicBedwars.getInstance().log("Voting bypassed for event arena {} (magic mode {}).",
                    arena.getName(), forced);
            return;
        }

        if (!plugin.getConfigManager().isGloballyEnabled() ||
            !plugin.getConfigManager().isArenaEnabled(arena.getName()) ||
            !plugin.getConfigManager().isVotingEnabled()) {
            MythicBedwars.getInstance().log("Voting skipped for arena " + arena.getName() + " - voting or plugin disabled");
            votingResults.put(arena.getName(), plugin.getConfigManager().getDefaultMagicMode());
            return;
        }

        if (arenaSessions.containsKey(arena.getName())) {
            MythicBedwars.getInstance().log("Voting already active for arena: {}", arena.getName());
            return;
        }

        VotingSession session = new VotingSession(arena, plugin);
        arenaSessions.put(arena.getName(), session);
        session.start();

        MythicBedwars.getInstance().log("Voting started for arena: " + arena.getName() + " with " + arena.getPlayers().size() + " players");

        for (Player player : arena.getPlayers()) {
            giveVotingItems(player, arena);
        }
    }

    public void giveVotingItems(Player player, Arena arena) {
        if (arena == null || !hasActiveVoting(arena.getName())) {
            MythicBedwars.getInstance().log("Cannot give voting items to {} - no active voting", player.getName());
            return;
        }

        VotingSession session = arenaSessions.get(arena.getName());
        if (session == null) {
            return;
        }

        List<MagicMode> ballot = session.ballot();
        List<ItemStack> items = new ArrayList<>(ballot.size());
        for (MagicMode mode : ballot) {
            items.add(createVotingItem(mode));
        }

        int[] slots = ballot.size() >= 3 ? SLOTS_THREE : SLOTS_TWO;

        int delayTicks = plugin.getConfigManager().getVotingItemDelay() * 20;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                Arena currentArena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
                if (currentArena == null || !currentArena.getName().equals(arena.getName())) {
                    MythicBedwars.getInstance().log("Player {} no longer in arena, skipping voting items", player.getName());
                    return;
                }

                if (!hasActiveVoting(arena.getName())) {
                    MythicBedwars.getInstance().log("Voting no longer active for arena {}, skipping items for {}", arena.getName(), player.getName());
                    return;
                }

                for (int i = 0; i < items.size() && i < slots.length; i++) {
                    player.getInventory().setItem(slots[i], items.get(i));
                }

                MythicBedwars.getInstance().log("Gave {} voting item(s) to player: {}", items.size(), player.getName());

                player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.voting.instructions"));
            }
        }.runTaskLater(plugin, delayTicks);
    }

    private ItemStack createVotingItem(MagicMode mode) {
        Material material = switch (mode) {
            case TEAM -> Material.LIME_DYE;
            case INDIVIDUAL -> Material.PURPLE_DYE;
            case OFF -> Material.RED_DYE;
        };

        NamedTextColor color = switch (mode) {
            case TEAM -> NamedTextColor.GREEN;
            case INDIVIDUAL -> NamedTextColor.LIGHT_PURPLE;
            case OFF -> NamedTextColor.RED;
        };

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plugin.getLocaleManager()
                .formatMessage("magic.voting.option." + mode.key() + ".name")
                .color(color)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(List.of(plugin.getLocaleManager()
                .formatMessage("magic.voting.option." + mode.key() + ".description")
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

    /**
     * Clears a slot only when it still holds a ballot item. Both slot layouts are swept on cleanup,
     * and they overlap with ordinary hotbar slots — blanking them unconditionally would delete
     * whatever a player had moved there.
     */
    private void clearBallotSlot(Player player, int slot) {
        ItemStack current = player.getInventory().getItem(slot);
        if (current != null && readBallot(current) != null) {
            player.getInventory().setItem(slot, null);
        }
    }

    /**
     * @return the mode this item votes for, or {@code null} when it is not a ballot item
     */
    public MagicMode readBallot(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(ballotKey, PersistentDataType.STRING);

        return raw == null ? null : MagicMode.fromId(raw, null);
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

        player.sendMessage(plugin.getLocaleManager()
                .formatMessage(player, "magic.voting.voted." + mode.key()));
    }

    public boolean hasActiveVoting(String arenaName) {
        VotingSession session = arenaSessions.get(arenaName);
        return session != null && session.isActive();
    }

    /**
     * @return the mode this arena is running, defaulting to the configured norm for an arena nobody
     * has voted on
     */
    public MagicMode getMagicMode(String arenaName) {
        MagicMode result = votingResults.get(arenaName);
        if (result != null) {
            return result;
        }

        VotingSession session = arenaSessions.get(arenaName);
        if (session != null) {
            return session.getResult();
        }

        return plugin.getConfigManager().getDefaultMagicMode();
    }

    public boolean isMagicEnabled(String arenaName) {
        return getMagicMode(arenaName).isMagicEnabled();
    }

    /**
     * @return whether this arena hands pathways out per player rather than per team
     */
    public boolean isPerPlayerPathways(String arenaName) {
        return getMagicMode(arenaName).isPerPlayer();
    }

    public void endVoting(Arena arena) {
        VotingSession session = arenaSessions.get(arena.getName());
        if (session != null) {
            session.end();

            MagicMode mode = session.getResult();
            votingResults.put(arena.getName(), mode);

            MythicBedwars.getInstance().log("Voting ended for arena: {} - magic mode {}", arena.getName(), mode);

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
        MythicBedwars.getInstance().log("Cleaned up voting data for arena: {}", arenaName);
    }

    /**
     * Forces the round's mode, bypassing the vote. Used by the event orchestrator and by
     * {@code /mb voting}.
     */
    public void setMagicMode(String arenaName, MagicMode mode) {
        votingResults.put(arenaName, mode);
        MythicBedwars.getInstance().log("Force set magic mode {} for arena: {}", mode, arenaName);
    }

    /**
     * Boolean form kept for the callers that only ever meant on-or-off; an "on" resolves to the
     * configured default enabled mode rather than assuming teams.
     */
    public void setMagicEnabled(String arenaName, boolean enabled) {
        setMagicMode(arenaName, enabled
                ? plugin.getConfigManager().getDefaultMagicMode().isMagicEnabled()
                    ? plugin.getConfigManager().getDefaultMagicMode()
                    : MagicMode.TEAM
                : MagicMode.OFF);
    }
}
