package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Everything players actually see about a recruitment drive.
 *
 * <p>The opening announcement is deliberately substantial. It is competing with whatever else is in
 * chat, it fires at most once an hour, and asking somebody to leave the server they are standing on
 * is a real ask — so it has to answer "what do I get?" and "what happens to me?" before it asks for
 * a click. Reminders stay to one line.
 *
 * <p>Every section is driven by the locale file, and an empty list switches its section off
 * entirely, so the copy can be tuned or trimmed without touching code.
 *
 * <p>The join prompt is an Adventure click callback rather than a command: the token is minted per
 * message and scoped to this event, so it cannot be replayed, guessed, or run by somebody who never
 * saw the offer. {@code /mb event join} still exists as the fallback, because callbacks do not
 * survive a reconnect.
 */
public class RecruitmentAnnouncer {

    /** How long a rendered [JOIN] stays clickable. Comfortably longer than any signup window. */
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(15);

    private final MythicBedwars plugin;

    public RecruitmentAnnouncer(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * The full pitch: what it is, what you win, what happens to you, and how long you have.
     */
    public void announceOpen(String arena, int count, int max, long secondsLeft, Consumer<Player> onJoin) {
        List<Component> lines = buildOpening(arena, count, max, secondsLeft, onJoin);

        for (Player player : recipients()) {
            lines.forEach(player::sendMessage);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        }
    }

    /**
     * Renders the announcement to one recipient without starting anything, so copy can be checked
     * in place rather than by waiting for a real drive.
     */
    public void preview(Audience audience, String arena, int max, long secondsLeft) {
        buildOpening(arena, 0, max, secondsLeft, player -> {
        }).forEach(audience::sendMessage);
    }

    private List<Component> buildOpening(String arena, int count, int max, long secondsLeft, Consumer<Player> onJoin) {
        Component header = message("magic.event.announce.header");
        Component title = message("magic.event.announce.title");
        Component body = message("magic.event.announce.body", "arena", arena);

        Component rewardsHeader = message("magic.event.announce.rewards_header");
        List<Component> rewards = messageList("magic.event.announce.rewards");

        Component flowHeader = message("magic.event.announce.flow_header");
        List<Component> flow = messageList("magic.event.announce.flow");

        Component slots = message("magic.event.announce.slots",
                "count", count, "max", max, "seconds", secondsLeft);
        Component footer = message("magic.event.announce.footer");

        List<Component> lines = new ArrayList<>();
        lines.add(header);
        lines.add(title);
        lines.add(body);

        // An emptied list in the locale file switches its whole section off.
        if (!rewards.isEmpty()) {
            lines.add(Component.empty());
            lines.add(rewardsHeader);
            lines.addAll(rewards);
        }

        if (!flow.isEmpty()) {
            lines.add(Component.empty());
            lines.add(flowHeader);
            lines.addAll(flow);
        }

        lines.add(Component.empty());
        lines.add(slots.append(Component.text("   ")).append(joinButton(onJoin)));
        lines.add(footer);
        return lines;
    }

    /**
     * A single line for the players who have not signed up yet. Repeats the reward hook, because a
     * bare countdown gives nobody a reason to act.
     */
    public void announceReminder(int count, int max, long secondsLeft, Consumer<Player> onJoin) {
        Component line = message("magic.event.announce.reminder",
                "count", count, "max", max, "seconds", secondsLeft);

        for (Player player : recipients()) {
            player.sendMessage(line.append(Component.text("  ")).append(joinButton(onJoin)));
        }
    }

    public void announceCancelled(Component reason) {
        for (Player player : recipients()) {
            player.sendMessage(reason);
        }
    }

    /**
     * Broadcasts that somebody joined, so the drive visibly builds momentum.
     */
    public void announceSignup(String playerName, int count, int max) {
        Component line = message("magic.event.announce.joined", "player", playerName, "count", count, "max", max);
        for (Player player : recipients()) {
            player.sendMessage(line);
        }
    }

    private Component joinButton(Consumer<Player> onJoin) {
        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(CALLBACK_LIFETIME)
                .build();

        return message("magic.event.announce.join_button")
                .hoverEvent(HoverEvent.showText(message("magic.event.announce.join_hover")))
                .clickEvent(ClickEvent.callback(audience -> {
                    if (audience instanceof Player player) {
                        onJoin.accept(player);
                    }
                }, options));
    }

    /**
     * @return the players who should see event chatter — everyone without the opt-out permission
     */
    private Iterable<? extends Player> recipients() {
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.hasPermission("mythicbedwars.event.exempt"))
                .toList();
    }

    private Component message(String key, Object... args) {
        return plugin.getLocaleManager().formatMessage(key, args);
    }

    private List<Component> messageList(String key) {
        return plugin.getLocaleManager().formatMessageList(key);
    }
}
