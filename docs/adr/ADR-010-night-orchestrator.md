---
id: ADR-010
title: Night Phase Orchestration
status: accepted
---

## Context

The night phase is fully backend-driven: the server steps through each role in a fixed order,
waits for that role's action (or its dead-role delay), and broadcasts audio cues via STOMP so every
client plays the same sound at the same moment. A critical security requirement is **dead-role
information hiding**: observers must not be able to infer which special roles are dead from
audio/timing cues.

## Decision

Drive the night phase from a single Kotlin-coroutine-based orchestrator that owns the night
timeline and emits role-locked STOMP events for every active night role, regardless of whether
the role's player is alive.

**Implementation:** `backend/src/main/kotlin/com/werewolf/game/night/NightOrchestrator.kt`.

The class collaborates with a list of `RoleHandler` plugins (one per role; see ADR-003). For each
active role in the room's config it plays open-eyes audio, waits for the action (via
`CompletableDeferred`), plays close-eyes audio, then advances to the next sub-phase. Each handler
declares the `NightSubPhase` values it owns (`nightSubPhases()`), so the sequence is derived from
active roles rather than hard-coded.

Other collaborators (injected): `GameRepository`, `GamePlayerRepository`, `NightPhaseRepository`,
`EliminationHistoryRepository`, `WinConditionChecker`, `StompPublisher`, `GameContextLoader`,
`AudioService`, `ActionLogService`, `GameTimingProperties`, and a `CoroutineScope`.

## Night Order

Driven by `@Order` on each handler (lowest runs first):

1. Werewolves — `WEREWOLF_PICK`
2. Seer — `SEER_PICK`, `SEER_RESULT`
3. Witch — `WITCH_ACT`
4. Guard — `GUARD_PICK`

Role sub-phases whose `hasXxx` flag is `false` are skipped entirely (no open-eyes, no close-eyes,
no audio).

## Dead-Role Information Hiding

A special role that is dead still produces observable audio and timing consistent with a live
role. Without this, listeners could infer who is dead from silence.

| Live Role                                                     | Dead Role                                             |
|---------------------------------------------------------------|-------------------------------------------------------|
| Broadcast open-eyes → wait up to `actionWindowMs` → close-eyes | Broadcast open-eyes → `delay(deadRoleDelayMs)` → close-eyes |
| Private action prompt sent to the role's player                | Nothing private is sent                                 |
| Action mutates game state                                     | No state change                                        |

`deadRoleDelayMs` is calibrated per role (see `GameTimingProperties` / `rooms.config` JSONB — V9
migration) to match the typical acting time of a live role.

## Complex Rules (owned by the orchestrator + role handlers)

- **Werewolf vote — first vote wins.** The first `WOLF_KILL` completes the deferred; subsequent
  submissions are rejected.
- **Witch single-use.** Antidote and poison are each once-per-game; enforced by `WitchHandler`.
  Can't use both the same night. State persists in `night_phases` columns.
- **Guard consecutive rule.** Guard cannot protect the same player two nights in a row; prior
  target is stored in `night_phases.prev_guard_target_user_id`. Self-protect is allowed.
- **Hunter — day-vote only.** Hunter's revenge shot is triggered exclusively by
  `DAY_VOTING / HUNTER_SHOOT`. Night kills do **not** trigger the hunter.
- **Idiot — day-vote reveal.** On first vote-elimination the idiot is revealed, stays alive, and
  loses their vote right (`game_players.idiot_revealed = true`, `can_vote = false`). Second
  vote-elimination kills normally.
- **Win condition check** runs after each death event (night resolution, hunter shot, vote
  elimination). Logic in `WinConditionChecker` — mode is `CLASSIC` vs `HARD_MODE`
  (`rooms.win_condition`).

## Consequences

- The orchestrator is the only driver of the night timeline; `StompPublisher` is the only class
  that calls `SimpMessagingTemplate` (see ADR-006).
- Adding a new night role = implementing `RoleHandler` with the right `@Order` and
  `nightSubPhases()`; no change to `NightOrchestrator`.
- The dead-role delay is a real gameplay cost (nights get longer when many special roles die) —
  accepted in exchange for information hiding.
- `job.cancel()` on the stored orchestrator `Job` cleanly aborts the night without orphaned
  actions.
