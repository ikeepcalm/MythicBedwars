package dev.ua.ikeepcalm.bedwars.command.admin;

import java.util.List;
import java.util.Locale;

public class Subcommands {

    public static final List<String> SHARED = List.of("toggle", "reload", "event");
    public static final List<String> MINIGAME = List.of("stats", "arena", "balance", "pathways", "voting");

    public static final String ADMIN_PERMISSION = "mythicbedwars.admin";
    public static final String JOIN_PERMISSION = "mythicbedwars.event.join";

    public static boolean isMinigameOnly(String subcommand) {
        return MINIGAME.contains(subcommand.toLowerCase(Locale.ROOT));
    }
}
