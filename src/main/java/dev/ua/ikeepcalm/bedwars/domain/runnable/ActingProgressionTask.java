package dev.ua.ikeepcalm.bedwars.domain.runnable;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.core.PathwayManager;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class ActingProgressionTask extends BukkitRunnable {

    private final MythicBedwars plugin;
    private final CircleOfImaginationAPI circleOfImaginationAPI;

    public ActingProgressionTask(MythicBedwars plugin) {
        this.plugin = plugin;
        this.circleOfImaginationAPI = plugin.getCircleOfImaginationAPI();
    }

    @Override
    public void run() {
        for (Arena arena : BedwarsAPI.getGameAPI().getArenas()) {
            if (arena.getStatus() != ArenaStatus.RUNNING) continue;
            if (!plugin.getVotingManager().isMagicEnabled(arena.getName())) continue;

            // Folded into the existing 1Hz loop rather than adding a second timer.
            boolean eventArena = plugin.isEventArena(arena.getName());

            for (Player player : arena.getPlayers()) {
                if (eventArena && plugin.getRewardService() != null) {
                    plugin.getRewardService().tracker().sample(arena.getName(), player);
                }

                PathwayManager.PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
                if (data == null || !data.isActive()) continue;
                if (!circleOfImaginationAPI.isBeyonder(player)) continue;

                BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);
                int sequence = beyonderData.lowestSequence();

                // Sync cached sequence and detect advancement
                int previousSequence = data.getCurrentSequence();
                if (sequence != previousSequence) {
                    data.setCurrentSequence(sequence);
                    if (sequence < previousSequence) {
                        notifySequenceAdvancement(player, arena, sequence, data.getPathway());
                    }
                }

                double multiplier = plugin.getConfigManager().getPassiveActingMultiplier();
                int baseAmount = plugin.getConfigManager().getPassiveActingAmount();
                double sequenceMultiplier = getSequenceMultiplier(sequence);
                double timeBasedMultiplier = getTimeBasedMultiplier(data.getEffectivePlayTime(), sequence);
                int actingAmount = (int) (baseAmount * multiplier * sequenceMultiplier * timeBasedMultiplier);

                for (PathwayData pathway : beyonderData.pathways()) {
                    circleOfImaginationAPI.addActing(player, pathway.name(), actingAmount);

                    if (circleOfImaginationAPI.getPrimaryActing(player) % 2000 == 0) {
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    }
                }
            }
        }
    }

    private void notifySequenceAdvancement(Player player, Arena arena, int newSequence, String pathway) {
        Team playerTeam = arena.getPlayerTeam(player);

        for (Player member : arena.getPlayers()) {
            if (playerTeam == null || playerTeam.equals(arena.getPlayerTeam(member))) {
                member.sendMessage(plugin.getLocaleManager().formatMessage(
                        "magic.messages.sequence_advanced",
                        "player", player.getName(),
                        "sequence", newSequence,
                        "pathway", pathway));
            }
        }

        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    private double getSequenceMultiplier(int sequence) {
        return plugin.getConfigManager().getSequenceMultiplier(sequence);
    }

    private double getTimeBasedMultiplier(long effectivePlayTimeMs, int sequence) {
        long playTimeMinutes = effectivePlayTimeMs / (1000 * 60);

        if (sequence >= 7) {
            if (playTimeMinutes >= 15) return 2.5;
            if (playTimeMinutes >= 10) return 2.0;
            if (playTimeMinutes >= 5)  return 1.5;
        } else if (sequence >= 5) {
            if (playTimeMinutes >= 10) return 1.8;
            if (playTimeMinutes >= 5)  return 1.3;
        } else if (sequence >= 3) {
            if (playTimeMinutes >= 8) return 1.4;
            if (playTimeMinutes >= 4) return 1.2;
        }

        return 1.0;
    }
}
