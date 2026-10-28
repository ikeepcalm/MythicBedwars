# MythicBedwars Developer Guide

**MythicBedwars** is a magical Bedwars addon. It integrates the magic and Beyonder pathway system
from **Circle of Imagination** (COI) into **Marcely's Bedwars** (MBedwars) gameplay, and runs
cross-server "event" matches recruited from a survival server.

---

## 🌐 The single most important architectural fact

**One jar, two roles.** `network.role` in `config.yml` decides which half of the plugin boots:

| Role | Where it runs | MBedwars |
|---|---|---|
| `MINIGAME` | the Bedwars server | **required** |
| `SMP` | the survival server players are recruited from | **not installed** |

MBedwars is therefore a `softdepend`, not a `depend`. On an `SMP` instance **no MBedwars class may
be loaded at all** — not via a field type, a method signature, a listener registration, a static
initialiser, or a `switch` on one of its enums. Bukkit resolves a listener's handler parameter types
eagerly at registration, so registering a Bedwars-facing listener there fails with
`NoClassDefFoundError`.

Practical rules when editing:

- Anything reaching `de.marcely.bedwars` is constructed only inside `enableMinigameRole()`.
- Role-neutral code asks the plugin class (`isEventArena`, `getReservedArenaNames`,
  `cancelHostedEvents`, `getEventParticipant`), which answers safely when the minigame half is absent.
- Subcommand *names* live in `command/admin/Subcommands.java`, deliberately apart from the class that
  implements them, so the router can recognise a Bedwars subcommand without loading it.
- `network.enabled: false` leaves the role intact and simply skips the transport, so an existing
  single-server install behaves exactly as it always did.

---

## 🛠️ Build and Development Commands

Gradle 9.1 wrapper, **Java 25**, Paper API **26.3**. The wrapper runs on the installed JDK 25; no
`JAVA_HOME` override is needed.

- **Compile**: `./gradlew compileJava`
- **Build the jar**: `./gradlew build`
- **Run a local Paper server**: `./gradlew runServer`

**Lombok** (`compileOnly` + `annotationProcessor`) generates getters, setters and constructors; the
code follows the `java-style` house style (Lombok POJOs instead of records, near-zero comments,
`domain/<feature>/{model,model/source,service,type,listener,task}`). Design reasoning lives in
`docs/DESIGN_NOTES.md` and `docs/CROSS_SERVER_EVENTS.md`, not in source comments.

There is **no shadow plugin**. Nothing is bundled into the jar: Jedis, commons-pool2 and sqlite-jdbc
are resolved at runtime through `libraries:` in `plugin.yml`, and their versions are declared once in
`build.gradle` and expanded into `plugin.yml` by `processResources`. Bump them together or not at all.

---

## 🏗️ Project Layout

Sources live under `src/main/java/dev/ua/ikeepcalm/bedwars/`.

```
MythicBedwars
├── MythicBedwars.java                # onEnable/onDisable, role split, reload, Lombok getters
├── command/
│   ├── admin/                        # /mb router (CommandManager), EventCommand (both roles),
│   │                                 #   MinigameSubcommands + VotingDebugCommand (MBedwars-facing),
│   │                                 #   Subcommands (role-neutral names/permissions),
│   │                                 #   UnavailableCommand (stub for minigame-only commands on SMP)
│   ├── player/PlayerCommand.java     # /bedwars - the player-facing half (both roles)
│   └── spectator/SpectatorCommand.java
├── config/
│   ├── ConfigLoader.java             # one getter per key, inline defaults
│   ├── LocaleLoader.java             # EN + UK, resolved per recipient
│   └── models/source/                # NetworkRole (SMP | MINIGAME), Language
├── domain/
│   ├── arena/                        # ArenaListener, ProjectileCooldownListener, ArenaEnvironmentService
│   ├── item/                         # ShopManager, MaterialGrantService, SandboxItems,
│   │                                 #   type/ shop items and sessions, model/source/MaterialKind
│   ├── pathway/                      # PathwayManager, PathwayBalancer, PlayerListener,
│   │                                 #   ServerShutdownListener, ActingProgression/PathwayVerification tasks
│   ├── reward/                       # the cross-server reward pipeline: CoiCapabilities, RewardConfig
│   │                                 #   (rewards.yml), RewardService, RewardRoller, RewardQueue (Redis
│   │                                 #   handoff, both idempotency guards), RewardRedeemer (SMP side)
│   ├── spectator/                    # SpectatorManager, SpectatorListener, SpectatorData
│   ├── stats/                        # SQLiteDatabase, DatabaseMigration, StatisticsManager,
│   │                                 #   PlanDataExtension (loaded reflectively; Plan is optional)
│   └── voting/                       # VotingManager, VotingSession, MagicMode, VotingReminderTask
├── net/
│   ├── service/NetworkService.java   # facade: client, bus, keys, heartbeat, registry
│   ├── type/EventParticipant.java    # the local half of an event, role-neutrally
│   ├── task/                         # EventReaperTask (stalled events), EventSyncTask (reconcile)
│   ├── event/                        # EventRecord, EventStore (the source of truth)
│   ├── health/                       # HeartbeatTask, ServerRegistry
│   ├── minigame/                     # host side: orchestrator, selector, reservation, guard,
│   │                                 #   listener, lobby hold, return service
│   ├── protocol/                     # Envelope, Heartbeat, payloads (model/), enums (source/)
│   ├── smp/                          # recruit side: manager, announcer, signups, listener,
│   │                                 #   ReturnGreeter, ScheduleJournal (schedule.yml),
│   │                                 #   ScheduleAnnouncer (the player-facing countdown)
│   ├── transport/                    # RedisClient, RedisBus, RedisKeys, LuaScripts
│   └── velocity/ServerTransferService.java
└── util/
    ├── command/Completions.java      # tab-completion prefix filter
    └── config/                       # ConfigBackfiller (config.yml), LocaleBackfiller (lang-*.yml)
```

Resources: `config.yml`, `rewards.yml`, `plugin.yml`, `lang/lang-en.yml`, `lang/lang-uk.yml`. YAML
carries no comment blocks, at most a one-line unit hint.
`schedule.yml` is written at runtime, not bundled: it is the SMP's record of when the last event
went out, so `network.event.schedule` survives a restart of the server or of Redis.
Feature documentation lives in `docs/CROSS_SERVER_EVENTS.md`, design notes in `docs/DESIGN_NOTES.md`.

---

## 🎮 Command Reference

### `/mythicbedwars` (`/mb`, `/mbw`)

Not permission-gated as a command — see `CommandManager`'s javadoc for why. `mythicbedwars.admin`
is checked per subcommand.

| Subcommand | Role | Permission |
|---|---|---|
| `event join` | SMP | `mythicbedwars.event.join` (default true) |
| `event next` | SMP | `mythicbedwars.event.join` (default true) |
| `event status` | both | admin |
| `event preview` | SMP | admin |
| `event start` | SMP | admin |
| `event cancel` | both | admin |
| `event send <player> <smp\|minigame\|server>` | both | admin |
| `toggle` | both | admin |
| `reload` | both | admin — config, locales **and** `rewards.yml`; re-arms interval tasks |
| `stats`, `arena`, `balance`, `pathways`, `voting` | MINIGAME | admin |

`mythicbedwars.event.exempt` (default false) opts the holder out of recruitment broadcasts.

### `/bedwars`

The player-facing command; `/mythicbedwars` stays the admin tool. Role-neutral, registered on both
backends, and not permission-gated at the command level for the same reason as `/mb`. Bare
`/bedwars` answers the common question (when is the next one) and points at `join`.

| Subcommand | Role | Permission |
|---|---|---|
| *(none)* / `next` / `when` | SMP | `mythicbedwars.event.join` (default true) |
| `join` | SMP | `mythicbedwars.event.join` (default true) |

Declared with **no aliases on purpose**: MBedwars owns `/bw` and very likely lists `bedwars` among
its own aliases. A primary command name beats another plugin's alias in Bukkit's command map, so
`/bedwars` should win — but `/mb event next`, `/mb event join` and `/mythicbedwars:bedwars` remain
as fallbacks if it ever does not.

### `/mbspec` (`/mbspectator`, `/mythicspec`)

`mythicbedwars.spectator`. Bound to a stub in the SMP role.

`toggle <hud|actionbar|detailed>` · `target [player]` · `teams` · `inspect <player>` · `settings`.
Right-clicking a player while spectating opens their inspect overview.

---

## 🔮 Magic modes

The lobby vote is a **three-way** `MagicMode`, not a boolean:

| Mode | Pathways |
|---|---|
| `TEAM` | one per team, shared — the original behaviour and the default |
| `INDIVIDUAL` | one per player, drawn distinctly across the whole arena |
| `OFF` | ordinary Bedwars |

`INDIVIDUAL` exists because pathways are not equally suited to Bedwars, so a team that drew a
non-combat one was behind before the round started. Spreading the draw over players leaves every
team holding a mix.

Consequences worth knowing:

- **Ask the player, not the team.** `PathwayManager#getPlayerPathway` is what nearly every caller
  wants. `getTeamPathway` is only correct in `TEAM` mode; `getTeamPathwayDisplay` is the spectator
  view, and lists several pathways per team in `INDIVIDUAL` mode.
- **`getExpectedPathway` is deliberately not `getPlayerPathway`.** The verification task compares
  the two, so reading the expected value out of the player's own state would make the comparison
  tautological.
- **Statistics go through `getRoundOutcomes`,** not through teams: in `INDIVIDUAL` mode a team
  holds several pathways and a pathway may sit on several teams.
- **Individual draws are lazy,** made as each loadout opens, because a player's team is not settled
  until MBedwars' auto-balancer has run — and the draw records the team it was made for, so an
  eliminated player still counts at `RoundEndEvent`.

---

## 🎨 Coding Guidelines

1. **Java 25**, Paper **26.3**, Lombok. Follow the `java-style` house style.
2. **Kyori Adventure** for all chat, bossbars and titles. Never hardcode `§`; legacy `&` codes belong
   only in the locale files, which `LocaleLoader` deserializes with `legacyAmpersand()`. A
   MiniMessage tag in a locale string renders **literally**.
3. **Locale strings use flat `{name}` placeholders.** Use the `CommandSender` overloads
   (`formatMessage(sender, key, …)`) whenever the recipient is known — the plain overloads always
   resolve to EN.
4. **Database work is async** via `CompletableFuture`, except in `onDisable`, where synchronous saves
   guarantee persistence.
5. **Thread safety.** `PathwayManager`, `SpectatorManager` and everything under `net/` are touched
   from Bukkit tasks, async tasks and the Redis subscriber thread. Use concurrent collections and
   `volatile`.
6. **Redis is never touched on the main thread.** `RedisBus` hops handlers *to* the main thread for
   Bukkit safety, which makes it easy to accidentally do blocking I/O there — push every round trip
   back out with `plugin.offMainThread` (which runs inline during `onDisable`, when the scheduler
   refuses new tasks).
7. **A server cannot message itself.** The bus dedups its own outgoing message ids, so publishing to
   your own role's channel is a no-op locally. Inform the local half through `EventParticipant`.
8. **`MythicBedwars`' public getters are generated by Lombok** from field names
   (`configManager` -> `getConfigManager`, `localeManager`, `arenaPathwayManager`); renaming a field
   renames the getter every other class calls.
9. **Dependencies**
   - Paper API `26.3` (`api-version: '26.3'`).
   - `MBedwars` 5.5.5+ — `softdepend`; runtime-gated in `checkMBedwarsSupported()`. Compiles against
     the 5.5.7 API.
   - `CircleOfImagination` 1.4.9-SNAPSHOT API — `depend`. Newer additions are probed at runtime by
     `CoiCapabilities`; never name a newly added enum constant as a compile-time constant.
   - `Plan` — optional, reached reflectively in `registerPlanStatistics`.
   - `Jedis` / `commons-pool2` / `sqlite-jdbc` — runtime-only via `libraries:`.
   - `Lombok` 1.18.44 — compile-time only.

---

## ⚠️ Traps worth knowing

- **COI sequence numbers are inverted.** Lower is *stronger*. A token's `maxSequence` is a power
  ceiling, and the exchange GUI walks 9 *down* to it, so `9` offers only the weakest tier and `0`
  offers everything.
- **`setActingSpeedMultiplier` / `setActingItemMultiplier` take a duration, not a timestamp.** COI
  adds "now" itself.
- **`grantActing` returns 0 for a pathway the player does not hold**, so no reward may name a
  pathway; everything resolves against their real primary one.
- **`KickReason#isRageQuit()` is far broader than its name** — it also covers `SERVER_DISCONNECT`,
  `TELEPORT`, `SPECTATE` and any third-party `PLUGIN` kick. Match on `LEAVE` explicitly.
- **`RoundEndEvent` reports every collection as empty on a tie.** That is the documented contract.
- **`Arena#setLobbyTimeRemaining` returns a boolean and does nothing below the arena's own
  `minPlayers`.** Check it.
- **`off`, `on`, `yes` and `no` are booleans in YAML 1.1**, including as *keys* — an unquoted
  `off:` parses as `false` and every lookup under it misses silently. `MagicMode#key` therefore
  maps `OFF` to `disabled` rather than relying on quotes, which `LocaleBackfiller` would have to
  re-emit correctly on every rewrite. Pick a non-boolean word for any new key.
- **A new lang key needs nothing extra to *work*** — `LocaleLoader` calls `setDefaults` with the
  bundled file — **but it needs `LocaleBackfiller` to be *visible***. That runs automatically over
  `lang-en.yml` and `lang-uk.yml`; nothing is needed per key.
- **COI casts ability projectiles as plain `Snowball`/`Arrow`/`SmallFireball` with no shared
  marker.** `ProjectileCooldownListener` therefore gates on the *launching item* — a real bow, an
  MBedwars special id, a throwable held in hand — and never on the projectile entity. Inspecting
  the entity would throttle abilities too.
- **`network.event.magic-mode: RANDOM` rolls on every read.** `ConfigLoader.resolveEventMagicMode`
  is named for it; the host resolves it once and carries the answer on `EventReservation`, because
  the value is seeded into the vote result at three points in an event's life.
