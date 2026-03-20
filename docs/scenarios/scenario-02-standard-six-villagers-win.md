# Scenario 02 — Standard Six-Player: Villagers Win

**Scenario ID:** 02
**Complexity:** Medium (condensed table format with critical steps in detail)
**Outcome:** VILLAGER wins at Day 2 vote

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

`hasSheriff=false` — sheriff election phase is skipped entirely.

## Night Subphase Sequence

`WEREWOLF_PICK → SEER_PICK → SEER_RESULT → WITCH_ACT → COMPLETE`

## Game Summary

| Round   | Event                                                                                   | Alive After                                           |
|---------|-----------------------------------------------------------------------------------------|-------------------------------------------------------|
| Start   | Roles assigned                                                                          | 6 alive                                               |
| Night 1 | Wolves target Frank; Seer checks Alice (wolf!); Witch saves Frank (antidote)            | All 6 alive                                           |
| Day 1   | Peaceful night; discussion; vote Alice                                                  | 5 alive: Bob(W), Carol(S), Dave(Wi), Eve(H), Frank(V) |
| Night 2 | Bob (only wolf) targets Carol; Seer checks Bob (wolf!); Witch has antidote used → skips | Carol dies                                            |
| Day 2   | Carol dead; Bob(W) vs Dave(Wi), Eve(H), Frank(V) = 1W vs 3 non-W → continues; vote Bob  | 0 wolves → VILLAGERS WIN                              |

---

## Game Timeline

| Step | Phase / SubPhase        | Key Actions                                        | Key Events                                                                                                           | Notable Screens                                   |
|------|-------------------------|----------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|
| 0    | ROLE_REVEAL             | All 6 players: CONFIRM_ROLE                        | `RoleAssigned` (private) × 6; `RoleConfirmed` × 6; `PhaseChanged(NIGHT, WEREWOLF_PICK)`                              | All: `RoleRevealCard` with role name              |
| 1    | NIGHT 1 / WEREWOLF_PICK | Alice+Bob: WOLF_KILL(Frank)                        | `NightSubPhaseChanged(SEER_PICK)`                                                                                    | Alice+Bob: `NightPhase(active)`, pick Frank       |
| 2    | NIGHT 1 / SEER_PICK     | Carol: SEER_CHECK(Alice)                           | `NightSubPhaseChanged(SEER_RESULT)`                                                                                  | Carol: `NightPhase(active)`, picks Alice          |
| 3    | NIGHT 1 / SEER_RESULT   | Carol: SEER_CONFIRM                                | `SeerResult(alice, isWerewolf: true)` private to Carol; `NightSubPhaseChanged(WITCH_ACT)`                            | Carol: sees "Alice — 是狼人！"                        |
| 4    | NIGHT 1 / WITCH_ACT     | Dave: WITCH_ACT(antidote) — saves Frank            | `NightSubPhaseChanged(COMPLETE)`; `PhaseChanged(DAY, RESULT_HIDDEN)`                                                 | Dave: sees "Frank 被袭击"; uses antidote             |
| 5    | DAY 1 / RESULT_HIDDEN   | Alice(host): REVEAL_NIGHT_RESULT                   | `NightResult(kills: [])`; `PhaseChanged(DAY, RESULT_REVEALED)`                                                       | All: waiting screen                               |
| 6    | DAY 1 / RESULT_REVEALED | Alice(host): DAY_ADVANCE                           | `PhaseChanged(VOTING, VOTING)`                                                                                       | All: "昨夜平安"                                       |
| 7    | VOTING 1 / VOTING       | All vote Alice (Carol convinces others)            | `VoteSubmitted` × 6; `PhaseChanged(VOTING, VOTE_RESULT)`                                                             | All: vote selection UI                            |
| 8    | VOTING 1 / VOTE_RESULT  | Alice(host): VOTING_REVEAL_TALLY + VOTING_CONTINUE | `VoteTally(eliminatedUserId: alice, ...)`; `PlayerEliminated(alice, WEREWOLF)`; `PhaseChanged(NIGHT, WEREWOLF_PICK)` | All: tally shown; Alice's WEREWOLF role revealed  |
| 9    | NIGHT 2 / WEREWOLF_PICK | Bob: WOLF_KILL(Carol)                              | `NightSubPhaseChanged(SEER_PICK)`                                                                                    | Bob: `NightPhase(active)` (solo wolf)             |
| 10   | NIGHT 2 / SEER_PICK     | Carol: SEER_CHECK(Bob)                             | `NightSubPhaseChanged(SEER_RESULT)`                                                                                  | Carol: `NightPhase(active)`                       |
| 11   | NIGHT 2 / SEER_RESULT   | Carol: SEER_CONFIRM                                | `SeerResult(bob, isWerewolf: true)` private; `NightSubPhaseChanged(WITCH_ACT)`                                       | Carol: "Bob — 是狼人！"                               |
| 12   | NIGHT 2 / WITCH_ACT     | Dave: WITCH_ACT(skip) — no antidote                | `NightSubPhaseChanged(COMPLETE)`; `PhaseChanged(DAY, RESULT_HIDDEN)`                                                 | Dave: antidote shown as "已用完"; skips              |
| 13   | DAY 2 / RESULT_HIDDEN   | Bob(host): REVEAL_NIGHT_RESULT                     | `NightResult(kills: [carol])`; `PlayerEliminated(carol, SEER)`; `PhaseChanged(DAY, RESULT_REVEALED)`                 | All: waiting                                      |
| 14   | DAY 2 / RESULT_REVEALED | Bob(host): DAY_ADVANCE                             | `PhaseChanged(VOTING, VOTING)`                                                                                       | All: Carol dead announced; SEER role revealed     |
| 15   | VOTING 2 / VOTING       | All alive vote Bob (1W vs 3 non-W, game continues) | `VoteSubmitted` × 4; `PhaseChanged(VOTING, VOTE_RESULT)`                                                             | All: vote Bob                                     |
| 16   | VOTING 2 / VOTE_RESULT  | Bob(host): VOTING_REVEAL_TALLY + VOTING_CONTINUE   | `VoteTally(eliminatedUserId: bob)`; `PlayerEliminated(bob, WEREWOLF)`; `GameOver(winner: VILLAGER)`                  | All: Bob's WEREWOLF role revealed; **win screen** |
| 17   | GAME_OVER               | —                                                  | —                                                                                                                    | All: `ResultView` "好人获胜！"                         |

> **Note on host transfer:** Alice (host) is eliminated at Day 1. Bob becomes the new host from Night 2 onwards.

---

## Critical Steps (Detailed)

---

### Critical Step: Night 1 Resolve — Frank Saved (Peaceful)

**State entering night resolve:**

- Wolf target: Frank
- Witch action: WITCH_ACT(antidote) — saves the wolf target
- Net deaths: none (antidote cancels wolf kill)

**Events broadcast:**

```
NightSubPhaseChanged(COMPLETE)
PhaseChanged(DAY, RESULT_HIDDEN)
```

**Key assertion:** `NightResult(kills: [])` — Frank is NOT in kills list despite being targeted.

**Witch state after Night 1:**

- Antidote: 0 (consumed)
- Poison: 1 (unused)

---

### Critical Step: Day 1 Vote — Alice Eliminated, WEREWOLF Revealed

**Vote tally:**

| Voter | Vote Target                                                                                                                          |
|-------|--------------------------------------------------------------------------------------------------------------------------------------|
| Alice | Bob (wolf protects wolf? No — wolf strategy might be to vote non-wolf, but here Carol has convinced others and Alice is outnumbered) |
| Bob   | Frank                                                                                                                                |
| Carol | Alice (knows Alice is wolf from Night 1 check)                                                                                       |
| Dave  | Alice                                                                                                                                |
| Eve   | Alice                                                                                                                                |
| Frank | Alice                                                                                                                                |

Tally: Alice = 4, Frank = 1, Bob = 1 → Alice eliminated.

**Events:**

```
VoteTally(eliminatedUserId: alice, tally: {alice: 4, frank: 1, bob: 1})
PlayerEliminated(userId: alice, role: WEREWOLF)
PhaseChanged(NIGHT, WEREWOLF_PICK)
```

**Player Screens at VOTE_RESULT:**

| Player | Component                    | Key Visible Info                                     |
|--------|------------------------------|------------------------------------------------------|
| Alice  | `VotingPhase` → `Eliminated` | Own role WEREWOLF revealed; "你已出局"                   |
| Bob    | `VotingPhase`                | Alice=WEREWOLF revealed; shocked reaction expected   |
| Carol  | `VotingPhase`                | Alice=WEREWOLF confirmed (matches her Night 1 check) |
| Dave   | `VotingPhase`                | Alice's role shown                                   |
| Eve    | `VotingPhase`                | Alice's role shown                                   |
| Frank  | `VotingPhase`                | Alice's role shown                                   |

**Win check:** Bob(W) vs Carol(S), Dave(Wi), Eve(H), Frank(V) = 1W vs 4 non-W → game continues.

---

### Critical Step: Night 2 Resolve — Carol Dies

**State entering night resolve:**

- Wolf target: Carol (only Bob is wolf now)
- Witch action: SKIP (antidote consumed Night 1)
- Net deaths: Carol

**Events broadcast:**

```
NightSubPhaseChanged(COMPLETE)
PhaseChanged(DAY, RESULT_HIDDEN)
```

Then when host (Bob) reveals:

```
NightResult(kills: [carol])
PlayerEliminated(userId: carol, role: SEER)
PhaseChanged(DAY, RESULT_REVEALED)
```

**Key assertion:** `NightResult(kills: [carol])` — Carol IS in kills list; SEER role revealed.

**Day 2 state:** Bob(W), Dave(Wi), Eve(H), Frank(V) = 1W vs 3 non-W → game continues.

**Dave's WITCH_ACT screen (Night 2):**

| Field             | Value                                     |
|-------------------|-------------------------------------------|
| Component         | `NightPhase(active)`                      |
| Wolf target shown | "Carol 今晚被狼人袭击"                           |
| Antidote status   | "解药已用完" (disabled, greyed out)            |
| Poison status     | ×1 available (Dave chooses not to poison) |
| Action taken      | `WITCH_ACT(skip)`                         |

---

### Critical Step: Day 2 Vote — Bob Eliminated → VILLAGERS WIN

**Alive players:** Bob(W), Dave(Wi), Eve(H), Frank(V) — 4 players, 1 wolf vs 3 non-wolves.

Carol revealed as SEER after Night 2 death, so players know seer is gone but have Bob identified.

**Vote tally:**

| Voter | Vote Target                   |
|-------|-------------------------------|
| Bob   | Frank (wolf tries to survive) |
| Dave  | Bob                           |
| Eve   | Bob                           |
| Frank | Bob                           |

Tally: Bob = 3, Frank = 1 → Bob eliminated.

**Events:**

```
VoteTally(eliminatedUserId: bob, tally: {bob: 3, frank: 1})
PlayerEliminated(userId: bob, role: WEREWOLF)
GameOver(winner: VILLAGER)
```

**Win check:** 0 wolves remaining → VILLAGER wins.

**Player Screens at GAME_OVER:**

| Player | Component    | Key Visible Info                                     |
|--------|--------------|------------------------------------------------------|
| Bob    | `ResultView` | **"好人获胜！"** banner; own WEREWOLF role shown in table |
| Dave   | `ResultView` | "好人获胜！" banner (winner)                              |
| Eve    | `ResultView` | "好人获胜！" banner (winner)                              |
| Frank  | `ResultView` | "好人获胜！" banner (winner)                              |
| Alice  | `ResultView` | "好人获胜！" banner (eliminated earlier)                  |
| Carol  | `ResultView` | "好人获胜！" banner (eliminated earlier)                  |

**Full Role Reveal Table:**

| Seat | Player | Role     | Status             |
|------|--------|----------|--------------------|
| 1    | Alice  | WEREWOLF | Eliminated Day 1   |
| 2    | Bob    | WEREWOLF | Eliminated Day 2   |
| 3    | Carol  | SEER     | Eliminated Night 2 |
| 4    | Dave   | WITCH    | Alive (winner)     |
| 5    | Eve    | HUNTER   | Alive (winner)     |
| 6    | Frank  | VILLAGER | Alive (winner)     |

---

## Assertions Summary

### Backend Integration Assertions

1. After Night 1 with witch antidote → `NightResult(kills: [])` — Frank not in kills
2. `SeerResult(alice, isWerewolf: true)` sent only to Carol's private queue (Night 1)
3. After Alice eliminated → no `HUNTER_SHOOT` phase (Alice is WEREWOLF, not HUNTER)
4. After Alice eliminated → no `BADGE_HANDOVER` phase (`hasSheriff=false`)
5. After Night 2 witch skips → `NightResult(kills: [carol])`
6. `SeerResult(bob, isWerewolf: true)` sent only to Carol's private queue (Night 2)
7. After Bob eliminated → wolves = 0 → `GameOver(winner: VILLAGER)` immediately

### Frontend E2E Assertions

1. After Night 1: `DayPhase` shows "昨夜平安，无人死亡" for all players
2. After Alice eliminated: `VotingPhase` shows Alice's role as WEREWOLF
3. Night 2 witch screen: antidote button is disabled/greyed with "已用完" label
4. After Carol eliminated: `DayPhase` shows Carol in deaths list with role SEER
5. After Bob eliminated: `ResultView` shows "好人获胜！" banner
