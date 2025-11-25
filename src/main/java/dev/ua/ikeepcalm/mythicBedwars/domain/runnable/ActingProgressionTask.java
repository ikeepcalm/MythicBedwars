package dev.ua.ikeepcalm.mythicBedwars.domain.runnable;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.core.PathwayManager;
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

            for (Player player : arena.getPlayers()) {
                PathwayManager.PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
                if (data == null || !data.isActive()) continue;

                if (!circleOfImaginationAPI.isBeyonder(player)) continue;

                double multiplier = plugin.getConfigManager().getPassiveActingMultiplier();
                int baseAmount = plugin.getConfigManager().getPassiveActingAmount();

                BeyonderData beyonderData = circleOfImaginationAPI.getBeyonderData(player);

                int sequence = beyonderData.lowestSequence();
                double sequenceMultiplier = getSequenceMultiplier(sequence);

                long effectivePlayTime = data.getEffectivePlayTime();
                double timeBasedMultiplier = getTimeBasedMultiplier(effectivePlayTime, sequence);

                int actingAmount = (int) (baseAmount * multiplier * sequenceMultiplier * timeBasedMultiplier);

                for (PathwayData pathway : beyonderData.pathways()) {
                    circleOfImaginationAPI.addActing(player, pathway.name(), actingAmount);
                }
            }
        }
    }

    private double getSequenceMultiplier(int sequence) {
        return switch (sequence) {
            case 9 -> 3.5;
            case 8 -> 3.0;
            case 7 -> 2.5;
            case 6 -> 1.3;
            case 5 -> 1.1;
            case 4 -> 0.4;
            case 3 -> 0.3;
            case 2 -> 0.2;
            case 1 -> 0.1;
            default -> 0.05;
        };
    }

    /**
     * Provides additional time-based multiplier to maintain fast progression for players
     * who have been playing longer, ensuring disconnections don't reset progression speed.
     */
    private double getTimeBasedMultiplier(long effectivePlayTimeMs, int sequence) {
        long playTimeMinutes = effectivePlayTimeMs / (1000 * 60);

        // For higher sequences (9-7), provide additional boost for longer play time
        // This helps players who disconnect and reconnect maintain their fast progression
        if (sequence >= 7) {
            // After 5 minutes: 1.5x boost, after 10 minutes: 2.0x boost, max 2.5x at 15+ minutes
            if (playTimeMinutes >= 15) {
                return 2.5;
            } else if (playTimeMinutes >= 10) {
                return 2.0;
            } else if (playTimeMinutes >= 5) {
                return 1.5;
            }
        } else if (sequence >= 5) {
            // For mid sequences (6-5), provide moderate boost
            if (playTimeMinutes >= 10) {
                return 1.8;
            } else if (playTimeMinutes >= 5) {
                return 1.3;
            }
        } else if (sequence >= 3) {
            // For lower sequences (4-3), provide small boost to maintain progression
            if (playTimeMinutes >= 8) {
                return 1.4;
            } else if (playTimeMinutes >= 4) {
                return 1.2;
            }
        }

        return 1.0;
    }
}