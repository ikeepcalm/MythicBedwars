package dev.ua.ikeepcalm.bedwars.domain.reward.service;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.Contribution;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardDenial;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.source.RewardTier;
import dev.ua.ikeepcalm.bedwars.net.minigame.model.EventReservation;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class RewardService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MythicBedwars plugin;
    private final RewardConfig config;
    private final RewardQueue queue;
    private final RewardRoller roller;
    @Getter
    private final MatchContributionTracker tracker = new MatchContributionTracker();

    public RewardService(MythicBedwars plugin, RewardConfig config, RewardQueue queue) {
        this.plugin = plugin;
        this.config = config;
        this.queue = queue;
        this.roller = new RewardRoller(config);
    }

    public void payOut(EventReservation reservation, Arena arena, List<Player> winners, List<Player> losers,
                       List<UUID> quitWinners, List<UUID> quitLosers, boolean tie) {
        if (!config.isEnabled()) {
            return;
        }

        int matchSize = winners.size() + losers.size() + quitWinners.size() + quitLosers.size();
        if (matchSize < config.minPlayers()) {
            plugin.log("Event {} had only {} player(s); no rewards paid.", reservation.getEventId(), matchSize);
            Consumer<Player> notify = player -> player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                    "magic.rewards.denied_small_match", "needed", config.minPlayers()));
            winners.forEach(notify);
            losers.forEach(notify);
            return;
        }

        String arenaName = arena.getName();
        Map.Entry<UUID, Double> mvp = tracker.mvp(arenaName, config, this::qualifies);
        UUID mvpId = mvp == null ? null : mvp.getKey();

        winners.forEach(player -> award(reservation, arenaName, player.getUniqueId(), player.getName(), true, tie, mvpId));
        losers.forEach(player -> award(reservation, arenaName, player.getUniqueId(), player.getName(), false, tie, mvpId));
        quitWinners.forEach(id -> award(reservation, arenaName, id, nameOf(id), true, tie, mvpId));
        quitLosers.forEach(id -> award(reservation, arenaName, id, nameOf(id), false, tie, mvpId));
    }

    private void award(EventReservation reservation, String arenaName, UUID playerId, String name,
                       boolean won, boolean tie, UUID mvpId) {
        Contribution contribution = tracker.of(arenaName, playerId);
        RewardDenial denial = denialReason(contribution);
        if (denial != null) {
            plugin.log("No rewards for {}: {}", name, denial.getReason());
            notifyDenied(playerId, denial);
            return;
        }

        boolean isMvp = playerId.equals(mvpId);
        String today = LocalDate.now(ZoneOffset.UTC).format(DAY);
        RewardBundle template = new RewardBundle(RewardBundle.SCHEMA, reservation.getEventId(), arenaName,
                playerId, name, eventPathway(playerId), won && !tie, System.currentTimeMillis(), List.of());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long paidToday = queue.bundlesToday(playerId, today);
            Bukkit.getScheduler().runTask(plugin, () -> rollAndQueue(template, contribution, paidToday, today, won, isMvp));
        });
    }

    private void rollAndQueue(RewardBundle template, Contribution contribution, long paidToday, String today,
                              boolean won, boolean isMvp) {
        if (paidToday >= config.dailyDropThreshold()) {
            plugin.log("No rewards for {}: {} bundles already today.", template.getPlayerName(), paidToday);
            notifyDenied(template.getPlayerId(), RewardDenial.DAILY_CAP);
            return;
        }

        boolean participationOnly = paidToday >= config.dailyDowngradeThreshold();
        List<RewardGrant> grants = new ArrayList<>(roller.roll(RewardTier.PARTICIPATION, participationScale(contribution)));
        if (won && !participationOnly) {
            grants.addAll(roller.roll(RewardTier.WINNER, 1.0));
        }
        if (isMvp && !participationOnly) {
            grants.addAll(roller.roll(RewardTier.MVP, 1.0));
        }
        double performance = participationOnly ? 0.0 : config.performanceActingPercent(contribution);
        List<RewardGrant> merged = mergeActing(grants, performance);
        if (merged.isEmpty()) {
            return;
        }

        RewardBundle bundle = new RewardBundle(template.getSchema(), template.getEventId(), template.getArena(),
                template.getPlayerId(), template.getPlayerName(), template.getEventPathway(), template.isWon(),
                template.getEarnedAtEpochMs(), merged);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!queue.emit(bundle)) {
                return;
            }
            queue.recordBundleToday(bundle.getPlayerId(), today);
            Bukkit.getScheduler().runTask(plugin, () -> preview(bundle, contribution, performance));
        });
    }

    private static List<RewardGrant> mergeActing(List<RewardGrant> grants, double performance) {
        double acting = performance + grants.stream()
                .filter(grant -> grant.getKind() == RewardKind.ACTING_PERCENT)
                .mapToDouble(RewardGrant::getAmount)
                .sum();

        List<RewardGrant> merged = new ArrayList<>();
        if (acting > 0) {
            merged.add(new RewardGrant(RewardKind.ACTING_PERCENT, RewardTier.PARTICIPATION, null,
                    Math.round(acting * 100.0) / 100.0, 1, 1, null, 9, false));
        }
        grants.stream().filter(grant -> grant.getKind() != RewardKind.ACTING_PERCENT).forEach(merged::add);
        return List.copyOf(merged);
    }

    private RewardDenial denialReason(Contribution contribution) {
        if (config.denyRageQuit() && contribution.isForfeit()) {
            return RewardDenial.RAGE_QUIT;
        }
        if (contribution.millisSinceJoin() < config.minPlayTimeSeconds() * 1000L) {
            return RewardDenial.TOO_SHORT;
        }
        if (contribution.actions() < config.minActions()) {
            return RewardDenial.INACTIVE;
        }
        if (participationRatio(contribution) < config.minParticipationRatio()) {
            return RewardDenial.AFK;
        }
        return null;
    }

    private boolean qualifies(Contribution contribution) {
        return denialReason(contribution) == null;
    }

    private double participationRatio(Contribution contribution) {
        int sampled = contribution.getSampledSeconds();
        if (sampled <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) contribution.getActiveSeconds() / sampled);
    }

    private double participationScale(Contribution contribution) {
        return Math.max(config.participationScaleFloor(), participationRatio(contribution));
    }

    private void preview(RewardBundle bundle, Contribution contribution, double performance) {
        Player player = Bukkit.getPlayer(bundle.getPlayerId());
        if (player == null) {
            return;
        }

        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.rewards.earned_header"));
        if (performance > 0) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.rewards.performance",
                    "beds", contribution.getBedsBroken(),
                    "finals", contribution.getFinalKills(),
                    "kills", contribution.getKills(),
                    "percent", RewardRedeemer.percent(performance)));
        }
        for (RewardGrant grant : bundle.getGrants()) {
            Component line = describe(player, grant);
            if (line != null) {
                player.sendMessage(line);
            }
        }
        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.rewards.earned_footer"));

        bundle.getGrants().stream().filter(RewardGrant::isEpic).forEach(grant -> announceEpic(player, grant));
    }

    private void announceEpic(Player player, RewardGrant grant) {
        player.showTitle(Title.title(
                plugin.getLocaleManager().formatMessage(player, "magic.rewards.epic_title"),
                plugin.getLocaleManager().formatMessage(player, "magic.rewards.epic_subtitle", "item", itemName(player, grant)),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.sendMessage(plugin.getLocaleManager().formatMessage(other, "magic.rewards.epic_broadcast",
                        "player", player.getName(), "item", itemName(other, grant)));
            }
        }
    }

    private Component describe(Player viewer, RewardGrant grant) {
        return switch (grant.getKind()) {
            case ACTING_PERCENT -> plugin.getLocaleManager().formatMessage(viewer,
                    "magic.rewards.line.acting_percent", "percent", RewardRedeemer.percent(grant.getAmount()));
            case COOLDOWN_CREDIT -> plugin.getLocaleManager().formatMessage(viewer,
                    "magic.rewards.line.cooldown_credit", "percent", RewardRedeemer.percent(grant.getAmount()));
            case ACTING_SPEED -> plugin.getLocaleManager().formatMessage(viewer,
                    "magic.rewards.line.acting_speed",
                    "percent", Math.round(grant.getAmount() * 100),
                    "duration", RewardRedeemer.formatDuration(grant.getIntArg()));
            case ACTING_ITEM_MULT -> plugin.getLocaleManager().formatMessage(viewer,
                    "magic.rewards.line.acting_item_mult",
                    "multiplier", RewardRedeemer.multiplier(grant.getAmount()),
                    "duration", RewardRedeemer.formatDuration(grant.getIntArg()));
            case ITEM -> grant.getItem() == null ? null : plugin.getLocaleManager().formatMessage(viewer,
                    grant.isEpic() ? "magic.rewards.line.epic_item" : "magic.rewards.line.item",
                    "amount", grant.getCount(), "item", itemName(viewer, grant));
        };
    }

    private String itemName(Player viewer, RewardGrant grant) {
        if (grant.getItem() == null) {
            return "?";
        }

        String base = plugin.getLocaleManager().getMessage(viewer,
                "magic.rewards.item_name." + grant.getItem().name().toLowerCase(Locale.ROOT));
        if (!grant.isExchangeToken()) {
            return base;
        }

        return plugin.getLocaleManager().getMessage(viewer, "magic.rewards.token_suffix")
                .replace("{item}", base)
                .replace("{sequence}", String.valueOf(grant.getMaxSequence()));
    }

    private void notifyDenied(UUID playerId, RewardDenial denial) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, denial.getMessageKey()));
        }
    }

    private String eventPathway(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return null;
        }

        var data = plugin.getArenaPathwayManager().getPlayerData(player);
        if (data != null) {
            return data.getPathway();
        }

        var pathways = plugin.getCircleOfImaginationAPI().getPathwayData(player);
        return pathways.isEmpty() ? null : pathways.getFirst().name();
    }

    private static String nameOf(UUID playerId) {
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? playerId.toString() : name;
    }
}
