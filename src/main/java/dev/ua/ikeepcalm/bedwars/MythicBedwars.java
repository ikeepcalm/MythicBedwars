package dev.ua.ikeepcalm.bedwars;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.bedwars.cmd.CommandManager;
import dev.ua.ikeepcalm.bedwars.cmd.impls.MinigameSubcommands;
import dev.ua.ikeepcalm.bedwars.cmd.impls.SpectatorCommand;
import dev.ua.ikeepcalm.bedwars.cmd.impls.UnavailableCommand;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import dev.ua.ikeepcalm.bedwars.config.LocaleLoader;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.domain.balancer.PathwayBalancer;
import dev.ua.ikeepcalm.bedwars.domain.core.PathwayManager;
import dev.ua.ikeepcalm.bedwars.domain.core.ShopManager;
import dev.ua.ikeepcalm.bedwars.domain.core.StatisticsManager;
import dev.ua.ikeepcalm.bedwars.domain.reward.CoiCapabilities;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.minigame.EventArenaGuard;
import dev.ua.ikeepcalm.bedwars.net.minigame.EventOrchestrator;
import dev.ua.ikeepcalm.bedwars.net.smp.RecruitmentManager;
import dev.ua.ikeepcalm.bedwars.net.velocity.ServerTransferService;
import dev.ua.ikeepcalm.bedwars.domain.runnable.ActingProgressionTask;
import dev.ua.ikeepcalm.bedwars.domain.runnable.PathwayVerificationTask;
import dev.ua.ikeepcalm.bedwars.domain.spectator.SpectatorManager;
import dev.ua.ikeepcalm.bedwars.domain.stats.db.DatabaseMigration;
import dev.ua.ikeepcalm.bedwars.domain.stats.db.PathwayStats;
import dev.ua.ikeepcalm.bedwars.domain.stats.db.SQLiteDatabase;
import dev.ua.ikeepcalm.bedwars.domain.voting.service.VotingManager;
import dev.ua.ikeepcalm.bedwars.listener.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MythicBedwars extends JavaPlugin {

    private static MythicBedwars instance;

    private NetworkRole networkRole = NetworkRole.MINIGAME;

    private ConfigLoader configLoader;
    private LocaleLoader localeLoader;
    private CommandManager commandManager;

    private PathwayManager pathwayManager;
    private ShopManager shopManager;
    private StatisticsManager statisticsManager;
    private PathwayBalancer pathwayBalancer;
    private SpectatorManager spectatorManager;
    private VotingManager votingManager;

    private SQLiteDatabase database;
    private BukkitTask periodicSaveTask;
    private int saveIntervalSeconds;

    private CircleOfImaginationAPI circleOfImaginationAPI;
    private CoiCapabilities coiCapabilities;
    private NetworkService networkService;
    private ServerTransferService transferService;
    private EventOrchestrator eventOrchestrator;
    private RecruitmentManager recruitmentManager;

    public static MythicBedwars getInstance() {
        return instance;
    }

    public CircleOfImaginationAPI getCircleOfImaginationAPI() {
        return this.circleOfImaginationAPI;
    }

    public CoiCapabilities getCoiCapabilities() {
        return this.coiCapabilities;
    }

    /**
     * @return the cross-server plumbing, or {@code null} when {@code network.enabled} is off
     */
    public NetworkService getNetworkService() {
        return this.networkService;
    }

    public ServerTransferService getTransferService() {
        return this.transferService;
    }

    public EventOrchestrator getEventOrchestrator() {
        return this.eventOrchestrator;
    }

    /**
     * @return the arenas currently held for events, or an empty set in any role that is not hosting.
     * Role-neutral on purpose, so shared code (commands, diagnostics) never has to name a
     * minigame-only type.
     */
    public java.util.Set<String> getReservedArenaNames() {
        return eventOrchestrator == null ? java.util.Set.of() : eventOrchestrator.reservations().keySet();
    }

    /**
     * Releases every arena this server is holding for an event.
     *
     * @return how many were released
     */
    public int cancelHostedEvents(dev.ua.ikeepcalm.bedwars.net.protocol.CancelReason reason) {
        if (eventOrchestrator == null) {
            return 0;
        }

        var held = eventOrchestrator.reservations();
        held.values().forEach(reservation -> eventOrchestrator.cancel(reservation.eventId(), reason));
        return held.size();
    }

    public RecruitmentManager getRecruitmentManager() {
        return this.recruitmentManager;
    }

    /**
     * Whether an arena is currently reserved for a cross-server event.
     *
     * <p>Lives on the plugin class so {@code ArenaListener} and {@code VotingManager} can ask
     * without importing anything role-specific, and answers a safe {@code false} whenever the event
     * system is not running.
     */
    public boolean isEventArena(String arenaName) {
        return eventOrchestrator != null && eventOrchestrator.isEventArena(arenaName);
    }

    @Override
    public void onEnable() {
        instance = this;

        ConfigurationSerialization.registerClass(PathwayStats.class);

        configLoader = new ConfigLoader(this);
        configLoader.loadConfig();

        localeLoader = new LocaleLoader(this, LocaleLoader.Locale.EN);
        localeLoader.loadLocales();

        // Cross-server support is opt-in, so an existing install that never heard of it keeps
        // booting as a minigame server exactly as before.
        this.networkRole = configLoader.isNetworkEnabled()
                ? configLoader.getNetworkRole()
                : NetworkRole.MINIGAME;

        circleOfImaginationAPI = loadCircleOfImagination();
        if (circleOfImaginationAPI == null) {
            log("CircleOfImagination not found! Disabling addon...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        coiCapabilities = CoiCapabilities.probe(circleOfImaginationAPI);
        if (coiCapabilities.isDegraded()) {
            log("Running against an older CircleOfImagination ({}) - event rewards will be substituted where unsupported.",
                    coiCapabilities.describe());
        }

        // Registered in both roles: the SMP sends recruits out, the minigame server sends them home.
        transferService = new ServerTransferService(this);
        transferService.register();

        commandManager = new CommandManager(this);
        bindCommand("mythicbedwars", commandManager, commandManager);

        boolean started = switch (networkRole) {
            case MINIGAME -> enableMinigameRole();
            case SMP -> enableSmpRole();
        };

        if (!started) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (configLoader.isNetworkEnabled()) {
            networkService = new NetworkService(this);
            networkService.start();
            startEventSubsystem();
        }
    }

    /**
     * Boots every Bedwars-facing subsystem. Everything constructed or registered here reaches
     * {@code de.marcely.bedwars}, so none of it may run in the {@link NetworkRole#SMP} role — in
     * particular {@link #registerEvents()}, because Bukkit resolves each handler's parameter types
     * eagerly at registration and would fail with {@code NoClassDefFoundError}.
     */
    private boolean enableMinigameRole() {
        if (!checkMBedwarsSupported()) {
            return false;
        }

        this.saveIntervalSeconds = configLoader.getAutoSaveInterval();

        pathwayManager = new PathwayManager();
        shopManager = new ShopManager(this);

        database = new SQLiteDatabase(this);
        database.initialize();

        pathwayBalancer = new PathwayBalancer(this);
        spectatorManager = new SpectatorManager(this);
        votingManager = new VotingManager(this);

        commandManager.installMinigameSubcommands(new MinigameSubcommands(this));

        SpectatorCommand spectatorCommand = new SpectatorCommand(this);
        bindCommand("mbspec", spectatorCommand, spectatorCommand);

        registerEvents();
        shopManager.scheduleRegistration();

        statisticsManager = new StatisticsManager(this);
        loadStatistics().thenRun(this::startMinigameTasks);

        return true;
    }

    /**
     * Boots the survival-server half. MBedwars is not expected to be present, so this must not touch
     * any Bedwars-facing manager, listener, or task.
     */
    private boolean enableSmpRole() {
        UnavailableCommand unavailable = new UnavailableCommand(this, "magic.commands.minigame_only");
        bindCommand("mbspec", unavailable, unavailable);

        log("Booted in SMP role - Bedwars features are inactive on this server.");
        return true;
    }

    private boolean checkMBedwarsSupported() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MBedwars")) {
            log("MBedwars not found! Disabling addon...");
            return false;
        }

        final int supportedAPIVersion = 205;
        final String supportedVersionName = "5.5.5";

        try {
            Class<?> apiClass = Class.forName("de.marcely.bedwars.api.BedwarsAPI");
            int apiVersion = (int) apiClass.getMethod("getAPIVersion").invoke(null);

            if (apiVersion < supportedAPIVersion) throw new IllegalStateException();
        } catch (Exception e) {
            log("Sorry, your installed version of MBedwars is not supported. Please install at least v" + supportedVersionName);
            return false;
        }

        return true;
    }

    private void startMinigameTasks() {
        Bukkit.getScheduler().runTask(this, this::registerPlanStatistics);

        new ActingProgressionTask(this).runTaskTimer(this, 20L, 20L);
        new PathwayVerificationTask(this).runTaskTimer(this, 200L, 400L);

        if (this.saveIntervalSeconds > 0 && database != null && statisticsManager != null) {
            long saveIntervalTicks = this.saveIntervalSeconds * 20L;
            this.periodicSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                if (database.isConnected() && statisticsManager.getPathwayStatistics() != null && !statisticsManager.getPathwayStatistics().isEmpty()) {
                    log("Periodically saving statistics...");
                    database.saveStatistics(statisticsManager.getPathwayStatistics()).thenRun(() -> log("Periodic statistics save complete.")).exceptionally(ex -> {
                        log("Periodic statistics save failed: " + ex.getMessage());
                        return null;
                    });
                } else if (!database.isConnected()) {
                    log("Cannot perform periodic statistics save: Database not connected.");
                }
            }, saveIntervalTicks, saveIntervalTicks);
            log("Scheduled periodic statistics save every " + this.saveIntervalSeconds + " seconds.");
        } else if (this.saveIntervalSeconds <= 0) {
            log("Periodic statistics saving is disabled (save-interval-seconds <= 0).");
        }

        log("MythicBedwars enabled!");
    }

    /**
     * Brings up the half of the event system this role is responsible for. Runs after the network
     * service, because both halves need the bus to register their handlers on.
     */
    private void startEventSubsystem() {
        EventStore store = new EventStore(networkService.client(), networkService.keys(),
                configLoader.getEventTtlSeconds());

        if (isMinigameRole()) {
            eventOrchestrator = new EventOrchestrator(this, networkService, store);
            eventOrchestrator.registerHandlers();
            Bukkit.getPluginManager().registerEvents(new EventArenaGuard(this, eventOrchestrator), this);
            eventOrchestrator.recoverOnBoot();
        } else {
            recruitmentManager = new RecruitmentManager(this, networkService, store);
            recruitmentManager.registerHandlers();
            recruitmentManager.recoverOnBoot();
        }
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

    private CircleOfImaginationAPI loadCircleOfImagination() {
        if (!Bukkit.getPluginManager().isPluginEnabled("CircleOfImagination")) {
            return null;
        }

        return Bukkit.getServer().getServicesManager().load(CircleOfImaginationAPI.class);
    }

    @Override
    public void onDisable() {
        if (eventOrchestrator != null) {
            eventOrchestrator.shutdown();
            eventOrchestrator = null;
        }

        if (networkService != null) {
            networkService.shutdown();
            networkService = null;
        }

        if (periodicSaveTask != null && !periodicSaveTask.isCancelled()) {
            periodicSaveTask.cancel();
            log("Cancelled periodic statistics save task.");
        }

        if (spectatorManager != null) {
            spectatorManager.shutdown();
            log("Spectator manager shut down.");
        }

        if (statisticsManager != null && database != null && database.isConnected()) {
            log("Saving final statistics synchronously on disable...");
            database.saveStatistics(statisticsManager.getPathwayStatistics(), true).thenRun(() -> {
                log("Final statistics saved.");
            }).exceptionally(ex -> {
                log("An unexpected issue occurred with the final save's CompletableFuture: " + ex.getMessage());
                return null;
            }).thenRun(() -> {
                database.close();
                log("Database connection closed.");
            });
        } else {
            if (database != null && !database.isConnected()) {
                log("Could not save final statistics: Database not connected.");
            } else if (database != null) {
                database.close();
                log("Database connection closed (statistics or manager was null).");
            }
        }

        if (pathwayManager != null) {
            pathwayManager.cleanupAll();
        }

        log("MythicBedwars disabled!");
    }

    private CompletableFuture<Void> loadStatistics() {
        DatabaseMigration migration = new DatabaseMigration(this, database);

        return migration.migrateFromYaml().thenCompose(migrated -> {
            if (migrated) {
                log("Successfully migrated statistics from YAML to SQLite!");
            }

            return database.loadStatistics().thenAccept(loadedStats -> {
                if (loadedStats != null && !loadedStats.isEmpty()) {
                    statisticsManager.setPathwayStatistics(loadedStats);
                    log("Loaded " + loadedStats.size() + " pathway statistics entries from database.");
                } else {
                    log("No statistics data found in database.");
                }
            });
        });
    }

    private void registerEvents() {
        Bukkit.getPluginManager().registerEvents(new ArenaListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DamageListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ServerShutdownListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SpectatorListener(this), this);
        Bukkit.getPluginManager().registerEvents(new VotingListener(this), this);
    }

    private void registerPlanStatistics() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Plan")) {
            return;
        }

        if (statisticsManager == null) {
            statisticsManager = new StatisticsManager(this);
        }

        try {
            // Use reflection to avoid ClassNotFoundException when Plan is not installed
            Class<?> capabilityServiceClass = Class.forName("com.djrapitops.plan.capability.CapabilityService");
            Object capabilityServiceInstance = capabilityServiceClass.getMethod("getInstance").invoke(null);
            boolean hasCapability = (boolean) capabilityServiceClass.getMethod("hasCapability", String.class)
                    .invoke(capabilityServiceInstance, "DATA_EXTENSION_VALUES");

            if (hasCapability) {
                Class<?> extensionServiceClass = Class.forName("com.djrapitops.plan.extension.ExtensionService");
                Object extensionServiceInstance = extensionServiceClass.getMethod("getInstance").invoke(null);

                if (extensionServiceInstance != null) {
                    // Load the PlanDataExtension class using reflection
                    Class<?> planExtensionClass = Class.forName("dev.ua.ikeepcalm.bedwars.integration.PlanDataExtension");
                    Object planExtension = planExtensionClass.getConstructor(StatisticsManager.class)
                            .newInstance(statisticsManager);

                    Class<?> dataExtensionClass = Class.forName("com.djrapitops.plan.extension.DataExtension");
                    extensionServiceClass.getMethod("register", dataExtensionClass)
                            .invoke(extensionServiceInstance, planExtension);
                    log("Successfully registered Plan statistics!");

                    capabilityServiceClass.getMethod("registerEnableListener", java.util.function.Consumer.class)
                            .invoke(capabilityServiceInstance, (java.util.function.Consumer<Boolean>) isPlanEnabled -> {
                                if (isPlanEnabled) registerPlanStatistics();
                            });
                }
            }
        } catch (ClassNotFoundException e) {
            log("Plan Player Analytics not found. Plan integration disabled.");
        } catch (Exception e) {
            log("Failed to register Plan statistics: " + e.getMessage());
        }
    }

    public void log(String message, Object... objects) {
        if (message == null) return;
        String formatted = message;
        if (objects != null) {
            for (Object obj : objects) {
                String replacement = obj == null ? "null" : obj.toString();
                formatted = formatted.replaceFirst("\\{\\}", java.util.regex.Matcher.quoteReplacement(replacement));
            }
        }
        Bukkit.getConsoleSender().sendMessage(Component.text("[MythicBedwars]").color(NamedTextColor.LIGHT_PURPLE).append(Component.text(" " + formatted)));
    }

    public void log(String message) {
        Bukkit.getConsoleSender().sendMessage(Component.text("[MythicBedwars]").color(NamedTextColor.LIGHT_PURPLE).append(Component.text(" " + message)));
    }

    public NetworkRole getNetworkRole() {
        return networkRole;
    }

    public boolean isMinigameRole() {
        return networkRole == NetworkRole.MINIGAME;
    }

    public boolean isSmpRole() {
        return networkRole == NetworkRole.SMP;
    }

    public PathwayBalancer getPathwayBalancer() {
        return pathwayBalancer;
    }

    public ConfigLoader getConfigManager() {
        return configLoader;
    }

    public PathwayManager getArenaPathwayManager() {
        return pathwayManager;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public LocaleLoader getLocaleManager() {
        return localeLoader;
    }

    public StatisticsManager getStatisticsManager() {
        return statisticsManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public VotingManager getVotingManager() {
        return votingManager;
    }

    public SpectatorManager getSpectatorManager() {
        return spectatorManager;
    }

    public List<String> getAvailablePathways() {
        return new ArrayList<>(circleOfImaginationAPI.getAllPathwayNames());
    }
}
