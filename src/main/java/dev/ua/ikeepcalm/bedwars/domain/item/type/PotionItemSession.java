package dev.ua.ikeepcalm.bedwars.domain.item.type;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.player.PlayerUseSpecialItemEvent;
import de.marcely.bedwars.api.game.specialitem.SpecialItemUseSession;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.pathway.model.PlayerMagicData;
import dev.ua.ikeepcalm.bedwars.domain.pathway.service.PathwayManager;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import org.bukkit.entity.Player;

import java.util.List;

public class PotionItemSession extends SpecialItemUseSession {

    private final MythicBedwars plugin = MythicBedwars.getInstance();
    private final int sequence;

    public PotionItemSession(PlayerUseSpecialItemEvent event, int sequence) {
        super(event);
        this.sequence = sequence;
    }

    @Override
    protected void handleStop() {
    }

    public void run() {
        if (drink(getEvent().getPlayer())) {
            takeItem();
        }
        stop();
    }

    private boolean drink(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null || !plugin.getVotingManager().isMagicEnabled(arena.getName())) {
            return false;
        }

        Team team = arena.getPlayerTeam(player);
        if (team == null) {
            return false;
        }

        // Loadout before pathway: an individual draw only exists once the loadout has opened.
        PathwayManager manager = plugin.getArenaPathwayManager();
        PlayerMagicData data = manager.getPlayerData(player);
        if (data == null) {
            return openLoadout(player, arena, team);
        }

        String expectedPathway = manager.getExpectedPathway(arena, team, player);
        if (expectedPathway == null || !advance(player)) {
            return false;
        }

        data.setCurrentSequence(sequence);
        data.setStoredActing(0);
        plugin.getStatisticsManager().recordSequenceReached(expectedPathway, sequence);
        return true;
    }

    private boolean openLoadout(Player player, Arena arena, Team team) {
        PathwayManager manager = plugin.getArenaPathwayManager();
        manager.initializePlayerMagic(player, arena, team);
        PlayerMagicData data = manager.getPlayerData(player);
        if (data == null) {
            return false;
        }

        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.messages.advanced",
                "pathway", String.valueOf(data.getPathway())));
        return true;
    }

    private boolean advance(Player player) {
        CircleOfImaginationAPI api = plugin.getCircleOfImaginationAPI();
        if (!api.isBeyonder(player)) {
            return false;
        }

        List<PathwayData> pathways = api.getPathwayData(player);
        if (pathways.isEmpty()) {
            return false;
        }

        PathwayData primary = pathways.getFirst();
        if (sequence != primary.lowestSequenceLevel() - 1) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.messages.wrong_potion"));
            return false;
        }

        if (primary.acting() != primary.neededActing()) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.messages.no_acting"));
            return false;
        }

        return api.enterVirtualBeyonder(player, primary.name(), sequence);
    }
}
