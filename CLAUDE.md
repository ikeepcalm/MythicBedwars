# MythicBedwars Developer Guide

This repository contains **MythicBedwars**, a magical Bedwars addon plugin for Minecraft. It integrates the magic and Beyonder pathway system from **Circle of Imagination** into **Marcely's Bedwars (MBedwars)** gameplay.

---

## 🛠️ Build and Development Commands

This is a Gradle-based project configured for Java 25.

- **Clean build directory**:
  ```bash
  ./gradlew clean
  ```
- **Build / compile project**:
  ```bash
  ./gradlew build
  ```
- **Run local Paper server (with development build loaded)**:
  ```bash
  ./gradlew runServer
  ```

---

## 🏗️ Project Architecture & Layout

The project source files are located under `src/main/java/dev/ua/ikeepcalm/mythicBedwars/`.

```
MythicBedwars
├── cmd/                          # Command executors and autocomplete
│   ├── impls/
│   │   ├── SpectatorCommand.java # Spectator mode HUD and targeting controls
│   │   └── VotingDebugCommand.java# Debug tools for the arena voting system
│   └── CommandManager.java       # Main admin commands router
├── config/                       # Configuration and translation files loader
│   ├── ConfigLoader.java         # Accessors for config.yml
│   └── LocaleLoader.java         # Multi-language messages (EN, UK)
├── domain/                       # Core business logic
│   ├── balancer/
│   │   └── PathwayBalancer.java  # Weighted team pathway assigner (win-rate based)
│   ├── core/
│   │   ├── PathwayManager.java   # Assigns and tracks players' active pathways
│   │   ├── ShopManager.java      # Registers potion items in MBedwars shop
│   │   └── StatisticsManager.java# Gathers game metadata and stats in memory
│   ├── item/
│   │   ├── PotionItemSession.java# Custom sequence potion consumption logic
│   │   └── PotionShopItem.java   # Handles purchases of magic potions
│   ├── runnable/
│   │   ├── ActingProgressionTask.java# Ticks player acting energy gain passively
│   │   ├── PathwayVerificationTask.java# Corrects player/team pathway mismatches
│   │   └── VotingReminderTask.java# Prompts lobby players to vote
│   ├── spectator/
│   │   └── SpectatorManager.java # Handles spectator HUD overlays (BossBar/ActionBar)
│   └── stats/db/
│       ├── DatabaseMigration.java# Migrates YAML statistics to SQLite format
│       ├── PathwayStats.java     # Data model representation of pathway analytics
│       └── SQLiteDatabase.java   # Database read/write/migration layer
│   └── voting/
│       ├── model/
│       │   └── VotingSession.java# Tracks vote statuses per-arena
│       └── service/
│           └── VotingManager.java# Initiates and finishes game vote states
├── gui/
│   └── VotingGUI.java            # Chest inventory UI used to select game mode preference
├── integration/
│   └── PlanDataExtension.java    # Integrates stats into Plan Player Analytics
├── listener/                     # Game and Bukkit event handlers
│   ├── ArenaListener.java        # Reacts to Bedwars arena start, end, joins, and deaths
│   ├── DamageListener.java       # Collects damage statistics and ability usages
│   ├── PlayerListener.java       # Restricts magic items, blocks, and abilities in Bedwars
│   ├── ServerShutdownListener.java# Ensures synchronized save of SQLite statistics on stop
│   ├── SpectatorListener.java    # Updates spectators and processes clicks/targeting
│   └── VotingListener.java       # Processes dye-clicks and GUI wool selections
└── MythicBedwars.java            # Plugin main class (onEnable, onDisable, loader)
```

---

## 🎮 Command Reference

### Admin Command (`/mythicbedwars`, `/mb`, `/mbw`)
Permission required: `mythicbedwars.admin`

- `/mb toggle` – Toggles all MythicBedwars functionality globally.
- `/mb reload` – Reloads configuration and localization files.
- `/mb stats` – Prints historical pathway wins/losses/win-rates to the console.
- `/mb arena <arena> <enable/disable>` – Sets whether MythicBedwars is allowed in a specific arena.
- `/mb balance [report|info]` – Toggles or reports info on the pathway auto-balancing algorithms.
- `/mb pathways [enable|disable] <pathway>` – Enables or disables specific magic pathways.
- `/mb voting [status|force|test|clear]` – Accesses voting diagnostic settings.

### Spectator Command (`/mbspec`, `/mbspectator`, `/mythicspec`)
Permission required: `mythicbedwars.spectator`

- `/mbspec toggle <hud|actionbar|detailed>` – Configures spectator overlays (BossBar, action bar, etc.).
- `/mbspec target [player]` – Attaches your action bar tracking to a specific player.
- `/mbspec teams` – Displays a breakdown of teams and their assigned pathways.
- `/mbspec inspect <player>` – Prints a player's current sequence and acting percentage.
- `/mbspec settings` – Displays your active spectator settings.
- *Tip: Right-clicking any player while spectating instantly opens their inspect overview.*

---

## 🎨 Coding & Development Guidelines

1. **Java Version Compatibility**: Target Java 25 compilation. Avoid using features or syntax incompatible with the target runtime.
2. **Kyori Adventure API**: Always format chat messages, bossbars, and titles using the Kyori Adventure API (use `Component` components). Never hardcode section signs `§` or legacy legacyAmpersand translations directly outside of localization resource lookups.
3. **Database Rules**: All database actions (reading/saving) via `SQLiteDatabase` should be executed asynchronously on worker threads (e.g. using `CompletableFuture.runAsync` or `supplyAsync`) to prevent blocking the main server thread. The only exception is server shutdown (`onDisable`), where synchronous saves are executed to guarantee persistence.
4. **Thread Safety**: Core runtime maps in `PathwayManager` and `SpectatorManager` are shared across Bukkit tasks and asynchronous tasks. Ensure fields modified or queried across threads utilize thread-safe wrappers (like `ConcurrentHashMap` or `AtomicLong`).
5. **Addon Dependencies**:
   - `MBedwars` (version 5.5.3+) - Required. Hook events prefixed with `Arena` or `Player` (e.g. `ArenaStatusChangeEvent`, `PlayerKillPlayerEvent`).
   - `CircleOfImagination` (version 1.1.7+) - Required. Handles Beyonder creations, destruction, acting increments, and ability locks.
   - `Plan` (Optional) - Handled through reflection in `MythicBedwars#registerPlanStatistics` to prevent class-loading exceptions if the plugin is absent.
