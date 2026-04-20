# Scenario 05 — Hunter Shoots After Vote: Villagers Win

**Scenario ID:** 05
**Complexity:** Medium
**Outcome:** VILLAGER wins Day 2; tests hunter mechanic

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

| Round   | Event                                                                                  | Alive After                                           |
|---------|----------------------------------------------------------------------------------------|-------------------------------------------------------|
| Night 1 | Wolves target Frank; Seer checks Bob (wolf!); Witch SKIPS → Frank dies                 | 5 alive: Alice(W), Bob(W), Carol(S), Dave(Wi), Eve(H) |
| Day 1   | Frank dead; vote → Eve eliminated (HUNTER triggered!); Eve shoots Bob → Bob eliminated | 3 alive: Alice(W), Carol(S), Dave(Wi)                 |
| Night 2 | Alice targets Carol; Seer checks Alice (wolf!); Witch saves Carol (antidote)           | All 3 still alive                                     |
| Day 2   | No deaths; vote → Alice eliminated → 0W → **VILLAGERS WIN**                            | —                                                     |

**Key test:** When Eve (HUNTER) is eliminated by vote, the `VOTING/HUNTER_SHOOT` subphase is triggered. Eve can then
shoot one player before game continues.

---

## Game Timeline

| Step   | Phase / SubPhase            | Key Actions                                        | Key Events                                                                                                                  | Notable Screens                            |
|--------|-----------------------------|----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| 0      | ROLE_REVEAL                 | All 6: CONFIRM_ROLE                                | `RoleAssigned` × 6; `PhaseChanged(NIGHT, WEREWOLF_PICK)`                                                                    | All: `RoleRevealCard`                      |
| 1      | NIGHT 1 / WEREWOLF_PICK     | Alice+Bob: WOLF_KILL(Frank)                        | `NightSubPhaseChanged(SEER_PICK)`                                                                                           | Wolves: active                             |
| 2      | NIGHT 1 / SEER_PICK         | Carol: SEER_CHECK(Bob)                             | `NightSubPhaseChanged(SEER_RESULT)`                                                                                         | Carol: active                              |
| 3      | NIGHT 1 / SEER_RESULT       | Carol: SEER_CONFIRM                                | `SeerResult(bob, true)` private; `NightSubPhaseChanged(WITCH_ACT)`                                                          | Carol: "Bob — 是狼人！"                        |
| 4      | NIGHT 1 / WITCH_ACT         | Dave: WITCH_ACT(skip)                              | `NightSubPhaseChanged(COMPLETE)` → resolve                                                                                  | Dave: sees Frank targeted; skips           |
| 5      | DAY 1 / RESULT_HIDDEN       | Alice(host): REVEAL_NIGHT_RESULT                   | `NightResult(kills: [frank])`; `PlayerEliminated(frank, VILLAGER)`                                                          | All: waiting                               |
| 6      | DAY 1 / RESULT_REVEALED     | Alice(host): DAY_ADVANCE                           | `PhaseChanged(VOTING, VOTING)`                                                                                              | All: Frank dead                            |
| **7**  | **VOTING 1 / VOTING**       | **All 5 vote; Eve gets majority**                  | `VoteSubmitted` × 5; `PhaseChanged(VOTING, VOTE_RESULT)`                                                                    | **Vote split shown**                       |
| **8**  | **VOTING 1 / VOTE_RESULT**  | Alice(host): VOTING_REVEAL_TALLY                   | `VoteTally(eliminatedUserId: eve)`; `PlayerEliminated(eve, HUNTER)`; `PhaseChanged(VOTING, HUNTER_SHOOT)`                   | **Eve's HUNTER role triggers shoot phase** |
| **9**  | **VOTING 1 / HUNTER_SHOOT** | **Eve: HUNTER_SHOOT(Bob)**                         | `HunterShot(hunterUserId: eve, targetUserId: bob)`; `PlayerEliminated(bob, WEREWOLF)`; `PhaseChanged(NIGHT, WEREWOLF_PICK)` | **Eve: shoot UI; all others: waiting**     |
| 10     | NIGHT 2 / WEREWOLF_PICK     | Alice (only wolf): WOLF_KILL(Carol)                | `NightSubPhaseChanged(SEER_PICK)`                                                                                           | Alice: solo wolf                           |
| 11     | NIGHT 2 / SEER_PICK         | Carol: SEER_CHECK(Alice)                           | `NightSubPhaseChanged(SEER_RESULT)`                                                                                         | Carol: active                              |
| 12     | NIGHT 2 / SEER_RESULT       | Carol: SEER_CONFIRM                                | `SeerResult(alice, true)` private; `NightSubPhaseChanged(WITCH_ACT)`                                                        | Carol: "Alice — 是狼人！"                      |
| 13     | NIGHT 2 / WITCH_ACT         | Dave: WITCH_ACT(antidote) — saves Carol            | `NightSubPhaseChanged(COMPLETE)` → resolve                                                                                  | Dave: uses antidote                        |
| 14     | DAY 2 / RESULT_HIDDEN       | Alice(host): REVEAL_NIGHT_RESULT                   | `NightResult(kills: [])`                                                                                                    | All: waiting                               |
| 15     | DAY 2 / RESULT_REVEALED     | Alice(host): DAY_ADVANCE                           | `PhaseChanged(VOTING, VOTING)`                                                                                              | All: "昨夜平安"                                |
| **16** | **VOTING 2 / VOTING**       | **All 3 vote Alice**                               | `VoteSubmitted` × 3                                                                                                         | **All vote Alice**                         |
| **17** | **VOTING 2 / VOTE_RESULT**  | Alice(host): VOTING_REVEAL_TALLY + VOTING_CONTINUE | `VoteTally(eliminatedUserId: alice)`; `PlayerEliminated(alice, WEREWOLF)`; `GameOver(winner: VILLAGER)`                     | **"好人获胜！"**                                |
| 18     | GAME_OVER                   | —                                                  | —                                                                                                                           | All: `ResultView`                          |

---

## Critical Steps (Detailed)

---

### Critical Step: VOTING Day 1 / VOTING — Vote Distribution

Five alive players vote (Frank is dead). Vote distribution:

| Voter | Vote Target | Reason                       |
|-------|-------------|------------------------------|
| Alice | Carol       | Wolf strategy — out the seer |
| Bob   | Carol       | Wolf strategy                |
| Carol | Bob         | Seer knowledge               |
| Dave  | Eve         | (wrong read — bad luck)      |
| Eve   | Bob         | Suspects Bob                 |

Tally: Bob=2, Carol=2, Eve=1 — hmm, that's a tie between Bob and Carol, not Eve.

Revised distribution to ensure Eve gets majority:

| Voter | Vote Target                           |
|-------|---------------------------------------|
| Alice | Eve (wolf strategy: eliminate hunter) |
| Bob   | Eve (wolf strategy)                   |
| Carol | Bob                                   |
| Dave  | Eve (influenced by Alice/Bob)         |
| Eve   | Bob                                   |

Tally: Eve=3, Bob=2 → Eve eliminated.

**Events:**

```
VoteSubmitted(alice), VoteSubmitted(bob), VoteSubmitted(carol), VoteSubmitted(dave), VoteSubmitted(eve)
PhaseChanged(VOTING, VOTE_RESULT)
```

**Player Screens:**

| Player | Component     | Key Visible Info                                | Available Actions                     |
|--------|---------------|-------------------------------------------------|---------------------------------------|
| Alice  | `VotingPhase` | Alive player list: Alice, Bob, Carol, Dave, Eve | `[投票给 Bob/Carol/Dave/Eve]` (not self) |
| Bob    | `VotingPhase` | Same                                            | Same                                  |
| Carol  | `VotingPhase` | Same                                            | Same                                  |
| Dave   | `VotingPhase` | Same                                            | Same                                  |
| Eve    | `VotingPhase` | Same                                            | Same                                  |

---

### Critical Step: VOTING Day 1 / VOTE_RESULT — Hunter Triggered

Host reveals tally. Eve is eliminated. Eve is HUNTER → backend detects this and transitions to `HUNTER_SHOOT` subphase.

**Events:**

```
VoteTally(eliminatedUserId: eve, tally: {eve: 3, bob: 2})
PlayerEliminated(userId: eve, role: HUNTER)
PhaseChanged(VOTING, HUNTER_SHOOT)
```

**Player Screens at VOTE_RESULT:**

| Player | Component                                       | Key Visible Info                                |
|--------|-------------------------------------------------|-------------------------------------------------|
| Alice  | `VotingPhase`                                   | Tally: Eve=3, Bob=2; Eve's HUNTER role revealed |
| Bob    | `VotingPhase`                                   | Same                                            |
| Carol  | `VotingPhase`                                   | Same                                            |
| Dave   | `VotingPhase`                                   | Same                                            |
| Eve    | `VotingPhase` → transitioning to `HUNTER_SHOOT` | "你是猎人，技能触发！"                                    |

---

### Critical Step: VOTING Day 1 / HUNTER_SHOOT — Eve Shoots Bob

This is the core mechanic for this scenario. Eve's hunter skill fires because she was voted out (not night-killed).

**Eve's screen:**

| Field             | Value                                                                     |
|-------------------|---------------------------------------------------------------------------|
| Component         | `VotingPhase` (hunter shoot overlay)                                      |
| Background        | Parchment with red border accent                                          |
| Message           | "你被投票出局，猎人技能触发！选择带走一名玩家，或放弃"                                              |
| Available targets | All alive players: Alice, Bob, Carol, Dave (not Eve — already eliminated) |
| Action            | `HUNTER_SHOOT(bob)`                                                       |

**All other players' screen:**

| Player | Component              | Key Visible Info |
|--------|------------------------|------------------|
| Alice  | `VotingPhase(waiting)` | "猎人正在选择目标…"      |
| Bob    | `VotingPhase(waiting)` | "猎人正在选择目标…"      |
| Carol  | `VotingPhase(waiting)` | "猎人正在选择目标…"      |
| Dave   | `VotingPhase(waiting)` | "猎人正在选择目标…"      |

**Events after Eve shoots Bob:**

```
HunterShot(hunterUserId: eve, targetUserId: bob)
PlayerEliminated(userId: bob, role: WEREWOLF)
PhaseChanged(NIGHT, WEREWOLF_PICK)
```

**Win check after both eliminations (Eve + Bob):**

- Eliminated this vote cycle: Eve (HUNTER), Bob (WEREWOLF via hunter shot)
- Alive: Alice(W), Carol(S), Dave(Wi)
- Wolves=1, Non-wolves=2 → game continues

**Key assertion:** Bob's WEREWOLF role is revealed when `PlayerEliminated(bob, WEREWOLF)` fires.

---

### Gating Test: Hunter Cannot Shoot Self

| Field             | Value                                                                 |
|-------------------|-----------------------------------------------------------------------|
| Actor             | Eve (HUNTER, already eliminated)                                      |
| Action            | `HUNTER_SHOOT(eve)`                                                   |
| Phase             | `VOTING / HUNTER_SHOOT`                                               |
| Expected Response | Error                                                                 |
| Rejection Reason  | "不能选择已出局的玩家" (cannot target an eliminated player)                     |
| Frontend behavior | Error message; Eve's selection resets; must pick a valid alive target |

---

### Variant: Hunter Skips (HUNTER_PASS Path)

This variant shows the `HUNTER_PASS` action. Instead of shooting Bob, Eve skips.

**Eve's action:**

```
HUNTER_PASS
```

**Events:**

```
HunterShot(hunterUserId: eve, targetUserId: null)   // or a separate HunterSkipped event
PhaseChanged(NIGHT, WEREWOLF_PICK)
```

**Effect:** No additional elimination. Eve is eliminated alone. Game continues with:

- Alive: Alice(W), Bob(W), Carol(S), Dave(Wi) — 4 players, 2W vs 2 non-W → WOLVES WIN immediately.

> **Note:** In the Hunter Skip variant, the game outcome changes — wolves win because 2W ≥ 2 non-W. This is the correct
> server-side win check behavior: win condition is re-evaluated after each elimination event.

**Win check (Hunter Skip variant):**

```
After Eve eliminated: Alice(W), Bob(W), Carol(S), Dave(Wi) → 2W vs 2 → WOLVES WIN
GameOver(winner: WEREWOLF)
```

This variant is useful for testing that:

1. `HUNTER_PASS` is a valid action and accepted by backend
2. Win condition is checked immediately after HUNTER_SHOOT/HUNTER_PASS completes, before moving to next phase

---

## GAME_OVER (Main Path — Eve Shoots Bob)

**Full Role Reveal Table:**

| Seat | Player | Role     | Status                         |
|------|--------|----------|--------------------------------|
| 1    | Alice  | WEREWOLF | Eliminated Day 2               |
| 2    | Bob    | WEREWOLF | Eliminated Day 1 (hunter shot) |
| 3    | Carol  | SEER     | Alive (winner)                 |
| 4    | Dave   | WITCH    | Alive (winner)                 |
| 5    | Eve    | HUNTER   | Eliminated Day 1 (vote)        |
| 6    | Frank  | VILLAGER | Eliminated Night 1             |

---

## Assertions Summary

### Backend Integration Assertions

1. Night 1 resolve: Frank dies (witch skipped) → `NightResult(kills: [frank])`
2. Day 1 vote: Eve eliminated (HUNTER) → backend triggers `PhaseChanged(VOTING, HUNTER_SHOOT)` immediately
3. `HUNTER_SHOOT` subphase only appears because eliminated player's role is HUNTER
4. Eve's `HUNTER_SHOOT(bob)` → `HunterShot(eve, bob)` + `PlayerEliminated(bob, WEREWOLF)`
5. After hunter phase: win check performed → 1W vs 2 non-W → game continues to Night 2
6. Night 2 witch antidote saves Carol → `NightResult(kills: [])`
7. Day 2 vote: Alice eliminated (WEREWOLF) → wolves=0 → `GameOver(winner: VILLAGER)`

### Frontend E2E Assertions

1. When Eve eliminated (HUNTER): `VotingPhase` briefly shows "Eve's role: HUNTER" then transitions to HUNTER_SHOOT UI
2. Eve's HUNTER_SHOOT screen: alive player list shown with `[射杀]` button per player + `[放弃]`
3. Other players: "猎人正在选择目标…" blocking screen (no actions available)
4. After `HunterShot(eve, bob)`: Bob's WEREWOLF role shown in a `PlayerEliminated` banner
5. `ResultView` shows "好人获胜！"

### Gating Tests

| Actor | Attempted Action      | Phase                 | Rejection Reason                         |
|-------|-----------------------|-----------------------|------------------------------------------|
| Eve   | `HUNTER_SHOOT(eve)`   | VOTING / HUNTER_SHOOT | "不能选择已出局的玩家"                             |
| Alice | `HUNTER_SHOOT(carol)` | VOTING / HUNTER_SHOOT | "你不是猎人" (role check)                     |
| Eve   | `SUBMIT_VOTE(bob)`    | VOTING / HUNTER_SHOOT | "当前阶段不允许此操作" (wrong action for subphase) |
