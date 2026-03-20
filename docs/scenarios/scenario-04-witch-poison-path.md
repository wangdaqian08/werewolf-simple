# Scenario 04 — Witch Poison Path: Villagers Win

**Scenario ID:** 04
**Complexity:** Medium
**Outcome:** VILLAGER wins at Night 2 via witch poison (0 wolves)

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

| Round     | Event                                                                          | Alive After                                           |
|-----------|--------------------------------------------------------------------------------|-------------------------------------------------------|
| Night 1   | Wolves target Carol; Seer checks Bob (wolf!); Witch **SKIPS** (lets Carol die) | 5 alive: Alice(W), Bob(W), Dave(Wi), Eve(H), Frank(V) |
| Day 1     | Carol dead (SEER revealed); players vote Bob based on discussion               | 4 alive: Alice(W), Dave(Wi), Eve(H), Frank(V)         |
| Night 2   | Alice (only wolf) targets Frank; Witch uses **POISON on Alice**                | Frank dies (wolf kill) + Alice dies (witch poison)    |
| Win Check | 0 wolves remain → **VILLAGERS WIN**                                            | —                                                     |

**Key test:** Witch deliberately skips saving on Night 1, then uses poison on Night 2 to eliminate the last wolf at the
cost of the wolf's target. Both antidote (unused) and poison paths are exercised.

---

## Game Timeline

| Step   | Phase / SubPhase           | Key Actions                                        | Key Events                                                                                                                                   | Notable Screens                             |
|--------|----------------------------|----------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------|
| 0      | ROLE_REVEAL                | All 6: CONFIRM_ROLE                                | `RoleAssigned` × 6; `PhaseChanged(NIGHT, WEREWOLF_PICK)`                                                                                     | All: `RoleRevealCard`                       |
| 1      | NIGHT 1 / WEREWOLF_PICK    | Alice+Bob: WOLF_KILL(Carol)                        | `NightSubPhaseChanged(SEER_PICK)`                                                                                                            | Wolves: active pick UI                      |
| 2      | NIGHT 1 / SEER_PICK        | Carol: SEER_CHECK(Bob)                             | `NightSubPhaseChanged(SEER_RESULT)`                                                                                                          | Carol: active check UI                      |
| 3      | NIGHT 1 / SEER_RESULT      | Carol: SEER_CONFIRM                                | `SeerResult(bob, true)` private; `NightSubPhaseChanged(WITCH_ACT)`                                                                           | Carol: "Bob — 是狼人！"                         |
| **4**  | **NIGHT 1 / WITCH_ACT**    | **Dave: WITCH_ACT(skip)**                          | `NightSubPhaseChanged(COMPLETE)`; `PhaseChanged(DAY, RESULT_HIDDEN)`                                                                         | **Dave: sees Carol targeted; chooses skip** |
| 5      | DAY 1 / RESULT_HIDDEN      | Alice(host): REVEAL_NIGHT_RESULT                   | `NightResult(kills: [carol])`; `PlayerEliminated(carol, SEER)`                                                                               | All: waiting                                |
| 6      | DAY 1 / RESULT_REVEALED    | Alice(host): DAY_ADVANCE                           | `PhaseChanged(VOTING, VOTING)`                                                                                                               | All: Carol dead, SEER revealed              |
| **7**  | **VOTING 1 / VOTING**      | **All vote Bob (lucky discussion win)**            | `VoteSubmitted` × 5; `PhaseChanged(VOTING, VOTE_RESULT)`                                                                                     | **All: vote Bob**                           |
| **8**  | **VOTING 1 / VOTE_RESULT** | Alice(host): VOTING_REVEAL_TALLY + VOTING_CONTINUE | `VoteTally(eliminatedUserId: bob)`; `PlayerEliminated(bob, WEREWOLF)`; `PhaseChanged(NIGHT, WEREWOLF_PICK)`                                  | **Bob's WEREWOLF revealed**                 |
| 9      | NIGHT 2 / WEREWOLF_PICK    | Alice (only wolf): WOLF_KILL(Frank)                | `NightSubPhaseChanged(SEER_PICK)` (seer dead → auto-advance)                                                                                 | Alice: solo wolf pick                       |
| 10     | NIGHT 2 / SEER_PICK        | (auto-skipped — seer dead)                         | `NightSubPhaseChanged(WITCH_ACT)`                                                                                                            | —                                           |
| **11** | **NIGHT 2 / WITCH_ACT**    | **Dave: WITCH_ACT(poison, Alice)**                 | Night resolves                                                                                                                               | **Dave: critical moment — poisons Alice**   |
| 12     | WIN CHECK                  | Server resolves                                    | `NightResult(kills: [frank, alice])`; `PlayerEliminated(frank, VILLAGER)`; `PlayerEliminated(alice, WEREWOLF)`; `GameOver(winner: VILLAGER)` | —                                           |
| 13     | GAME_OVER                  | —                                                  | —                                                                                                                                            | All: `ResultView` "好人获胜！"                   |

> **Note on SEER_PICK auto-skip:** When Carol (seer) is eliminated, Night 2 has no seer to act. The server skips
> SEER_PICK and SEER_RESULT subphases and advances directly to WITCH_ACT.

---

## Critical Steps (Detailed)

---

### Critical Step: Night 1 / WITCH_ACT — Witch Deliberately Skips

This is the key decision point. Dave (witch) sees that Carol is the wolf target. Despite having both antidote and poison
available, Dave chooses to skip.

**Dave's screen:**

| Field             | Value                                                  |
|-------------------|--------------------------------------------------------|
| Component         | `NightPhase(active)`                                   |
| Background        | ink (`#2a1f14`)                                        |
| Wolf target shown | "Carol 今晚被狼人袭击"                                        |
| Antidote status   | ×1 available → `[使用解药]` button (enabled)               |
| Poison status     | ×1 available → player list + `[使用毒药]` button (enabled) |
| Action taken      | `WITCH_ACT(action: "skip")`                            |

**Why this is valid strategy:** Dave may not trust Carol, or Dave may be saving the antidote for a more valuable
target (themselves or another player). The backend accepts SKIP regardless of available potions.

**Events emitted:**

```
NightSubPhaseChanged(COMPLETE)
PhaseChanged(DAY, RESULT_HIDDEN)
```

**Night 1 resolve:**

- Carol dies (no antidote used, no guard)
- Witch state: antidote ×1 (unused!), poison ×1 (unused)

**Key assertion:** `NightResult(kills: [carol])` — Carol in kills list.

---

### Critical Step: Day 1 Vote — Bob Eliminated, WEREWOLF Revealed

Carol (seer) died Night 1 but cannot communicate her check results in-game after elimination. However, in this scenario
players make a "lucky" correct vote through discussion. (In a real game, Carol might have told someone before dying, or
players may have deduced from behavior.)

**Vote distribution (5 alive voters: Alice, Bob, Dave, Eve, Frank):**

| Voter | Vote Target | Reason                      |
|-------|-------------|-----------------------------|
| Alice | Frank       | Wolf strategy — protect Bob |
| Bob   | Frank       | Wolf strategy               |
| Dave  | Bob         | Suspicion from discussion   |
| Eve   | Bob         | Agrees with Dave            |
| Frank | Bob         | Follows group               |

Tally: Bob = 3, Frank = 2 → Bob eliminated.

**Events:**

```
VoteTally(eliminatedUserId: bob, tally: {bob: 3, frank: 2})
PlayerEliminated(userId: bob, role: WEREWOLF)
PhaseChanged(NIGHT, WEREWOLF_PICK)
```

**Player Screens at VOTE_RESULT:**

| Player | Component                    | Key Visible Info                             |
|--------|------------------------------|----------------------------------------------|
| Alice  | `VotingPhase`                | Bob's WEREWOLF role revealed (bad for Alice) |
| Bob    | `VotingPhase` → `Eliminated` | Own WEREWOLF role shown; "你已出局"              |
| Dave   | `VotingPhase`                | Bob's WEREWOLF revealed                      |
| Eve    | `VotingPhase`                | Bob's WEREWOLF revealed                      |
| Frank  | `VotingPhase`                | Bob's WEREWOLF revealed                      |

**Win check:** Alice(W) vs Dave(Wi), Eve(H), Frank(V) = 1W vs 3 non-W → game continues.

---

### Critical Step: Night 2 / WITCH_ACT — Witch Uses Poison on Alice

This is the defining moment of this scenario. Alice (only remaining wolf) targeted Frank. Dave (witch) has:

- Antidote: ×1 (never used!)
- Poison: ×1

Dave sees Frank is targeted. Dave makes a strategic sacrifice: let Frank die, but poison Alice (the wolf) to end the
game.

**Dave's screen:**

| Field             | Value                                                        |
|-------------------|--------------------------------------------------------------|
| Component         | `NightPhase(active)`                                         |
| Background        | ink                                                          |
| Wolf target shown | "Frank 今晚被狼人袭击"                                              |
| Antidote status   | ×1 available → `[使用解药]` (would save Frank)                   |
| Poison status     | ×1 available → player list includes Alice → `[使用毒药 → Alice]` |
| Action taken      | `WITCH_ACT(action: "poison", targetUserId: alice)`           |

**Critical behavior:** Witch uses POISON on Alice (not antidote on Frank). Both actions are independent:

- The wolf kill (Frank dies) is separate from the poison kill (Alice dies)
- Antidote is NOT used; it is consumed/wasted at game end

**Events emitted:**

```
NightSubPhaseChanged(COMPLETE)
```

---

### Critical Step: Night 2 Resolve — Two Deaths

After WITCH_ACT completes, backend resolves night:

1. Wolf kill target: Frank (not saved — antidote not used)
2. Witch poison target: Alice
3. Both Frank and Alice die simultaneously

**Events broadcast:**

```
NightResult(kills: [frank, alice])
PlayerEliminated(userId: frank, role: VILLAGER)
PlayerEliminated(userId: alice, role: WEREWOLF)
GameOver(winner: VILLAGER)
```

**Win check:** Alive after night = Dave(Wi), Eve(H). Wolves = 0 → VILLAGERS WIN immediately (no Day phase).

**Key assertions:**

1. `NightResult` contains BOTH frank and alice in kills list
2. `PlayerEliminated` fired for both players
3. Alice's role = WEREWOLF revealed
4. `GameOver(winner: VILLAGER)` fires without entering DAY phase

---

### GAME_OVER

**Player Screens:**

| Player | Component    | Key Visible Info                                |
|--------|--------------|-------------------------------------------------|
| Alice  | `ResultView` | "好人获胜！"; own WEREWOLF role shown                |
| Bob    | `ResultView` | "好人获胜！" (eliminated earlier)                    |
| Carol  | `ResultView` | "好人获胜！" (eliminated earlier)                    |
| Dave   | `ResultView` | "好人获胜！" (winner — witch saved the game)         |
| Eve    | `ResultView` | "好人获胜！" (winner)                                |
| Frank  | `ResultView` | "好人获胜！"; own VILLAGER role (eliminated Night 2) |

**Full Role Reveal Table:**

| Seat | Player | Role     | Status                            |
|------|--------|----------|-----------------------------------|
| 1    | Alice  | WEREWOLF | Eliminated Night 2 (witch poison) |
| 2    | Bob    | WEREWOLF | Eliminated Day 1                  |
| 3    | Carol  | SEER     | Eliminated Night 1                |
| 4    | Dave   | WITCH    | Alive (winner)                    |
| 5    | Eve    | HUNTER   | Alive (winner)                    |
| 6    | Frank  | VILLAGER | Eliminated Night 2 (wolf kill)    |

---

## Witch Potion State Tracking

| Night   | Antidote     | Poison       | Action        | State After                   |
|---------|--------------|--------------|---------------|-------------------------------|
| Start   | ×1           | ×1           | —             | antidote=1, poison=1          |
| Night 1 | ×1 available | ×1 available | SKIP          | antidote=1, poison=1          |
| Night 2 | ×1 available | ×1 available | POISON(alice) | antidote=1 (wasted), poison=0 |

**Key assertion:** Antidote remaining=1 at game end (was never used). Backend should track this correctly and not
incorrectly report it as consumed.

---

## Assertions Summary

### Backend Integration Assertions

1. Night 1: Witch SKIP → `NightResult(kills: [carol])` (Carol not saved)
2. Night 1: Witch state after SKIP → antidote=1, poison=1 (unchanged)
3. Day 1: Bob eliminated (WEREWOLF) → no HUNTER_SHOOT (Bob is not hunter); no BADGE_HANDOVER (`hasSheriff=false`)
4. Night 2: SEER_PICK subphase auto-skipped (Carol eliminated = no seer)
5. Night 2: `WITCH_ACT(poison, alice)` accepted → antidote still available but not used
6. Night 2 resolve: `NightResult(kills: [frank, alice])` — both deaths in one event
7. Win check after Night 2: 0 wolves → `GameOver(winner: VILLAGER)` without DAY phase

### Frontend E2E Assertions

1. Night 1 witch screen: both antidote and poison buttons enabled; player skips
2. Day 1: Carol's SEER role shown in death announcement
3. Night 2 witch screen: wolf target = Frank; both antidote and poison still enabled (never used)
4. Night 2 witch screen: witch can select Alice in poison target list
5. After `GameOver`: `ResultView` shows BOTH Frank and Alice in eliminated column with Night 2 cause
6. `ResultView` shows "好人获胜！"

### Gating Tests

| Actor | Attempted Action                                                | Phase               | Rejection Reason  |
|-------|-----------------------------------------------------------------|---------------------|-------------------|
| Dave  | `WITCH_ACT(antidote)` + `WITCH_ACT(poison, alice)` in same turn | NIGHT 2 / WITCH_ACT | "不能在同一晚同时使用解药和毒药" |
| Dave  | `WITCH_ACT(poison, dave)`                                       | NIGHT 2 / WITCH_ACT | "女巫不能对自己使用毒药"     |
