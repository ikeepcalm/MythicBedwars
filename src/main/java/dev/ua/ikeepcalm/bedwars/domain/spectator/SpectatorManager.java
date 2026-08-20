package dev.ua.ikeepcalm.bedwars.domain.spectator;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import de.marcely.bedwars.api.game.spectator.Spectator;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
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
        long period = Math.max(1L, plugin.getConfigManager().getSpectatorUpdateInterval());

        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateSpectatorDisplays();
            }
        }.runTaskTimer(plugin, period, period);
    }

    /**
     * Re-arms the display refresh at the currently configured interval.
     *
     * <p>A task's period is fixed when it is scheduled, so picking up a changed
     * {@code spectator.update-interval-ticks} means replacing the task rather than re-reading a value.
     */
    public void restartUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
        }

        startUpdateTask();
    }

    public void addSpectator(Player player, Arena arena) {
        Spectator spectator = arena.getSpectateData(player);
        if (spectator == null || !canUseSpectatorFeatures(spectator)) {
            return;
        }

        addSpectator(player, arena, spectator.getReason());
    }

    public void addSpectator(Player player, Arena arena, SpectateReason reason) {
        if (!plugin.getConfigManager().isSpectatorFeaturesEnabled()) {
            return;
        }

        if (!canUseSpectatorFeatures(reason)) {
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

        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.welcome"));
        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.commands_help"));
        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.right_click_hint"));
        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.boss_bar_hint"));
    }

    private void cleanupSpectatorDisplay(Player player) {
        BossBar bossBar = spectatorBossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    private void sendWelcomeMessage(Player player, Arena arena) {
        player.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.now_spectating", "arena", arena.getName()));
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
            player.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.team_pathways"));
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
                plugin.getLocaleManager().formatMessage("magic.spectator.magical_status_loading"),
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

            if (!canUseSpectatorFeatures(spectator, arena)) {
                removeSpectator(spectator);
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

        List<Player> teamPlayers = arena.getPlayers().stream()
                .filter(p -> currentTeam.equals(arena.getPlayerTeam(p)))
                .filter(circleOfImaginationAPI::isBeyonder)
                .toList();

        double avgSequence = 9.0;
        double avgActingPercent = 0.0;

        if (!teamPlayers.isEmpty()) {
            avgSequence = teamPlayers.stream()
                    .mapToInt(p -> circleOfImaginationAPI.getBeyonderData(p).lowestSequence())
                    .average()
                    .orElse(9.0);

            avgActingPercent = teamPlayers.stream()
                    .mapToDouble(p -> {
                        BeyonderData bd = circleOfImaginationAPI.getBeyonderData(p);
                        if (bd == null || bd.pathways().isEmpty()) return 0.0;
                        PathwayData pd = bd.pathways().getFirst();
                        return pd.neededActing() > 0 ? (double) pd.acting() / pd.neededActing() * 100.0 : 0.0;
                    })
                    .average()
                    .orElse(0.0);
        }

        TextColor teamColor = getTeamColor(currentTeam);
        Component title = Component.text(currentTeam.getDisplayName(), teamColor)
                .append(Component.text(" (", NamedTextColor.GRAY))
                .append(Component.text(pathway, NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(") | Seq: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.1f", avgSequence), getSequenceColor((int) Math.round(avgSequence))))
                .append(Component.text(" | Acting: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.0f%%", avgActingPercent), getActingColor(avgActingPercent)));

        bossBar.name(title);
        bossBar.progress((float) Math.min(avgActingPercent / 100.0, 1.0f));
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
        String pathway = team != null ? plugin.getArenaPathwayManager().getTeamPathway(arena, team) : null;

        spectator.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.player_status", "player", target.getName()));

        TextColor teamColor = team != null ? getTeamColor(team) : NamedTextColor.WHITE;
        spectator.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.team")
                .append(Component.space())
                .append(Component.text(team != null ? team.getDisplayName() : "None", teamColor)));

        spectator.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.pathway")
                .append(Component.space())
                .append(Component.text(pathway != null ? pathway : "Unknown", NamedTextColor.LIGHT_PURPLE)));

        BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(target);

        if (beyonderData == null || beyonderData.pathways().isEmpty()) {
            spectator.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.no_magic_data"));
            return;
        }

        PathwayData flexPathway = beyonderData.pathways().getFirst();
        int sequence = flexPathway.lowestSequenceLevel();
        int acting = flexPathway.acting();
        int neededActing = flexPathway.neededActing();
        double actingPercent = neededActing > 0 ? ((double) acting / neededActing) * 100 : 0;

        spectator.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.current_sequence")
                .append(Component.space())
                .append(Component.text(String.valueOf(sequence), getSequenceColor(sequence))));

        spectator.sendMessage(plugin.getLocaleManager().formatMessage("magic.spectator.acting")
                .append(Component.space())
                .append(Component.text(acting + "/" + neededActing, NamedTextColor.GREEN))
                .append(Component.text(" (" + String.format("%.1f%%", actingPercent) + ")", getActingColor(actingPercent))));
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

    public boolean canUseSpectatorFeatures(Player player, Arena arena) {
        if (!plugin.getConfigManager().isSpectatorFeaturesEnabled()) {
            return false;
        }

        Spectator spectator = arena.getSpectateData(player);
        return spectator != null && canUseSpectatorFeatures(spectator);
    }

    private boolean canUseSpectatorFeatures(Spectator spectator) {
        return spectator.isPresent() && canUseSpectatorFeatures(spectator.getReason());
    }

    private boolean canUseSpectatorFeatures(SpectateReason reason) {
        return reason != SpectateReason.DEATH;
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
