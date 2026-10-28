package dev.ua.ikeepcalm.bedwars.domain.spectator.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.entity.Player;

@Getter
@Setter
@RequiredArgsConstructor
public class SpectatorData {

    private final String arenaName;
    private boolean hudEnabled = true;
    private boolean actionBarEnabled = true;
    private boolean detailedMode;
    private Player targetPlayer;
}
