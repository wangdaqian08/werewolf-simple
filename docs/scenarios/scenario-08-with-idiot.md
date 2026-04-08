# Scenario 08 — Standard Six-Player: Idiot Reveal Flow

**Scenario ID:** 08
**Complexity:** Medium (focus on idiot role mechanics)
**Outcome:** VILLAGERS win with idiot surviving

---

## Cast

| Seat | Nickname | Role     | Notes                     |
|------|----------|----------|---------------------------|
| 1    | Alice    | WEREWOLF | Host (game master)        |
| 2    | Bob      | WEREWOLF |                           |
| 3    | Carol    | SEER     |                           |
| 4    | Dave     | IDIOT    | **Key role for this scenario** |
| 5    | Eve      | WITCH    |                           |
| 6    | Frank    | VILLAGER |                           |

## Room Configuration

```json
{
  "hasSeer": true,
  "hasWitch": true,
  "hasHunter": false,
  "hasGuard": false,
  "hasSheriff": false,
  "hasIdiot": true,
  "playerCount": 6
}
```

## Night Subphase Sequence

`WEREWOLF_PICK → SEER_PICK → SEER_RESULT → WITCH_ACT → COMPLETE`

(No GUARD_PICK because `hasGuard=false`)

## Game Summary

| Round     | Event                                                                                | Alive After                                                 |
|-----------|--------------------------------------------------------------------------------------|-------------------------------------------------------------|
| Night 1   | Wolves target Carol; Seer checks Bob (wolf!); Witch saves Carol (antidote)           | All 6 alive                                                 |
| Day 1     | Peaceful night announced; Idiot (Dave) voted out → reveals → survives, loses vote     | 6 alive: Alice(W), Bob(W), Carol(S), Dave(I)†, Eve(Wi), Frank(V) |
| Night 2   | Wolves target Frank; Seer checks Alice (wolf!); Witch uses poison on Bob               | Bob dies (poison); Frank survives (wolf kill)                |
| Day 2     | Night result announced; Alice voted out                                              | 5 alive: Carol(S), Dave(I)†, Eve(Wi), Frank(V)               |
| Night 3   | Wolves target Eve; Seer checks Dave (villager); Witch no potions                     | Eve dies                                                     |
| Day 3     | Night result announced; Bob voted out (eliminated earlier, but voting continues)      | 4 alive: Carol(S), Dave(I)†, Eve(Wi), Frank(V)               |
| Win Check | 0W vs 4V → **VILLAGERS WIN**                                                         | —                                                           |

† Dave (Idiot) revealed on Day 1, lost voting right, but remained alive and counted in win condition

---

## Phase Flow

### Step 0 — ROLE_REVEAL

> **Entry trigger:** Game started by host; backend assigns roles and sends private `RoleAssigned` events
> **Exit trigger:** All 6 players emit `CONFIRM_ROLE`; backend advances to NIGHT

#### Actions

| Actor | Action       | Target | Constraint            |
|-------|--------------|--------|-----------------------|
| Alice | CONFIRM_ROLE | —      | Must confirm own role |
| Bob   | CONFIRM_ROLE | —      | Must confirm own role |
| Carol | CONFIRM_ROLE | —      | Must confirm own role |
| Dave  | CONFIRM_ROLE | —      | Must confirm own role |
| Eve   | CONFIRM_ROLE | —      | Must confirm own role |
| Frank | CONFIRM_ROLE | —      | Must confirm own role |

#### Events Emitted

| Channel                       | Event           | Key Fields                                          |
|-------------------------------|-----------------|-----------------------------------------------------|
| `/user/queue/private` → Alice | `RoleAssigned`  | `userId: alice, role: WEREWOLF, teammates: [bob]`   |
| `/user/queue/private` → Bob   | `RoleAssigned`  | `userId: bob, role: WEREWOLF, teammates: [alice]`   |
| `/user/queue/private` → Carol | `RoleAssigned`  | `userId: carol, role: SEER`                         |
| `/user/queue/private` → Dave  | `RoleAssigned`  | `userId: dave, role: IDIOT`                         |
| `/user/queue/private` → Eve   | `RoleAssigned`  | `userId: eve, role: WITCH, antidote: 1, poison: 1`  |
| `/user/queue/private` → Frank | `RoleAssigned`  | `userId: frank, role: VILLAGER`                     |
| `/topic/game/{gameId}`        | `RoleConfirmed` | `userId: alice` (broadcast after each confirm)      |
| `/topic/game/{gameId}`        | `PhaseChanged`  | `phase: NIGHT, subPhase: WEREWOLF_PICK`              |

#### Player Screens

| Player | Role     | Component        | Key Visible Info              | Available Actions |
|--------|----------|------------------|-------------------------------|-------------------|
| Alice  | WEREWOLF | `RoleRevealCard` | "狼人"; teammate: Bob           | `[确认]`            |
| Bob    | WEREWOLF | `RoleRevealCard` | "狼人"; teammate: Alice         | `[确认]`            |
| Carol  | SEER     | `RoleRevealCard` | "预言家"                         | `[确认]`            |
| Dave   | IDIOT    | `RoleRevealCard` | "白痴"; 被投票驱逐时揭示身份，免于出局但失去投票权 | `[确认]`            |
| Eve    | WITCH    | `RoleRevealCard` | "女巫"; antidote ×1, poison ×1  | `[确认]`            |
| Frank  | VILLAGER | `RoleRevealCard` | "平民"                           | `[确认]`            |

---

### Step 1 — NIGHT 1 / WEREWOLF_PICK

> **Entry trigger:** `PhaseChanged(NIGHT, WEREWOLF_PICK)`
> **Exit trigger:** Both wolves submit WOLF_KILL → backend advances to SEER_PICK

#### Actions

| Actor | Action    | Target | Constraint            |
|-------|-----------|--------|-----------------------|
| Alice | WOLF_KILL | Carol  | Alive non-wolf player |
| Bob   | WOLF_KILL | Carol  | Must match Alice      |

#### Events Emitted

| Channel                    | Event                | Key Fields                            |
|----------------------------|----------------------|---------------------------------------|
| `/topic/game/{gameId}`     | `NightSubPhaseChanged`| `subPhase: SEER_PICK`                 |

#### Player Screens

| Player | Role     | Component      | Key Visible Info                | Available Actions              |
|--------|----------|----------------|---------------------------------|--------------------------------|
| Alice  | WEREWOLF | `NightPhase`   | "请选择今晚的袭击目标"             | `[点击玩家头像选择目标] [确认]` |
| Bob    | WEREWOLF | `NightPhase`   | "请选择今晚的袭击目标"             | `[点击玩家头像选择目标] [确认]` |
| Carol  | SEER     | `NightPhase`   | "等待狼人行动..."                  | —                              |
| Dave   | IDIOT    | `NightPhase`   | "等待狼人行动..."                  | —                              |
| Eve    | WITCH    | `NightPhase`   | "等待狼人行动..."                  | —                              |
| Frank  | VILLAGER | `NightPhase`   | "等待狼人行动..."                  | —                              |

---

### Step 2 — NIGHT 1 / SEER_PICK

> **Entry trigger:** `NightSubPhaseChanged(SEER_PICK)`
> **Exit trigger:** Seer submits SEER_CHECK → backend advances to SEER_RESULT

#### Actions

| Actor | Action      | Target | Constraint            |
|-------|-------------|--------|-----------------------|
| Carol | SEER_CHECK  | Bob    | Any alive player      |

#### Events Emitted

| Channel                    | Event                | Key Fields                            |
|----------------------------|----------------------|---------------------------------------|
| `/topic/game/{gameId}`     | `NightSubPhaseChanged`| `subPhase: SEER_RESULT`               |

#### Player Screens

| Player | Role     | Component      | Key Visible Info                | Available Actions              |
|--------|----------|----------------|---------------------------------|--------------------------------|
| Carol  | SEER     | `NightPhase`   | "请选择要查验的玩家"             | `[点击玩家头像选择目标] [查验]` |
| Others | —        | `NightPhase`   | "等待预言家查验..."              | —                              |

---

### Step 3 — NIGHT 1 / SEER_RESULT

> **Entry trigger:** `NightSubPhaseChanged(SEER_RESULT)`
> **Exit trigger:** 3-second timer → backend advances to WITCH_ACT

#### Events Emitted

| Channel                           | Event                | Key Fields                            |
|-----------------------------------|----------------------|---------------------------------------|
| `/user/queue/private` → Carol     | `SeerResult`         | `targetUserId: bob, isWolf: true`     |
| `/topic/game/{gameId}`            | `NightSubPhaseChanged`| `subPhase: WITCH_ACT`                 |

#### Player Screens

| Player | Role     | Component      | Key Visible Info                     | Available Actions |
|--------|----------|----------------|--------------------------------------|-------------------|
| Carol  | SEER     | `NightPhase`   | "Bob 是狼人！"                        | —                 |
| Others | —        | `NightPhase`   | "等待女巫行动..."                     | —                 |

---

### Step 4 — NIGHT 1 / WITCH_ACT

> **Entry trigger:** `NightSubPhaseChanged(WITCH_ACT)`
> **Exit trigger:** Witch submits action (or timeout) → backend advances to COMPLETE

#### Actions

| Actor | Action        | Target | Constraint                          |
|-------|---------------|--------|-------------------------------------|
| Eve   | WITCH_SAVE    | Carol  | Must match wolf kill target (Carol) |

#### Events Emitted

| Channel                    | Event                | Key Fields                            |
|----------------------------|----------------------|---------------------------------------|
| `/topic/game/{gameId}`     | `NightSubPhaseChanged`| `subPhase: COMPLETE`                  |

#### Player Screens

| Player | Role     | Component      | Key Visible Info                     | Available Actions              |
|--------|----------|----------------|--------------------------------------|--------------------------------|
| Eve    | WITCH    | `NightPhase`   | "今晚 Carol 被狼人袭击"               | `[使用解药] [使用毒药] [跳过]`  |
| Others | —        | `NightPhase`   | "等待女巫行动..."                     | —                              |

---

### Step 5 — NIGHT 1 / COMPLETE → DAY 1

> **Entry trigger:** `NightSubPhaseChanged(COMPLETE)`
> **Exit trigger:** Backend resolves night → transitions to DAY

#### Events Emitted

| Channel                    | Event                | Key Fields                                    |
|----------------------------|----------------------|-----------------------------------------------|
| `/topic/game/{gameId}`     | `NightResult`         | `kills: []` (no one died, witch saved Carol) |
| `/topic/game/{gameId}`     | `PhaseChanged`        | `phase: DAY, subPhase: DISCUSSION`           |

#### Player Screens

| Player | Role     | Component   | Key Visible Info                 | Available Actions |
|--------|----------|-------------|----------------------------------|-------------------|
| All    | —        | `DayPhase`  | "平安夜！今晚没有人死亡"          | `[进入投票]`      |

---

### Step 6 — DAY 1 / VOTING

> **Entry trigger:** All players click "进入投票" or timer expires
> **Exit trigger:** All votes submitted → host reveals tally

#### Actions

| Actor | Action         | Target | Constraint            |
|-------|----------------|--------|-----------------------|
| Alice | SUBMIT_VOTE    | Dave   | Alive player          |
| Bob   | SUBMIT_VOTE    | Dave   | Alive player          |
| Carol | SUBMIT_VOTE    | Dave   | Alive player          |
| Eve   | SUBMIT_VOTE    | Dave   | Alive player          |
| Frank | SUBMIT_VOTE    | Dave   | Alive player          |
| Dave  | SUBMIT_VOTE    | Bob    | Alive player          |

#### Events Emitted

| Channel                       | Event                | Key Fields                                    |
|-------------------------------|----------------------|-----------------------------------------------|
| `/topic/game/{gameId}`        | `VotingTallyRevealed`| `tally: [{playerId: dave, votes: 5}, {playerId: bob, votes: 1}]` |
| `/topic/game/{gameId}`        | `PhaseChanged`       | `phase: VOTING, subPhase: VOTE_RESULT`        |

#### Player Screens

| Player | Role     | Component        | Key Visible Info                     | Available Actions              |
|--------|----------|------------------|--------------------------------------|--------------------------------|
| Dave   | IDIOT    | `VotingPhase`    | "白痴翻牌 · IDIOT REVEALED"          | `[翻牌]`                        |
| Others | —        | `VotingPhase`    | "等待白痴行动..."                     | —                              |

---

### Step 7 — DAY 1 / IDIOT_REVEAL

> **Entry trigger:** Idiot clicks "翻牌"
> **Exit trigger:** Idiot action processed → backend updates idiot state

#### Actions

| Actor | Action        | Target | Constraint            |
|-------|---------------|--------|-----------------------|
| Dave  | IDIOT_REVEAL  | —      | Must be the idiot     |

#### Events Emitted

| Channel                    | Event                | Key Fields                                    |
|----------------------------|----------------------|-----------------------------------------------|
| `/topic/game/{gameId}`     | `IdiotRevealed`      | `userId: dave, nickname: Dave, seatIndex: 4`  |

#### Player Screens

| Player | Role     | Component        | Key Visible Info                                     | Available Actions              |
|--------|----------|------------------|------------------------------------------------------|--------------------------------|
| Dave   | IDIOT    | `VotingPhase`    | "白痴翻牌 · IDIOT REVEALED"; "存活，失去投票权"        | —                              |
| Others | —        | `VotingPhase`    | "白痴翻牌 · IDIOT REVEALED"; "存活，失去投票权"        | —                              |

**Key Points:**
- Dave (Idiot) is NOT eliminated
- Dave's `canVote` is set to `false`
- Dave's `idiotRevealed` is set to `true`
- Dave's player card shows 🃏 overlay
- Dave cannot vote in future rounds

---

### Step 8 — DAY 1 / VOTE_RESULT → NIGHT 2

> **Entry trigger:** Host clicks "继续"
> **Exit trigger:** Backend transitions to NIGHT 2

#### Actions

| Actor | Action            | Target | Constraint            |
|-------|-------------------|--------|-----------------------|
| Alice | VOTING_CONTINUE   | —      | Host only             |

#### Events Emitted

| Channel                    | Event                | Key Fields                            |
|----------------------------|----------------------|---------------------------------------|
| `/topic/game/{gameId}`     | `PhaseChanged`       | `phase: NIGHT, subPhase: WEREWOLF_PICK` |

---

### Step 9 — NIGHT 2 (Abbreviated)

> Night 2 proceeds with wolves killing Frank, seer checking Alice (wolf!), witch poisoning Bob.

**Key Events:**
- Wolves target Frank
- Seer checks Alice → WOLF
- Witch uses poison on Bob

**Result:**
- Bob dies (poison)
- Frank survives (wolf kill, witch saved Frank by not using antidote)
- Idiot (Dave) survives

---

### Step 10 — DAY 2 / VOTING (Abbreviated)

> Day 2 voting phase. Alice (wolf) is voted out and eliminated normally.

**Key Points:**
- Idiot (Dave) cannot vote (shows "🃏 已揭示白痴 · 无投票权")
- Alice is eliminated
- Game continues with 5 alive players

---

### Step 11 — NIGHT 3 / COMPLETE → DAY 3 (Abbreviated)

> Night 3: Wolves target Eve, seer checks Dave (villager), witch has no potions.

**Result:**
- Eve dies
- Idiot (Dave) survives
- Remaining: Carol (Seer), Dave (Idiot), Frank (Villager)

---

### Step 12 — WIN CHECK → VILLAGERS WIN

> **Entry trigger:** After night result is resolved
> **Exit trigger:** Backend checks win condition

**Win Condition:**
- Wolves alive: 0 (Alice and Bob both eliminated)
- Non-wolves alive: 3 (Carol, Dave, Frank)
- Idiot (Dave) counts as non-wolf even though `canVote=false`

#### Events Emitted

| Channel                    | Event                | Key Fields                            |
|----------------------------|----------------------|---------------------------------------|
| `/topic/game/{gameId}`     | `GameOver`           | `winner: VILLAGER`                    |

#### Player Screens

| Player | Role     | Component     | Key Visible Info        | Available Actions |
|--------|----------|---------------|-------------------------|-------------------|
| All    | —        | `ResultView`  | "村民获胜！"              | `[返回大厅]`       |

---

## Key Insights from This Scenario

1. **Idiot Reveal Mechanics:**
   - Idiot only reveals on first elimination by voting
   - Reveal happens in VOTE_RESULT sub-phase
   - Idiot survives but permanently loses voting right
   - Idiot's player card shows 🃏 overlay

2. **Idiot in Win Condition:**
   - Revealed idiot (`idiotRevealed=true, canVote=false`) still counts as alive non-wolf
   - Idiot contributes to villager count in win condition
   - This is why villagers can win even if idiot can't vote

3. **Idiot Vulnerabilities:**
   - After reveal, idiot can still be killed by:
     - Wolf attack at night
     - Witch poison
     - Hunter shot
     - Second elimination by voting (no second protection)

4. **UI Considerations:**
   - Idiot reveal banner appears for all players
   - Idiot's voting button changes to "🃏 已揭示白痴 · 无投票权"
   - Idiot cannot select players to vote
   - 🃏 overlay persists on idiot's card for entire game

5. **Edge Cases:**
   - Idiot as sheriff: can reveal, keeps badge, loses voting right, can still pass badge
   - Idiot reveal triggers no HUNTER_SHOOT or BADGE_HANDOVER (no elimination)
   - Idiot in tie vote: if re-voted, second time results in elimination