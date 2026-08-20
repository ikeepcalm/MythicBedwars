package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.event.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.payload.Payloads;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.Consumer;

/**
 * The survival-server half: decide when to run an event, ask for a host, and hold the result.
 *
 * <p>The rule that matters most here is that <b>nothing is ever announced to players before a host
 * has accepted</b>. Advertising a match the network cannot actually run is worse than not
 * advertising one at all.
 */
public class RecruitmentManager {

    /** Guards the decide-and-propose critical section against a second SMP node. */
    private static final int PROPOSE_LOCK_TTL_SECONDS = 10;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;
    private final RecruitmentAnnouncer announcer;
    private final SignupRegistry signups;

    /**
     * Reminder thresholds already fired for the current drive, so each fires once.
     */
    private final Set<Integer> firedReminders = new HashSet<>();

    private volatile BukkitTask signupTask;
    private volatile int cap;

    /**
     * Roster size as last observed, for reminder text without an extra Redis round trip.
     */
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
        this.signups = new SignupRegistry(network.client(), network.keys(),
                plugin.getConfigManager().getEventTtlSeconds());
    }

    public void registerHandlers() {
        network.bus().on(MessageType.EVENT_ACCEPT, this::onAccept);
        network.bus().on(MessageType.EVENT_REJECT, this::onReject);
        network.bus().on(MessageType.ARENA_READY, this::onArenaReady);
        network.bus().on(MessageType.PLAYER_ARRIVED, this::onPlayerArrived);
        network.bus().on(MessageType.EVENT_STARTED, this::onEventStarted);
        network.bus().on(MessageType.EVENT_FINISHED, this::onEventFinished);
        network.bus().on(MessageType.EVENT_CANCELLED, this::onCancelled);
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

    /**
     * Attempts to start an event.
     *
     * <p>Everything up to and including the publish happens off the main thread, because each
     * precondition is a Redis round trip. {@code feedback} is invoked back on the main thread so the
     * caller can safely message a command sender.
     *
     * @param ignoreCooldown for the admin command, which should not be blocked by the quiet period
     */
    public void propose(boolean ignoreCooldown, Consumer<String> feedback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String outcome = tryPropose(ignoreCooldown);
            Bukkit.getScheduler().runTask(plugin, () -> feedback.accept(outcome));
        });
    }

    /**
     * @return {@code null} on success, otherwise a human-readable reason it did not happen
     */
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

        // Pre-flight: never advertise a game nobody can host.
        network.registry().invalidate();
        if (!network.registry().hasLiveMinigameServer()) {
            return "No Bedwars server is online to host.";
        }

        String lockToken = UUID.randomUUID().toString();
        if (!network.client().setIfAbsent(network.keys().proposeLock(), lockToken, PROPOSE_LOCK_TTL_SECONDS)) {
            return "Another server is proposing an event right now.";
        }

        try {
            if (store.activeEventId().isPresent()) {
                return "An event is already active on the network.";
            }

            String eventId = UUID.randomUUID().toString();
            if (!store.claimActiveSlot(eventId)) {
                return "Could not claim the network event slot.";
            }

            int minPlayers = plugin.getConfigManager().getEventMinPlayers();
            int maxPlayers = plugin.getConfigManager().getEventMaxPlayers();

            EventRecord record = new EventRecord(
                    eventId, EventState.PROPOSED,
                    network.serverId(), plugin.getConfigManager().getThisVelocityServer(),
                    null, null, null,
                    minPlayers, maxPlayers,
                    System.currentTimeMillis(), 0L, null);
            store.write(record);

            currentEventId = eventId;
            currentState = EventState.PROPOSED;

            network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_PROPOSE, eventId,
                    new Payloads.Propose(network.serverId(), plugin.getConfigManager().getThisVelocityServer(),
                            minPlayers, maxPlayers, Bukkit.getOnlinePlayers().size()));

            plugin.log("Proposed event {} (min {}, max {}).", eventId, minPlayers, maxPlayers);
            return null;
        } finally {
            network.client().deleteIfEquals(network.keys().proposeLock(), lockToken);
        }
    }

    private void onAccept(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.Accept accept = network.bus().payload(envelope, Payloads.Accept.class);
        if (accept == null) {
            return;
        }

        hostServerId = accept.minigameServerId();
        hostServerName = accept.minigameServerName();
        arenaName = accept.arenaName();
        currentState = EventState.ACCEPTED;

        plugin.log("Event {} accepted by {} on arena {} (capacity {}).",
                envelope.eventId(), accept.minigameServerId(), accept.arenaName(), accept.arenaCapacity());

        // Persist the transition before anybody can click JOIN: the signup script refuses unless the
        // stored state already reads ANNOUNCED, so announcing first would bounce the fastest clicker.
        // This write also covers us if the host's own write was the thing that failed - it is
        // idempotent, and it stops a later reconcile dragging us back to PROPOSED.
        String eventId = envelope.eventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.read(eventId)
                    .map(record -> record.accepted(accept.minigameServerId(), accept.minigameServerName(),
                                    accept.arenaName(), accept.arenaCapacity(), accept.signupDeadline())
                            .withState(EventState.ANNOUNCED))
                    .ifPresent(store::write);

            Bukkit.getScheduler().runTask(plugin, () -> beginRecruiting(eventId, accept));
        });
    }

    /**
     * Opens the drive: announce it, then tick down to the deadline.
     */
    private void beginRecruiting(String eventId, Payloads.Accept accept) {
        if (!isCurrent(eventId)) {
            return;
        }

        currentState = EventState.ANNOUNCED;
        cap = Math.min(plugin.getConfigManager().getEventMaxPlayers(), accept.arenaCapacity());
        firedReminders.clear();

        long secondsLeft = Math.max(0, (accept.signupDeadline() - System.currentTimeMillis()) / 1000);
        announcer.announceOpen(accept.arenaName(), 0, cap, secondsLeft, this::join);

        signupTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickSignupWindow(eventId, accept.signupDeadline()), 20L, 20L);
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
                int soFar = lastKnownCount;
                announcer.announceReminder(soFar, cap, secondsLeft, this::join);
                break;
            }
        }
    }

    /**
     * Signup window is over: either hand the roster to the host, or call the whole thing off.
     */
    private void closeSignups(String eventId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<UUID> roster = signups.roster(eventId);
            int minPlayers = plugin.getConfigManager().getEventMinPlayers();

            if (roster.size() < minPlayers) {
                plugin.log("Event {} cancelled: only {}/{} signed up.", eventId, roster.size(), minPlayers);

                store.read(eventId).map(record -> record.cancelled(CancelReason.TOO_FEW_SIGNUPS))
                        .ifPresent(store::write);
                network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, eventId,
                        new Payloads.Cancelled(CancelReason.TOO_FEW_SIGNUPS, network.serverId()));
                store.purge(eventId);

                // Half the usual quiet period: too few signups is worth retrying sooner than a
                // match that actually ran.
                store.startCooldown(plugin.getConfigManager().getEventCooldownMinutes() * 30);

                int finalCount = roster.size();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    announcer.announceCancelled(plugin.getLocaleManager().formatMessage(
                            "magic.event.cancelled.not_enough", "count", finalCount, "min", minPlayers));
                    clearLocal();
                });
                return;
            }

            List<String> ids = roster.stream().map(UUID::toString).toList();
            store.read(eventId).map(record -> record.withState(EventState.SIGNUP_CLOSED)).ifPresent(store::write);
            network.bus().send(NetworkRole.MINIGAME, MessageType.ROSTER_CLOSED, eventId, hostServerId,
                    new Payloads.RosterClosed(ids, ids.size()));

            plugin.log("Event {} roster closed with {} player(s).", eventId, ids.size());
            Bukkit.getScheduler().runTask(plugin, () -> currentState = EventState.SIGNUP_CLOSED);
        });
    }

    /**
     * Adds a player to the roster, from either the chat prompt or {@code /mb event join}.
     */
    public void join(Player player) {
        String eventId = currentEventId;
        if (eventId == null || currentState != EventState.ANNOUNCED) {
            player.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.signup.closed"));
            return;
        }

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SignupRegistry.Result result = signups.signUp(eventId, playerId, cap);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }

                switch (result.outcome()) {
                    case ADDED -> {
                        lastKnownCount = result.position();
                        player.sendMessage(plugin.getLocaleManager().formatMessage(
                                "magic.event.signup.confirmed", "count", result.position(), "max", cap));
                        announcer.announceSignup(player.getName(), result.position(), cap);
                        network.bus().broadcast(NetworkRole.MINIGAME, MessageType.ROSTER_ADD, eventId,
                                new Payloads.RosterAdd(playerId.toString(), player.getName(), result.position()));

                        if (result.position() >= cap) {
                            plugin.log("Event {} reached capacity; closing signups early.", eventId);
                            stopSignupTask();
                            closeSignups(eventId);
                        }
                    }
                    case ALREADY_SIGNED_UP -> player.sendMessage(
                            plugin.getLocaleManager().formatMessage("magic.event.signup.already"));
                    case FULL -> player.sendMessage(
                            plugin.getLocaleManager().formatMessage("magic.event.signup.full"));
                    case CLOSED -> player.sendMessage(
                            plugin.getLocaleManager().formatMessage("magic.event.signup.closed"));
                    case ERROR -> player.sendMessage(
                            plugin.getLocaleManager().formatMessage("magic.event.signup.error"));
                }
            });
        });
    }

    /**
     * Shows the announcement to one viewer without starting a drive.
     */
    public void previewAnnouncement(org.bukkit.command.CommandSender viewer) {
        announcer.preview(viewer, "<arena>",
                plugin.getConfigManager().getEventMaxPlayers(),
                plugin.getConfigManager().getEventSignupSeconds());
    }

    private void stopSignupTask() {
        BukkitTask task = signupTask;
        if (task != null) {
            task.cancel();
            signupTask = null;
        }
    }

    /**
     * The host is holding an arena. Move everybody who signed up.
     */
    private void onArenaReady(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.ArenaReady ready = network.bus().payload(envelope, Payloads.ArenaReady.class);
        if (ready == null) {
            return;
        }

        currentState = EventState.TRANSFERRING;
        String eventId = envelope.eventId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<UUID> roster = signups.roster(eventId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                for (UUID playerId : roster) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        player.sendMessage(plugin.getLocaleManager().formatMessage(
                                "magic.event.signup.transferring"));
                    }
                }

                plugin.getTransferService().transferStaggered(roster, ready.minigameServerName(),
                        missed -> plugin.log("Signed-up player {} was not online to transfer.", missed));

                plugin.log("Transferring {} player(s) to {} for event {}.",
                        roster.size(), ready.minigameServerName(), eventId);
            });
        });
    }

    private void onPlayerArrived(Envelope envelope) {
        Payloads.PlayerArrived arrived = network.bus().payload(envelope, Payloads.PlayerArrived.class);
        if (arrived != null && isCurrent(envelope.eventId())) {
            lastKnownCount = arrived.arrived();
        }
    }

    private void onEventStarted(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.EventStarted started = network.bus().payload(envelope, Payloads.EventStarted.class);
        currentState = EventState.RUNNING;

        if (started != null) {
            announcer.announceCancelled(plugin.getLocaleManager().formatMessage(
                    "magic.event.started", "count", started.playerCount()));
            plugin.log("Event {} is under way on {} with {} player(s).",
                    envelope.eventId(), started.arenaName(), started.playerCount());
        }
    }

    private void onEventFinished(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.EventFinished finished = network.bus().payload(envelope, Payloads.EventFinished.class);
        currentState = EventState.FINISHED;

        if (finished != null) {
            plugin.log("Event {} finished on {} ({} winners).",
                    envelope.eventId(), finished.arenaName(), finished.winners().size());
        }

        String eventId = envelope.eventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.startCooldown(plugin.getConfigManager().getEventCooldownMinutes() * 60);
            Bukkit.getScheduler().runTask(plugin, this::clearLocal);
        });
    }

    private void onReject(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.Reject reject = network.bus().payload(envelope, Payloads.Reject.class);
        String reason = reject == null ? "unspecified" : reject.reason();

        plugin.log("Event {} rejected by {}: {}", envelope.eventId(),
                reject == null ? "?" : reject.minigameServerId(), reason);

        // A reject is only final when nobody else could pick it up; with one Bedwars server that is
        // immediately true, so release rather than leaving the slot claimed.
        clearAndRelease(envelope.eventId());
    }

    private void onCancelled(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.Cancelled cancelled = network.bus().payload(envelope, Payloads.Cancelled.class);
        CancelReason reason = cancelled == null ? CancelReason.ADMIN : cancelled.reason();

        plugin.log("Event {} cancelled ({}).", envelope.eventId(), reason.display());

        if (currentState == EventState.ANNOUNCED) {
            announcer.announceCancelled(plugin.getLocaleManager().formatMessage(
                    "magic.event.cancelled.generic", "reason", reason.display()));
        }

        clearLocal();
    }

    /**
     * Cancels the in-flight event from this side.
     */
    public void cancel(CancelReason reason, Consumer<String> feedback) {
        String eventId = currentEventId;
        if (eventId == null) {
            feedback.accept("No event is in flight.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.read(eventId).map(record -> record.cancelled(reason)).ifPresent(store::write);
            network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, eventId,
                    new Payloads.Cancelled(reason, network.serverId()));
            store.purge(eventId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Component notice = plugin.getLocaleManager().formatMessage(
                        "magic.event.cancelled.generic", "reason", reason.display());
                if (currentState == EventState.ANNOUNCED) {
                    announcer.announceCancelled(notice);
                }
                clearLocal();
                feedback.accept("Cancelled event " + eventId + ".");
            });
        });
    }

    private void clearAndRelease(String eventId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.purge(eventId);
            Bukkit.getScheduler().runTask(plugin, this::clearLocal);
        });
    }

    private void clearLocal() {
        stopSignupTask();
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

    /**
     * Picks up an event this server proposed before a restart, or drops it if it is past saving.
     */
    public void recoverOnBoot() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<String> active = store.activeEventId();
            if (active.isEmpty()) {
                return;
            }

            Optional<EventRecord> stored = store.read(active.get());
            if (stored.isEmpty()) {
                // Slot claimed but the record is gone: nothing can be recovered from it.
                store.releaseActiveSlot(active.get());
                return;
            }

            EventRecord record = stored.get();
            if (!network.serverId().equals(record.smpServerId()) || record.state().isTerminal()) {
                return;
            }

            plugin.log("Abandoning event {} left over from a previous run.", record.eventId());
            store.write(record.cancelled(CancelReason.PROPOSER_GONE));
            network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, record.eventId(),
                    new Payloads.Cancelled(CancelReason.PROPOSER_GONE, network.serverId()));
            store.purge(record.eventId());
        });
    }

    public Optional<String> hostServerName() {
        return Optional.ofNullable(hostServerName);
    }
}
