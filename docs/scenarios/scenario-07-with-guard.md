# Scenario 07 — With Guard: Guard Protect and Repeat Rejection

**Scenario ID:** 07
**Complexity:** Medium (7 players; tests guard mechanic)
**Outcome:** Partial game shown through Night 2; key mechanic = guard same-player repeat rejection

---

## Cast

| Seat | Nickname | Role            |
|------|----------|-----------------|
| 1    | Alice    | WEREWOLF (host) |
| 2    | Bob      | WEREWOLF        |
| 3    | Carol    | SEER            |
| 4    | Dave     | WITCH           |
| 5    | Eve      | HUNTER          |
| 6    | Frank    | GUARD           |
| 7    | Grace    | VILLAGER        |

## Room Configuration

```json
{
  "hasSeer": true,
  "hasWitch": true,
  "hasHunter": true,
  "hasGuard": true,
  "hasSheriff": false,
  "playerCount": 7
}
```

## Night Subphase Sequence (with guard)

`WEREWOLF_PICK → SEER_PICK → SEER_RESULT → WITCH_ACT → GUARD_PICK → COMPLETE`

> **Guard acts last** — after witch. This means if guard protects the wolf target, the protection overrides the kill
> even after witch has acted.

## Game Summary

| Round   | Event                                                                                                          | Alive After                                                     |
|---------|----------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| Night 1 | Wolves target Carol; Seer checks Bob (wolf!); Witch skips; **Guard PROTECTS Carol**                            | All 7 alive (Carol saved by guard)                              |
| Day 1   | No deaths; discuss; vote Grace                                                                                 | 6 alive: Alice(W), Bob(W), Carol(S), Dave(Wi), Eve(H), Frank(G) |
| Night 2 | Wolves target Carol again; Seer checks Alice (wolf!); Witch skips; **Guard tries Carol → REJECTED; picks Eve** | Carol dies (wolf kill, guard chose wrong target)                |
| Day 2+  | Summarized; eventual villager win                                                                              | —                                                               |

---

## Game Timeline

| Step   | Phase / SubPhase                     | Key Actions                                                                 | Key Events                                                                                                      | Notable Screens                                        |
|--------|--------------------------------------|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| 0      | ROLE_REVEAL                          | All 7: CONFIRM_ROLE                                                         | `RoleAssigned` × 7; `PhaseChanged(NIGHT, WEREWOLF_PICK)`                                                        | All: `RoleRevealCard` including Frank: "守卫"            |
| 1      | NIGHT 1 / WEREWOLF_PICK              | Alice+Bob: WOLF_KILL(Carol)                                                 | `NightSubPhaseChanged(SEER_PICK)`                                                                               | Wolves: active                                         |
| 2      | NIGHT 1 / SEER_PICK                  | Carol: SEER_CHECK(Bob)                                                      | `NightSubPhaseChanged(SEER_RESULT)`                                                                             | Carol: active                                          |
| 3      | NIGHT 1 / SEER_RESULT                | Carol: SEER_CONFIRM                                                         | `SeerResult(bob, true)` private; `NightSubPhaseChanged(WITCH_ACT)`                                              | Carol: "Bob — 是狼人！"                                    |
| 4      | NIGHT 1 / WITCH_ACT                  | Dave: WITCH_ACT(skip)                                                       | `NightSubPhaseChanged(GUARD_PICK)`                                                                              | Dave: sees Carol targeted; skips                       |
| **5**  | **NIGHT 1 / GUARD_PICK**             | **Frank: GUARD_PROTECT(Carol)**                                             | `NightSubPhaseChanged(COMPLETE)` → resolve                                                                      | **Frank: guard UI; Carol highlighted as valid target** |
| 6      | NIGHT 1 RESOLVE                      | (server resolves)                                                           | `NightResult(kills: [])` — Carol protected by guard; `PhaseChanged(DAY, RESULT_HIDDEN)`                         | —                                                      |
| 7      | DAY 1 / RESULT_HIDDEN                | Alice(host): REVEAL_NIGHT_RESULT                                            | `NightResult(kills: [])`; `PhaseChanged(DAY, RESULT_REVEALED)`                                                  | All: waiting                                           |
| 8      | DAY 1 / RESULT_REVEALED              | Alice(host): DAY_ADVANCE                                                    | `PhaseChanged(VOTING, VOTING)`                                                                                  | All: "昨夜平安"                                            |
| 9      | VOTING 1 / VOTING                    | All 7 vote; Grace gets majority                                             | `VoteSubmitted` × 7; `PhaseChanged(VOTING, VOTE_RESULT)`                                                        | All: vote UI                                           |
| 10     | VOTING 1 / VOTE_RESULT               | Alice(host): VOTING_REVEAL_TALLY + VOTING_CONTINUE                          | `VoteTally(eliminatedUserId: grace)`; `PlayerEliminated(grace, VILLAGER)`; `PhaseChanged(NIGHT, WEREWOLF_PICK)` | Grace's VILLAGER revealed                              |
| 11     | NIGHT 2 / WEREWOLF_PICK              | Alice+Bob: WOLF_KILL(Carol)                                                 | `NightSubPhaseChanged(SEER_PICK)`                                                                               | Wolves: target Carol again                             |
| 12     | NIGHT 2 / SEER_PICK                  | Carol: SEER_CHECK(Alice)                                                    | `NightSubPhaseChanged(SEER_RESULT)`                                                                             | Carol: active                                          |
| 13     | NIGHT 2 / SEER_RESULT                | Carol: SEER_CONFIRM                                                         | `SeerResult(alice, true)` private; `NightSubPhaseChanged(WITCH_ACT)`                                            | Carol: "Alice — 是狼人！"                                  |
| 14     | NIGHT 2 / WITCH_ACT                  | Dave: WITCH_ACT(skip)                                                       | `NightSubPhaseChanged(GUARD_PICK)`                                                                              | Dave: skips again                                      |
| **15** | **NIGHT 2 / GUARD_PICK — REJECTION** | **Frank tries: GUARD_PROTECT(Carol) → REJECTED; Frank: GUARD_PROTECT(Eve)** | **Error response; then valid `NightSubPhaseChanged(COMPLETE)` → resolve**                                       | **Frank: Carol greyed out; error shown; picks Eve**    |
| 16     | NIGHT 2 RESOLVE                      | (server resolves)                                                           | `NightResult(kills: [carol])`; `PlayerEliminated(carol, SEER)`; `PhaseChanged(DAY, RESULT_HIDDEN)`              | Carol dies                                             |
| 17+    | DAY 2 onwards                        | Condensed (see summary)                                                     | Bob eventually eliminated; villagers win                                                                        | —                                                      |

---

## Detailed Steps

---

### Step 0 — ROLE_REVEAL

All 7 players receive private `RoleAssigned` events. Frank receives `GUARD` role.

#### Player Screens

| Player | Role     | Component        | Key Visible Info             |
|--------|----------|------------------|------------------------------|
| Alice  | WEREWOLF | `RoleRevealCard` | "狼人"; teammate: Bob          |
| Bob    | WEREWOLF | `RoleRevealCard` | "狼人"; teammate: Alice        |
| Carol  | SEER     | `RoleRevealCard` | "预言家"                        |
| Dave   | WITCH    | `RoleRevealCard` | "女巫"; antidote ×1, poison ×1 |
| Eve    | HUNTER   | `RoleRevealCard` | "猎人"                         |
| Frank  | GUARD    | `RoleRevealCard` | "守卫"; "不能连续两晚守护同一人"          |
| Grace  | VILLAGER | `RoleRevealCard` | "平民"                         |

---

### Step 5 — NIGHT 1 / GUARD_PICK (Active)

> **Entry trigger:** `NightSubPhaseChanged(GUARD_PICK)` after witch acts
> **Exit trigger:** Frank submits `GUARD_PROTECT(Carol)`

This is Night 1 — first night, no restriction on who guard can protect.

#### Frank's Screen

| Field                 | Value                                                                |
|-----------------------|----------------------------------------------------------------------|
| Component             | `NightPhase(active)`                                                 |
| Background            | ink                                                                  |
| Title                 | "守卫 — 请选择今晚的守护目标"                                                    |
| Available targets     | All alive players: Alice, Bob, Carol, Dave, Eve, Frank (self), Grace |
| Last-protected marker | None (first night)                                                   |
| Action                | `GUARD_PROTECT(carol)`                                               |

#### All Other Players' Screen

| Player | Component             | Key Visible Info                             |
|--------|-----------------------|----------------------------------------------|
| Alice  | `NightPhase(waiting)` | "守卫正在行动…"                                    |
| Bob    | `NightPhase(waiting)` | "守卫正在行动…"                                    |
| Carol  | `NightPhase(waiting)` | "守卫正在行动…" (unaware she is about to be saved) |
| Dave   | `NightPhase(waiting)` | "守卫正在行动…"                                    |
| Eve    | `NightPhase(waiting)` | "守卫正在行动…"                                    |
| Grace  | `NightPhase(waiting)` | "守卫正在行动…"                                    |

#### Events Emitted

| Channel                | Event                  | Key Fields           |
|------------------------|------------------------|----------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: COMPLETE` |

---

> ### Night 1 Resolve Summary
> - Wolf target: Carol
> - Witch action: SKIP
> - Guard protection: Carol
> - Resolution: Carol protected by guard → wolf kill **cancelled**
> - Net deaths: **none** ("昨夜平安")
> - Guard state: `lastProtected = carol` (cannot protect Carol again Night 2)
>
> **Key distinction from antidote save:** The `NightResult(kills: [])` is identical whether guard saved or antidote
> saved. The game does not reveal HOW someone was saved — only that no one died. Neither Carol nor anyone else is told who
> saved her or how.

---

### Step 15 — NIGHT 2 / GUARD_PICK — Rejection Then Re-pick

> **Entry trigger:** `NightSubPhaseChanged(GUARD_PICK)`
> **Exit trigger:** Frank successfully submits `GUARD_PROTECT(Eve)` (after Carol rejected)

#### Frank's Initial Attempt (Rejected)

Frank tries to protect Carol again (same player as Night 1):

| Field            | Value                  |
|------------------|------------------------|
| Actor            | Frank (GUARD)          |
| Action           | `GUARD_PROTECT(carol)` |
| Server response  | Error / rejection      |
| Rejection reason | "不能连续两晚守护同一名玩家"        |

**Backend behavior:** Server checks `lastProtected == carol` for Frank. Since Frank protected Carol on Night 1, this
action is rejected.

**Frontend behavior:**

- Inline error message displayed on Frank's screen
- Carol's player card is visually greyed out / marked "上晚已守护" (protected last night)
- Frank's selection resets; he must pick again
- No `NightSubPhaseChanged` event is emitted (guard has not yet completed their turn)

#### Frank's Screen After Rejection

| Field             | Value                                                      |
|-------------------|------------------------------------------------------------|
| Component         | `NightPhase(active)` (still in GUARD_PICK)                 |
| Error shown       | "不能连续两晚守护同一名玩家，请重新选择"                                      |
| Carol's card      | Greyed out, not selectable                                 |
| Remaining targets | Alice, Bob, Dave, Eve, Frank (self), (Grace is eliminated) |
| Action taken      | `GUARD_PROTECT(eve)`                                       |

#### Frank's Corrected Action

| Actor | Action        | Target | Constraint                     |
|-------|---------------|--------|--------------------------------|
| Frank | GUARD_PROTECT | Eve    | Valid — not same as last night |

#### Events Emitted (after valid pick)

| Channel                | Event                  | Key Fields           |
|------------------------|------------------------|----------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: COMPLETE` |

---

> ### Night 2 Resolve Summary
> - Wolf target: Carol
> - Witch action: SKIP
> - Guard protection: Eve (not Carol — Frank's repeat attempt rejected)
> - Resolution: Carol is **NOT** protected → wolf kill lands → Carol dies
> - Net deaths: **Carol**
> - Carol's role SEER revealed when `PlayerEliminated(carol, SEER)` broadcast
>
> **Key point:** Guard's protection of Eve is irrelevant to the wolf kill outcome. Eve was not targeted. Guard's "
> wasted" protection on Eve demonstrates that guard must make their choice carefully.

---

### Day 2 and Beyond (Condensed)

After Night 2: Alive = Alice(W), Bob(W), Dave(Wi), Eve(H), Frank(G). 2W vs 3 non-W → continues.

| Round   | Event                                                                                                                                       |
|---------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Day 2   | Carol dead (SEER revealed). Players vote Bob based on discussion. Bob eliminated (WEREWOLF revealed). Win check: 1W vs 3 non-W → continues. |
| Night 3 | Alice (solo wolf) picks Dave. Seer dead. Witch (Dave) sees self targeted — uses antidote or poison as desired. Guard picks someone.         |
| Day 3+  | Game continues until Alice eliminated or wins. Exact outcome depends on guard/witch choices in Night 3.                                     |

> Full detail for Day 2+ is intentionally condensed — the purpose of this scenario is to test the guard mechanic in
> Nights 1 and 2. For a complete game narrative, see Scenario 01 or 02.

---

## Guard Mechanic Reference

### Rule Summary

| Night   | Guard's Last-Protected       | Allowed Targets              | Rejected Target |
|---------|------------------------------|------------------------------|-----------------|
| Night 1 | None                         | Any alive player             | —               |
| Night 2 | Carol                        | Any alive except Carol       | Carol           |
| Night 3 | Eve (from N2)                | Any alive except Eve         | Eve             |
| Night N | Whoever was picked Night N-1 | Any alive except that player | That player     |

### Guard Protection vs Witch Antidote Interaction

Both guard protection and witch antidote can save a player in the same night. The outcomes:

| Guard picks X | Witch antidote (saves wolf target) | Wolf target | Result                                       |
|---------------|------------------------------------|-------------|----------------------------------------------|
| X             | Not used                           | X           | X lives (guard saved)                        |
| X             | Used                               | X           | X lives (both tried to save; one sufficient) |
| Y (not X)     | Not used                           | X           | X dies                                       |
| Y (not X)     | Used                               | X           | X lives (witch antidote saved)               |

> **Backend rule:** If both guard and antidote would save the same player, they are treated as a single save. The player
> lives. No "double save" bonus or error.

---

## Gating Tests

### Gating Test 1 — Guard Repeat Protection

| Field             | Value                                                                  |
|-------------------|------------------------------------------------------------------------|
| Actor             | Frank (GUARD)                                                          |
| Action            | `GUARD_PROTECT(carol)` on Night 2                                      |
| Phase             | `NIGHT / GUARD_PICK`                                                   |
| Expected          | HTTP 400 / STOMP error event                                           |
| Rejection Reason  | "不能连续两晚守护同一名玩家"                                                        |
| Frontend behavior | Inline error on Frank's screen; Carol greyed out; Frank must re-select |

### Gating Test 2 — Non-Guard Attempts Guard Action

| Field            | Value                  |
|------------------|------------------------|
| Actor            | Eve (HUNTER)           |
| Action           | `GUARD_PROTECT(carol)` |
| Phase            | `NIGHT / GUARD_PICK`   |
| Expected         | HTTP 403 / STOMP error |
| Rejection Reason | "你不是守卫"                |

### Gating Test 3 — Guard Acts During Wrong Subphase

| Field            | Value                   |
|------------------|-------------------------|
| Actor            | Frank (GUARD)           |
| Action           | `GUARD_PROTECT(carol)`  |
| Phase            | `NIGHT / WEREWOLF_PICK` |
| Expected         | HTTP 400 / STOMP error  |
| Rejection Reason | "当前阶段不允许此操作"            |

---

## Full Role Reveal Table (End State — partial game shown)

| Seat | Player | Role     | Status at Night 2 end |
|------|--------|----------|-----------------------|
| 1    | Alice  | WEREWOLF | Alive                 |
| 2    | Bob    | WEREWOLF | Alive                 |
| 3    | Carol  | SEER     | Eliminated Night 2    |
| 4    | Dave   | WITCH    | Alive                 |
| 5    | Eve    | HUNTER   | Alive                 |
| 6    | Frank  | GUARD    | Alive                 |
| 7    | Grace  | VILLAGER | Eliminated Day 1      |

---

## Assertions Summary

### Backend Integration Assertions

1. Night 1: Guard protects Carol → `NightResult(kills: [])` despite wolves targeting Carol
2. Guard's `lastProtected` persists across nights; Night 2 Carol attempt rejected
3. After rejection, Frank's turn in `GUARD_PICK` remains open (no phase advance)
4. Frank's valid `GUARD_PROTECT(eve)` → `NightSubPhaseChanged(COMPLETE)`
5. Night 2 resolve: Carol targeted + guard protected Eve + witch skipped → `NightResult(kills: [carol])`
6. `PlayerEliminated(carol, SEER)` broadcast after Night 2 resolve
7. Night subphase sequence includes `GUARD_PICK` (7th step, only because `hasGuard=true`)

### Frontend E2E Assertions

1. Frank's Night 1 `NightPhase(active)`: all alive players shown as selectable (no restrictions)
2. Frank's Night 2 `NightPhase(active)`: Carol card is greyed out with "上晚已守护" label
3. After Frank selects Carol Night 2: error message shown inline; selection reset
4. After Night 1 (guard saved Carol): Day 1 shows "昨夜平安" — save is invisible/anonymous
5. After Night 2 (guard failed Carol): Day 2 shows Carol in deaths list with SEER role revealed
6. Guard role revealed in `ResultView` final role table
