# Scenario 06 — Tie Vote: No Elimination, Game Continues

**Scenario ID:** 06
**Complexity:** Medium
**Outcome:** VILLAGER wins Day 3; core test is tie vote at Day 1

---

## Cast

| Seat | Nickname | Role            |
|------|----------|-----------------|
| 1    | Alice    | WEREWOLF (host) |
| 2    | Bob      | WEREWOLF        |
| 3    | Carol    | SEER            |
| 4    | Dave     | WITCH           |
| 5    | Eve      | HUNTER          |
| 6    | Frank    | VILLAGER        |

## Room Configuration

```json
{
  "hasSeer": true,
  "hasWitch": true,
  "hasHunter": true,
  "hasGuard": false,
  "hasSheriff": false,
  "playerCount": 6
}
```

## Night Subphase Sequence

`WEREWOLF_PICK → SEER_PICK → SEER_RESULT → WITCH_ACT → COMPLETE`

## Game Summary

| Round     | Event                                                                                       | Alive After |
|-----------|---------------------------------------------------------------------------------------------|-------------|
| Night 1   | Wolves target Frank; Seer checks Bob (wolf!); Witch SKIPS → Frank dies                      | 5 alive     |
| **Day 1** | **Vote: Bob=2, Carol=2, Alice=1 → TIE → no elimination**                                    | **5 alive** |
| Night 2   | Wolves target Carol; Seer checks Alice (wolf!); Witch saves Carol (antidote)                | 5 alive     |
| Day 2     | No deaths; vote → Bob eliminated                                                            | 4 alive     |
| Night 3   | Alice targets Eve; Seer checks (another player); Witch has antidote used → skips → Eve dies | 3 alive     |
| Day 3     | Alice(W) vs Carol(S), Dave(Wi) = 1W vs 2 → continues; vote Alice → 0W → **VILLAGERS WIN**   | —           |

**Key test:** Day 1 tie vote → no elimination, `VOTING_CONTINUE` used, game continues without anyone leaving.

---

## Game Timeline

| Step  | Phase / SubPhase           | Key Actions                                                | Key Events                                                                                                      | Notable Screens                        |
|-------|----------------------------|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|----------------------------------------|
| 0     | ROLE_REVEAL                | All 6: CONFIRM_ROLE                                        | `RoleAssigned` × 6; `PhaseChanged(NIGHT, WEREWOLF_PICK)`                                                        | All: `RoleRevealCard`                  |
| 1     | NIGHT 1 / WEREWOLF_PICK    | Alice+Bob: WOLF_KILL(Frank)                                | `NightSubPhaseChanged(SEER_PICK)`                                                                               | Wolves: active                         |
| 2     | NIGHT 1 / SEER_PICK        | Carol: SEER_CHECK(Bob)                                     | `NightSubPhaseChanged(SEER_RESULT)`                                                                             | Carol: active                          |
| 3     | NIGHT 1 / SEER_RESULT      | Carol: SEER_CONFIRM                                        | `SeerResult(bob, true)` private; `NightSubPhaseChanged(WITCH_ACT)`                                              | Carol: "Bob — 是狼人！"                    |
| 4     | NIGHT 1 / WITCH_ACT        | Dave: WITCH_ACT(skip)                                      | Resolve → Frank dies                                                                                            | Dave: skips                            |
| 5     | DAY 1 / RESULT_HIDDEN      | Alice(host): REVEAL_NIGHT_RESULT                           | `NightResult(kills: [frank])`; `PlayerEliminated(frank, VILLAGER)`                                              | All: waiting                           |
| 6     | DAY 1 / RESULT_REVEALED    | Alice(host): DAY_ADVANCE                                   | `PhaseChanged(VOTING, VOTING)`                                                                                  | All: Frank dead                        |
| **7** | **VOTING 1 / VOTING**      | **5 players vote; Carol=2, Bob=2, Alice=1**                | `VoteSubmitted` × 5; `PhaseChanged(VOTING, VOTE_RESULT)`                                                        | **All: vote UI**                       |
| **8** | **VOTING 1 / VOTE_RESULT** | **Alice(host): VOTING_REVEAL_TALLY; then VOTING_CONTINUE** | **`VoteTally(eliminatedUserId: null, tally: {carol:2, bob:2, alice:1})`; `PhaseChanged(NIGHT, WEREWOLF_PICK)`** | **All: "平票，无人出局"**                     |
| 9     | NIGHT 2 / WEREWOLF_PICK    | Alice+Bob: WOLF_KILL(Carol)                                | `NightSubPhaseChanged(SEER_PICK)`                                                                               | Wolves: active                         |
| 10    | NIGHT 2 / SEER_PICK        | Carol: SEER_CHECK(Alice)                                   | `NightSubPhaseChanged(SEER_RESULT)`                                                                             | Carol: active                          |
| 11    | NIGHT 2 / SEER_RESULT      | Carol: SEER_CONFIRM                                        | `SeerResult(alice, true)` private; `NightSubPhaseChanged(WITCH_ACT)`                                            | Carol: "Alice — 是狼人！"                  |
| 12    | NIGHT 2 / WITCH_ACT        | Dave: WITCH_ACT(antidote) — saves Carol                    | Resolve → no deaths                                                                                             | Dave: uses antidote                    |
| 13    | DAY 2 / RESULT_HIDDEN      | Alice(host): REVEAL_NIGHT_RESULT                           | `NightResult(kills: [])`                                                                                        | All: waiting                           |
| 14    | DAY 2 / RESULT_REVEALED    | Alice(host): DAY_ADVANCE                                   | `PhaseChanged(VOTING, VOTING)`                                                                                  | All: "昨夜平安"                            |
| 15    | VOTING 2 / VOTING          | 5 alive vote Bob                                           | `VoteSubmitted` × 5                                                                                             | All: vote Bob                          |
| 16    | VOTING 2 / VOTE_RESULT     | Alice(host): VOTING_REVEAL_TALLY + VOTING_CONTINUE         | `VoteTally(eliminatedUserId: bob)`; `PlayerEliminated(bob, WEREWOLF)`; `PhaseChanged(NIGHT, WEREWOLF_PICK)`     | Bob's WEREWOLF revealed                |
| 17    | NIGHT 3 / WEREWOLF_PICK    | Alice (only wolf): WOLF_KILL(Eve)                          | `NightSubPhaseChanged(SEER_PICK)`                                                                               | Alice: solo wolf                       |
| 18    | NIGHT 3 / SEER_PICK        | Carol: SEER_CHECK(Dave)                                    | `NightSubPhaseChanged(SEER_RESULT)`                                                                             | Carol: active                          |
| 19    | NIGHT 3 / SEER_RESULT      | Carol: SEER_CONFIRM                                        | `SeerResult(dave, false)` private; `NightSubPhaseChanged(WITCH_ACT)`                                            | Carol: "Dave — 不是狼人"                   |
| 20    | NIGHT 3 / WITCH_ACT        | Dave: WITCH_ACT(skip) — antidote used                      | Resolve → Eve dies                                                                                              | Dave: antidote "已用完"                   |
| 21    | DAY 3 / RESULT_HIDDEN      | Alice(host): REVEAL_NIGHT_RESULT                           | `NightResult(kills: [eve])`; `PlayerEliminated(eve, HUNTER)`                                                    | All: waiting                           |
| 22    | DAY 3 / RESULT_REVEALED    | Alice(host): DAY_ADVANCE                                   | `PhaseChanged(VOTING, VOTING)`                                                                                  | All: Eve dead                          |
| 23    | VOTING 3 / VOTING          | 3 alive vote Alice                                         | `VoteSubmitted` × 3                                                                                             | Alice, Carol, Dave vote                |
| 24    | VOTING 3 / VOTE_RESULT     | Alice(host): VOTING_REVEAL_TALLY + VOTING_CONTINUE         | `VoteTally(eliminatedUserId: alice)`; `PlayerEliminated(alice, WEREWOLF)`; `GameOver(winner: VILLAGER)`         | Alice's WEREWOLF revealed; **"好人获胜！"** |
| 25    | GAME_OVER                  | —                                                          | —                                                                                                               | All: `ResultView`                      |

> **Note on hunter:** Eve is eliminated at Night 3 (night kill by wolf), NOT by vote. Hunter skill does NOT trigger on
> night kills — only on vote elimination. Eve has no shoot opportunity.

---

## Critical Steps (Detailed)

---

### Critical Step: VOTING Day 1 / VOTING — Vote Distribution Leading to Tie

Five alive players vote (Frank is dead). The tie scenario:

| Voter | Vote Target | Reasoning                     |
|-------|-------------|-------------------------------|
| Alice | Carol       | Wolf strategy: target seer    |
| Bob   | Carol       | Wolf strategy                 |
| Carol | Bob         | Seer knowledge: Bob is wolf   |
| Dave  | Bob         | Follows Carol's lead          |
| Eve   | Alice       | Suspicious of Alice's pushing |

Tally:

- Carol = 2 (votes from Alice, Bob)
- Bob = 2 (votes from Carol, Dave)
- Alice = 1 (vote from Eve)

→ **TIE between Carol and Bob (both at 2 votes). No elimination.**

**Events:**

```
VoteSubmitted(alice)
VoteSubmitted(bob)
VoteSubmitted(carol)
VoteSubmitted(dave)
VoteSubmitted(eve)
PhaseChanged(VOTING, VOTE_RESULT)
```

**Player Screens at VOTING:**

| Player | Component     | Key Visible Info                                | Available Actions                                  |
|--------|---------------|-------------------------------------------------|----------------------------------------------------|
| Alice  | `VotingPhase` | Alive player list: Alice, Bob, Carol, Dave, Eve | `[投票给 Bob]` `[投票给 Carol]` `[投票给 Dave]` `[投票给 Eve]` |
| Bob    | `VotingPhase` | Same                                            | Same                                               |
| Carol  | `VotingPhase` | Same                                            | Same                                               |
| Dave   | `VotingPhase` | Same                                            | Same                                               |
| Eve    | `VotingPhase` | Same                                            | Same                                               |

---

### Critical Step: VOTING Day 1 / VOTE_RESULT — Tie Shown, No Elimination

This is the core mechanic being tested. Host reveals tally. Result is a tie → no elimination.

**Host actions:**

1. `VOTING_REVEAL_TALLY` → reveals tally table to all players
2. `VOTING_CONTINUE` → advances game to next phase (night) without any elimination

**Events:**

```
VoteTally(eliminatedUserId: null, tally: {carol: 2, bob: 2, alice: 1, dave: 0, eve: 0})
PhaseChanged(NIGHT, WEREWOLF_PICK)
```

**Key fields in `VoteTally`:**

- `eliminatedUserId: null` — explicitly null, no one eliminated
- Tally map shows all vote counts including zero-vote players

**Player Screens at VOTE_RESULT:**

| Player       | Component     | Key Visible Info                                   | Available Actions                                                   |
|--------------|---------------|----------------------------------------------------|---------------------------------------------------------------------|
| Alice        | `VotingPhase` | **"平票！Carol 和 Bob 各得 2 票，无人出局"**; full tally table | — (waiting for host)                                                |
| Bob          | `VotingPhase` | Same — sees own high vote count                    | —                                                                   |
| Carol        | `VotingPhase` | Same — sees own high vote count                    | —                                                                   |
| Dave         | `VotingPhase` | Same                                               | —                                                                   |
| Eve          | `VotingPhase` | Same                                               | —                                                                   |
| Alice (host) | `VotingPhase` | Same as above                                      | `[公布票数]` → `VOTING_REVEAL_TALLY`; then `[继续游戏]` → `VOTING_CONTINUE` |

**Host action flow:**

1. Host sees tally (perhaps hidden initially) → presses `[公布票数]`
2. Tally revealed to all → tie message shown
3. Host presses `[继续游戏]` → game continues to Night 2

**Win check on tie:** No player eliminated → win condition not re-evaluated for tie. Game continues. Alive = 5 (Alice,
Bob, Carol, Dave, Eve). 2W vs 3 non-W → game would continue regardless.

---

### Key Assertions for Tie Vote

#### Backend Integration Assertions

1. `VoteTally` event with `eliminatedUserId: null` when tie occurs
2. No `PlayerEliminated` event fired
3. `PhaseChanged(NIGHT, WEREWOLF_PICK)` fires after `VOTING_CONTINUE` with no eliminations
4. All 5 players remain alive and in `alivePlayerIds` list
5. `VOTING_CONTINUE` is accepted by backend when called after a tie result
6. Re-vote is NOT triggered automatically — game simply moves to night (per this game's rules)

#### Frontend E2E Assertions

1. `VotingPhase` shows tie message: "平票" with tied players and their counts
2. No role reveal animation fires (no one was eliminated)
3. Host sees `[继续游戏]` button after tie is revealed
4. Non-host players see waiting state after tally shown
5. After `VOTING_CONTINUE`: all 5 players' status remains "alive" in the player list

---

### Subsequent Rounds Summary

**Night 2 → Day 2:** Wolves target Carol; witch saves Carol (antidote). No deaths. Bob voted out Day 2.

After Bob eliminated: Alice(W) vs Carol(S), Dave(Wi), Eve(H) = 1W vs 3 non-W → continues.

**Night 3:** Alice targets Eve. Seer checks Dave (not wolf). Witch has no antidote (used Night 2). Eve dies.

After Eve's Night 3 death:

- Eve eliminated by wolf kill — NOT a vote elimination → **Hunter shoot does NOT trigger**
- Alive: Alice(W), Carol(S), Dave(Wi) = 1W vs 2 non-W → continues

**Day 3:** Vote Alice. Alice eliminated. Wolves = 0 → VILLAGERS WIN.

---

## Full Role Reveal Table

| Seat | Player | Role     | Eliminated When                      |
|------|--------|----------|--------------------------------------|
| 1    | Alice  | WEREWOLF | Day 3 (vote)                         |
| 2    | Bob    | WEREWOLF | Day 2 (vote)                         |
| 3    | Carol  | SEER     | Alive (winner)                       |
| 4    | Dave   | WITCH    | Alive (winner)                       |
| 5    | Eve    | HUNTER   | Night 3 (wolf kill — no hunter shot) |
| 6    | Frank  | VILLAGER | Night 1                              |

---

## Assertions Summary

### Backend Integration Assertions

1. Night 1 resolve: Frank dies → `NightResult(kills: [frank])`
2. Day 1 tie vote: `VoteTally(eliminatedUserId: null)` — no player eliminated
3. `VOTING_CONTINUE` valid action when result is a tie
4. Game transitions to NIGHT phase after tie (no re-vote in this game ruleset)
5. Eve eliminated Night 3 by wolf kill → hunter skill does NOT trigger (only triggers on vote elimination)
6. `GameOver(winner: VILLAGER)` after Alice eliminated Day 3

### Frontend E2E Assertions

1. Tie message displayed clearly: "平票" + tied player names + counts
2. No `PlayerEliminated` event → no role reveal animation at Day 1 vote
3. All 5 players show as alive in player list after Day 1 tie
4. Night 3: Eve eliminated at night → no HUNTER_SHOOT subphase appears
5. `ResultView` shows "好人获胜！"

### Gating Tests

| Actor        | Attempted Action                               | Phase                | Rejection Reason                           |
|--------------|------------------------------------------------|----------------------|--------------------------------------------|
| Alice (host) | `VOTING_CONTINUE` before `VOTING_REVEAL_TALLY` | VOTING / VOTE_RESULT | "请先公布投票结果" (must reveal before continuing) |
| Any player   | `SUBMIT_VOTE` (second vote)                    | VOTING / VOTING      | "你已投票" (vote already submitted)            |
