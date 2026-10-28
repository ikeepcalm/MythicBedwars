package dev.ua.ikeepcalm.bedwars.domain.arena.listener;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaBedBreakEvent;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.ArenaUnloadEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerKillPlayerEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerTeamChangeEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.LocaleLoader;
import dev.ua.ikeepcalm.bedwars.domain.item.service.MaterialGrantService;
import dev.ua.ikeepcalm.bedwars.domain.item.service.SandboxItems;
import dev.ua.ikeepcalm.bedwars.domain.pathway.model.PlayerMagicData;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class ArenaListener implements Listener {

    private final MythicBedwars plugin;
    private final CircleOfImaginationAPI circleOfImaginationAPI;
    private final Map<String, Long> arenaStartTimes = new HashMap<>();

    public ArenaListener(MythicBedwars plugin) {
        this.plugin = plugin;
        this.circleOfImaginationAPI = plugin.getCircleOfImaginationAPI();
    }

    @EventHandler
    public void onArenaStatusChange(ArenaStatusChangeEvent event) {
        Arena arena = event.getArena();
        switch (event.getNewStatus()) {
            case LOBBY -> {
                if (event.getOldStatus() != ArenaStatus.LOBBY && isVotingArena(arena)) {
                    plugin.getVotingManager().startVoting(arena);
                }
            }
            case RUNNING -> startRound(arena);
            case RESETTING -> endRound(arena);
            case STOPPED -> {
                endRound(arena);
                plugin.getVotingManager().cleanupArena(arena.getName());
            }
            default -> {
            }
        }
    }

    private boolean isVotingArena(Arena arena) {
        return plugin.getConfigManager().isGloballyEnabled()
               && plugin.getConfigManager().isArenaEnabled(arena.getName())
               && !plugin.isEventArena(arena.getName());
    }

    private void startRound(Arena arena) {
        arenaStartTimes.put(arena.getName(), System.currentTimeMillis());
        plugin.getVotingManager().endVoting(arena);

        if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            return;
        }

        plugin.getArenaEnvironmentService().apply(arena);
        plugin.getArenaPathwayManager().assignPathwaysToTeams(arena);

        for (Player player : arena.getPlayers()) {
            Team team = arena.getPlayerTeam(player);
            if (team != null) {
                plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
                continue;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Team delayedTeam = arena.getPlayerTeam(player);
                if (delayedTeam != null && plugin.getVotingManager().isMagicEnabled(arena.getName())) {
                    plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, delayedTeam);
                }
            }, 20L);
        }
    }

    private void endRound(Arena arena) {
        plugin.getArenaEnvironmentService().clear(arena);

        Long startTime = arenaStartTimes.remove(arena.getName());
        if (startTime != null) {
            plugin.getStatisticsManager().recordGameDuration(arena, System.currentTimeMillis() - startTime);
        }
        plugin.getArenaPathwayManager().cleanupArena(arena);
    }

    @EventHandler
    public void onArenaUnload(ArenaUnloadEvent event) {
        Arena arena = event.getArena();
        plugin.getArenaEnvironmentService().clear(arena);
        plugin.getArenaPathwayManager().cleanupArena(arena);
        plugin.getVotingManager().cleanupArena(arena.getName());
        arenaStartTimes.remove(arena.getName());
    }

    @EventHandler
    public void onRoundEnd(RoundEndEvent event) {
        Arena arena = event.getArena();
        if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            return;
        }

        plugin.getStatisticsManager().recordGameEnd(arena, event.getWinnerTeam());
        for (Player player : arena.getPlayers()) {
            sendMagicSummary(player, arena, event.getWinnerTeam());
        }
    }

    private void sendMagicSummary(Player player, Arena arena, Team winner) {
        PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
        if (data == null) {
            return;
        }

        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);
        if (beyonderData == null || beyonderData.pathways().isEmpty()) {
            return;
        }

        PathwayData primary = beyonderData.pathways().getFirst();
        int neededActing = primary.neededActing();
        double actingPercent = neededActing > 0 ? (double) primary.acting() / neededActing * 100 : 0;

        LocaleLoader locale = plugin.getLocaleManager();
        player.sendMessage(locale.formatMessage(player, "magic.summary.header"));
        player.sendMessage(locale.formatMessage(player, "magic.summary.pathway", "pathway", data.getPathway()));
        player.sendMessage(locale.formatMessage(player, "magic.summary.sequence", "sequence", primary.lowestSequenceLevel()));
        player.sendMessage(locale.formatMessage(player, "magic.summary.acting", "percent", String.format("%.1f", actingPercent)));
        if (winner != null && winner.equals(arena.getPlayerTeam(player))) {
            player.sendMessage(locale.formatMessage(player, "magic.summary.victory"));
        }
    }

    @EventHandler
    public void onPlayerJoinArena(PlayerJoinArenaEvent event) {
        Player player = event.getPlayer();
        Arena arena = event.getArena();

        if (arena.getStatus() == ArenaStatus.LOBBY && !plugin.isEventArena(arena.getName())) {
            if (plugin.getVotingManager().hasActiveVoting(arena.getName())) {
                plugin.getVotingManager().giveVotingItems(player, arena);
            } else if (isVotingArena(arena)) {
                plugin.getVotingManager().startVoting(arena);
                plugin.getVotingManager().giveVotingItems(player, arena);
            }
        }

        if (arena.getStatus() == ArenaStatus.RUNNING && plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            Team team = arena.getPlayerTeam(player);
            if (team != null) {
                plugin.getArenaPathwayManager().initializePlayerMagic(player, arena, team);
            }
        }
    }

    @EventHandler
    public void onPlayerQuitArena(PlayerQuitArenaEvent event) {
        Player player = event.getPlayer();
        Arena arena = event.getArena();

        if (arena.getStatus() == ArenaStatus.LOBBY) {
            plugin.getVotingManager().removeVotingItems(player);
        }

        plugin.getProjectileCooldownListener().clear(player);

        int stripped = SandboxItems.strip(player);
        if (stripped > 0) {
            plugin.log("Reclaimed {} match-issued item stack(s) from {} on leaving {}.",
                    stripped, player.getName(), arena.getName());
        }

        KickReason reason = event.getReason();
        boolean leftForGood = reason == KickReason.GAME_LOSE || reason == KickReason.GAME_END
                              || reason == KickReason.ARENA_STOP || reason == KickReason.LEAVE
                              || reason == KickReason.PLUGIN_STOP;

        if (leftForGood || arena.getStatus() != ArenaStatus.RUNNING) {
            plugin.getArenaPathwayManager().cleanupPlayer(player);
        } else {
            plugin.getArenaPathwayManager().markPlayerInactive(player);
        }
    }

    @EventHandler
    public void onPlayerTeamChange(PlayerTeamChangeEvent event) {
        Arena arena = event.getArena();
        Team team = event.getNewTeam();

        if (arena.getStatus() == ArenaStatus.RUNNING && team != null && plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            plugin.getArenaPathwayManager().initializePlayerMagic(event.getPlayer(), arena, team);
        }
    }

    @EventHandler
    public void onPlayerKill(PlayerKillPlayerEvent event) {
        if (!plugin.getVotingManager().isMagicEnabled(event.getArena().getName())) {
            return;
        }

        Player killer = event.getKiller();
        if (killer != null) {
            double multiplier = event.isFatalDeath()
                    ? plugin.getConfigManager().getFinalKillActingMultiplier()
                    : plugin.getConfigManager().getKillActingMultiplier();
            grantActing(killer, multiplier);
            grantCraftingDrop(killer, event.getPlayer(), event.isFatalDeath());
        }

        if (!event.isFatalDeath()) {
            applyDeathPenalty(event.getPlayer());
        }
    }

    private void grantActing(Player player, double multiplier) {
        if (!circleOfImaginationAPI.isVirtualBeyonder(player)) {
            return;
        }

        int actingAmount = (int) (100 * multiplier);
        for (PathwayData pathway : circleOfImaginationAPI.getPathwayData(player)) {
            circleOfImaginationAPI.addActing(player, pathway.name(), actingAmount);
        }
    }

    private void applyDeathPenalty(Player victim) {
        double penaltyFraction = plugin.getConfigManager().getDeathActingPenalty();
        if (victim == null || penaltyFraction <= 0 || !circleOfImaginationAPI.isVirtualBeyonder(victim)) {
            return;
        }

        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(victim);
        if (beyonderData == null || beyonderData.pathways().isEmpty()) {
            return;
        }

        int currentActing = beyonderData.pathways().getFirst().acting();
        int penalty = (int) (currentActing * penaltyFraction);
        if (penalty > 0) {
            circleOfImaginationAPI.setPrimaryActing(victim, Math.max(0, currentActing - penalty));
            victim.sendMessage(plugin.getLocaleManager().formatMessage(victim, "magic.messages.death_penalty", "amount", penalty));
        }
    }

    private void grantCraftingDrop(Player killer, Player victim, boolean finalKill) {
        if (!plugin.getConfigManager().isCraftingKillDropEnabled()) {
            return;
        }

        MaterialGrantService materials = plugin.getMaterialGrantService();
        String pathway = plugin.getArenaPathwayManager().getPlayerPathway(killer);
        if (pathway == null || !materials.isCraftingPathway(pathway)) {
            return;
        }

        double chance = finalKill
                ? plugin.getConfigManager().getCraftingFinalKillDropChance()
                : plugin.getConfigManager().getCraftingKillDropChance();
        if (chance <= 0.0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }

        ItemStack ingredient = materials.rollIngredient(pathway, victimSequence(victim));
        if (ingredient == null) {
            return;
        }

        materials.give(killer, ingredient);
        killer.sendMessage(plugin.getLocaleManager().formatMessage(killer, "magic.messages.ingredient_harvested"));
    }

    private int victimSequence(Player victim) {
        BeyonderData data = victim == null ? null : circleOfImaginationAPI.getBeyonderData(victim);
        if (data == null || data.pathways().isEmpty()) {
            return 9;
        }
        return data.lowestSequence();
    }

    @EventHandler
    public void onBedBreak(ArenaBedBreakEvent event) {
        Player breaker = event.getPlayer();
        if (breaker == null || !plugin.getVotingManager().isMagicEnabled(event.getArena().getName())) {
            return;
        }

        grantActing(breaker, plugin.getConfigManager().getBedBreakActingMultiplier());
    }
}
