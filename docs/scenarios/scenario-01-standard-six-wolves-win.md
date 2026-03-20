# Scenario 01 — Standard Six-Player: Wolves Win

**Scenario ID:** 01
**Complexity:** Full (most detailed scenario — canonical reference)
**Outcome:** WEREWOLF wins at Night 2

---

## Cast

| Seat | Nickname | Role     | Notes              |
|------|----------|----------|--------------------|
| 1    | Alice    | WEREWOLF | Host (game master) |
| 2    | Bob      | WEREWOLF |                    |
| 3    | Carol    | SEER     | Elected Sheriff    |
| 4    | Dave     | WITCH    |                    |
| 5    | Eve      | HUNTER   |                    |
| 6    | Frank    | VILLAGER |                    |

## Room Configuration

```json
{
  "hasSeer": true,
  "hasWitch": true,
  "hasHunter": true,
  "hasGuard": false,
  "hasSheriff": true,
  "playerCount": 6
}
```

## Night Subphase Sequence

`WEREWOLF_PICK → SEER_PICK → SEER_RESULT → WITCH_ACT → COMPLETE`

(No GUARD_PICK because `hasGuard=false`)

## Game Summary

| Round     | Event                                                                               | Alive After                                            |
|-----------|-------------------------------------------------------------------------------------|--------------------------------------------------------|
| Night 1   | Wolves target Carol; Seer checks Bob (wolf!); Witch saves Carol (antidote)          | All 6 alive                                            |
| Day 1     | Peaceful night announced; Frank voted out                                           | 5 alive: Alice(W), Bob(W), Carol(S)†, Dave(Wi), Eve(H) |
| Night 2   | Wolves target Carol again; Seer checks Alice (wolf!); Witch has no antidote → skips | Carol dies                                             |
| Win Check | 2W (Alice, Bob) vs 2 non-W (Dave, Eve) → Wolves ≥ non-Wolves → **WOLVES WIN**       | —                                                      |

† Carol holds Sheriff badge

---

## Phase Flow

### Step 0 — ROLE_REVEAL

> **Entry trigger:** Game started by host; backend assigns roles and sends private `RoleAssigned` events
> **Exit trigger:** All 6 players emit `CONFIRM_ROLE`; backend advances to SHERIFF_ELECTION

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
| `/user/queue/private` → Dave  | `RoleAssigned`  | `userId: dave, role: WITCH, antidote: 1, poison: 1` |
| `/user/queue/private` → Eve   | `RoleAssigned`  | `userId: eve, role: HUNTER`                         |
| `/user/queue/private` → Frank | `RoleAssigned`  | `userId: frank, role: VILLAGER`                     |
| `/topic/game/{gameId}`        | `RoleConfirmed` | `userId: alice` (broadcast after each confirm)      |
| `/topic/game/{gameId}`        | `RoleConfirmed` | `userId: bob`                                       |
| `/topic/game/{gameId}`        | `RoleConfirmed` | `userId: carol`                                     |
| `/topic/game/{gameId}`        | `RoleConfirmed` | `userId: dave`                                      |
| `/topic/game/{gameId}`        | `RoleConfirmed` | `userId: eve`                                       |
| `/topic/game/{gameId}`        | `RoleConfirmed` | `userId: frank`                                     |
| `/topic/game/{gameId}`        | `PhaseChanged`  | `phase: SHERIFF_ELECTION, subPhase: SIGNUP`         |

#### Player Screens

| Player | Role     | Component        | Key Visible Info             | Available Actions |
|--------|----------|------------------|------------------------------|-------------------|
| Alice  | WEREWOLF | `RoleRevealCard` | "狼人"; teammate: Bob          | `[确认]`            |
| Bob    | WEREWOLF | `RoleRevealCard` | "狼人"; teammate: Alice        | `[确认]`            |
| Carol  | SEER     | `RoleRevealCard` | "预言家"                        | `[确认]`            |
| Dave   | WITCH    | `RoleRevealCard` | "女巫"; antidote ×1, poison ×1 | `[确认]`            |
| Eve    | HUNTER   | `RoleRevealCard` | "猎人"                         | `[确认]`            |
| Frank  | VILLAGER | `RoleRevealCard` | "平民"                         | `[确认]`            |

---

### Step 1 — SHERIFF_ELECTION / SIGNUP

> **Entry trigger:** `PhaseChanged(SHERIFF_ELECTION, SIGNUP)` broadcast
> **Exit trigger:** Host advances signup phase (or timeout); backend transitions to SPEECH

#### Actions

| Actor | Action           | Target | Constraint         |
|-------|------------------|--------|--------------------|
| Carol | SHERIFF_CAMPAIGN | —      | Declares candidacy |
| Frank | SHERIFF_CAMPAIGN | —      | Declares candidacy |
| Alice | —                | —      | Chooses not to run |
| Bob   | —                | —      | Chooses not to run |
| Dave  | —                | —      | Chooses not to run |
| Eve   | —                | —      | Chooses not to run |

#### Events Emitted

| Channel                | Event                   | Key Fields                                          |
|------------------------|-------------------------|-----------------------------------------------------|
| `/topic/game/{gameId}` | `SheriffCampaignUpdate` | `candidates: [carol]` (after Carol signs up)        |
| `/topic/game/{gameId}` | `SheriffCampaignUpdate` | `candidates: [carol, frank]` (after Frank signs up) |
| `/topic/game/{gameId}` | `PhaseChanged`          | `phase: SHERIFF_ELECTION, subPhase: SPEECH`         |

#### Player Screens

| Player | Role     | Component         | Key Visible Info                               | Available Actions           |
|--------|----------|-------------------|------------------------------------------------|-----------------------------|
| Alice  | WEREWOLF | `SheriffElection` | Candidates: Carol, Frank; Alice not running    | `[参选]` (she may still join) |
| Bob    | WEREWOLF | `SheriffElection` | Same as Alice                                  | `[参选]`                      |
| Carol  | SEER     | `SheriffElection` | Carol signed up (highlighted); Frank signed up | `[退出参选]`                    |
| Dave   | WITCH    | `SheriffElection` | Candidates: Carol, Frank                       | `[参选]`                      |
| Eve    | HUNTER   | `SheriffElection` | Candidates: Carol, Frank                       | `[参选]`                      |
| Frank  | VILLAGER | `SheriffElection` | Frank signed up (highlighted); Carol signed up | `[退出参选]`                    |

---

### Step 2 — SHERIFF_ELECTION / SPEECH

> **Entry trigger:** `PhaseChanged(SHERIFF_ELECTION, SPEECH)` broadcast
> **Exit trigger:** Host advances past last candidate's speech; backend transitions to VOTING

Speech order: Frank (seat 6 is first to speak per convention, or order determined by server), then Carol.

#### Actions

| Actor        | Action                 | Target | Constraint                               |
|--------------|------------------------|--------|------------------------------------------|
| Alice (host) | SHERIFF_START_SPEECH   | —      | Starts Frank's speech timer              |
| Alice (host) | SHERIFF_ADVANCE_SPEECH | —      | Ends Frank's speech, starts Carol's      |
| Alice (host) | SHERIFF_ADVANCE_SPEECH | —      | Ends Carol's speech, closes speech phase |

#### Events Emitted

| Channel                | Event                  | Key Fields                                  |
|------------------------|------------------------|---------------------------------------------|
| `/topic/game/{gameId}` | `SheriffSpeechStarted` | `speakerUserId: frank, index: 1, total: 2`  |
| `/topic/game/{gameId}` | `SheriffSpeechStarted` | `speakerUserId: carol, index: 2, total: 2`  |
| `/topic/game/{gameId}` | `PhaseChanged`         | `phase: SHERIFF_ELECTION, subPhase: VOTING` |

#### Player Screens

| Player | Role     | Component         | Key Visible Info                                | Available Actions |
|--------|----------|-------------------|-------------------------------------------------|-------------------|
| Alice  | WEREWOLF | `SheriffElection` | Current speaker highlighted; speech order shown | `[下一位]` (host)    |
| Bob    | WEREWOLF | `SheriffElection` | Current speaker highlighted                     | —                 |
| Carol  | SEER     | `SheriffElection` | Carol's turn indicated when it's her speech     | —                 |
| Dave   | WITCH    | `SheriffElection` | Speaker list                                    | —                 |
| Eve    | HUNTER   | `SheriffElection` | Speaker list                                    | —                 |
| Frank  | VILLAGER | `SheriffElection` | Frank's turn highlighted first                  | —                 |

---

### Step 3 — SHERIFF_ELECTION / VOTING

> **Entry trigger:** `PhaseChanged(SHERIFF_ELECTION, VOTING)` broadcast
> **Exit trigger:** All non-candidate players have voted; backend tallies and transitions to RESULT

#### Actions

| Actor | Action       | Target | Constraint                                |
|-------|--------------|--------|-------------------------------------------|
| Alice | SHERIFF_VOTE | Carol  | Non-candidate; may vote for any candidate |
| Bob   | SHERIFF_VOTE | Carol  | Non-candidate                             |
| Dave  | SHERIFF_VOTE | Carol  | Non-candidate                             |
| Eve   | SHERIFF_VOTE | Carol  | Non-candidate                             |
| Carol | —            | —      | Candidate; cannot vote in own election    |
| Frank | —            | —      | Candidate; cannot vote in own election    |

Tally: Carol = 4, Frank = 0.

#### Events Emitted

| Channel                | Event                  | Key Fields                                  |
|------------------------|------------------------|---------------------------------------------|
| `/topic/game/{gameId}` | `SheriffVoteSubmitted` | `voterUserId: alice` (one per voter)        |
| `/topic/game/{gameId}` | `SheriffVoteSubmitted` | `voterUserId: bob`                          |
| `/topic/game/{gameId}` | `SheriffVoteSubmitted` | `voterUserId: dave`                         |
| `/topic/game/{gameId}` | `SheriffVoteSubmitted` | `voterUserId: eve`                          |
| `/topic/game/{gameId}` | `PhaseChanged`         | `phase: SHERIFF_ELECTION, subPhase: RESULT` |

#### Player Screens

| Player | Role     | Component         | Key Visible Info                        | Available Actions           |
|--------|----------|-------------------|-----------------------------------------|-----------------------------|
| Alice  | WEREWOLF | `SheriffElection` | Candidate list; own vote cast indicator | `[投票给 Carol]` `[投票给 Frank]` |
| Bob    | WEREWOLF | `SheriffElection` | Same                                    | Same                        |
| Carol  | SEER     | `SheriffElection` | "候选人不参与投票" notice                       | —                           |
| Dave   | WITCH    | `SheriffElection` | Candidate list                          | `[投票给 Carol]` `[投票给 Frank]` |
| Eve    | HUNTER   | `SheriffElection` | Candidate list                          | `[投票给 Carol]` `[投票给 Frank]` |
| Frank  | VILLAGER | `SheriffElection` | "候选人不参与投票" notice                       | —                           |

---

### Step 4 — SHERIFF_ELECTION / RESULT

> **Entry trigger:** `PhaseChanged(SHERIFF_ELECTION, RESULT)` broadcast
> **Exit trigger:** Host reveals result; `SheriffElected` broadcast; backend transitions to NIGHT

#### Actions

| Actor        | Action                | Target | Constraint                               |
|--------------|-----------------------|--------|------------------------------------------|
| Alice (host) | SHERIFF_REVEAL_RESULT | —      | Reveals tally and assigns badge to Carol |

#### Events Emitted

| Channel                | Event            | Key Fields                              |
|------------------------|------------------|-----------------------------------------|
| `/topic/game/{gameId}` | `SheriffElected` | `sheriffUserId: carol`                  |
| `/topic/game/{gameId}` | `PhaseChanged`   | `phase: NIGHT, subPhase: WEREWOLF_PICK` |

#### Player Screens

| Player | Role     | Component         | Key Visible Info                        | Available Actions |
|--------|----------|-------------------|-----------------------------------------|-------------------|
| Alice  | WEREWOLF | `SheriffElection` | "Carol 当选警长"                            | —                 |
| Bob    | WEREWOLF | `SheriffElection` | "Carol 当选警长"                            | —                 |
| Carol  | SEER     | `SheriffElection` | Badge icon appears next to Carol's name | —                 |
| Dave   | WITCH    | `SheriffElection` | "Carol 当选警长"                            | —                 |
| Eve    | HUNTER   | `SheriffElection` | "Carol 当选警长"                            | —                 |
| Frank  | VILLAGER | `SheriffElection` | "Carol 当选警长"                            | —                 |

---

### Step 5 — NIGHT Day 1 / WEREWOLF_PICK

> **Entry trigger:** `PhaseChanged(NIGHT, WEREWOLF_PICK)` — screen goes dark for all
> **Exit trigger:** Both werewolves agree on target (WOLF_KILL submitted by both or last wolf confirms); backend
> transitions to SEER_PICK

#### Actions

| Actor | Action    | Target | Constraint                                |
|-------|-----------|--------|-------------------------------------------|
| Alice | WOLF_KILL | Carol  | Must be a non-wolf alive player           |
| Bob   | WOLF_KILL | Carol  | Both wolves must agree on the same target |

#### Events Emitted

| Channel                | Event                  | Key Fields                                     |
|------------------------|------------------------|------------------------------------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: SEER_PICK` (after wolves lock pick) |

> **Internal server state:** pending kill target = Carol (not yet broadcast; resolved at night end)

#### Player Screens

| Player | Role     | Component             | Key Visible Info                                                            | Available Actions                                |
|--------|----------|-----------------------|-----------------------------------------------------------------------------|--------------------------------------------------|
| Alice  | WEREWOLF | `NightPhase(active)`  | "狼人行动"; alive non-wolf list: Carol, Dave, Eve, Frank; Bob shown as teammate | `[选择 Carol]` `[选择 Dave]` `[选择 Eve]` `[选择 Frank]` |
| Bob    | WEREWOLF | `NightPhase(active)`  | Same as Alice; Alice's pick indicator visible                               | Same options                                     |
| Carol  | SEER     | `NightPhase(waiting)` | "狼人正在行动…"                                                                   | —                                                |
| Dave   | WITCH    | `NightPhase(waiting)` | "狼人正在行动…"                                                                   | —                                                |
| Eve    | HUNTER   | `NightPhase(waiting)` | "狼人正在行动…"                                                                   | —                                                |
| Frank  | VILLAGER | `NightPhase(waiting)` | "狼人正在行动…"                                                                   | —                                                |

---

### Step 6 — NIGHT Day 1 / SEER_PICK

> **Entry trigger:** `NightSubPhaseChanged(SEER_PICK)`
> **Exit trigger:** Seer submits SEER_CHECK; backend transitions to SEER_RESULT

#### Actions

| Actor | Action     | Target | Constraint                   |
|-------|------------|--------|------------------------------|
| Carol | SEER_CHECK | Bob    | Any alive player except self |

#### Events Emitted

| Channel                | Event                  | Key Fields              |
|------------------------|------------------------|-------------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: SEER_RESULT` |

> **Internal server state:** Check result computed: Bob = WEREWOLF

#### Player Screens

| Player | Role     | Component             | Key Visible Info                                      | Available Actions                                           |
|--------|----------|-----------------------|-------------------------------------------------------|-------------------------------------------------------------|
| Alice  | WEREWOLF | `NightPhase(waiting)` | "预言家正在查验…"                                            | —                                                           |
| Bob    | WEREWOLF | `NightPhase(waiting)` | "预言家正在查验…"                                            | —                                                           |
| Carol  | SEER     | `NightPhase(active)`  | Player list: Alice, Bob, Dave, Eve, Frank (not Carol) | `[查验 Alice]` `[查验 Bob]` `[查验 Dave]` `[查验 Eve]` `[查验 Frank]` |
| Dave   | WITCH    | `NightPhase(waiting)` | "预言家正在查验…"                                            | —                                                           |
| Eve    | HUNTER   | `NightPhase(waiting)` | "预言家正在查验…"                                            | —                                                           |
| Frank  | VILLAGER | `NightPhase(waiting)` | "预言家正在查验…"                                            | —                                                           |

---

### Step 7 — NIGHT Day 1 / SEER_RESULT

> **Entry trigger:** `NightSubPhaseChanged(SEER_RESULT)`; `SeerResult` event sent privately to Carol
> **Exit trigger:** Carol submits SEER_CONFIRM; backend transitions to WITCH_ACT

#### Actions

| Actor | Action       | Target | Constraint          |
|-------|--------------|--------|---------------------|
| Carol | SEER_CONFIRM | —      | Acknowledges result |

#### Events Emitted

| Channel                       | Event                  | Key Fields                             |
|-------------------------------|------------------------|----------------------------------------|
| `/user/queue/private` → Carol | `SeerResult`           | `checkedUserId: bob, isWerewolf: true` |
| `/topic/game/{gameId}`        | `NightSubPhaseChanged` | `subPhase: WITCH_ACT`                  |

#### Player Screens

| Player | Role     | Component             | Key Visible Info                  | Available Actions |
|--------|----------|-----------------------|-----------------------------------|-------------------|
| Alice  | WEREWOLF | `NightPhase(waiting)` | "预言家正在确认结果…"                      | —                 |
| Bob    | WEREWOLF | `NightPhase(waiting)` | "预言家正在确认结果…"                      | —                 |
| Carol  | SEER     | `NightPhase(active)`  | **"Bob — 是狼人！"** (red, wolf icon) | `[确认]`            |
| Dave   | WITCH    | `NightPhase(waiting)` | "预言家正在确认结果…"                      | —                 |
| Eve    | HUNTER   | `NightPhase(waiting)` | "预言家正在确认结果…"                      | —                 |
| Frank  | VILLAGER | `NightPhase(waiting)` | "预言家正在确认结果…"                      | —                 |

---

### Step 8 — NIGHT Day 1 / WITCH_ACT

> **Entry trigger:** `NightSubPhaseChanged(WITCH_ACT)`
> **Exit trigger:** Dave submits WITCH_ACT(antidote); backend resolves night and transitions to DAY/RESULT_HIDDEN

#### Actions

| Actor | Action    | Target             | Constraint                                        |
|-------|-----------|--------------------|---------------------------------------------------|
| Dave  | WITCH_ACT | action: "antidote" | Saves wolf kill target (Carol); antidote consumed |

#### Events Emitted

| Channel                | Event                  | Key Fields                            |
|------------------------|------------------------|---------------------------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: COMPLETE`                  |
| `/topic/game/{gameId}` | `PhaseChanged`         | `phase: DAY, subPhase: RESULT_HIDDEN` |

> **Night 1 resolve:** Carol was targeted (kill) but saved (antidote). Net deaths = []. Witch antidote = consumed.

#### Player Screens

| Player | Role     | Component             | Key Visible Info                                                  | Available Actions          |
|--------|----------|-----------------------|-------------------------------------------------------------------|----------------------------|
| Alice  | WEREWOLF | `NightPhase(waiting)` | "女巫正在行动…"                                                         | —                          |
| Bob    | WEREWOLF | `NightPhase(waiting)` | "女巫正在行动…"                                                         | —                          |
| Carol  | SEER     | `NightPhase(waiting)` | "女巫正在行动…"                                                         | —                          |
| Dave   | WITCH    | `NightPhase(active)`  | **"Carol 今晚被狼人袭击"**; antidote: ×1 available; poison: ×1 available | `[使用解药]` `[使用毒药 →]` `[跳过]` |
| Eve    | HUNTER   | `NightPhase(waiting)` | "女巫正在行动…"                                                         | —                          |
| Frank  | VILLAGER | `NightPhase(waiting)` | "女巫正在行动…"                                                         | —                          |

#### Gating — Rejected Actions

| Actor | Attempted Action                                        | Rejection Reason  |
|-------|---------------------------------------------------------|-------------------|
| Dave  | WITCH_ACT(antidote) + WITCH_ACT(poison, X) in same turn | "不能在同一晚同时使用解药和毒药" |

---

> ### Night 1 Resolve Summary
> - Wolf target: Carol
> - Witch antidote used: YES → Carol survives
> - Net deaths this night: **none**
> - State change: Dave's antidote = 0 (consumed)

---

### Step 9 — DAY Day 1 / RESULT_HIDDEN

> **Entry trigger:** `PhaseChanged(DAY, RESULT_HIDDEN)`; background transitions to parchment
> **Exit trigger:** Host (Alice) reveals result via REVEAL_NIGHT_RESULT; backend transitions to RESULT_REVEALED

#### Actions

| Actor        | Action              | Target | Constraint                   |
|--------------|---------------------|--------|------------------------------|
| Alice (host) | REVEAL_NIGHT_RESULT | —      | Triggers public announcement |

#### Events Emitted

| Channel                | Event          | Key Fields                              |
|------------------------|----------------|-----------------------------------------|
| `/topic/game/{gameId}` | `NightResult`  | `kills: []` (empty — no deaths)         |
| `/topic/game/{gameId}` | `PhaseChanged` | `phase: DAY, subPhase: RESULT_REVEALED` |

#### Player Screens

| Player | Role     | Component  | Key Visible Info                   | Available Actions    |
|--------|----------|------------|------------------------------------|----------------------|
| Alice  | WEREWOLF | `DayPhase` | "等待主持人公布结果"                        | `[公布结果]` (host only) |
| Bob    | WEREWOLF | `DayPhase` | "等待主持人公布结果"                        | —                    |
| Carol  | SEER     | `DayPhase` | "等待主持人公布结果"; Sheriff badge visible | —                    |
| Dave   | WITCH    | `DayPhase` | "等待主持人公布结果"                        | —                    |
| Eve    | HUNTER   | `DayPhase` | "等待主持人公布结果"                        | —                    |
| Frank  | VILLAGER | `DayPhase` | "等待主持人公布结果"                        | —                    |

---

### Step 10 — DAY Day 1 / RESULT_REVEALED

> **Entry trigger:** `PhaseChanged(DAY, RESULT_REVEALED)` + `NightResult(kills: [])`
> **Exit trigger:** Host advances with DAY_ADVANCE; backend transitions to VOTING/VOTING

#### Actions

| Actor        | Action      | Target | Constraint                     |
|--------------|-------------|--------|--------------------------------|
| Alice (host) | DAY_ADVANCE | —      | Ends discussion, begins voting |

#### Events Emitted

| Channel                | Event          | Key Fields                        |
|------------------------|----------------|-----------------------------------|
| `/topic/game/{gameId}` | `PhaseChanged` | `phase: VOTING, subPhase: VOTING` |

#### Player Screens

| Player | Role     | Component  | Key Visible Info                              | Available Actions    |
|--------|----------|------------|-----------------------------------------------|----------------------|
| Alice  | WEREWOLF | `DayPhase` | **"昨夜平安，无人死亡"**                               | `[进入投票]` (host only) |
| Bob    | WEREWOLF | `DayPhase` | "昨夜平安，无人死亡"                                   | —                    |
| Carol  | SEER     | `DayPhase` | "昨夜平安，无人死亡"; knows Bob is wolf (private info) | —                    |
| Dave   | WITCH    | `DayPhase` | "昨夜平安，无人死亡"                                   | —                    |
| Eve    | HUNTER   | `DayPhase` | "昨夜平安，无人死亡"                                   | —                    |
| Frank  | VILLAGER | `DayPhase` | "昨夜平安，无人死亡"                                   | —                    |

---

### Step 11 — VOTING Day 1 / VOTING

> **Entry trigger:** `PhaseChanged(VOTING, VOTING)`
> **Exit trigger:** All alive players have submitted votes; backend transitions to VOTE_RESULT

Vote distribution (6 players all alive):

- Alice → Frank (wolf strategy: eliminate villager)
- Bob → Frank (wolf strategy)
- Carol → Bob (seer knows Bob is wolf, tries to out him)
- Dave → Frank (coincidence / unaware)
- Eve → Frank (follows majority pressure)
- Frank → Bob (defends self)

Tally: Frank = 4, Bob = 2.

#### Actions

| Actor | Action      | Target | Constraint                                  |
|-------|-------------|--------|---------------------------------------------|
| Alice | SUBMIT_VOTE | Frank  | One vote per player, no change after submit |
| Bob   | SUBMIT_VOTE | Frank  |                                             |
| Carol | SUBMIT_VOTE | Bob    |                                             |
| Dave  | SUBMIT_VOTE | Frank  |                                             |
| Eve   | SUBMIT_VOTE | Frank  |                                             |
| Frank | SUBMIT_VOTE | Bob    |                                             |

#### Events Emitted

| Channel                | Event           | Key Fields                             |
|------------------------|-----------------|----------------------------------------|
| `/topic/game/{gameId}` | `VoteSubmitted` | `voterUserId: alice`                   |
| `/topic/game/{gameId}` | `VoteSubmitted` | `voterUserId: bob`                     |
| `/topic/game/{gameId}` | `VoteSubmitted` | `voterUserId: carol`                   |
| `/topic/game/{gameId}` | `VoteSubmitted` | `voterUserId: dave`                    |
| `/topic/game/{gameId}` | `VoteSubmitted` | `voterUserId: eve`                     |
| `/topic/game/{gameId}` | `VoteSubmitted` | `voterUserId: frank`                   |
| `/topic/game/{gameId}` | `PhaseChanged`  | `phase: VOTING, subPhase: VOTE_RESULT` |

#### Player Screens

| Player | Role     | Component     | Key Visible Info                           | Available Actions                |
|--------|----------|---------------|--------------------------------------------|----------------------------------|
| Alice  | WEREWOLF | `VotingPhase` | Alive player list; own vote cast indicator | `[投票给 Bob/Carol/Dave/Eve/Frank]` |
| Bob    | WEREWOLF | `VotingPhase` | Alive player list                          | Same options                     |
| Carol  | SEER     | `VotingPhase` | Alive player list; Sheriff badge visible   | Same options                     |
| Dave   | WITCH    | `VotingPhase` | Alive player list                          | Same options                     |
| Eve    | HUNTER   | `VotingPhase` | Alive player list                          | Same options                     |
| Frank  | VILLAGER | `VotingPhase` | Alive player list                          | Same options                     |

---

### Step 12 — VOTING Day 1 / VOTE_RESULT

> **Entry trigger:** `PhaseChanged(VOTING, VOTE_RESULT)`
> **Exit trigger:** Host reveals tally (VOTING_REVEAL_TALLY); Frank eliminated (not HUNTER → no HUNTER_SHOOT; not
> Sheriff → no BADGE_HANDOVER); host advances (VOTING_CONTINUE)

#### Actions

| Actor        | Action              | Target | Constraint                                                  |
|--------------|---------------------|--------|-------------------------------------------------------------|
| Alice (host) | VOTING_REVEAL_TALLY | —      | Reveals vote counts                                         |
| Alice (host) | VOTING_CONTINUE     | —      | Frank is not HUNTER, not Sheriff — game advances to Night 2 |

#### Events Emitted

| Channel                | Event              | Key Fields                                                                          |
|------------------------|--------------------|-------------------------------------------------------------------------------------|
| `/topic/game/{gameId}` | `VoteTally`        | `eliminatedUserId: frank, tally: {alice:0, bob:0, carol:0, dave:0, eve:0, frank:4}` |
| `/topic/game/{gameId}` | `PlayerEliminated` | `userId: frank, role: VILLAGER`                                                     |
| `/topic/game/{gameId}` | `PhaseChanged`     | `phase: NIGHT, subPhase: WEREWOLF_PICK`                                             |

> **Win check:** Alice(W), Bob(W) vs Carol(S), Dave(Wi), Eve(H) = 2W vs 3 non-W → game continues

#### Player Screens

| Player | Role     | Component                         | Key Visible Info                                      | Available Actions                            |
|--------|----------|-----------------------------------|-------------------------------------------------------|----------------------------------------------|
| Alice  | WEREWOLF | `VotingPhase`                     | Tally: Frank=4, Bob=2; Frank's role VILLAGER revealed | `[VOTING_REVEAL_TALLY]` then `[继续游戏]` (host) |
| Bob    | WEREWOLF | `VotingPhase`                     | Same tally                                            | —                                            |
| Carol  | SEER     | `VotingPhase`                     | Same tally; Sheriff badge visible                     | —                                            |
| Dave   | WITCH    | `VotingPhase`                     | Same tally                                            | —                                            |
| Eve    | HUNTER   | `VotingPhase`                     | Same tally                                            | —                                            |
| Frank  | VILLAGER | `VotingPhase` (then `Eliminated`) | Own role VILLAGER shown as revealed                   | —                                            |

---

> ### Day 1 → Night 2 Transition
> Frank (VILLAGER) eliminated. 5 players alive.
> Alice(W), Bob(W), Carol(S)†, Dave(Wi), Eve(H)
> Win check: 2W vs 3 non-W → game continues

---

### Step 13 — NIGHT Day 2 / WEREWOLF_PICK

> **Entry trigger:** `PhaseChanged(NIGHT, WEREWOLF_PICK)`
> **Exit trigger:** Alice and Bob both pick Carol; backend transitions to SEER_PICK

#### Actions

| Actor | Action    | Target | Constraint                      |
|-------|-----------|--------|---------------------------------|
| Alice | WOLF_KILL | Carol  | Non-wolf alive player           |
| Bob   | WOLF_KILL | Carol  | Same target as Alice to confirm |

#### Events Emitted

| Channel                | Event                  | Key Fields            |
|------------------------|------------------------|-----------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: SEER_PICK` |

#### Player Screens

| Player | Role     | Component             | Key Visible Info                       | Available Actions                   |
|--------|----------|-----------------------|----------------------------------------|-------------------------------------|
| Alice  | WEREWOLF | `NightPhase(active)`  | Alive non-wolf list: Carol†, Dave, Eve | `[选择 Carol]` `[选择 Dave]` `[选择 Eve]` |
| Bob    | WEREWOLF | `NightPhase(active)`  | Same                                   | Same                                |
| Carol  | SEER     | `NightPhase(waiting)` | "狼人正在行动…"                              | —                                   |
| Dave   | WITCH    | `NightPhase(waiting)` | "狼人正在行动…"                              | —                                   |
| Eve    | HUNTER   | `NightPhase(waiting)` | "狼人正在行动…"                              | —                                   |

---

### Step 14 — NIGHT Day 2 / SEER_PICK

> **Entry trigger:** `NightSubPhaseChanged(SEER_PICK)`
> **Exit trigger:** Carol submits SEER_CHECK(Alice)

#### Actions

| Actor | Action     | Target | Constraint                                         |
|-------|------------|--------|----------------------------------------------------|
| Carol | SEER_CHECK | Alice  | Bob already checked Night 1; Alice not yet checked |

#### Events Emitted

| Channel                | Event                  | Key Fields              |
|------------------------|------------------------|-------------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: SEER_RESULT` |

#### Player Screens

| Player | Role     | Component             | Key Visible Info                                                                | Available Actions                                       |
|--------|----------|-----------------------|---------------------------------------------------------------------------------|---------------------------------------------------------|
| Alice  | WEREWOLF | `NightPhase(waiting)` | "预言家正在查验…"                                                                      | —                                                       |
| Bob    | WEREWOLF | `NightPhase(waiting)` | "预言家正在查验…"                                                                      | —                                                       |
| Carol  | SEER     | `NightPhase(active)`  | Player list: Alice, Bob(★wolf), Dave, Eve; Bob's prior check shown as wolf icon | `[查验 Alice]` `[查验 Dave]` `[查验 Eve]` (Bob already known) |
| Dave   | WITCH    | `NightPhase(waiting)` | "预言家正在查验…"                                                                      | —                                                       |
| Eve    | HUNTER   | `NightPhase(waiting)` | "预言家正在查验…"                                                                      | —                                                       |

---

### Step 15 — NIGHT Day 2 / SEER_RESULT

> **Entry trigger:** `NightSubPhaseChanged(SEER_RESULT)`; `SeerResult` sent privately to Carol
> **Exit trigger:** Carol submits SEER_CONFIRM; backend transitions to WITCH_ACT

#### Actions

| Actor | Action       | Target | Constraint          |
|-------|--------------|--------|---------------------|
| Carol | SEER_CONFIRM | —      | Acknowledges result |

#### Events Emitted

| Channel                       | Event                  | Key Fields                               |
|-------------------------------|------------------------|------------------------------------------|
| `/user/queue/private` → Carol | `SeerResult`           | `checkedUserId: alice, isWerewolf: true` |
| `/topic/game/{gameId}`        | `NightSubPhaseChanged` | `subPhase: WITCH_ACT`                    |

#### Player Screens

| Player | Role     | Component             | Key Visible Info         | Available Actions |
|--------|----------|-----------------------|--------------------------|-------------------|
| Alice  | WEREWOLF | `NightPhase(waiting)` | "预言家正在确认结果…"             | —                 |
| Bob    | WEREWOLF | `NightPhase(waiting)` | "预言家正在确认结果…"             | —                 |
| Carol  | SEER     | `NightPhase(active)`  | **"Alice — 是狼人！"** (red) | `[确认]`            |
| Dave   | WITCH    | `NightPhase(waiting)` | "预言家正在确认结果…"             | —                 |
| Eve    | HUNTER   | `NightPhase(waiting)` | "预言家正在确认结果…"             | —                 |

---

### Step 16 — NIGHT Day 2 / WITCH_ACT

> **Entry trigger:** `NightSubPhaseChanged(WITCH_ACT)`
> **Exit trigger:** Dave submits WITCH_ACT(skip); backend resolves night

#### Actions

| Actor | Action    | Target         | Constraint                                              |
|-------|-----------|----------------|---------------------------------------------------------|
| Dave  | WITCH_ACT | action: "skip" | Antidote already consumed in Night 1; cannot save Carol |

#### Events Emitted

| Channel                | Event                  | Key Fields                            |
|------------------------|------------------------|---------------------------------------|
| `/topic/game/{gameId}` | `NightSubPhaseChanged` | `subPhase: COMPLETE`                  |
| `/topic/game/{gameId}` | `PhaseChanged`         | `phase: DAY, subPhase: RESULT_HIDDEN` |

> **Night 2 resolve:** Carol targeted by wolves. No antidote (consumed). No guard. Carol dies. Net deaths = [carol].

#### Player Screens

| Player | Role     | Component             | Key Visible Info                                             | Available Actions |
|--------|----------|-----------------------|--------------------------------------------------------------|-------------------|
| Alice  | WEREWOLF | `NightPhase(waiting)` | "女巫正在行动…"                                                    | —                 |
| Bob    | WEREWOLF | `NightPhase(waiting)` | "女巫正在行动…"                                                    | —                 |
| Carol  | SEER     | `NightPhase(waiting)` | "女巫正在行动…" (unaware she will die)                             | —                 |
| Dave   | WITCH    | `NightPhase(active)`  | **"Carol 今晚被狼人袭击"**; antidote: **已用完**; poison: ×1 available | `[使用毒药 →]` `[跳过]` |
| Eve    | HUNTER   | `NightPhase(waiting)` | "女巫正在行动…"                                                    | —                 |

---

> ### Night 2 Resolve Summary
> - Wolf target: Carol
> - Witch antidote used: NO (consumed Night 1)
> - Witch action: SKIP
> - Net deaths: **Carol**
> - Carol's role: SEER (revealed at Day announcement)

---

> ### Win Check After Night 2
> Alive players: Alice (WEREWOLF), Bob (WEREWOLF), Dave (WITCH), Eve (HUNTER)
> Wolves = 2, Non-wolves = 2
> Condition: Wolves (2) ≥ Non-wolves (2) → **WEREWOLF WINS**
> → Backend emits `GameOver(winner: WEREWOLF)` and transitions to GAME_OVER

---

### Step 17 — GAME_OVER

> **Entry trigger:** `GameOver(winner: WEREWOLF)` broadcast; `PhaseChanged(GAME_OVER)`
> **No exit trigger** — game ended

#### Events Emitted

| Channel                | Event              | Key Fields                                  |
|------------------------|--------------------|---------------------------------------------|
| `/topic/game/{gameId}` | `GameOver`         | `winner: WEREWOLF`                          |
| `/topic/game/{gameId}` | `NightResult`      | `kills: [carol]` (Night 2 deaths broadcast) |
| `/topic/game/{gameId}` | `PlayerEliminated` | `userId: carol, role: SEER`                 |

#### Player Screens

| Player | Role            | Component    | Key Visible Info                          | Available Actions |
|--------|-----------------|--------------|-------------------------------------------|-------------------|
| Alice  | WEREWOLF        | `ResultView` | **"狼人获胜！"** banner (red); full role table | `[返回大厅]`          |
| Bob    | WEREWOLF        | `ResultView` | Same                                      | `[返回大厅]`          |
| Carol  | SEER (elim)     | `ResultView` | Same; own role SEER shown                 | `[返回大厅]`          |
| Dave   | WITCH           | `ResultView` | Same; own role WITCH shown                | `[返回大厅]`          |
| Eve    | HUNTER          | `ResultView` | Same; own role HUNTER shown               | `[返回大厅]`          |
| Frank  | VILLAGER (elim) | `ResultView` | Same; own role VILLAGER shown             | `[返回大厅]`          |

#### Full Role Reveal Table (shown to all)

| Seat | Player | Role     | Status                  |
|------|--------|----------|-------------------------|
| 1    | Alice  | WEREWOLF | Alive (winner)          |
| 2    | Bob    | WEREWOLF | Alive (winner)          |
| 3    | Carol  | SEER     | Eliminated (Night 2)    |
| 4    | Dave   | WITCH    | Alive (defeated)        |
| 5    | Eve    | HUNTER   | Alive (defeated)        |
| 6    | Frank  | VILLAGER | Eliminated (Day 1 vote) |

---

## Assertions Summary (for test implementation)

### Backend Integration Assertions

1. After all 6 `CONFIRM_ROLE` actions → phase transitions to `SHERIFF_ELECTION/SIGNUP`
2. After sheriff voting → `SheriffElected(carol)` broadcast
3. After Night 1 wolves pick Carol + witch uses antidote → `NightResult(kills: [])` broadcast (Carol not in kills)
4. Dave's antidote count = 0 after Night 1 use
5. `SeerResult(checkedUserId: bob, isWerewolf: true)` sent only to Carol's private queue
6. Frank eliminated at Day 1 → `PlayerEliminated(frank, VILLAGER)` broadcast
7. After Night 2 witch skips → `NightResult(kills: [carol])`
8. After Carol killed → win condition met → `GameOver(winner: WEREWOLF)`

### Frontend E2E Assertions

1. `RoleRevealCard` shows wolf teammate name for Alice and Bob
2. Witch's `NightPhase(active)` screen in Night 1 shows antidote button enabled, poison button enabled
3. Witch's `NightPhase(active)` screen in Night 2 shows antidote button disabled ("已用完")
4. Seer's result screen shows "Bob — 是狼人！" after Night 1 check
5. Seer's result screen shows "Alice — 是狼人！" after Night 2 check
6. `ResultView` shows "狼人获胜" banner
7. All 6 players see full role reveal table in `ResultView`
