package dev.ua.ikeepcalm.bedwars.net.smp.service;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.event.model.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.service.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.AcceptPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.ArenaReadyPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.CancelledPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.EventFinishedPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.EventStartedPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.PlayerArrivedPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.PlayerReturnPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.ProposePayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.RejectPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.model.RosterClosedPayload;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import dev.ua.ikeepcalm.bedwars.net.service.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.smp.model.ScheduleOutlook;
import dev.ua.ikeepcalm.bedwars.net.transport.LuaScripts;
import dev.ua.ikeepcalm.bedwars.net.type.EventParticipant;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class RecruitmentManager implements EventParticipant {

    private static final int PROPOSE_LOCK_TTL_SECONDS = 10;
    private static final long MIN_ANNOUNCE_WINDOW_MILLIS = 15_000L;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;
    private final RecruitmentAnnouncer announcer;
    private final ScheduleJournal journal;

    private final Set<Integer> firedReminders = new HashSet<>();
    private final AtomicBoolean signupsClosing = new AtomicBoolean();

    private volatile BukkitTask signupTask;
    private volatile BukkitTask proposeTimeoutTask;
    private volatile BukkitTask scheduleTask;
    private volatile int cap;
    private volatile int lastKnownCount;
    private volatile String currentEventId;
    private volatile EventState currentState;
    private volatile String hostServerId;
    private volatile String hostServerName;
    private volatile String arenaName;

    public RecruitmentManager(MythicBedwars plugin, NetworkService network, EventStore store) {
        this.plugin = plugin;
        this.network = network;
        this.store = store;
        this.announcer = new RecruitmentAnnouncer(plugin);
        this.journal = new ScheduleJournal(plugin);
        this.journal.load();
    }

    public void registerHandlers() {
        network.getBus().on(MessageType.EVENT_ACCEPT, this::onAccept);
        network.getBus().on(MessageType.EVENT_REJECT, this::onReject);
        network.getBus().on(MessageType.ARENA_READY, this::onArenaReady);
        network.getBus().on(MessageType.PLAYER_ARRIVED, this::onPlayerArrived);
        network.getBus().on(MessageType.EVENT_STARTED, this::onEventStarted);
        network.getBus().on(MessageType.EVENT_FINISHED, this::onEventFinished);
        network.getBus().on(MessageType.EVENT_CANCELLED, this::onCancelled);
        network.getBus().on(MessageType.PLAYER_RETURN, this::onPlayerReturn);
    }

    @Override
    public Set<String> localEventIds() {
        String eventId = currentEventId;
        return eventId == null ? Set.of() : Set.of(eventId);
    }

    @Override
    public void abandonLocally(String eventId, CancelReason reason) {
        if (!isCurrent(eventId)) {
            return;
        }

        if (currentState == EventState.ANNOUNCED) {
            announcer.broadcast(reason.localeKey());
        }
        clearLocal();
    }

    public void startSchedule() {
        BukkitTask existing = scheduleTask;
        if (existing != null) {
            existing.cancel();
            scheduleTask = null;
        }

        if (!plugin.getConfigManager().isEventScheduleEnabled()) {
            return;
        }

        long period = Math.max(30L, plugin.getConfigManager().getEventScheduleCheckSeconds()) * 20L;
        scheduleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::considerScheduledEvent, period, period);

        plugin.log("Scheduling one event every {} whenever at least {} players are online.",
                scheduleInterval(), plugin.getConfigManager().getEventScheduleMinPlayers());
    }

    private void considerScheduledEvent() {
        if (currentEventId != null || !network.isAvailable() || !journal.isDue(windowSeconds() * 1000L)) {
            return;
        }

        int online = countEligiblePlayers();
        if (online < plugin.getConfigManager().getEventScheduleMinPlayers()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String token = UUID.randomUUID().toString();
            if (!network.getClient().setIfAbsent(network.getKeys().scheduleWindow(), token, windowLockSeconds())) {
                return;
            }

            String problem = tryPropose(false);
            if (problem == null) {
                plugin.log("Scheduled event offered with {} player(s) online; next window in {}.", online, scheduleInterval());
                return;
            }

            network.getClient().deleteIfEquals(network.getKeys().scheduleWindow(), token);
            plugin.log("Scheduled event skipped: {}", problem);
        });
    }

    private String scheduleInterval() {
        double hours = plugin.getConfigManager().getEventScheduleIntervalHours();
        return hours == Math.rint(hours) ? (long) hours + "h" : hours + "h";
    }

    private int windowSeconds() {
        double hours = plugin.getConfigManager().getEventScheduleIntervalHours();
        return (int) Math.max(60L, Math.round(hours * 3600.0));
    }

    private int windowLockSeconds() {
        long now = System.currentTimeMillis();
        return (int) Math.max(60L, (ScheduleJournal.nextDueAfter(now, windowSeconds() * 1000L) - now) / 1000L);
    }

    private int countEligiblePlayers() {
        return (int) Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.hasPermission("mythicbedwars.event.exempt"))
                .count();
    }

    public ScheduleOutlook outlook() {
        return new ScheduleOutlook(
                plugin.getConfigManager().isEventScheduleEnabled(),
                currentEventId != null,
                journal.millisUntilDue(windowSeconds() * 1000L),
                countEligiblePlayers(),
                plugin.getConfigManager().getEventScheduleMinPlayers());
    }

    public long windowStamp() {
        return journal.getLastOfferedAt();
    }

    public String describeSchedule() {
        if (!plugin.getConfigManager().isEventScheduleEnabled()) {
            return "disabled";
        }

        String due = ScheduleJournal.describeDuration(journal.millisUntilDue(windowSeconds() * 1000L));
        return "every " + scheduleInterval() + " with "
               + plugin.getConfigManager().getEventScheduleMinPlayers() + "+ players online ("
               + countEligiblePlayers() + " now); last offered " + journal.describeLastOffered()
               + ", next " + (due == null ? "due now" : "in " + due);
    }

    public Optional<String> currentEventId() {
        return Optional.ofNullable(currentEventId);
    }

    public EventState currentState() {
        return currentState;
    }

    public Optional<String> arena() {
        return Optional.ofNullable(arenaName);
    }

    public Optional<String> host() {
        return Optional.ofNullable(hostServerId);
    }

    public Optional<String> hostServerName() {
        return Optional.ofNullable(hostServerName);
    }

    public void propose(boolean ignoreCooldown, Consumer<String> feedback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String outcome = tryPropose(ignoreCooldown);
            Bukkit.getScheduler().runTask(plugin, () -> feedback.accept(outcome));
        });
    }

    private String tryPropose(boolean ignoreCooldown) {
        if (!plugin.getConfigManager().isEventEnabled()) {
            return "Events are disabled in the config.";
        }
        if (!network.isAvailable()) {
            return "Redis is unavailable - cannot reach the Bedwars server.";
        }
        if (currentEventId != null) {
            return "An event is already in flight (" + currentEventId + ", " + currentState + ").";
        }
        if (!ignoreCooldown && store.isOnCooldown()) {
            return "An event ran recently; still on cooldown.";
        }

        network.getRegistry().invalidate();
        if (network.getRegistry().bestMinigameHost().isEmpty()) {
            return "No Bedwars server is online to host.";
        }

        String lockToken = UUID.randomUUID().toString();
        if (!network.getClient().setIfAbsent(network.getKeys().proposeLock(), lockToken, PROPOSE_LOCK_TTL_SECONDS)) {
            return "Another server is proposing an event right now.";
        }

        try {
            return proposeLocked();
        } finally {
            network.getClient().deleteIfEquals(network.getKeys().proposeLock(), lockToken);
        }
    }

    private String proposeLocked() {
        if (store.activeEventId().isPresent()) {
            return "An event is already active on the network.";
        }

        String eventId = UUID.randomUUID().toString();
        if (!store.claimActiveSlot(eventId)) {
            return "Could not claim the network event slot.";
        }

        int minPlayers = plugin.getConfigManager().getEventMinPlayers();
        int maxPlayers = plugin.getConfigManager().getEventMaxPlayers();
        String thisServer = plugin.getConfigManager().getThisVelocityServer();

        store.write(new EventRecord(eventId, EventState.PROPOSED, network.getServerId(), thisServer,
                null, null, null, minPlayers, maxPlayers, System.currentTimeMillis(), 0L, null));
        currentEventId = eventId;
        currentState = EventState.PROPOSED;

        boolean published = network.getBus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_PROPOSE, eventId,
                new ProposePayload(network.getServerId(), thisServer, minPlayers, maxPlayers, expectedTurnout()));
        if (!published) {
            store.purge(eventId);
            Bukkit.getScheduler().runTask(plugin, this::clearLocal);
            return "Could not reach the Bedwars server; nothing was announced.";
        }

        // every offer spends the window, admin-started ones included; only the cooldown is overridable
        journal.recordOffered(eventId);
        network.getClient().setWithTtl(network.getKeys().scheduleWindow(), eventId, windowLockSeconds());

        long timeoutTicks = Math.max(20L, plugin.getConfigManager().getEventProposeTimeoutSeconds() * 20L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            cancelProposeTimeout();
            proposeTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> onProposeTimedOut(eventId), timeoutTicks);
        });

        plugin.log("Proposed event {} (min {}, max {}).", eventId, minPlayers, maxPlayers);
        return null;
    }

    private void onProposeTimedOut(String eventId) {
        proposeTimeoutTask = null;
        if (!isCurrent(eventId) || currentState != EventState.PROPOSED) {
            return;
        }

        plugin.log("No Bedwars server answered event {} within {}s; abandoning it.",
                eventId, plugin.getConfigManager().getEventProposeTimeoutSeconds());
        clearAndRelease(eventId);
    }

    private void cancelProposeTimeout() {
        BukkitTask task = proposeTimeoutTask;
        if (task != null) {
            task.cancel();
            proposeTimeoutTask = null;
        }
    }

    private int expectedTurnout() {
        return Math.max(plugin.getConfigManager().getEventMinPlayers(),
                Math.min(plugin.getConfigManager().getEventMaxPlayers(), Bukkit.getOnlinePlayers().size()));
    }

    private void onAccept(Envelope envelope) {
        if (!isCurrent(envelope.getEventId())) {
            return;
        }

        if (currentState != EventState.PROPOSED) {
            plugin.log("Ignoring duplicate accept for event {}.", envelope.getEventId());
            return;
        }

        AcceptPayload accept = network.getBus().payload(envelope, AcceptPayload.class);
        if (accept == null) {
            return;
        }

        cancelProposeTimeout();
        hostServerId = accept.getMinigameServerId();
        hostServerName = accept.getMinigameServerName();
        arenaName = accept.getArenaName();
        currentState = EventState.ACCEPTED;

        plugin.log("Event {} accepted by {} on arena {} (capacity {}).",
                envelope.getEventId(), accept.getMinigameServerId(), accept.getArenaName(), accept.getArenaCapacity());

        // the signup script admits clicks only once the stored state reads ANNOUNCED, so persist first
        String eventId = envelope.getEventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.read(eventId)
                    .map(record -> record.accepted(accept.getMinigameServerId(), accept.getMinigameServerName(),
                                    accept.getArenaName(), accept.getArenaCapacity(), accept.getSignupDeadline())
                            .withState(EventState.ANNOUNCED))
                    .ifPresent(store::write);

            Bukkit.getScheduler().runTask(plugin, () -> beginRecruiting(eventId, accept));
        });
    }

    private void beginRecruiting(String eventId, AcceptPayload accept) {
        if (!isCurrent(eventId)) {
            return;
        }

        int resolvedCap = Math.min(plugin.getConfigManager().getEventMaxPlayers(), accept.getArenaCapacity());
        if (resolvedCap < plugin.getConfigManager().getEventMinPlayers()) {
            plugin.log("Event {}: host offered room for only {}; not worth announcing.", eventId, resolvedCap);
            clearAndRelease(eventId);
            return;
        }

        long remaining = accept.getSignupDeadline() - System.currentTimeMillis();
        if (remaining < MIN_ANNOUNCE_WINDOW_MILLIS) {
            plugin.log("Event {}: only {}ms of signup window left; abandoning before announcing.", eventId, remaining);
            clearAndRelease(eventId);
            return;
        }

        stopSignupTask();
        signupsClosing.set(false);
        currentState = EventState.ANNOUNCED;
        cap = resolvedCap;

        long secondsLeft = remaining / 1000;
        skipPassedReminders(secondsLeft);
        announcer.announceOpen(0, cap, secondsLeft, this::join);
        startSignupTicker(eventId, accept.getSignupDeadline());
    }

    private void skipPassedReminders(long secondsLeft) {
        firedReminders.clear();
        for (int threshold : plugin.getConfigManager().getEventSignupReminders()) {
            if (threshold >= secondsLeft) {
                firedReminders.add(threshold);
            }
        }
    }

    private void startSignupTicker(String eventId, long deadline) {
        signupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickSignupWindow(eventId, deadline), 20L, 20L);
    }

    private void tickSignupWindow(String eventId, long deadline) {
        if (!isCurrent(eventId) || currentState != EventState.ANNOUNCED) {
            stopSignupTask();
            return;
        }

        long secondsLeft = (deadline - System.currentTimeMillis()) / 1000;
        if (secondsLeft <= 0) {
            stopSignupTask();
            closeSignups(eventId);
            return;
        }

        for (int threshold : plugin.getConfigManager().getEventSignupReminders()) {
            if (secondsLeft <= threshold && firedReminders.add(threshold)) {
                announcer.announceReminder(lastKnownCount, cap, secondsLeft, this::join);
                break;
            }
        }
    }

    private void closeSignups(String eventId) {
        if (!signupsClosing.compareAndSet(false, true)) {
            return;
        }

        String host = hostServerId;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!isCurrent(eventId)) {
                return;
            }

            // close the stored state before reading the roster, or a late click joins Redis but not the sent roster
            store.read(eventId).map(record -> record.withState(EventState.SIGNUP_CLOSED)).ifPresent(store::write);

            Set<UUID> roster = roster(eventId);
            if (roster.size() < plugin.getConfigManager().getEventMinPlayers()) {
                cancelForTooFewSignups(eventId, roster.size());
            } else if (host == null) {
                plugin.log("Event {}: no host recorded; cannot close the roster.", eventId);
                Bukkit.getScheduler().runTask(plugin, this::clearLocal);
            } else {
                sendRoster(eventId, host, roster);
            }
        });
    }

    private void cancelForTooFewSignups(String eventId, int count) {
        int minPlayers = plugin.getConfigManager().getEventMinPlayers();
        plugin.log("Event {} cancelled: only {}/{} signed up.", eventId, count, minPlayers);

        cancelOnNetwork(eventId, CancelReason.TOO_FEW_SIGNUPS);
        store.startCooldown(plugin.getConfigManager().getEventCooldownMinutes() * 30);

        Bukkit.getScheduler().runTask(plugin, () -> {
            announcer.broadcast("magic.event.cancelled.not_enough", "count", count, "min", minPlayers);
            clearLocal();
        });
    }

    private void sendRoster(String eventId, String host, Set<UUID> roster) {
        List<String> ids = roster.stream().map(UUID::toString).toList();
        network.getBus().send(NetworkRole.MINIGAME, MessageType.ROSTER_CLOSED, eventId, host,
                new RosterClosedPayload(ids, ids.size()));
        plugin.log("Event {} roster closed with {} player(s).", eventId, ids.size());

        Bukkit.getScheduler().runTask(plugin, () -> {
            // ARENA_READY may already have moved us on; clobbering TRANSFERRING would allow a second transfer
            if (currentState == EventState.ANNOUNCED) {
                currentState = EventState.SIGNUP_CLOSED;
            }
        });
    }

    public void join(Player player) {
        String eventId = currentEventId;
        if (eventId == null || currentState != EventState.ANNOUNCED) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.signup.closed"));
            return;
        }

        UUID playerId = player.getUniqueId();
        int seats = cap;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long reply = network.getClient().evalLong(LuaScripts.SIGNUP,
                    List.of(network.getKeys().eventRoster(eventId), network.getKeys().event(eventId)),
                    List.of(playerId.toString(), Integer.toString(seats),
                            Integer.toString(plugin.getConfigManager().getEventTtlSeconds())),
                    Long.MIN_VALUE);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    answerSignup(player, eventId, reply);
                }
            });
        });
    }

    private void answerSignup(Player player, String eventId, long reply) {
        if (reply >= 0) {
            confirmSignup(player, eventId, (int) reply);
            return;
        }

        String key;
        if (reply == LuaScripts.SIGNUP_CLOSED) {
            key = "magic.event.signup.closed";
        } else if (reply == LuaScripts.SIGNUP_DUPLICATE) {
            key = "magic.event.signup.already";
        } else if (reply == LuaScripts.SIGNUP_FULL) {
            key = "magic.event.signup.full";
        } else {
            key = "magic.event.signup.error";
        }
        player.sendMessage(plugin.getLocaleManager().formatMessage(player, key));
    }

    private void confirmSignup(Player player, String eventId, int position) {
        lastKnownCount = position;
        player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                "magic.event.signup.confirmed", "count", position, "max", cap));
        announcer.announceSignup(player.getName(), position, cap);

        if (position >= cap) {
            plugin.log("Event {} reached capacity; closing signups early.", eventId);
            stopSignupTask();
            closeSignups(eventId);
        }
    }

    private Set<UUID> roster(String eventId) {
        return network.getClient().smembers(network.getKeys().eventRoster(eventId)).stream()
                .map(RecruitmentManager::parseUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public void previewAnnouncement(CommandSender viewer) {
        announcer.preview(viewer, plugin.getConfigManager().getEventMaxPlayers(),
                plugin.getConfigManager().getEventSignupSeconds());
    }

    private void stopSignupTask() {
        BukkitTask task = signupTask;
        if (task != null) {
            task.cancel();
            signupTask = null;
        }
    }

    private void onArenaReady(Envelope envelope) {
        if (!isCurrent(envelope.getEventId())) {
            return;
        }

        ArenaReadyPayload ready = network.getBus().payload(envelope, ArenaReadyPayload.class);
        if (ready == null || currentState == EventState.TRANSFERRING || currentState == EventState.RUNNING) {
            return;
        }

        currentState = EventState.TRANSFERRING;
        String eventId = envelope.getEventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<UUID> roster = roster(eventId);
            Bukkit.getScheduler().runTask(plugin, () -> transferRoster(eventId, roster, ready));
        });
    }

    private void transferRoster(String eventId, Set<UUID> roster, ArenaReadyPayload ready) {
        String target = ready.getMinigameServerName();
        for (UUID playerId : roster) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                        "magic.event.signup.transferring", "server", target));
            }
        }

        // never send past the host's arrival window: a late arrival is refused after crossing the proxy
        int stagger = Math.max(1, plugin.getConfigManager().getTransferStaggerTicks());
        long budgetTicks = Math.max(0, (ready.getTransferDeadline() - System.currentTimeMillis()) / 50L);
        int seats = budgetTicks <= 0 ? 0 : (int) Math.max(1, budgetTicks / stagger);

        List<UUID> travelling = roster.stream().limit(seats).toList();
        roster.stream().skip(seats).forEach(late -> {
            plugin.log("No time left in the arrival window for {}; not transferring them.", late);
            markNoShow(eventId, late);
        });

        plugin.getTransferService().transferStaggered(travelling, target, missed -> markNoShow(eventId, missed));
        plugin.log("Transferring {} of {} player(s) to {} for event {}.", travelling.size(), roster.size(), target, eventId);
    }

    private void markNoShow(String eventId, UUID playerId) {
        plugin.log("Signed-up player {} was not online to transfer.", playerId);

        String host = hostServerId;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            network.getClient().srem(network.getKeys().eventRoster(eventId), playerId.toString());
            if (host != null) {
                network.getBus().send(NetworkRole.MINIGAME, MessageType.PLAYER_RETURN, eventId, host,
                        new PlayerReturnPayload(playerId.toString(), ReturnOutcome.NO_SHOW));
            }
        });
    }

    private void onPlayerReturn(Envelope envelope) {
        PlayerReturnPayload returning = network.getBus().payload(envelope, PlayerReturnPayload.class);
        UUID playerId = returning == null ? null : parseUuid(returning.getUuid());
        if (playerId != null) {
            plugin.getReturnGreeter().greetWhenReady(playerId, envelope.getEventId(), returning.getOutcome());
        }
    }

    private void onPlayerArrived(Envelope envelope) {
        PlayerArrivedPayload arrived = network.getBus().payload(envelope, PlayerArrivedPayload.class);
        if (arrived != null && isCurrent(envelope.getEventId())) {
            lastKnownCount = arrived.getArrived();
        }
    }

    private void onEventStarted(Envelope envelope) {
        if (!isCurrent(envelope.getEventId())) {
            return;
        }

        EventStartedPayload started = network.getBus().payload(envelope, EventStartedPayload.class);
        currentState = EventState.RUNNING;
        journal.recordStarted(envelope.getEventId());

        if (started != null) {
            announcer.broadcast("magic.event.started", "count", started.getPlayerCount());
            plugin.log("Event {} is under way on {} with {} player(s).",
                    envelope.getEventId(), started.getArenaName(), started.getPlayerCount());
        }
    }

    private void onEventFinished(Envelope envelope) {
        if (!isCurrent(envelope.getEventId())) {
            return;
        }

        EventFinishedPayload finished = network.getBus().payload(envelope, EventFinishedPayload.class);
        currentState = EventState.FINISHED;
        if (finished != null) {
            plugin.log("Event {} finished on {} ({} winners).",
                    envelope.getEventId(), finished.getArenaName(), finished.getWinners().size());
        }

        int cooldownSeconds = plugin.getConfigManager().getEventCooldownMinutes() * 60;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.startCooldown(cooldownSeconds);
            Bukkit.getScheduler().runTask(plugin, this::clearLocal);
        });
    }

    private void onReject(Envelope envelope) {
        if (!isCurrent(envelope.getEventId())) {
            return;
        }

        RejectPayload reject = network.getBus().payload(envelope, RejectPayload.class);
        plugin.log("Event {} rejected by {}: {}", envelope.getEventId(),
                reject == null ? "?" : reject.getMinigameServerId(), reject == null ? "unspecified" : reject.getReason());

        // with several hosts a reject must not tear the event down; the propose timeout is the authority
        String eventId = envelope.getEventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean claimed = network.getClient().get(network.getKeys().eventHost(eventId)).isPresent();
            if (claimed || network.getRegistry().aliveWithRole(NetworkRole.MINIGAME).size() > 1) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (isCurrent(eventId) && currentState == EventState.PROPOSED) {
                    plugin.log("Event {} had no other candidate host; abandoning it now.", eventId);
                    clearAndRelease(eventId);
                }
            });
        });
    }

    private void onCancelled(Envelope envelope) {
        if (!isCurrent(envelope.getEventId())) {
            return;
        }

        CancelledPayload cancelled = network.getBus().payload(envelope, CancelledPayload.class);
        // Gson maps an unknown enum constant to null rather than throwing
        CancelReason reason = cancelled == null || cancelled.getReason() == null ? CancelReason.ADMIN : cancelled.getReason();

        plugin.log("Event {} cancelled ({}).", envelope.getEventId(), reason);
        abandonLocally(envelope.getEventId(), reason);
    }

    public void cancel(CancelReason reason, Consumer<String> feedback) {
        String eventId = currentEventId;
        if (eventId == null) {
            feedback.accept("No event is in flight.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            cancelOnNetwork(eventId, reason);
            Bukkit.getScheduler().runTask(plugin, () -> {
                abandonLocally(eventId, reason);
                feedback.accept("Cancelled event " + eventId + ".");
            });
        });
    }

    private void cancelOnNetwork(String eventId, CancelReason reason) {
        store.read(eventId).map(record -> record.cancelled(reason)).ifPresent(store::write);
        network.getBus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, eventId,
                new CancelledPayload(reason, network.getServerId()));
        store.retire(eventId);
    }

    private void clearAndRelease(String eventId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.purge(eventId);
            Bukkit.getScheduler().runTask(plugin, this::clearLocal);
        });
    }

    private void clearLocal() {
        stopSignupTask();
        cancelProposeTimeout();
        signupsClosing.set(false);
        firedReminders.clear();
        lastKnownCount = 0;
        cap = 0;
        currentEventId = null;
        currentState = null;
        hostServerId = null;
        hostServerName = null;
        arenaName = null;
    }

    private boolean isCurrent(String eventId) {
        return eventId != null && eventId.equals(currentEventId);
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public void recoverOnBoot() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<String> active = store.activeEventId();
            if (active.isEmpty()) {
                return;
            }

            Optional<EventRecord> stored = store.read(active.get());
            if (stored.isEmpty()) {
                store.releaseActiveSlot(active.get());
                return;
            }

            EventRecord record = stored.get();
            if (network.getServerId().equals(record.getSmpServerId()) && !record.getState().isTerminal()
                && !tryResume(record)) {
                plugin.log("Abandoning event {} left over from a previous run.", record.getEventId());
                cancelOnNetwork(record.getEventId(), CancelReason.PROPOSER_GONE);
            }
        });
    }

    private boolean tryResume(EventRecord record) {
        if (record.getState() != EventState.ANNOUNCED || record.getMinigameServerId() == null) {
            return false;
        }

        long remaining = record.getSignupDeadline() - System.currentTimeMillis();
        if (remaining > MIN_ANNOUNCE_WINDOW_MILLIS) {
            Bukkit.getScheduler().runTask(plugin, () -> resumeRecruiting(record));
            return true;
        }

        if (remaining > -MIN_ANNOUNCE_WINDOW_MILLIS) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                adoptRecord(record);
                closeSignups(record.getEventId());
            });
            return true;
        }
        return false;
    }

    private void resumeRecruiting(EventRecord record) {
        adoptRecord(record);

        long secondsLeft = Math.max(0, (record.getSignupDeadline() - System.currentTimeMillis()) / 1000);
        plugin.log("Resuming event {} with {}s of signups left.", record.getEventId(), secondsLeft);

        skipPassedReminders(secondsLeft);
        announcer.announceReminder(0, cap, secondsLeft, this::join);
        startSignupTicker(record.getEventId(), record.getSignupDeadline());
    }

    private void adoptRecord(EventRecord record) {
        currentEventId = record.getEventId();
        currentState = record.getState();
        hostServerId = record.getMinigameServerId();
        hostServerName = record.getMinigameServerName();
        arenaName = record.getArenaName();
        cap = Math.max(plugin.getConfigManager().getEventMinPlayers(),
                Math.min(plugin.getConfigManager().getEventMaxPlayers(), record.getMaxPlayers()));
        signupsClosing.set(false);
    }

    public void shutdown() {
        stopSignupTask();
        cancelProposeTimeout();

        BukkitTask scheduled = scheduleTask;
        if (scheduled != null) {
            scheduled.cancel();
            scheduleTask = null;
        }

        String eventId = currentEventId;
        if (eventId != null) {
            cancelOnNetwork(eventId, CancelReason.PROPOSER_GONE);
        }
    }
}
