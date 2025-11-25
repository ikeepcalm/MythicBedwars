package dev.ua.ikeepcalm.mythicBedwars;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.mythicBedwars.cmd.CommandManager;
import dev.ua.ikeepcalm.mythicBedwars.cmd.impls.SpectatorCommand;
import dev.ua.ikeepcalm.mythicBedwars.config.ConfigLoader;
import dev.ua.ikeepcalm.mythicBedwars.config.LocaleLoader;
import dev.ua.ikeepcalm.mythicBedwars.domain.balancer.PathwayBalancer;
import dev.ua.ikeepcalm.mythicBedwars.domain.core.PathwayManager;
import dev.ua.ikeepcalm.mythicBedwars.domain.core.ShopManager;
import dev.ua.ikeepcalm.mythicBedwars.domain.core.StatisticsManager;
import dev.ua.ikeepcalm.mythicBedwars.domain.runnable.ActingProgressionTask;
import dev.ua.ikeepcalm.mythicBedwars.domain.runnable.PathwayVerificationTask;
import dev.ua.ikeepcalm.mythicBedwars.domain.spectator.SpectatorManager;
import dev.ua.ikeepcalm.mythicBedwars.domain.stats.db.DatabaseMigration;
import dev.ua.ikeepcalm.mythicBedwars.domain.stats.db.PathwayStats;
import dev.ua.ikeepcalm.mythicBedwars.domain.stats.db.SQLiteDatabase;
import dev.ua.ikeepcalm.mythicBedwars.domain.voting.service.VotingManager;
import dev.ua.ikeepcalm.mythicBedwars.listener.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class MythicBedwars extends JavaPlugin {

    private static MythicBedwars instance;
    private ConfigLoader configLoader;
    private LocaleLoader localeLoader;
    private PathwayManager pathwayManager;
    private ShopManager shopManager;
    private StatisticsManager statisticsManager;
    private CommandManager commandManager;
    private PathwayBalancer pathwayBalancer;
    private SpectatorManager spectatorManager;
    private VotingManager votingManager;

    private SQLiteDatabase database;
    private BukkitTask periodicSaveTask;
    private int saveIntervalSeconds;

    private CircleOfImaginationAPI circleOfImaginationAPI;

    public static MythicBedwars getInstance() {
        return instance;
    }

    public CircleOfImaginationAPI getCircleOfImaginationAPI() {
        return this.circleOfImaginationAPI;
    }

    @Override
    public void onEnable() {
        instance = this;

        ConfigurationSerialization.registerClass(PathwayStats.class);

        CircleOfImaginationAPI coiApi = loadCircleOfImagination();
        if (coiApi != null) {
            circleOfImaginationAPI = coiApi;
        } else {
            log("CircleOfImagination not found! Disabling addon...");
            getServer().getPluginManager().disablePlugin(this);
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("MBedwars")) {
            log("MBedwars not found! Disabling addon...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        } else {
            final int supportedAPIVersion = 205;
            final String supportedVersionName = "5.5.5";

            try {
                Class<?> apiClass = Class.forName("de.marcely.bedwars.api.BedwarsAPI");
                int apiVersion = (int) apiClass.getMethod("getAPIVersion").invoke(null);

                if (apiVersion < supportedAPIVersion) throw new IllegalStateException();
            } catch (Exception e) {
                log("Sorry, your installed version of MBedwars is not supported. Please install at least v" + supportedVersionName);
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("CircleOfImagination")) {
            log("CircleOfImagination not found! Disabling addon...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        configLoader = new ConfigLoader(this);
        configLoader.loadConfig();

        this.saveIntervalSeconds = configLoader.getAutoSaveInterval();

        localeLoader = new LocaleLoader(this, LocaleLoader.Locale.UK);
        localeLoader.loadLocales();

        pathwayManager = new PathwayManager();
        shopManager = new ShopManager(this);

        database = new SQLiteDatabase(this);
        database.initialize();

        pathwayBalancer = new PathwayBalancer(this);
        commandManager = new CommandManager(this);
        spectatorManager = new SpectatorManager(this);
        votingManager = new VotingManager(this);

        Objects.requireNonNull(getCommand("mythicbedwars")).setExecutor(commandManager);
        Objects.requireNonNull(getCommand("mythicbedwars")).setTabCompleter(commandManager);

        Objects.requireNonNull(getCommand("mb")).setExecutor(commandManager);
        Objects.requireNonNull(getCommand("mb")).setTabCompleter(commandManager);

        SpectatorCommand spectatorCommand = new SpectatorCommand(this);
        Objects.requireNonNull(getCommand("mbspec")).setExecutor(spectatorCommand);
        Objects.requireNonNull(getCommand("mbspec")).setTabCompleter(spectatorCommand);

        statisticsManager = new StatisticsManager(this);

        CompletableFuture<Void> loadingFuture = loadStatistics().thenRun(() -> {
            registerEvents();
            registerShopItems();

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

            log("BedwarsMagicAddon enabled!");
        });
    }

    private CircleOfImaginationAPI loadCircleOfImagination() {
        Plugin coiPlugin = getServer().getPluginManager().getPlugin("CircleOfImagination");

        if (coiPlugin != null) {
            log("CircleOfImagination found, enabling Mythic Bedwars!");
            CircleOfImaginationAPI api = Bukkit.getServer().getServicesManager().load(CircleOfImaginationAPI.class);
            if (api != null) {
                return api;
            } else {
                log("CircleOfImagination API not found, disabling Venture To The Subspace");
                getServer().getPluginManager().disablePlugin(this);
            }
        }

        return null;
    }

    @Override
    public void onDisable() {
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

        log("BedwarsMagicAddon disabled!");
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

    private void registerShopItems() {
        BedwarsAPI.onReady(() -> {
            shopManager.registerPotionItems();
        });
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
                    Class<?> planExtensionClass = Class.forName("dev.ua.ikeepcalm.mythicBedwars.integration.PlanDataExtension");
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

    public void log(String message) {
        Bukkit.getConsoleSender().sendMessage(Component.text("[MythicBedwars]").color(NamedTextColor.LIGHT_PURPLE).append(Component.text(" " + message)));
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

    public List<String> getArenaNames() {
        return BedwarsAPI.getGameAPI().getArenas().stream()
                .map(Arena::getName)
                .collect(Collectors.toList());
    }

    public List<String> getAvailablePathways() {
        return new ArrayList<>(circleOfImaginationAPI.getAllPathwayNames());
    }
}