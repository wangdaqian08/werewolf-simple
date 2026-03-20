# Scenario 03 — Minimal Four-Player: Wolves Win Night 1

**Scenario ID:** 03
**Complexity:** Minimal (no special roles)
**Outcome:** WEREWOLF wins immediately after Night 1

---

## Cast

| Seat | Nickname | Role            |
|------|----------|-----------------|
| 1    | Alice    | WEREWOLF (host) |
| 2    | Bob      | WEREWOLF        |
| 3    | Carol    | VILLAGER        |
| 4    | Dave     | VILLAGER        |

## Room Configuration

```json
{
  "hasSeer": false,
  "hasWitch": false,
  "hasHunter": false,
  "hasGuard": false,
  "hasSheriff": false,
  "playerCount": 4
}
```

## Night Subphase Sequence

`WEREWOLF_PICK → COMPLETE`

(All special role subphases skipped because no special roles exist.)

## Game Summary

| Round     | Event                                               | Alive After                        |
|-----------|-----------------------------------------------------|------------------------------------|
| Start     | Roles assigned                                      | 4 alive                            |
| Night 1   | Wolves kill Carol                                   | 3 alive: Alice(W), Bob(W), Dave(V) |
| Win Check | 2W ≥ 1V → **WOLVES WIN immediately** — no Day phase | —                                  |

> No sheriff election, no seer, no witch, no day discussion, no voting. Game ends right after Night 1 resolve.

---

## Game Timeline

| Step | Phase / SubPhase        | Key Actions                           | Key Events                                                                                       | Notable Screens                                                    |
|------|-------------------------|---------------------------------------|--------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| 0    | ROLE_REVEAL             | Alice, Bob, Carol, Dave: CONFIRM_ROLE | `RoleAssigned` (private) × 4; `RoleConfirmed` × 4; `PhaseChanged(NIGHT, WEREWOLF_PICK)`          | All: `RoleRevealCard`                                              |
| 1    | NIGHT 1 / WEREWOLF_PICK | Alice + Bob: WOLF_KILL(Carol)         | `NightSubPhaseChanged(COMPLETE)`; `PhaseChanged → win check`                                     | Alice+Bob: `NightPhase(active)`; Carol+Dave: `NightPhase(waiting)` |
| 2    | WIN CHECK               | (server resolves)                     | `NightResult(kills: [carol])`; `PlayerEliminated(carol, VILLAGER)`; `GameOver(winner: WEREWOLF)` | —                                                                  |
| 3    | GAME_OVER               | —                                     | —                                                                                                | All: `ResultView` "狼人获胜！"                                          |

---

## Detailed Steps

### Step 0 — ROLE_REVEAL

> **Entry trigger:** Host starts game; backend assigns roles and sends private `RoleAssigned` events
> **Exit trigger:** All 4 players emit `CONFIRM_ROLE`

#### Events Emitted

| Channel                       | Event           | Key Fields                                                    |
|-------------------------------|-----------------|---------------------------------------------------------------|
| `/user/queue/private` → Alice | `RoleAssigned`  | `role: WEREWOLF, teammates: [bob]`                            |
| `/user/queue/private` → Bob   | `RoleAssigned`  | `role: WEREWOLF, teammates: [alice]`                          |
| `/user/queue/private` → Carol | `RoleAssigned`  | `role: VILLAGER`                                              |
| `/user/queue/private` → Dave  | `RoleAssigned`  | `role: VILLAGER`                                              |
| `/topic/game/{gameId}`        | `RoleConfirmed` | `userId: alice` (one per player after each confirm)           |
| `/topic/game/{gameId}`        | `PhaseChanged`  | `phase: NIGHT, subPhase: WEREWOLF_PICK` (after all 4 confirm) |

#### Player Screens

| Player | Role     | Component        | Key Visible Info      | Available Actions |
|--------|----------|------------------|-----------------------|-------------------|
| Alice  | WEREWOLF | `RoleRevealCard` | "狼人"; teammate: Bob   | `[确认]`            |
| Bob    | WEREWOLF | `RoleRevealCard` | "狼人"; teammate: Alice | `[确认]`            |
| Carol  | VILLAGER | `RoleRevealCard` | "平民"                  | `[确认]`            |
| Dave   | VILLAGER | `RoleRevealCard` | "平民"                  | `[确认]`            |

---

### Step 1 — NIGHT 1 / WEREWOLF_PICK

> **Entry trigger:** `PhaseChanged(NIGHT, WEREWOLF_PICK)` — screen goes dark for all
> **Exit trigger:** Both wolves submit WOLF_KILL(Carol) → backend resolves night immediately (no other roles)

#### Actions

| Actor | Action    | Target | Constraint              |
|-------|-----------|--------|-------------------------|
| Alice | WOLF_KILL | Carol  | Alive non-wolf player   |
| Bob   | WOLF_KILL | Carol  | Must match Alice's pick |

#### Events Emitted (night resolve — no day phase triggered because win condition met)

| Channel                | Event                  | Key Fields                      |
|------------------------|------------------------|---------------------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: COMPLETE`            |
| `/topic/game/{gameId}` | `NightResult`          | `kills: [carol]`                |
| `/topic/game/{gameId}` | `PlayerEliminated`     | `userId: carol, role: VILLAGER` |
| `/topic/game/{gameId}` | `GameOver`             | `winner: WEREWOLF`              |

> **Win check:** Alive = Alice(W), Bob(W), Dave(V). Wolves=2, Non-wolves=1. 2 ≥ 1 → WOLVES WIN.
> Backend skips DAY phase entirely and emits GameOver directly.

#### Player Screens

| Player | Role     | Component             | Key Visible Info                   | Available Actions        |
|--------|----------|-----------------------|------------------------------------|--------------------------|
| Alice  | WEREWOLF | `NightPhase(active)`  | "狼人行动"; non-wolf list: Carol, Dave | `[选择 Carol]` `[选择 Dave]` |
| Bob    | WEREWOLF | `NightPhase(active)`  | Same; Alice's pick indicator       | Same                     |
| Carol  | VILLAGER | `NightPhase(waiting)` | "夜晚降临，请闭眼"; "狼人正在行动…"              | —                        |
| Dave   | VILLAGER | `NightPhase(waiting)` | Same as Carol                      | —                        |

---

### Step 2 — GAME_OVER

#### Player Screens

| Player | Role            | Component    | Key Visible Info         |
|--------|-----------------|--------------|--------------------------|
| Alice  | WEREWOLF        | `ResultView` | "狼人获胜！"; full role table |
| Bob    | WEREWOLF        | `ResultView` | Same                     |
| Carol  | VILLAGER (elim) | `ResultView` | Same; own role VILLAGER  |
| Dave   | VILLAGER        | `ResultView` | Same                     |

**Full Role Reveal Table:**

| Seat | Player | Role     | Status             |
|------|--------|----------|--------------------|
| 1    | Alice  | WEREWOLF | Alive (winner)     |
| 2    | Bob    | WEREWOLF | Alive (winner)     |
| 3    | Carol  | VILLAGER | Eliminated Night 1 |
| 4    | Dave   | VILLAGER | Alive (defeated)   |

---

## Gating Tests

These rejection scenarios must be validated at the backend and surfaced as error responses (not silent failures).

### Gating Test 1 — Non-Wolf Attempts WOLF_KILL

| Field             | Value                                                     |
|-------------------|-----------------------------------------------------------|
| Actor             | Dave (VILLAGER)                                           |
| Action            | `WOLF_KILL(carol)`                                        |
| Phase             | `NIGHT / WEREWOLF_PICK`                                   |
| Expected Response | HTTP 403 / STOMP error event                              |
| Rejection Reason  | "你不是狼人，无法执行此操作" (not a werewolf)                          |
| Frontend behavior | Error message displayed; player remains on waiting screen |

### Gating Test 2 — Non-Existent Role Action

| Field             | Value                                            |
|-------------------|--------------------------------------------------|
| Actor             | Alice (WEREWOLF)                                 |
| Action            | `SEER_CHECK(carol)`                              |
| Phase             | `NIGHT / WEREWOLF_PICK`                          |
| Expected Response | HTTP 400 / STOMP error event                     |
| Rejection Reason  | "本局游戏没有预言家" (no seer in this game configuration) |
| Frontend behavior | Error shown; no phase transition                 |

### Gating Test 3 — Action During Wrong Phase

| Field             | Value                                      |
|-------------------|--------------------------------------------|
| Actor             | Alice                                      |
| Action            | `SUBMIT_VOTE(dave)`                        |
| Phase             | `NIGHT / WEREWOLF_PICK`                    |
| Expected Response | HTTP 400 / STOMP error event               |
| Rejection Reason  | "当前阶段不允许此操作" (wrong phase for this action) |
| Frontend behavior | Error shown; no state change               |

---

## Assertions Summary

### Backend Integration Assertions

1. After all 4 `CONFIRM_ROLE` → `PhaseChanged(NIGHT, WEREWOLF_PICK)` (no SHERIFF_ELECTION because `hasSheriff=false`)
2. Night subphase sequence = `WEREWOLF_PICK → COMPLETE` only (no SEER_PICK, WITCH_ACT, etc.)
3. After wolves kill Carol → win condition evaluated immediately
4. Win condition: 2 wolves ≥ 1 non-wolf → `GameOver(winner: WEREWOLF)` without transitioning to DAY
5. Dave's `WOLF_KILL` attempt → rejected with appropriate error code
6. Alice's `SEER_CHECK` attempt → rejected with "no seer" error

### Frontend E2E Assertions

1. Villager `RoleRevealCard` does NOT show teammate list (only wolves see each other)
2. Night phase for Carol and Dave: no action buttons present at any point
3. After `GameOver` — `ResultView` displayed without any DAY or VOTING phase having been shown
4. `ResultView` shows "狼人获胜！" (not "好人获胜！")
