# Werewolf Game — Backend Architecture Design
**Date**: 2026-04-14
**Stack**: Kotlin + Spring Boot · Vue 3 · PostgreSQL · STOMP over WebSocket
**Scope**: Single game room, 6–12 players, one developer

---

# Context
Building a digital Werewolf game host. The backend drives the night phase fully automatically (including audio cues via STOMP events), while the human host controls day phase pacing. A critical security requirement is that the backend must never leak which special roles have been eliminated — it achieves this by always emitting `OPEN_EYES`/`CLOSE_EYES` events for dead roles (with a fixed configurable delay), making the timing pattern indistinguishable from a live role acting.

---

## Roles (v1)
|Role      |Night Actor  | Death Trigger                                                     |
|--------|------------|-------------------------------------------------------------------|
|Werewolf|	Yes — votes to kill (first vote wins)| 	No special action                                                |
|Villager|	No| 	No special action                                                |
|Seer|	Yes — investigates one player's role| No special action                                                 |
|Witch|	Yes — one heal potion + one poison potion| 	No special action                                                |
|Guard|	Yes — protects one player (not same player consecutively)	| No special action                                                 |
|Hunter|	No night action| 	Shot triggered only when voted out by day vote                   |
|Idiot|	No night action| 	When day-voted: revealed as Idiot, survives, loses voting rights |

-----

## System Architecture

┌─────────────────────────────────────────────────────────┐  	
│                  Vue Frontend                           │
│ PlayerView  │ HostView  │ SpectatorView                 │
│ - Audio FSM │ - Controls│ - Observer only               │
└──────────────┬──────────────────────────────────────────┘
               │ STOMP over WebSocket
┌──────────────▼─────────────────────────────────────────┐
│           Spring Boot (Kotlin)                         │
│                                                        │
│ WebSocketConfig ──► STOMP broker configuration         │
│ GameController ──► host/player command entry           │
│ GameService ──► game lifecycle + phase state           │
│ NightPhaseOrchestrator──► coroutine, drives night seq  │
│ GameEventPublisher ──► sole owner of SimpMessaging     │
│ GameStateRepository ──► reads/writes PostgreSQL        │
│ RoleActionRegistry ──► maps Role → NightAction impl    │
└──────────────┬─────────────────────────────────────────┘
               │ JPA
┌──────────────▼──────────────────────────────────────────┐
│ PostgreSQL                                              │
│ game_room │ player │ night_log │ game_action │ day_vote │
└─────────────────────────────────────────────────────────┘

-----

**Invariant**: `GameEventPublisher` is the only class that calls `SimpMessagingTemplate`. All game logic talks to the publisher, never directly to STOMP.

## Phase Model

```
WAITING ──► NIGHT ──► DAY_PENDING ──► DAY_DISCUSSION ──► DAY_VOTING ──► GAME_OVER
▲                                                            │
│                                                            │
└────────────────────────────────────────────────────────────┘ 
               (loop: host triggers next night)
```

| Phase| Who Controls                      |
|------|-----------------------------------|
|WAITING| Host (sets up roles, starts game) |
|NIGHT|               Backend (fully automated coroutine)|
|DAY_PENDING|Backend signals host when night ends|
|DAY_DISCUSSION|Host (free discussion, no backend action)|
|DAY_VOTING|Host opens vote; players submit; backend tallies|
|GAME_OVER|Backend broadcasts winner|

----


## Night Phase Orchestrator (Kotlin Coroutines)
### Night Order
1. **Werewolves**
2. **Seer**
3. **Witch** 
4. **Guard**



### Coroutine Design

```kotlin
class NightPhaseOrchestrator( 
    private val room: GameRoom, 
    private val publisher: GameEventPublisher, 
    private val actionRegistry: RoleActionRegistry, 
    private val scope: CoroutineScope
){ 
    private val pendingActions = ConcurrentHashMap<Role, CompletableDeferred<ActionResult?>>() 
    fun start(): Job = scope.launch { 
        val nightOrder = listOf(WEREWOLF, SEER, WITCH, GUARD)
        for (role in nightOrder) { 
            val alivePlayers = getAlivePlayers(role) 
            val config = room.config.roleConfig(role) 
            publisher.broadcastNightEvent(OPEN_EYES, role) 
            if (alivePlayers.isNotEmpty()) { 
                val deferred = CompletableDeferred<ActionResult?>() 
                pendingActions[role] = deferred 
                alivePlayers.forEach { player -> 
                    publisher.sendActionPrompt(player, role, buildPrompt(role)) 
                } 
                val action = withTimeoutOrNull(config.actionWindowMs) { deferred.await() } 
                actionRegistry.process(role, action) 
                pendingActions.remove(role) 
            } else { 
                delay(config.deadRoleDelayMs) // simulate dead role — identical timing 
            } 
            publisher.broadcastNightEvent(CLOSE_EYES, role) 
        } 
        resolveNightOutcome() 
        publisher.notifyHost(NIGHT_RESULTS_READY) 
    } 
    fun submitAction(role: Role, action: ActionResult) { 
        pendingActions[role]?.complete(action) 
    }
}
```
 

**Key properties:**
- The `Job` reference is stored in `GameService`. Calling `job.cancel()` cleanly aborts the night.
- Dead role path: `OPEN_EYES` sent → `delay(deadRoleDelayMs)` → `CLOSE_EYES` sent. Same broadcast, same audio. No action prompt ever sent.
- Werewolf: first `submitAction()` call wins. Subsequent calls from other werewolves are ignored (deferred already completed).

---

## Dead Role Information Hiding Protocol

When a special role is dead, the backend must produce identical observable behavior to a live role. This prevents players from inferring deaths from audio/timing cues.

| Live Role | Dead Role |
|-----------|-----------|
| `OPEN_EYES` broadcast → wait up to `actionWindowMs` → `CLOSE_EYES` | `OPEN_EYES` broadcast → `delay(deadRoleDelayMs)` → `CLOSE_EYES` |
| `ROLE_ACTION` sent to player | Nothing sent (dead player sees no action panel) |
| Action resolves game state | No game state change |

`deadRoleDelayMs` should be calibrated to approximate the typical action time for that role (configured in `game_room.config` JSONB).

---
## STOMP Topic Design
### Channels

```
Server → Client (subscribe)
────────────────────────────────────────────────────
/topic/game/{roomId}/events ← broadcast ALL players
/topic/game/{roomId}/host ← host-only events
/user/queue/game/action ← private per-player prompts
/user/queue/game/state ← private player state on join/reconnect

Client → Server (send)
────────────────────────────────────────────────────
/app/game/join ← player joins room
/app/game/action ← player submits night action
/app/game/vote ← player casts day vote
/app/game/host/reveal ← host reveals night results to all
/app/game/host/vote-start ← host opens day voting
/app/game/host/start-night ← host triggers next night
```

### Event Payloads
**Broadcast (night events):**
```
json{"type": "OPEN_EYES","role": "seer","phase": "NIGHT","nightNumber": 2}
```
**Private action prompt (`/user/queue/game/action`):**
```
json{"type": "ROLE_ACTION","role": "witch","actionType": "WITCH_POTIONS","victimId": "player-3","canHeal": true,"canPoison": true,"targets": ["player-1", "player-4", "player-7"],"timeoutMs": 25000}
```
### Audio Contract (Frontend Responsibility)

```
On OPEN_EYES { role: "witch" } → play witch_open_eyes.mp3
On CLOSE_EYES { role: "witch" } → play witch_close_eyes.mp3
On ROLE_ACTION received → show action panel (player alive check on frontend)
```

Audio is played for **every** `OPEN_EYES`/`CLOSE_EYES` event regardless of whether the local player is the named role or whether that role is dead. This is correct behavior — it preserves the game's information hiding.

---

## PostgreSQL Schema

```sql
CREATE TABLE game_room (id UUID PRIMARY KEY DEFAULT gen_random_uuid(),code VARCHAR(6) UNIQUE NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'WAITING',night_number INT NOT NULL DEFAULT 0,config JSONB NOT NULL DEFAULT '{}',created_at TIMESTAMP NOT NULL DEFAULT NOW());
CREATE TABLE player (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id UUID NOT NULL REFERENCES game_room(id),
    name VARCHAR(50) NOT NULL,
    role VARCHAR(30),
    status VARCHAR(30) NOT NULL DEFAULT 'ALIVE', -- ALIVE | DEAD | IDIOT_REVEALED
    voting_rights BOOLEAN NOT NULL DEFAULT TRUE,
    is_host BOOLEAN NOT NULL DEFAULT FALSE,
    session_id VARCHAR(100), -- STOMP session for /user/ routing
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE TABLE night_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),room_id UUID NOT NULL REFERENCES game_room(id),night_number INT NOT NULL,werewolf_target_id UUID REFERENCES player(id),guard_protected_id UUID REFERENCES player(id),witch_healed BOOLEAN NOT NULL DEFAULT FALSE,witch_poisoned_id UUID REFERENCES player(id),
    final_dead_id UUID REFERENCES player(id), -- net result
    created_at TIMESTAMP NOT NULL DEFAULT NOW());
CREATE TABLE game_action (id UUID PRIMARY KEY DEFAULT gen_random_uuid(),room_id UUID NOT NULL REFERENCES game_room(id),night_number INT NOT NULL,player_id UUID NOT NULL REFERENCES player(id),action_type VARCHAR(30) NOT NULL, -- WEREWOLF_VOTE | INVESTIGATE | GUARD | WITCH_HEAL | WITCH_POISON
target_id UUID REFERENCES player(id),created_at TIMESTAMP NOT NULL DEFAULT NOW());
CREATE TABLE day_vote (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id UUID NOT NULL REFERENCES game_room(id),
    night_number INT NOT NULL, -- day N follows night N
    voter_id UUID NOT NULL REFERENCES player(id),target_id UUID NOT NULL REFERENCES player(id),created_at TIMESTAMP NOT NULL DEFAULT NOW());
```
**`config` JSONB structure:**
```json
{"roleDelays": {"WEREWOLF": { "actionWindowMs": 30000, "deadRoleDelayMs": 25000 },"SEER": { "actionWindowMs": 20000, "deadRoleDelayMs": 15000 },"WITCH": { "actionWindowMs": 25000, "deadRoleDelayMs": 20000 },"GUARD": { "actionWindowMs": 20000, "deadRoleDelayMs": 15000 }}}
```
---

## Complex Rules
### Night Outcome Resolution (order matters)

```
1. Werewolf vote → sets candidateVictim
2. Witch heal → if witchHealed, victim = null (uses heal potion)
3. Witch poison → poisonTarget added to finalDead5. finalDead = victim (if not null) + poisonTarget (if any)
4. Guard protection → if guardTarget == candidateVictim, victim = null
```

### Hunter — Day Vote Only
- Hunter night death: silent, no special action
- Hunter day vote elimination:
1. Pause vote resolution
2. Send `ROLE_ACTION { actionType: HUNTER_SHOOT }` to Hunter
3. Await target selection (with timeout; no shot if no response)
4. Kill target → check win condition
5. Resume / close day phase

### Idiot — Day Vote Reveal
When Idiot receives majority day vote:
1. Do NOT eliminate
2. Broadcast `IDIOT_REVEALED { playerId }` to all
3. Set `player.status = IDIOT_REVEALED`, `player.voting_rights = false`
4. Continue game — Idiot stays alive without voting rights

### Guard — Consecutive Protection Rule
Before accepting Guard's action, validate:
```kotlin
val lastNight = nightLogRepo.findByRoomAndNight(roomId, nightNumber - 1)require(lastNight?.guardProtectedId != targetId) { "Cannot protect same player consecutively" }
```
### Witch Potions — Single Use
Track on `NightPhaseOrchestrator` instance:
```kotlin
var healUsed: Boolean = falsevar poisonUsed: Boolean = false
```
Persisted in `night_log.witch_healed` and `night_log.witch_poisoned_id` for auditability. Witch action prompt only includes available potions.

### Werewolf Vote — First Vote Wins
First werewolf to submit an action resolves the `CompletableDeferred`. Subsequent submissions are ignored (deferred already completed). No consensus mechanism.

### Win Condition
Evaluated after every death event:
```kotlin
fun checkWinCondition(roomId: UUID): WinResult? {val alive = playerRepo.findAliveByRoom(roomId)val werewolves = alive.count { it.role == WEREWOLF }val village = alive.size - werewolvesreturn when {werewolves == 0 -> VILLAGE_WINSwerewolves >= village -> WEREWOLF_WINSelse -> null}}
```

Win condition is checked:
- After `resolveNightOutcome()`
- After Hunter shot resolves
- After each day vote elimination

---
## Extensibility for Future Roles

The `RoleActionRegistry` maps `Role → NightAction` interface:
```kotlin
interface NightAction {
    val role: Role
    val nightOrderIndex: Int // determines sequence position
    val actionWindowMs: Long // live player window
    val deadRoleDelayMs: Long // simulated window when dead
suspend fun buildPrompt(player: Player, room: GameRoom): ActionPrompt
suspend fun process(action: ActionResult?, room: GameRoom)}
```

Adding a new role = implementing `NightAction` + registering it. Night order is derived from `nightOrderIndex`. No changes to `NightPhaseOrchestrator` itself.

---
## Verification Plan
1. **Night phase flow**: Start a game with 2 werewolves, 1 seer, 1 witch, 1 guard. Observe STOMP events in browser dev tools — confirm `OPEN_EYES`/`CLOSE_EYES` events fire in correct order with correct timing.
2. **Dead role hiding**: Kill the Seer on night 1. On night 2, confirm `OPEN_EYES {role: seer}` and `CLOSE_EYES {role: seer}` still broadcast with the configured delay between them. Confirm no `ROLE_ACTION` is sent.
3. **Guard consecutive rule**: Have Guard protect player A on night 1. On night 2, attempt to protect A again — confirm the action is rejected.
4. **Witch single-use**: Have Witch use heal on night 1. On night 2, confirm `canHeal: false` in the prompt.
5. **Hunter day vote**: Vote out Hunter during day phase — confirm `HUNTER_SHOOT` prompt fires before day phase closes.
6. **Idiot reveal**: Vote out Idiot — confirm they stay alive with `voting_rights = false` and cannot submit a vote on the next day.
7. **Win condition**: Eliminate all werewolves — confirm `GAME_OVER { winner: VILLAGE }` broadcast fires immediately.
8. **Coroutine cancel**: During night phase, trigger game reset — confirm the orchestrator job cancels cleanly with no orphaned actions.