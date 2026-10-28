
## Rewards

- A `RewardGrant` carries intent, never a resolved amount or a pathway. Percentages are sized on the SMP
  against the player's real bar (the Bedwars loadout is a throwaway Sequence 9), and `grantActing`
  returns 0 for a pathway the player does not hold, so everything resolves against their primary one.
- `RewardBundle` is Gson-serialised into Redis: its field names are the wire format. Bump
  `RewardBundle.SCHEMA` on an incompatible change; unknown schemas go to the dead-letter list.
- Apply order is `RewardKind#applyOrder`: acting before buffs (`grantActing` reads the live multipliers
  and would inflate the grant rolled alongside them), items last (a full inventory is the only retry).
- Idempotency: emit is guarded by a per-event granted set (Lua, atomic with the push), apply by a
  per-player-per-event claim key. Giving a bundle back deletes the claim and pushes in one script, so a
  concurrent drain can never see the bundle while the stale claim still exists.
- Once any grant of a bundle has landed, a failing grant is logged and skipped, never requeued, or the
  grants before it would be paid again on the next login.
- Overflowing items are requeued under `<eventId>:overflow` so the claim guard does not reject them.
- Exchange token ceilings are rolled on the Bedwars server (no reroll by reconnecting). Lower is
  stronger; a ceiling outside 1-9 (legacy bundles decode as 0) is treated as 9.
- Never name `ActingSourceCategory.EVENT` directly: it is resolved by name in `CoiCapabilities` so an
  older COI jar degrades to `PLAYER_INTERACTION` instead of failing at class load.
- The AFK ratio's denominator is samples taken while in play, not match length, so early elimination
  costs nothing. Any of movement, rotation, recent client input or a recent action counts as active.
- Personal performance is paid to everyone, winners and losers alike: the MVP score (bed 5, final 3,
  kill 0.5) times `performance.acting-percent-per-point`, capped. Win-only rewards bred camping and
  endstone boxing; with this, a 3-bed second place out-earns a winner who camped. Kills are weighted
  low because respawning opponents make them farmable.
- All Acting percentages of a bundle are merged into one grant, so the summary shows one Acting line.
- A cooldown credit that cannot apply (method ready or missing) pays `amount * cooldown-substitute-ratio`
  Acting, capped at `cooldown-substitute-percent`. A flat payout made keeping the method ready the
  better play. Non-Beyonder bottles size cooldown grants the same way.
- `RewardConfig.RETIRED_DEFAULTS` moves a value to the new bundled one only if the operator never
  changed it; edited values are left alone.
- The schedule is anchored to fixed UTC slots (`interval-hours` from the epoch), not to the last offer,
  so check delays and player-count waits cannot drift the time of day. A late offer still holds the next
  one back by half an interval.

## Pathways and the arena round

- Per-arena round state (team pathways, individual draws, teams that played, start time, the world's
  neutral flag) is torn down on `RESETTING` as well as `STOPPED`. A non-cloned arena goes
  `RUNNING -> END_LOBBY -> RESETTING -> LOBBY` and never reaches `STOPPED`; `RoundEndEvent` fires before
  `END_LOBBY`, so the statistics have already read the round by then.
- An individual draw records the team it was made for: at `RoundEndEvent` an eliminated player is on no
  team, and asking the arena then would drop them from the tally. A draw sticks across a team swap,
  since a second draw would reset the progression built on the first.
- `PathwayBalancer` hands pathways to the arena's *enabled* teams, not the teams players hold at
  `LOBBY -> RUNNING`: the auto-balancer places most players a tick later.
- Weighted draw: the balanced pool repeats a pathway once per weight point; shuffling and keeping the
  first occurrence of each name is a weighted draw without replacement.
- `PathwayManager#cleanupArena` only drops players whose magic data still belongs to that arena: a
  player who disconnected from one round may already be playing another by the time the first resets.
- Acting is only granted or taken while `isVirtualBeyonder` holds. If the sandbox failed to open the
  player still carries their real Beyonder, and kill/bed/passive acting or the death penalty would
  otherwise land on persisted progression.
- `ArenaEnvironmentService` neutralises the world's light/shadow gates while a magic round runs (arenas
  sit frozen at noon, which disables shadow abilities and punishes Nocturnality). It catches
  `LinkageError` and latches off: an older COI jar lacks the method, and retrying on every status change
  would bury the one log line explaining it.
- `ProjectileCooldownListener` gates on the launching item (bow in hand, MBedwars special item, the
  throwable's material in hand), never on the projectile entity: COI casts abilities as plain
  `Snowball`/`Arrow`/`SmallFireball` entities with no marker. Refused special items get
  `setTakingItem(false)` so the player keeps what they paid for.

## Shop items, voting, spectators, statistics

- Shop items are vouchers: a potion or crafting input resolves against who uses it and what the round
  assigned them, which MBedwars cannot know when it builds the shop at boot. Every special id is
  registered even when `shop.materials.enabled` is off, because MBedwars resolves `special-id` once when
  it reads `shop.yml`; the switch is enforced on use, so a reload is enough to flip it.
- A voucher is only taken once something was produced. A roll that finds nothing leaves the player
  holding what they paid for.
- Characteristics roll from a random allowed pathway (for Paragon the characteristic decides the
  artifact's abilities), ingredients from the buyer's own (only their recipes can use them). Pathways
  are shuffled rather than picked once, since a pathway may have nothing registered at a sequence.
- Match-issued characteristics and ingredients carry a PDC tag (`SandboxItems`) and are stripped when
  the player leaves the arena and again on the SMP: they are real COI progression items, minted out of
  iron, on a server players are transferred home from. Untagged items brought into the match are left
  alone.
- The vote tallies in two rounds: magic on or off first (`TEAM` and `INDIVIDUAL` both count as on, so
  3 team + 3 individual beats 4 off), then which kind among magic voters. Ties go to magic, then to the
  configured default mode. Below `voting.min-participation` the ballots are ignored: a magic default
  rolls a random enabled mode, an `OFF` default keeps ordinary Bedwars.
- Ballot items are recognised by a PDC tag, not by material, so an ordinary dye in a lobby hotbar is
  never swallowed as a vote. Cleanup only clears slots that still hold a ballot.
- An event arena never gets a voting session: ending one would overwrite the mode the orchestrator
  seeded at reservation (and `RANDOM` would roll again).
- Statistics are rewritten in full on every save. A load that failed disables saving for the session,
  or the first save would replace the stored history with the empty in-memory map. Saves and loads are
  serialised on the database object, so the synchronous save in `onDisable` waits for an in-flight
  periodic one instead of interleaving with its transaction. The total game count is persisted in
  `global_stats` and read back from there.

## Boot, commands and config

- `/mythicbedwars` and `/bedwars` carry no `permission:` in `plugin.yml`: Bukkit would reject an
  ordinary player before the executor ran, and `/mb event join` is the relog-proof way to sign up once a
  chat button has expired. Permissions are checked per subcommand.
- `Subcommands` holds the `/mb` subcommand names apart from `MinigameSubcommands` so the router can
  recognise a Bedwars subcommand on the SMP without loading the class that references MBedwars.
- The minigame tasks (acting progression, verification, periodic save, Plan) start after the statistics
  load completes, whether it succeeded or not, and always on the main thread.
- The Plan enable listener is registered once; Plan reloads re-register only the extension.
- `/mb reload` re-reads `config.yml`, the language files and `rewards.yml`, re-probes `CoiCapabilities`
  (the acting source lives in `rewards.yml`) and re-arms every interval-driven task. The Redis
  connection, the network role and listener registration stay restart-only.
- `ConfigBackfiller` writes keys an older `config.yml` lacks and removes retired ones. The projectile
  cooldown items are only written when the whole `items` section is absent: omitting one item is how an
  operator lifts its limit, so it must not come back on the next boot. `LocaleBackfiller` never removes
  keys: a stale line in a language file is harmless, a deleted translation is not.
- Language files are loaded into a fresh map and swapped in, so a reload never leaves a window where
  every lookup resolves to "Missing locale".
