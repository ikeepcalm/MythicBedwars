# Cross-Server Event Bedwars — Deployment & Testing

Recruits players from the survival server into a Bedwars match on the minigame server, then sends
them home with Circle of Imagination rewards applied to their **real** profile.

One jar runs on both servers; `network.role` decides which half boots.

---

## Prerequisites

| | SMP server | Bedwars server |
|---|---|---|
| MythicBedwars | required | required |
| CircleOfImagination | required (**1.4.8+** for cooldown rewards) | required |
| MBedwars | **not needed** | required (5.5.5+) |
| Redis | reachable | reachable (same instance) |
| Velocity | `bungee-plugin-message-channel = true` (the default) | same |

Both servers must reach the **same** Redis. The plugin namespaces its keys under `mythicbedwars`,
distinct from COI's `coi`, so sharing one instance is fine.

---

## Configuration

### Survival server — `plugins/MythicBedwars/config.yml`

```yaml
network:
  enabled: true
  role: SMP
  server-id: "smp-1"          # REQUIRED and unique; startup refuses without it
  velocity:
    this-server: "survival"   # REQUIRED - this server's name in velocity.toml
    smp-server: "survival"
    minigame-server: "bedwars"
  redis:
    host: "10.0.0.5"
    port: 6379
    password: ""
  event:
    # Read on this side: the SMP decides how big an event to ask for, and advertises the cap.
    min-players: 4
    max-players: 16
    cooldown-minutes: 60
    # Off by default. With it on, the SMP offers a match by itself once enough players have been
    # idle - which is the difference between a feature that runs and one that waits to be asked.
    auto-propose: false
    auto-propose-interval-seconds: 300
    auto-propose-min-idle-players: 8
    idle-threshold-seconds: 300
```

`server-id` and `velocity.this-server` are validated at startup. Both fail silently and
confusingly if left unset — two servers sharing an id overwrite each other's heartbeat and each
accepts messages addressed to the other; a blank `this-server` makes every transfer a no-op — so
the plugin now refuses to join the network rather than half-working.

### Bedwars server — `plugins/MythicBedwars/config.yml`

```yaml
network:
  enabled: true
  role: MINIGAME
  server-id: "bw-1"
  velocity:
    this-server: "bedwars"
    smp-server: "survival"
    minigame-server: "bedwars"
  redis:
    host: "10.0.0.5"
    port: 6379
    password: ""
  event:
    signup-seconds: 120       # read by this role only - see note below
    arrival-grace-seconds: 60
    start-countdown-seconds: 5
    min-arrivals: 4
    # Team count to favour when several arenas fit the turnout equally well. Four is the classic
    # Bedwars shape: it is what puts sixteen players on a 4x4 rather than an 8x2, and four on a
    # 1v1v1v1 rather than a 2v2. Capacity fit always comes first.
    preferred-team-count: 4
    allow-spectators: true
    announce-locally: true    # tell locals when spare slots open to them
    arena-whitelist: []       # empty = any eligible arena
```

**Arena choice follows the turnout.** The selector scores capacity fit above everything else, then
prefers `preferred-team-count`, and picks **at random** among equally good fits so a regular event
does not always run on the same map. The arena has to be reserved before signups open, on an
estimate; once signups close and the real number is known, the host will move to a materially
better-fitting free arena — nobody has been transferred yet at that point, so the swap is safe.

**The host owns the signup window.** `signup-seconds`, `arrival-grace-seconds`,
`start-countdown-seconds` and the arena filters are read on the **MINIGAME** side. The host commits
to holding an arena until the deadline it publishes, and the SMP honours that rather than its own
value.

### Recommended: turn on COI's own Redis sync

Independent of this feature, and a free win — time spent in the event then counts toward acting
cooldowns back home.

`plugins/CircleOfImagination/redis-config.yml` on **both** servers:

```yaml
enabled: true
server-id: "smp"        # "bedwars" on the other one
connection:
  host: "10.0.0.5"
acting:
  server-multipliers:
    bedwars: 1.5        # on the SMP: credit Bedwars time at 1.5x
```

---

## Commands

`/mythicbedwars` is **not** permission-gated as a command — Bukkit would reject an ordinary player
before the executor ran, and `/mb event join` has to work for exactly those players. Every
subcommand except `event join` checks `mythicbedwars.admin` itself.

| Command | Where | What |
|---|---|---|
| `/mb event status` | both | Role, Redis state, live peers, event in flight |
| `/mb event preview` | SMP | Renders the announcement without starting anything |
| `/mb event start` | SMP | Offers an event now, ignoring the quiet period. Nothing is announced to players until a host accepts. |
| `/mb event cancel` | both | Calls off the event / releases held arenas |
| `/mb event join` | SMP | Sign up (relog-proof alternative to clicking `[ JOIN NOW ]`) |
| `/mb event send <player> <smp\|minigame\|server>` | both | Proxy transfer smoke test. Also accepts a literal Velocity server name. |

Permissions: `mythicbedwars.event.join` (default true), `mythicbedwars.event.exempt` (default
false — holders never see event broadcasts).

---

## What to test first

1. **Boot both.** `/mb event status` on each should list *both* servers as live peers with recent
   heartbeats. If the SMP does not see the Bedwars server, nothing else will work.
2. **Proxy link.** `/mb event send <you> minigame`, then `/mb event send <you> smp`.
3. **Copy.** `/mb event preview` on the SMP. Tune `magic.event.announce.*` in `lang/lang-en.yml`.
4. **Dry run.** `/mb event start` with one player. It should announce, take the signup, then cancel
   at the deadline for too few signups — and burn only *half* the cooldown.
5. **Real run.** Drop `min-players` and `min-arrivals` to 2, get two accounts in, and follow the
   whole chain: transfer → auto-join → **no dye voting items** → countdown → pathways assigned →
   play → rewards message → return → rewards applied on the SMP.
6. **Idempotency.** Relog on the SMP. Nothing should be granted twice.

Useful while watching:

```bash
redis-cli --scan --pattern 'mythicbedwars:*'
redis-cli HGETALL mythicbedwars:evt:<id>
redis-cli LRANGE mythicbedwars:rewards:pending:<uuid> 0 -1
```

---

## How it hangs together

```
SMP                                     Bedwars
 /mb event start
   ├─ pre-flight: live host? cooldown?
   ├─ SET NX evt:active
   └─ EVENT_PROPOSE ─────────────────▶  pick + reserve arena, SET NX evt:<id>:host
                                        force magic on, hold lobby
   announce + take signups  ◀────────── EVENT_ACCEPT
   ├─ atomic Lua signup (cap exact)
   └─ ROSTER_CLOSED ─────────────────▶  promote arena to LOBBY, hold it open
   transfer roster (staggered) ◀────── ARENA_READY
                                        arrivals seated ─▶ PLAYER_ARRIVED
                                        all in, or grace expires ─▶ countdown
                                   ◀── EVENT_STARTED
                                        ── match runs on the normal workflow ──
                                        RoundEndEvent: roll rewards, queue to Redis
                                   ◀── EVENT_FINISHED, PLAYER_RETURN
   player logs in ─▶ drain queue, apply to their real profile
```

**Durability rule:** every transition is written to the event hash *before* it is published, so a
dropped pub/sub message costs latency rather than the event. `EventSyncTask` re-reads the hash every
two seconds while an event is in flight and gives up locally if it has gone terminal;
`EventReaperTask` sweeps anything that stopped progressing (dead peer, blown deadline) every 30s.

**A server cannot message itself.** `RedisBus` registers each outgoing message id in its own dedup
ring before publishing — so that a Redis which echoes a publish back cannot double-apply a
transition — which also means a message published to your *own* role's channel never comes back.
Anything that has to inform the local half calls it directly through `EventParticipant`. Getting
this wrong is what made the reaper's own cancellations no-ops.

**Exactly-once rewards:** an emit guard (`SADD rewards:granted:<event>`) stops a duplicate round-end
paying twice; an apply guard (`SET NX rewards:claimed:<uuid>:<event>`) stops a popped-then-crashed
bundle applying twice.

---

## Deliberate design choices worth knowing

- **Rewards carry percentages, never amounts.** On the Bedwars server a player is a synthetic
  Sequence-9 Beyonder, so any number computed there would be meaningless. The SMP resolves the
  percentage against their real bar.
- **No grant names a pathway.** `grantActing` silently returns 0 for a pathway the player does not
  hold, so everything resolves against their real primary pathway.
- **Match loadouts are virtual.** `PathwayManager` uses COI's `enterVirtualBeyonder`, which stashes
  the real Beyonder instead of deleting it. Nothing the match does can reach persisted progression.
- **Event arenas never vote.** Three guards ensure no `VotingSession` is created, because
  `endVoting` would overwrite the pre-seeded "magic on".
- **Start goes through MBedwars.** The countdown is handed back via `setLobbyTimeRemaining`, not
  `setStatus(RUNNING)`, so team balancing and spawns still happen.

---

## Known gaps

- **Nothing on the Bedwars side has run against a real MBedwars server.** `EventOrchestrator`,
  `ArenaSelector`, `EventArenaGuard`, `EventArenaListener`, `RewardService` and the return flow are
  reviewed and compile against the 5.5.7 API, but no jar was available to test with. The SMP half
  has been exercised end to end against real Redis. Treat the first live run as a test.
- **AFK detection is movement-based** (sampled once a second). A genuinely stationary defender loses
  participation ratio; it scales the reward down rather than denying it, but it is a crude proxy.
  Shop purchases now count as activity, which softens the worst case.
- **Reward magnitudes are calibrated, not tuned.** Sized so a committed player gets six to eight
  meaningful matches per sequence before COI's `event` acting cap starts refusing grants. That is a
  judgement about how supplementary event play should feel, and it is worth revisiting with real
  numbers.
- **Exchange tokens are deliberately uncapped.** They touch no `ActingSource` ledger, so the daily
  bundle limits and the acting cap do not bound them; only their roll weight does. See
  `rewards.token-sequences`.
- **Announcement reward copy is prose, not numbers.** `magic.event.announce.rewards` names reward
  *kinds*, not values. Once the figures settle, put real ones in the copy.
- **Arena selection is no longer deterministic across hosts.** Two Bedwars servers may pick
  different arenas for the same roster. Harmless — only one wins the host claim — but it does mean
  you cannot predict the map from the roster alone.
