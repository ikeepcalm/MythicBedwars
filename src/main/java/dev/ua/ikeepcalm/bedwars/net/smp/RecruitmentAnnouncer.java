package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
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
 * <p>The join prompt is an Adventure click callback rather than a command, so nobody who never saw
 * the offer can trigger it. It is minted <b>once per announcement</b>, not once per recipient: a
 * per-player callback would register one server-side token for every player on every reminder, all
 * of them unlimited-use and outliving the drive by a quarter of an hour.
 * {@code /mb event join} exists as the fallback, because callbacks do not survive a reconnect.
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
        // `arena` is accepted for the log and for operators who want it back in the copy; the shipped
        // wording leaves it out, because a raw MBedwars id like "bw_4x1_castle" tells a player nothing.

        Component button = joinButtonFor(null, onJoin);

        for (Player player : recipients()) {
            // Rendered per recipient so each reads it in their own language.
            for (Component line : buildOpening(player, arena, count, max, secondsLeft, onJoin, button)) {
                player.sendMessage(line);
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        }
    }

    /**
     * Renders the announcement to one recipient without starting anything, so copy can be checked
     * in place rather than by waiting for a real drive.
     */
    public void preview(Audience audience, String arena, int max, long secondsLeft) {
        CommandSender viewer = audience instanceof CommandSender sender ? sender : null;
        Consumer<Player> noop = player -> {
        };

        buildOpening(viewer, arena, 0, max, secondsLeft, noop, joinButtonFor(viewer, noop))
                .forEach(audience::sendMessage);
    }

    private List<Component> buildOpening(CommandSender viewer, String arena, int count, int max,
                                         long secondsLeft, Consumer<Player> onJoin, Component button) {
        Component header = message(viewer, "magic.event.announce.header");
        Component title = message(viewer, "magic.event.announce.title");
        Component body = message(viewer, "magic.event.announce.body");

        Component rewardsHeader = message(viewer, "magic.event.announce.rewards_header");
        List<Component> rewards = messageList(viewer, "magic.event.announce.rewards");

        Component flowHeader = message(viewer, "magic.event.announce.flow_header");
        List<Component> flow = messageList(viewer, "magic.event.announce.flow");

        Component slots = message(viewer, "magic.event.announce.slots",
                "count", count, "max", max, "seconds", secondsLeft);
        Component footer = message(viewer, "magic.event.announce.footer");

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
        lines.add(slots.append(Component.text("   ")).append(button));
        lines.add(footer);
        return lines;
    }

    /**
     * A single line for the players who have not signed up yet.
     */
    public void announceReminder(int count, int max, long secondsLeft, Consumer<Player> onJoin) {
        Component button = joinButtonFor(null, onJoin);

        for (Player player : recipients()) {
            player.sendMessage(message(player, "magic.event.announce.reminder",
                    "count", count, "max", max, "seconds", secondsLeft)
                    .append(Component.text("  "))
                    .append(button));
        }
    }

    /**
     * Sends one line to everybody who has not opted out, in their own language.
     */
    public void broadcast(String localeKey, Object... args) {
        for (Player player : recipients()) {
            player.sendMessage(message(player, localeKey, args));
        }
    }

    /**
     * Broadcasts that somebody joined, so the drive visibly builds momentum.
     */
    public void announceSignup(String playerName, int count, int max) {
        for (Player player : recipients()) {
            player.sendMessage(message(player, "magic.event.announce.joined",
                    "player", playerName, "count", count, "max", max));
        }
    }

    /**
     * Builds the clickable prompt once. The label and hover text are resolved against
     * {@code viewer}, or the default locale when it is being shared across recipients.
     */
    private Component joinButtonFor(CommandSender viewer, Consumer<Player> onJoin) {
        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(CALLBACK_LIFETIME)
                .build();

        return message(viewer, "magic.event.announce.join_button")
                .hoverEvent(HoverEvent.showText(message(viewer, "magic.event.announce.join_hover")))
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

    private Component message(CommandSender viewer, String key, Object... args) {
        return viewer == null
                ? plugin.getLocaleManager().formatMessage(key, args)
                : plugin.getLocaleManager().formatMessage(viewer, key, args);
    }

    private List<Component> messageList(CommandSender viewer, String key) {
        return viewer == null
                ? plugin.getLocaleManager().formatMessageList(key)
                : plugin.getLocaleManager().formatMessageList(viewer, key);
    }
}
