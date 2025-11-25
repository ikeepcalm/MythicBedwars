package dev.ua.ikeepcalm.mythicBedwars.domain.spectator;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpectatorManager {

    private final MythicBedwars plugin;
    private final Map<UUID, SpectatorData> spectatorData = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> spectatorBossBars = new ConcurrentHashMap<>();
    private final CircleOfImaginationAPI circleOfImaginationAPI;
    private BukkitTask updateTask;

    public SpectatorManager(MythicBedwars plugin) {
        this.plugin = plugin;
        this.circleOfImaginationAPI = plugin.getCircleOfImaginationAPI();
        startUpdateTask();
    }

    private void startUpdateTask() {
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateSpectatorDisplays();
            }
        }.runTaskTimer(plugin, 20L, 20L); // Update every second
    }

    public void addSpectator(Player player, Arena arena) {
        if (!plugin.getConfigManager().isSpectatorFeaturesEnabled()) {
            return;
        }

        SpectatorData data = new SpectatorData(player.getUniqueId(), arena.getName());
        spectatorData.put(player.getUniqueId(), data);

        setupSpectatorDisplay(player, data);
        sendWelcomeMessage(player, arena);
    }

    public void removeSpectator(Player player) {
        UUID playerId = player.getUniqueId();
        SpectatorData data = spectatorData.remove(playerId);

        if (data != null) {
            cleanupSpectatorDisplay(player);
        }
    }

    private void setupSpectatorDisplay(Player player, SpectatorData data) {
        if (data.isHudEnabled()) {
            createBossBar(player);
        }

        player.sendMessage(Component.text("=== MythicBedwars Spectator Mode ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Use /mbspec to configure your spectator experience!", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("• Right-click players to view their magical status", NamedTextColor.GRAY));
        player.sendMessage(Component.text("• Boss bar shows team magical progress", NamedTextColor.GRAY));
    }

    private void cleanupSpectatorDisplay(Player player) {
        BossBar bossBar = spectatorBossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    private void sendWelcomeMessage(Player player, Arena arena) {
        Component welcome = Component.text("Now spectating: ", NamedTextColor.GRAY)
                .append(Component.text(arena.getName(), NamedTextColor.GOLD))
                .append(Component.text(" with magical enhancements!", NamedTextColor.GRAY));
        player.sendMessage(welcome);

        showTeamPathways(player, arena);
    }

    private void showTeamPathways(Player player, Arena arena) {
        Map<Team, String> teamPathways = new HashMap<>();
        for (Team team : arena.getAliveTeams()) {
            String pathway = plugin.getArenaPathwayManager().getTeamPathway(arena, team);
            if (pathway != null) {
                teamPathways.put(team, pathway);
            }
        }

        if (!teamPathways.isEmpty()) {
            player.sendMessage(Component.text("Team Pathways:", NamedTextColor.YELLOW));
            for (Map.Entry<Team, String> entry : teamPathways.entrySet()) {
                TextColor teamColor = getTeamColor(entry.getKey());
                Component line = Component.text("• ", NamedTextColor.GRAY)
                        .append(Component.text(entry.getKey().getDisplayName(), teamColor))
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(Component.text(entry.getValue(), NamedTextColor.LIGHT_PURPLE));
                player.sendMessage(line);
            }
        }
    }

    private void createBossBar(Player player) {
        BossBar bossBar = BossBar.bossBar(
                Component.text("Magical Status Loading...", NamedTextColor.LIGHT_PURPLE),
                0.0f,
                BossBar.Color.PURPLE,
                BossBar.Overlay.PROGRESS
        );

        spectatorBossBars.put(player.getUniqueId(), bossBar);
        player.showBossBar(bossBar);
    }

    private void updateSpectatorDisplays() {
        for (Map.Entry<UUID, SpectatorData> entry : spectatorData.entrySet()) {
            Player spectator = Bukkit.getPlayer(entry.getKey());
            if (spectator == null || !spectator.isOnline()) {
                continue;
            }

            SpectatorData data = entry.getValue();
            Arena arena = BedwarsAPI.getGameAPI().getArenaByName(data.getArenaName());
            if (arena == null) {
                continue;
            }

            updateBossBar(spectator, arena, data);
            updateActionBar(spectator, arena, data);
        }
    }

    private void updateBossBar(Player spectator, Arena arena, SpectatorData data) {
        if (!data.isHudEnabled()) return;

        BossBar bossBar = spectatorBossBars.get(spectator.getUniqueId());
        if (bossBar == null) return;

        List<Team> teams = new ArrayList<>(arena.getAliveTeams());
        if (teams.isEmpty()) return;

        int teamIndex = (int) ((System.currentTimeMillis() / 5000) % teams.size());
        Team currentTeam = teams.get(teamIndex);

        String pathway = plugin.getArenaPathwayManager().getTeamPathway(arena, currentTeam);
        if (pathway == null) return;

        double avgSequence = 9.0;
        double avgActingPercent = 0.0;

        TextColor teamColor = getTeamColor(currentTeam);
        Component title = Component.text(currentTeam.getDisplayName(), teamColor)
                .append(Component.text(" (", NamedTextColor.GRAY))
                .append(Component.text(pathway, NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(") - Avg Sequence: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.1f", 9 - avgSequence), NamedTextColor.YELLOW))
                .append(Component.text(" | Acting: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.0f%%", avgActingPercent), NamedTextColor.GREEN));

        bossBar.name(title);
        bossBar.progress((float) Math.min(avgActingPercent / 100.0, 1.0));
    }

    private void updateActionBar(Player spectator, Arena arena, SpectatorData data) {
        if (!data.isActionBarEnabled()) return;

        Player target = data.getTargetPlayer();
        if (target == null || !arena.getPlayers().contains(target)) {
            target = findNearestPlayer(spectator, arena);
        }

        if (target != null) {
            Component actionBar = createPlayerStatusComponent(target, arena);
            spectator.sendActionBar(actionBar);
        }
    }

    private Component createPlayerStatusComponent(Player player, Arena arena) {
        Team team = arena.getPlayerTeam(player);
        String pathway = team != null ? plugin.getArenaPathwayManager().getTeamPathway(arena, team) : "Unknown";

        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);

        if (beyonderData == null || beyonderData.pathways().isEmpty()) {
            return Component.text(player.getName(), NamedTextColor.WHITE)
                    .append(Component.text(" - No magical data", NamedTextColor.GRAY));
        }

        PathwayData flexPathway = beyonderData.pathways().getFirst();
        int sequence = flexPathway.lowestSequenceLevel();
        double actingPercent = ((double) flexPathway.acting() / flexPathway.neededActing()) * 100;

        TextColor teamColor = team != null ? getTeamColor(team) : NamedTextColor.WHITE;

        return Component.text(player.getName(), teamColor)
                .append(Component.text(" | ", NamedTextColor.GRAY))
                .append(Component.text(pathway, NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" Sequence:", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(sequence), getSequenceColor(sequence)))
                .append(Component.text(" | Acting:", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.0f%%", actingPercent), getActingColor(actingPercent)));
    }

    public void showPlayerDetails(Player spectator, Player target) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(target);
        if (arena == null) return;

        Team team = arena.getPlayerTeam(target);
        String pathway = team != null ? plugin.getArenaPathwayManager().getTeamPathway(arena, team) : "Unknown";

        spectator.sendMessage(Component.text("=== " + target.getName() + "'s Magical Status ===", NamedTextColor.GOLD));

        TextColor teamColor = team != null ? getTeamColor(team) : NamedTextColor.WHITE;
        spectator.sendMessage(Component.text("Team: ", NamedTextColor.GRAY)
                .append(Component.text(team != null ? team.getDisplayName() : "None", teamColor)));

        spectator.sendMessage(Component.text("Pathway: ", NamedTextColor.GRAY)
                .append(Component.text(pathway, NamedTextColor.LIGHT_PURPLE)));

        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(target);

        if (beyonderData == null || beyonderData.pathways().isEmpty()) {
            spectator.sendMessage(Component.text("No magical data available", NamedTextColor.RED));
            return;
        }

        PathwayData flexPathway = beyonderData.pathways().getFirst();
        int sequence = flexPathway.lowestSequenceLevel();
        int acting = flexPathway.acting();
        int neededActing = flexPathway.neededActing();
        double actingPercent = ((double) acting / neededActing) * 100;

        spectator.sendMessage(Component.text("Current Sequence: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(sequence), getSequenceColor(sequence))));

        spectator.sendMessage(Component.text("Acting: ", NamedTextColor.GRAY)
                .append(Component.text(acting + "/" + neededActing, NamedTextColor.GREEN))
                .append(Component.text(" (", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.1f%%", actingPercent), getActingColor(actingPercent)))
                .append(Component.text(")", NamedTextColor.GRAY)));
    }

    private Player findNearestPlayer(Player spectator, Arena arena) {
        return arena.getPlayers().stream()
                .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                .min(Comparator.comparingDouble(p -> p.getLocation().distance(spectator.getLocation())))
                .orElse(null);
    }

    private TextColor getTeamColor(Team team) {
        return switch (team.getDisplayName().toLowerCase()) {
            case "red" -> NamedTextColor.RED;
            case "blue" -> NamedTextColor.BLUE;
            case "green" -> NamedTextColor.GREEN;
            case "yellow" -> NamedTextColor.YELLOW;
            case "aqua", "cyan" -> NamedTextColor.AQUA;
            case "white" -> NamedTextColor.WHITE;
            case "pink" -> TextColor.color(255, 182, 193);
            case "gray", "grey" -> NamedTextColor.GRAY;
            default -> NamedTextColor.WHITE;
        };
    }

    private TextColor getSequenceColor(int sequence) {
        return switch (sequence) {
            case 9, 8 -> NamedTextColor.GREEN;
            case 7, 6 -> NamedTextColor.YELLOW;
            case 5, 4 -> NamedTextColor.GOLD;
            case 3, 2 -> NamedTextColor.RED;
            case 1, 0 -> NamedTextColor.DARK_PURPLE;
            default -> NamedTextColor.WHITE;
        };
    }

    private TextColor getActingColor(double percent) {
        if (percent >= 90) return NamedTextColor.DARK_GREEN;
        if (percent >= 75) return NamedTextColor.GREEN;
        if (percent >= 50) return NamedTextColor.YELLOW;
        if (percent >= 25) return NamedTextColor.GOLD;
        return NamedTextColor.RED;
    }

    public SpectatorData getSpectatorData(Player player) {
        return spectatorData.get(player.getUniqueId());
    }

    public boolean isSpectating(Player player) {
        return spectatorData.containsKey(player.getUniqueId());
    }

    public void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
        }

        for (UUID playerId : spectatorBossBars.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                cleanupSpectatorDisplay(player);
            }
        }

        spectatorData.clear();
        spectatorBossBars.clear();
    }

    public static class SpectatorData {
        private final UUID playerId;
        private final String arenaName;
        private boolean hudEnabled = true;
        private boolean actionBarEnabled = true;
        private boolean detailedMode = false;
        private Player targetPlayer;

        public SpectatorData(UUID playerId, String arenaName) {
            this.playerId = playerId;
            this.arenaName = arenaName;
        }

        // Getters and setters
        public UUID getPlayerId() {
            return playerId;
        }

        public String getArenaName() {
            return arenaName;
        }

        public boolean isHudEnabled() {
            return hudEnabled;
        }

        public void setHudEnabled(boolean hudEnabled) {
            this.hudEnabled = hudEnabled;
        }

        public boolean isActionBarEnabled() {
            return actionBarEnabled;
        }

        public void setActionBarEnabled(boolean actionBarEnabled) {
            this.actionBarEnabled = actionBarEnabled;
        }

        public boolean isDetailedMode() {
            return detailedMode;
        }

        public void setDetailedMode(boolean detailedMode) {
            this.detailedMode = detailedMode;
        }

        public Player getTargetPlayer() {
            return targetPlayer;
        }

        public void setTargetPlayer(Player targetPlayer) {
            this.targetPlayer = targetPlayer;
        }
    }
}