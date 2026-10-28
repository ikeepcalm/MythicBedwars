package dev.ua.ikeepcalm.bedwars.net.smp.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import lombok.RequiredArgsConstructor;
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

@RequiredArgsConstructor
public class RecruitmentAnnouncer {

    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(15);

    private final MythicBedwars plugin;

    public void announceOpen(int count, int max, long secondsLeft, Consumer<Player> onJoin) {
        // one callback per announcement, not per recipient: each registers a server-side token
        Component button = joinButtonFor(null, onJoin);

        for (Player player : recipients()) {
            buildOpening(player, count, max, secondsLeft, button).forEach(player::sendMessage);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        }
    }

    public void preview(CommandSender viewer, int max, long secondsLeft) {
        buildOpening(viewer, 0, max, secondsLeft, joinButtonFor(viewer, player -> {
        })).forEach(viewer::sendMessage);
    }

    private List<Component> buildOpening(CommandSender viewer, int count, int max, long secondsLeft, Component button) {
        List<Component> lines = new ArrayList<>();
        lines.add(message(viewer, "magic.event.announce.header"));
        lines.add(message(viewer, "magic.event.announce.title"));
        lines.add(message(viewer, "magic.event.announce.body"));

        addSection(lines, viewer, "magic.event.announce.rewards_header", "magic.event.announce.rewards");
        addSection(lines, viewer, "magic.event.announce.flow_header", "magic.event.announce.flow");

        lines.add(Component.empty());
        lines.add(message(viewer, "magic.event.announce.slots", "count", count, "max", max, "seconds", secondsLeft)
                .append(Component.text("   "))
                .append(button));
        lines.add(message(viewer, "magic.event.announce.footer"));
        return lines;
    }

    private void addSection(List<Component> lines, CommandSender viewer, String headerKey, String listKey) {
        List<Component> entries = messageList(viewer, listKey);
        if (entries.isEmpty()) {
            return;
        }

        lines.add(Component.empty());
        lines.add(message(viewer, headerKey));
        lines.addAll(entries);
    }

    public void announceReminder(int count, int max, long secondsLeft, Consumer<Player> onJoin) {
        Component button = joinButtonFor(null, onJoin);

        for (Player player : recipients()) {
            player.sendMessage(message(player, "magic.event.announce.reminder",
                    "count", count, "max", max, "seconds", secondsLeft)
                    .append(Component.text("  "))
                    .append(button));
        }
    }

    public void broadcast(String localeKey, Object... args) {
        for (Player player : recipients()) {
            player.sendMessage(message(player, localeKey, args));
        }
    }

    public void announceSignup(String playerName, int count, int max) {
        broadcast("magic.event.announce.joined", "player", playerName, "count", count, "max", max);
    }

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

    private List<? extends Player> recipients() {
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
