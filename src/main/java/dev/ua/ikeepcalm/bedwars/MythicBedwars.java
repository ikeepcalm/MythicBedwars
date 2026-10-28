package dev.ua.ikeepcalm.bedwars;

import dev.ua.ikeepcalm.bedwars.command.admin.CommandManager;
import dev.ua.ikeepcalm.bedwars.command.admin.MinigameSubcommands;
import dev.ua.ikeepcalm.bedwars.command.admin.UnavailableCommand;
import dev.ua.ikeepcalm.bedwars.command.player.PlayerCommand;
import dev.ua.ikeepcalm.bedwars.command.spectator.SpectatorCommand;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import dev.ua.ikeepcalm.bedwars.config.LocaleLoader;
import dev.ua.ikeepcalm.bedwars.config.models.source.NetworkRole;
import dev.ua.ikeepcalm.bedwars.domain.arena.listener.ArenaListener;
import dev.ua.ikeepcalm.bedwars.domain.arena.listener.ProjectileCooldownListener;
import dev.ua.ikeepcalm.bedwars.domain.arena.service.ArenaEnvironmentService;
import dev.ua.ikeepcalm.bedwars.domain.item.service.MaterialGrantService;
import dev.ua.ikeepcalm.bedwars.domain.item.service.ShopManager;
import dev.ua.ikeepcalm.bedwars.domain.pathway.listener.PlayerListener;
import dev.ua.ikeepcalm.bedwars.domain.pathway.listener.ServerShutdownListener;
import dev.ua.ikeepcalm.bedwars.domain.pathway.service.PathwayBalancer;
import dev.ua.ikeepcalm.bedwars.domain.pathway.service.PathwayManager;
import dev.ua.ikeepcalm.bedwars.domain.pathway.task.ActingProgressionTask;
import dev.ua.ikeepcalm.bedwars.domain.pathway.task.PathwayVerificationTask;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.CoiCapabilities;
import dev.ua.ikeepcalm.bedwars.domain.reward.service.RewardConfig;
import dev.ua.ikeepcalm.bedwars.domain.reward.service.RewardQueue;
import dev.ua.ikeepcalm.bedwars.domain.reward.service.RewardRedeemer;
import dev.ua.ikeepcalm.bedwars.domain.reward.service.RewardService;
import dev.ua.ikeepcalm.bedwars.domain.spectator.listener.SpectatorListener;
import dev.ua.ikeepcalm.bedwars.domain.spectator.service.SpectatorManager;
import dev.ua.ikeepcalm.bedwars.domain.stats.listener.DamageListener;
import dev.ua.ikeepcalm.bedwars.domain.stats.model.PathwayStats;
import dev.ua.ikeepcalm.bedwars.domain.stats.service.DatabaseMigration;
import dev.ua.ikeepcalm.bedwars.domain.stats.service.SQLiteDatabase;
import dev.ua.ikeepcalm.bedwars.domain.stats.service.StatisticsManager;
import dev.ua.ikeepcalm.bedwars.domain.voting.listener.VotingListener;
import dev.ua.ikeepcalm.bedwars.domain.voting.service.VotingManager;
import dev.ua.ikeepcalm.bedwars.net.event.service.EventStore;
import dev.ua.ikeepcalm.bedwars.net.minigame.listener.EventArenaGuard;
import dev.ua.ikeepcalm.bedwars.net.minigame.listener.EventArenaListener;
import dev.ua.ikeepcalm.bedwars.net.minigame.service.EventOrchestrator;
import dev.ua.ikeepcalm.bedwars.net.minigame.service.EventReturnService;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.service.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.smp.listener.SmpEventListener;
import dev.ua.ikeepcalm.bedwars.net.smp.service.RecruitmentManager;
import dev.ua.ikeepcalm.bedwars.net.smp.service.ReturnGreeter;
import dev.ua.ikeepcalm.bedwars.net.smp.service.ScheduleAnnouncer;
import dev.ua.ikeepcalm.bedwars.net.task.EventReaperTask;
import dev.ua.ikeepcalm.bedwars.net.task.EventSyncTask;
import dev.ua.ikeepcalm.bedwars.net.type.EventParticipant;
import dev.ua.ikeepcalm.bedwars.net.velocity.ServerTransferService;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class MythicBedwars extends JavaPlugin {

    @Getter
    private static MythicBedwars instance;

    @Getter
    private NetworkRole networkRole = NetworkRole.MINIGAME;
    @Getter
    private ConfigLoader configManager;
    @Getter
    private LocaleLoader localeManager;
    private CommandManager commandManager;

    @Getter
    private PathwayManager arenaPathwayManager;
    private ShopManager shopManager;
    @Getter
    private MaterialGrantService materialGrantService;
    @Getter
    private ArenaEnvironmentService arenaEnvironmentService;
    @Getter
    private ProjectileCooldownListener projectileCooldownListener;
    @Getter
    private StatisticsManager statisticsManager;
    @Getter
    private PathwayBalancer pathwayBalancer;
    @Getter
    private SpectatorManager spectatorManager;
    @Getter
    private VotingManager votingManager;

    private SQLiteDatabase database;
    private BukkitTask periodicSaveTask;
    private EventReaperTask eventReaperTask;
    private EventSyncTask eventSyncTask;
    private EventStore eventStore;
    private boolean planEnableListenerRegistered;

    @Getter
    private CircleOfImaginationAPI circleOfImaginationAPI;
    @Getter
    private CoiCapabilities coiCapabilities;
    @Getter
    private NetworkService networkService;
    @Getter
    private ServerTransferService transferService;
    private EventOrchestrator eventOrchestrator;
    @Getter
    private RecruitmentManager recruitmentManager;
    @Getter
    private ScheduleAnnouncer scheduleAnnouncer;
    @Getter
    private ReturnGreeter returnGreeter;
    @Getter
    private EventReturnService returnService;
    @Getter
    private RewardService rewardService;
    private RewardConfig rewardConfig;

    @Override
    public void onEnable() {
        instance = this;
        ConfigurationSerialization.registerClass(PathwayStats.class);

        configManager = new ConfigLoader(this);
        configManager.loadConfig();
        localeManager = new LocaleLoader(this);
        localeManager.loadLocales();

        networkRole = configManager.getNetworkRole();
        if (configManager.hasUnparseableNetworkRole()) {
            log("Could not read network.role '{}' - falling back to {}. Valid values are SMP and MINIGAME.",
                    configManager.getRawNetworkRole(), networkRole);
        }

        circleOfImaginationAPI = Bukkit.getPluginManager().isPluginEnabled("CircleOfImagination")
                ? Bukkit.getServicesManager().load(CircleOfImaginationAPI.class)
                : null;
        if (circleOfImaginationAPI == null) {
            log("CircleOfImagination not found, disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        rewardConfig = new RewardConfig(this);
        loadRewards();

        transferService = new ServerTransferService(this);
        transferService.register();

        commandManager = new CommandManager(this);
        bindCommand("mythicbedwars", commandManager, commandManager);
        PlayerCommand playerCommand = new PlayerCommand(this, commandManager.getEventCommand());
        bindCommand("bedwars", playerCommand, playerCommand);

        boolean started = switch (networkRole) {
            case MINIGAME -> enableMinigameRole();
            case SMP -> enableSmpRole();
        };
        if (!started) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (configManager.isNetworkEnabled()) {
            if (!validateNetworkIdentity()) {
                log("Cross-server events are disabled until the network identity settings are fixed.");
                return;
            }
            networkService = new NetworkService(this);
            startEventSubsystem();
        }
    }

    private void loadRewards() {
        rewardConfig.load();
        coiCapabilities = CoiCapabilities.probe(circleOfImaginationAPI, rewardConfig.actingSourceName());
        if (coiCapabilities.isDegraded()) {
            log("Running against an older CircleOfImagination ({}) - event rewards will be substituted where unsupported.",
                    coiCapabilities.describe());
        }
    }

    private boolean enableMinigameRole() {
        if (!checkMBedwarsSupported()) {
            return false;
        }

        arenaPathwayManager = new PathwayManager();
        shopManager = new ShopManager(this);
        materialGrantService = new MaterialGrantService(this);
        arenaEnvironmentService = new ArenaEnvironmentService(this);

        database = new SQLiteDatabase(this);
        database.initialize();

        pathwayBalancer = new PathwayBalancer(this);
        spectatorManager = new SpectatorManager(this);
        votingManager = new VotingManager(this);

        commandManager.setMinigameSubcommands(new MinigameSubcommands(this));
        SpectatorCommand spectatorCommand = new SpectatorCommand(this);
        bindCommand("mbspec", spectatorCommand, spectatorCommand);

        projectileCooldownListener = new ProjectileCooldownListener(this);
        registerListeners(new ArenaListener(this), new PlayerListener(this), new DamageListener(this),
                new ServerShutdownListener(this), new SpectatorListener(this), new VotingListener(this),
                projectileCooldownListener);
        shopManager.scheduleRegistration();

        statisticsManager = new StatisticsManager(this);
        loadStatistics();
        return true;
    }

    private boolean enableSmpRole() {
        UnavailableCommand unavailable = new UnavailableCommand(this, "magic.commands.minigame_only");
        bindCommand("mbspec", unavailable, unavailable);
        log("Booted in SMP role - Bedwars features are inactive on this server.");
        return true;
    }

    private boolean checkMBedwarsSupported() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MBedwars")) {
            log("MBedwars not found, disabling.");
            return false;
        }

        try {
            Class<?> apiClass = Class.forName("de.marcely.bedwars.api.BedwarsAPI");
            if ((int) apiClass.getMethod("getAPIVersion").invoke(null) >= 205) {
                return true;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        log("This MBedwars version is not supported. Install at least v5.5.5.");
        return false;
    }

    private void loadStatistics() {
        new DatabaseMigration(this, database).migrateFromYaml()
                .thenCompose(migrated -> {
                    if (migrated) {
                        log("Migrated statistics from YAML to SQLite.");
                    }
                    return database.loadStatistics();
                })
                .whenComplete((loaded, error) -> {
                    if (error != null) {
                        log("Could not load statistics: {}", error.getMessage());
                    } else if (loaded != null && !loaded.isEmpty()) {
                        statisticsManager.setPathwayStatistics(loaded, database.loadTotalGames());
                    }
                    Bukkit.getScheduler().runTask(this, this::startMinigameTasks);
                });
    }

    private void startMinigameTasks() {
        registerPlanStatistics();
        new ActingProgressionTask(this).runTaskTimer(this, 20L, 20L);
        new PathwayVerificationTask(this).runTaskTimer(this, 200L, 400L);
        scheduleStatisticsSave();
    }

    private boolean validateNetworkIdentity() {
        boolean valid = true;

        if (configManager.getServerId().isBlank()) {
            log("network.server-id is not set. Give every backend its own unique id.");
            valid = false;
        }
        if (configManager.getThisVelocityServer().isBlank()) {
            log("network.velocity.this-server is not set. It must match this backend's name in velocity.toml.");
            valid = false;
        }

        String target = networkRole == NetworkRole.SMP
                ? configManager.getMinigameVelocityServer()
                : configManager.getSmpVelocityServer();
        if (target.isBlank()) {
            log("The Velocity name of the server players are sent to is not set; transfers cannot work.");
            valid = false;
        }
        return valid;
    }

    private void bindCommand(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            log("Command '{}' is missing from plugin.yml - skipping registration.", name);
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    private void registerListeners(Listener... listeners) {
        for (Listener listener : listeners) {
            Bukkit.getPluginManager().registerEvents(listener, this);
        }
    }

    private void scheduleStatisticsSave() {
        if (periodicSaveTask != null) {
            periodicSaveTask.cancel();
            periodicSaveTask = null;
        }

        int intervalSeconds = configManager.getAutoSaveInterval();
        if (intervalSeconds <= 0 || database == null || statisticsManager == null) {
            return;
        }

        long intervalTicks = intervalSeconds * 20L;
        periodicSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!database.isConnected()) {
                log("Cannot perform periodic statistics save: database not connected.");
                return;
            }
            if (!statisticsManager.getPathwayStatistics().isEmpty()) {
                database.saveStatistics(statisticsManager.getPathwayStatistics()).exceptionally(error -> {
                    log("Periodic statistics save failed: {}", error.getMessage());
                    return null;
                });
            }
        }, intervalTicks, intervalTicks);
    }

    private void startEventSubsystem() {
        eventStore = new EventStore(networkService.getClient(), networkService.getKeys(), configManager.getEventTtlSeconds());
        RewardQueue rewardQueue = new RewardQueue(this, networkService.getClient(), networkService.getKeys(), rewardConfig);

        if (isMinigameRole()) {
            startHostSide(rewardQueue);
            networkService.start();
            eventOrchestrator.recoverOnBoot();
        } else {
            startRecruitSide(rewardQueue);
            networkService.start();
            recruitmentManager.recoverOnBoot();
        }
        scheduleNetworkTasks();
    }

    private void startHostSide(RewardQueue rewardQueue) {
        returnService = new EventReturnService(this, networkService);
        rewardService = new RewardService(this, rewardConfig, rewardQueue);
        eventOrchestrator = new EventOrchestrator(this, networkService, eventStore);
        eventOrchestrator.registerHandlers();

        registerListeners(new EventArenaGuard(this, eventOrchestrator),
                new EventArenaListener(this, eventOrchestrator, returnService, rewardService));
    }

    private void startRecruitSide(RewardQueue rewardQueue) {
        recruitmentManager = new RecruitmentManager(this, networkService, eventStore);
        recruitmentManager.registerHandlers();
        returnGreeter = new ReturnGreeter(this, networkService);

        registerListeners(new SmpEventListener(this, new RewardRedeemer(this, rewardConfig, rewardQueue), returnGreeter));

        recruitmentManager.startSchedule();
        scheduleAnnouncer = new ScheduleAnnouncer(this, recruitmentManager);
        scheduleAnnouncer.start();
    }

    private void scheduleNetworkTasks() {
        if (eventReaperTask != null) {
            eventReaperTask.cancel();
        }
        if (eventSyncTask != null) {
            eventSyncTask.cancel();
        }

        long reapTicks = Math.max(1L, configManager.getEventReapIntervalSeconds()) * 20L;
        eventReaperTask = new EventReaperTask(this, networkService, eventStore);
        eventReaperTask.runTaskTimerAsynchronously(this, reapTicks, reapTicks);

        long syncTicks = Math.max(1L, configManager.getEventSyncIntervalSeconds()) * 20L;
        eventSyncTask = new EventSyncTask(this, eventStore);
        eventSyncTask.runTaskTimerAsynchronously(this, syncTicks, syncTicks);
    }

    public List<String> reload() {
        configManager.loadConfig();
        localeManager.loadLocales();
        loadRewards();

        List<String> rearmed = new ArrayList<>();
        if (networkService != null && eventStore != null) {
            scheduleNetworkTasks();
            rearmed.add("event reaper + sync");
        }
        if (recruitmentManager != null) {
            recruitmentManager.startSchedule();
            rearmed.add("event schedule");
        }
        if (scheduleAnnouncer != null) {
            scheduleAnnouncer.start();
            rearmed.add("event countdown");
        }
        if (statisticsManager != null && database != null) {
            scheduleStatisticsSave();
            rearmed.add("statistics save");
        }
        if (spectatorManager != null) {
            spectatorManager.restartUpdateTask();
            rearmed.add("spectator display");
        }
        return rearmed;
    }

    private void registerPlanStatistics() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Plan")) {
            return;
        }

        try {
            Class<?> capabilityService = Class.forName("com.djrapitops.plan.capability.CapabilityService");
            Object capabilities = capabilityService.getMethod("getInstance").invoke(null);
            if (!(boolean) capabilityService.getMethod("hasCapability", String.class).invoke(capabilities, "DATA_EXTENSION_VALUES")) {
                return;
            }

            registerPlanExtension();
            if (!planEnableListenerRegistered) {
                planEnableListenerRegistered = true;
                Consumer<Boolean> onPlanEnable = enabled -> {
                    if (enabled) {
                        registerPlanExtension();
                    }
                };
                capabilityService.getMethod("registerEnableListener", Consumer.class).invoke(capabilities, onPlanEnable);
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            log("Could not register Plan statistics: {}", exception.getMessage());
        }
    }

    private void registerPlanExtension() {
        try {
            Class<?> extensionService = Class.forName("com.djrapitops.plan.extension.ExtensionService");
            Object extensions = extensionService.getMethod("getInstance").invoke(null);
            Object extension = Class.forName("dev.ua.ikeepcalm.bedwars.domain.stats.service.PlanDataExtension")
                    .getConstructor(StatisticsManager.class)
                    .newInstance(statisticsManager);
            extensionService.getMethod("register", Class.forName("com.djrapitops.plan.extension.DataExtension"))
                    .invoke(extensions, extension);
        } catch (ReflectiveOperationException | LinkageError exception) {
            log("Could not register Plan statistics: {}", exception.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (eventSyncTask != null) {
            eventSyncTask.cancel();
        }
        if (eventReaperTask != null) {
            eventReaperTask.cancel();
        }
        if (eventOrchestrator != null) {
            eventOrchestrator.shutdown();
        }
        if (scheduleAnnouncer != null) {
            scheduleAnnouncer.stop();
        }
        if (recruitmentManager != null) {
            recruitmentManager.shutdown();
        }
        if (networkService != null) {
            networkService.shutdown();
        }
        if (periodicSaveTask != null) {
            periodicSaveTask.cancel();
        }
        if (spectatorManager != null) {
            spectatorManager.shutdown();
        }
        if (database != null) {
            if (statisticsManager != null) {
                database.saveStatisticsNow(statisticsManager.getPathwayStatistics());
            }
            database.close();
        }
        if (arenaPathwayManager != null) {
            arenaPathwayManager.cleanupAll();
        }
    }

    public Set<String> getReservedArenaNames() {
        return eventOrchestrator == null ? Set.of() : eventOrchestrator.reservations().keySet();
    }

    public int cancelHostedEvents(CancelReason reason) {
        if (eventOrchestrator == null) {
            return 0;
        }

        var held = List.copyOf(eventOrchestrator.reservations().values());
        held.forEach(reservation -> eventOrchestrator.cancel(reservation.getEventId(), reason));
        return held.size();
    }

    public EventParticipant getEventParticipant() {
        return eventOrchestrator != null ? eventOrchestrator : recruitmentManager;
    }

    public void offMainThread(Runnable task) {
        // Bukkit refuses to schedule for a disabled plugin, and onDisable runs after isEnabled() turns false.
        if (!isEnabled()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    public boolean isEventArena(String arenaName) {
        return eventOrchestrator != null && eventOrchestrator.isEventArena(arenaName);
    }

    public boolean isNetworkAvailable() {
        return networkService != null && networkService.isAvailable();
    }

    public boolean isMinigameRole() {
        return networkRole == NetworkRole.MINIGAME;
    }

    public void log(String message, Object... arguments) {
        String formatted = format(message, arguments);
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getConsoleSender().sendMessage(Component.text("[MythicBedwars]", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(" " + formatted)));
        } else {
            getLogger().info(formatted);
        }
    }

    private static String format(String template, Object... arguments) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int cursor = 0;
        for (Object argument : arguments) {
            int at = template.indexOf("{}", cursor);
            if (at < 0) {
                break;
            }
            out.append(template, cursor, at).append(argument);
            cursor = at + 2;
        }
        return out.append(template, cursor, template.length()).toString();
    }
}
