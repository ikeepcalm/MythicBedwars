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
  server-id: "smp-1"          # must be unique across the network
  velocity:
    this-server: "survival"   # this server's name in velocity.toml
    smp-server: "survival"
    minigame-server: "bedwars"
  redis:
    host: "10.0.0.5"
    port: 6379
    password: ""
```

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
    min-players: 4
    max-players: 16
    signup-seconds: 120       # read by this role only - see note below
    arena-whitelist: []       # empty = any eligible arena
```

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

All under `mythicbedwars.admin` except `join`.

| Command | Where | What |
|---|---|---|
| `/mb event status` | both | Role, Redis state, live peers, event in flight |
| `/mb event preview` | SMP | Renders the announcement without starting anything |
| `/mb event start` | SMP | Proposes an event now, ignoring the cooldown |
| `/mb event cancel` | both | Calls off the event / releases held arenas |
| `/mb event join` | SMP | Sign up (relog-proof alternative to clicking `[JOIN]`) |
| `/mb event send <player> <smp\|minigame>` | both | Proxy transfer smoke test |

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
dropped pub/sub message costs latency rather than the event. `EventReaperTask` sweeps anything that
stops progressing (dead peer, blown deadline) every 30s.

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

- **`EventSyncTask` is not implemented.** A dropped pub/sub message currently stalls an event until
  the reaper cancels it (~30–90s) rather than self-correcting in ~2s. The safety net exists; the
  fast path does not.
- **Announcement reward copy is prose, not numbers.** `magic.event.announce.rewards` names reward
  *kinds*, not values, because the exact figures in `rewards.yml` are a first cut. Once tuned, put
  real numbers in the copy.
- **Everything on the Bedwars side is untested.** `EventOrchestrator`, `ArenaSelector`,
  `EventArenaGuard`, `EventArenaListener`, `RewardService` and the return flow have never run
  against a real MBedwars server — no jar was available to test with. The SMP half has been
  exercised end to end against real Redis.
- **AFK detection is movement-based** (sampled once a second). A genuinely stationary defender loses
  participation ratio; it scales the reward down rather than denying it, but it is a crude proxy.
- **`rewards.yml` values are unbalanced guesses.** Calibrated against COI's own bounty range
  (0.1–5% per objective), but they want real tuning.
