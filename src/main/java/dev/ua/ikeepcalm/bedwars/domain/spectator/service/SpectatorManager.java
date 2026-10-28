package dev.ua.ikeepcalm.bedwars.domain.spectator.service;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import de.marcely.bedwars.api.game.spectator.Spectator;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.LocaleLoader;
import dev.ua.ikeepcalm.bedwars.domain.spectator.model.SpectatorData;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpectatorManager {

    private final MythicBedwars plugin;
    private final Map<UUID, SpectatorData> spectatorData = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> spectatorBossBars = new ConcurrentHashMap<>();
    private BukkitTask updateTask;

    public SpectatorManager(MythicBedwars plugin) {
        this.plugin = plugin;
        restartUpdateTask();
    }

    public void restartUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        long period = Math.max(1L, plugin.getConfigManager().getSpectatorUpdateInterval());
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateSpectatorDisplays, period, period);
    }

    public void addSpectator(Player player, Arena arena, SpectateReason reason) {
        if (!plugin.getConfigManager().isSpectatorFeaturesEnabled() || !canUseSpectatorFeatures(reason)) {
            return;
        }

        hideBossBar(player);
        spectatorData.put(player.getUniqueId(), new SpectatorData(arena.getName()));
        showBossBar(player);
        sendWelcome(player, arena);
    }

    public void removeSpectator(Player player) {
        spectatorData.remove(player.getUniqueId());
        hideBossBar(player);
    }

    private void sendWelcome(Player player, Arena arena) {
        LocaleLoader locale = plugin.getLocaleManager();
        player.sendMessage(locale.formatMessage(player, "magic.spectator.welcome"));
        player.sendMessage(locale.formatMessage(player, "magic.spectator.commands_help"));
        player.sendMessage(locale.formatMessage(player, "magic.spectator.right_click_hint"));
        player.sendMessage(locale.formatMessage(player, "magic.spectator.boss_bar_hint"));
        player.sendMessage(locale.formatMessage(player, "magic.spectator.now_spectating", "arena", arena.getName()));
        showTeamPathways(player, arena);
    }

    private void showTeamPathways(Player player, Arena arena) {
        boolean headerSent = false;
        for (Team team : arena.getAliveTeams()) {
            String pathway = plugin.getArenaPathwayManager().getTeamPathwayDisplay(arena, team);
            if (pathway == null) {
                continue;
            }
            if (!headerSent) {
                player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.spectator.team_pathways"));
                headerSent = true;
            }
            player.sendMessage(Component.text("• ", NamedTextColor.GRAY)
                    .append(Component.text(team.getDisplayName(player), teamColor(team)))
                    .append(Component.text(": ", NamedTextColor.GRAY))
                    .append(Component.text(pathway, NamedTextColor.LIGHT_PURPLE)));
        }
    }

    private void showBossBar(Player player) {
        BossBar bossBar = BossBar.bossBar(
                plugin.getLocaleManager().formatMessage(player, "magic.spectator.magical_status_loading"),
                0.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        spectatorBossBars.put(player.getUniqueId(), bossBar);
        player.showBossBar(bossBar);
    }

    private void hideBossBar(Player player) {
        BossBar bossBar = spectatorBossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }
    }

    private void updateSpectatorDisplays() {
        for (Map.Entry<UUID, SpectatorData> entry : spectatorData.entrySet()) {
            Player spectator = Bukkit.getPlayer(entry.getKey());
            if (spectator == null) {
                continue;
            }

            SpectatorData data = entry.getValue();
            Arena arena = BedwarsAPI.getGameAPI().getArenaByName(data.getArenaName());
            if (arena == null || !canUseSpectatorFeatures(spectator, arena)) {
                removeSpectator(spectator);
                continue;
            }

            updateBossBar(spectator, arena, data);
            updateActionBar(spectator, arena, data);
        }
    }

    private void updateBossBar(Player spectator, Arena arena, SpectatorData data) {
        if (!data.isHudEnabled()) {
            hideBossBar(spectator);
            return;
        }
        if (!spectatorBossBars.containsKey(spectator.getUniqueId())) {
            showBossBar(spectator);
        }

        List<Team> teams = List.copyOf(arena.getAliveTeams());
        if (teams.isEmpty()) return;

        Team team = teams.get((int) ((System.currentTimeMillis() / 5000) % teams.size()));
        String pathway = plugin.getArenaPathwayManager().getTeamPathwayDisplay(arena, team);
        if (pathway == null) return;

        List<PathwayData> primaries = arena.getPlayers().stream()
                .filter(player -> team.equals(arena.getPlayerTeam(player)))
                .map(this::primaryPathway)
                .filter(Objects::nonNull)
                .toList();
        double avgSequence = primaries.stream().mapToInt(PathwayData::lowestSequenceLevel).average().orElse(9.0);
        double avgActingPercent = primaries.stream().mapToDouble(this::actingPercent).average().orElse(0.0);

        BossBar bossBar = spectatorBossBars.get(spectator.getUniqueId());
        bossBar.name(Component.text(team.getDisplayName(spectator), teamColor(team))
                .append(Component.text(" (", NamedTextColor.GRAY))
                .append(Component.text(pathway, NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(") | Seq: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.1f", avgSequence), sequenceColor((int) Math.round(avgSequence))))
                .append(Component.text(" | Acting: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.0f%%", avgActingPercent), actingColor(avgActingPercent))));
        bossBar.progress((float) Math.clamp(avgActingPercent / 100.0, 0.0, 1.0));
    }

    private void updateActionBar(Player spectator, Arena arena, SpectatorData data) {
        if (!data.isActionBarEnabled()) return;

        Player target = data.getTargetPlayer();
        if (target == null || !arena.getPlayers().contains(target)) {
            target = findNearestPlayer(spectator, arena);
        }
        if (target != null) {
            spectator.sendActionBar(playerStatus(target, arena));
        }
    }

    private Component playerStatus(Player player, Arena arena) {
        PathwayData primary = primaryPathway(player);
        if (primary == null) {
            return Component.text(player.getName(), NamedTextColor.WHITE)
                    .append(Component.text(" - No magical data", NamedTextColor.GRAY));
        }

        String pathway = plugin.getArenaPathwayManager().getPlayerPathway(player);
        Team team = arena.getPlayerTeam(player);
        int sequence = primary.lowestSequenceLevel();
        double actingPercent = actingPercent(primary);

        return Component.text(player.getName(), team != null ? teamColor(team) : NamedTextColor.WHITE)
                .append(Component.text(" | ", NamedTextColor.GRAY))
                .append(Component.text(pathway != null ? pathway : "Unknown", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" Sequence:", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(sequence), sequenceColor(sequence)))
                .append(Component.text(" | Acting:", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.0f%%", actingPercent), actingColor(actingPercent)));
    }

    public void showPlayerDetails(Player spectator, Player target) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(target);
        if (arena == null) return;

        LocaleLoader locale = plugin.getLocaleManager();
        Team team = arena.getPlayerTeam(target);
        String pathway = plugin.getArenaPathwayManager().getPlayerPathway(target);

        spectator.sendMessage(locale.formatMessage(spectator, "magic.spectator.player_status", "player", target.getName()));
        spectator.sendMessage(locale.formatMessage(spectator, "magic.spectator.team").append(Component.space())
                .append(team != null
                        ? Component.text(team.getDisplayName(spectator), teamColor(team))
                        : Component.text("None", NamedTextColor.WHITE)));
        spectator.sendMessage(locale.formatMessage(spectator, "magic.spectator.pathway").append(Component.space())
                .append(Component.text(pathway != null ? pathway : "Unknown", NamedTextColor.LIGHT_PURPLE)));

        PathwayData primary = primaryPathway(target);
        if (primary == null) {
            spectator.sendMessage(locale.formatMessage(spectator, "magic.spectator.no_magic_data"));
            return;
        }

        int sequence = primary.lowestSequenceLevel();
        double actingPercent = actingPercent(primary);
        spectator.sendMessage(locale.formatMessage(spectator, "magic.spectator.current_sequence").append(Component.space())
                .append(Component.text(String.valueOf(sequence), sequenceColor(sequence))));
        spectator.sendMessage(locale.formatMessage(spectator, "magic.spectator.acting").append(Component.space())
                .append(Component.text(primary.acting() + "/" + primary.neededActing(), NamedTextColor.GREEN))
                .append(Component.text(" (" + String.format("%.1f%%", actingPercent) + ")", actingColor(actingPercent))));
    }

    private PathwayData primaryPathway(Player player) {
        BeyonderData data = plugin.getCircleOfImaginationAPI().getBeyonderData(player);
        return data == null || data.pathways().isEmpty() ? null : data.pathways().getFirst();
    }

    private double actingPercent(PathwayData pathway) {
        return pathway.neededActing() > 0 ? (double) pathway.acting() / pathway.neededActing() * 100.0 : 0.0;
    }

    private Player findNearestPlayer(Player spectator, Arena arena) {
        return arena.getPlayers().stream()
                .filter(player -> player.getGameMode() != GameMode.SPECTATOR)
                .filter(player -> player.getWorld().equals(spectator.getWorld()))
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(spectator.getLocation())))
                .orElse(null);
    }

    private TextColor teamColor(Team team) {
        return TextColor.color(team.getBukkitColor().asRGB());
    }

    private TextColor sequenceColor(int sequence) {
        return switch (sequence) {
            case 9, 8 -> NamedTextColor.GREEN;
            case 7, 6 -> NamedTextColor.YELLOW;
            case 5, 4 -> NamedTextColor.GOLD;
            case 3, 2 -> NamedTextColor.RED;
            case 1, 0 -> NamedTextColor.DARK_PURPLE;
            default -> NamedTextColor.WHITE;
        };
    }

    private TextColor actingColor(double percent) {
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
        return spectator != null && spectator.isPresent() && canUseSpectatorFeatures(spectator.getReason());
    }

    private boolean canUseSpectatorFeatures(SpectateReason reason) {
        return reason != SpectateReason.DEATH;
    }

    public void shutdown() {
        updateTask.cancel();
        for (UUID playerId : spectatorBossBars.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                hideBossBar(player);
            }
        }
        spectatorData.clear();
        spectatorBossBars.clear();
    }
}
