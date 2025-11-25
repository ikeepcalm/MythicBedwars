package dev.ua.ikeepcalm.mythicBedwars.domain.item;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseSession;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import dev.ua.ikeepcalm.mythicBedwars.MythicBedwars;
import dev.ua.ikeepcalm.mythicBedwars.domain.core.PathwayManager;
import org.bukkit.entity.Player;

import java.util.List;

public class PotionItemSession extends SpecialItemUseSession {

    private final int sequence;
    private final CircleOfImaginationAPI circleOfImaginationAPI;

    public PotionItemSession(PlayerUseSpecialItemEvent event, int sequence) {
        super(event);
        this.sequence = sequence;
        this.circleOfImaginationAPI = MythicBedwars.getInstance().getCircleOfImaginationAPI();
    }

    @Override
    protected void handleStop() {
    }

    public void run() {
        Player player = getEvent().getPlayer();
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;

        if (!MythicBedwars.getInstance().getVotingManager().isMagicEnabled(arena.getName())) {
            stop();
            return;
        }

        Team team = arena.getPlayerTeam(player);
        if (team == null) return;

        PathwayManager manager = MythicBedwars.getInstance().getArenaPathwayManager();
        String teamPathway = manager.getTeamPathway(arena, team);

        if (teamPathway == null) {
            stop();
            return;
        }

        PathwayManager.PlayerMagicData data = manager.getPlayerData(player);

        if (data == null) {
            manager.initializePlayerMagic(player, arena, team);
            data = manager.getPlayerData(player);
            player.sendMessage(MythicBedwars.getInstance().getLocaleManager().formatMessage("magic.messages.advanced", "pathway", String.valueOf(data.getPathway())));
            takeItem();
            stop();
            return;
        }

        if (!circleOfImaginationAPI.isBeyonder(player)) return;

        List<PathwayData> pathwayData = circleOfImaginationAPI.getPathwayData(player);

        if (pathwayData.isEmpty()) return;

        if (sequence != pathwayData.getFirst().lowestSequenceLevel() - 1) {
            player.sendMessage(MythicBedwars.getInstance().getLocaleManager().formatMessage("magic.messages.wrong_potion"));
            return;
        }

        if (pathwayData.getFirst().acting() != pathwayData.getFirst().neededActing()) {
            player.sendMessage(MythicBedwars.getInstance().getLocaleManager().formatMessage("magic.messages.no_acting"));
            return;
        }

        circleOfImaginationAPI.destroyBeyonder(player);
        circleOfImaginationAPI.createBeyonder(player, pathwayData.getFirst().name(), sequence);

        if (MythicBedwars.getInstance().getStatisticsManager() != null) {
            MythicBedwars.getInstance().getStatisticsManager().recordSequenceReached(teamPathway, sequence);
        }

        takeItem();
        stop();
    }
}