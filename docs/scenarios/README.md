# Werewolf Game — Scenario Documentation Index

This directory is the **single source of truth** for the design intent of each scenario, and for the assertions
made by Playwright real-backend E2E tests (`frontend/e2e/real/`) and frontend unit tests. Every assertion a test
makes should be derivable from these documents.

---

## Scenario Index

| ID | File                                                                                   | Players                 | Outcome               | Key Mechanic Tested                                     |
|----|----------------------------------------------------------------------------------------|-------------------------|-----------------------|---------------------------------------------------------|
| 01 | [scenario-01-standard-six-wolves-win.md](scenario-01-standard-six-wolves-win.md)       | 6 (W×2, S, Wi, H, V)    | Wolves win Night 2    | Full game flow, sheriff election, all special roles     |
| 02 | [scenario-02-standard-six-villagers-win.md](scenario-02-standard-six-villagers-win.md) | 6 (W×2, S, Wi, H, V)    | Villagers win Day 2   | Seer leads to wolf elimination, villager win path       |
| 03 | [scenario-03-minimal-four-player.md](scenario-03-minimal-four-player.md)               | 4 (W×2, V×2)            | Wolves win Night 1    | Minimal flow, immediate win condition, no special roles |
| 04 | [scenario-04-witch-poison-path.md](scenario-04-witch-poison-path.md)                   | 6 (W×2, S, Wi, H, V)    | Villagers win Night 2 | Witch poison mechanic, dual-death night resolve         |
| 05 | [scenario-05-hunter-shoots-after-vote.md](scenario-05-hunter-shoots-after-vote.md)     | 6 (W×2, S, Wi, H, V)    | Villagers win Day 2   | Hunter shoot on elimination, HUNTER_PASS variant        |
| 06 | [scenario-06-tie-vote.md](scenario-06-tie-vote.md)                                     | 6 (W×2, S, Wi, H, V)    | Villagers win Day 3   | Tie vote → no elimination, VOTING_CONTINUE              |
| 07 | [scenario-07-with-guard.md](scenario-07-with-guard.md)                                 | 7 (W×2, S, Wi, H, G, V) | TBD (mid-game)        | Guard protect, same-player repeat rejection             |
| 08 | [scenario-08-with-idiot.md](scenario-08-with-idiot.md)                                 | 6 (W×2, S, I, H, V)     | Mid-game (see doc)    | Idiot reveal on vote, loses vote right, stays alive     |
| 09 | [scenario-09-e2e.md](scenario-09-e2e.md)                                               | 12 (hard mode)          | see doc               | HARD_MODE win condition, full 12-player regression      |

> **Role abbreviations used throughout:** W = WEREWOLF, S = SEER, Wi = WITCH, H = HUNTER, G = GUARD, V = VILLAGER

---

## Role × Phase Reference Matrix

Rows = phase/subphase. Columns = role. Each cell contains:

- **Screen** component name (see naming convention below)
- Visible information specific to that role
- Available action buttons (`[ActionType]`)

### Legend

| Symbol      | Meaning                                   |
|-------------|-------------------------------------------|
| `(active)`  | Role can take action during this subphase |
| `(waiting)` | Role waits; no actions available          |
| `(elim)`    | Player has been eliminated; spectating    |
| `—`         | No action available                       |

---

### Matrix

| Phase / Subphase              | WEREWOLF                                                                                                                                                  | SEER                                                                                   | WITCH                                                                                                                                                          | HUNTER                                                                                 | GUARD                                                                                                                           | VILLAGER                                                                               |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| **ROLE_REVEAL**               | RoleReveal(active) · Role name "狼人", wolf teammates list · `[CONFIRM_ROLE]`                                                                               | RoleReveal(active) · Role name "预言家" · `[CONFIRM_ROLE]`                                | RoleReveal(active) · Role name "女巫", antidote/poison counts · `[CONFIRM_ROLE]`                                                                                 | RoleReveal(active) · Role name "猎人" · `[CONFIRM_ROLE]`                                 | RoleReveal(active) · Role name "守卫" · `[CONFIRM_ROLE]`                                                                          | RoleReveal(active) · Role name "平民" · `[CONFIRM_ROLE]`                                 |
| **SHERIFF_ELECTION / SIGNUP** | SheriffElection(active) · Signup list updating · `[SHERIFF_CAMPAIGN]` `[SHERIFF_QUIT]`                                                                    | SheriffElection(active) · Signup list updating · `[SHERIFF_CAMPAIGN]` `[SHERIFF_QUIT]` | SheriffElection(active) · Signup list updating · `[SHERIFF_CAMPAIGN]` `[SHERIFF_QUIT]`                                                                         | SheriffElection(active) · Signup list updating · `[SHERIFF_CAMPAIGN]` `[SHERIFF_QUIT]` | SheriffElection(active) · Signup list updating · `[SHERIFF_CAMPAIGN]` `[SHERIFF_QUIT]`                                          | SheriffElection(active) · Signup list updating · `[SHERIFF_CAMPAIGN]` `[SHERIFF_QUIT]` |
| **SHERIFF_ELECTION / SPEECH** | SheriffElection(waiting/active) · Candidates list, current speaker highlighted · host: `[SHERIFF_START_SPEECH]` `[SHERIFF_ADVANCE_SPEECH]`; non-host: `—` | same                                                                                   | same                                                                                                                                                           | same                                                                                   | same                                                                                                                            | same                                                                                   |
| **SHERIFF_ELECTION / VOTING** | SheriffElection(active) · Candidate list · `[SHERIFF_VOTE]` (if not a candidate) or spectating own speech                                                 | same                                                                                   | same                                                                                                                                                           | same                                                                                   | same                                                                                                                            | same                                                                                   |
| **SHERIFF_ELECTION / RESULT** | SheriffElection(result) · Elected sheriff name displayed · host: `[SHERIFF_REVEAL_RESULT]`; others: `—`                                                   | same                                                                                   | same                                                                                                                                                           | same                                                                                   | same                                                                                                                            | same                                                                                   |
| **NIGHT / WEREWOLF_PICK**     | NightPhase(active) · All alive non-wolf player list · `[WOLF_KILL(target)]`                                                                               | NightPhase(waiting) · "狼人正在行动" · `—`                                                   | NightPhase(waiting) · "狼人正在行动" · `—`                                                                                                                           | NightPhase(waiting) · "狼人正在行动" · `—`                                                   | NightPhase(waiting) · "狼人正在行动" · `—`                                                                                            | NightPhase(waiting) · "狼人正在行动" · `—`                                                   |
| **NIGHT / SEER_PICK**         | NightPhase(waiting) · "预言家正在查验" · `—`                                                                                                                     | NightPhase(active) · All alive non-self player list · `[SEER_CHECK(target)]`           | NightPhase(waiting) · "预言家正在查验" · `—`                                                                                                                          | NightPhase(waiting) · "预言家正在查验" · `—`                                                  | NightPhase(waiting) · "预言家正在查验" · `—`                                                                                           | NightPhase(waiting) · "预言家正在查验" · `—`                                                  |
| **NIGHT / SEER_RESULT**       | NightPhase(waiting) · `—`                                                                                                                                 | NightPhase(active) · Check result: target name + "是狼人" / "不是狼人" · `[SEER_CONFIRM]`     | NightPhase(waiting) · `—`                                                                                                                                      | NightPhase(waiting) · `—`                                                              | NightPhase(waiting) · `—`                                                                                                       | NightPhase(waiting) · `—`                                                              |
| **NIGHT / WITCH_ACT**         | NightPhase(waiting) · `—`                                                                                                                                 | NightPhase(waiting) · `—`                                                              | NightPhase(active) · Wolf target name shown; antidote available? `[WITCH_ACT(antidote)]`; poison available? `[WITCH_ACT(poison, target)]`; `[WITCH_ACT(skip)]` | NightPhase(waiting) · `—`                                                              | NightPhase(waiting) · `—`                                                                                                       | NightPhase(waiting) · `—`                                                              |
| **NIGHT / GUARD_PICK**        | NightPhase(waiting) · `—`                                                                                                                                 | NightPhase(waiting) · `—`                                                              | NightPhase(waiting) · `—`                                                                                                                                      | NightPhase(waiting) · `—`                                                              | NightPhase(active) · All alive player list (except last-protected player greyed out) · `[GUARD_PROTECT(target)]` `[GUARD_SKIP]` | NightPhase(waiting) · `—`                                                              |
| **DAY / RESULT_HIDDEN**       | DayPhase · "等待主持人公布结果" · `—`                                                                                                                              | same                                                                                   | same                                                                                                                                                           | same                                                                                   | same                                                                                                                            | same                                                                                   |
| **DAY / RESULT_REVEALED**     | DayPhase · Deaths list (or "平安夜") shown · `—`                                                                                                             | same                                                                                   | same                                                                                                                                                           | same                                                                                   | same                                                                                                                            | same                                                                                   |
| **VOTING / VOTING**           | VotingPhase(active) · Alive player list · `[SUBMIT_VOTE(target)]`                                                                                         | same (if alive)                                                                        | same (if alive)                                                                                                                                                | same (if alive)                                                                        | same (if alive)                                                                                                                 | same (if alive)                                                                        |
| **VOTING / VOTE_RESULT**      | VotingPhase · Tally table, eliminated player (or tie message) · host: `[VOTING_REVEAL_TALLY]` then `[VOTING_CONTINUE]`; others: `—`                       | same                                                                                   | same                                                                                                                                                           | same                                                                                   | same                                                                                                                            | same                                                                                   |
| **VOTING / HUNTER_SHOOT**     | VotingPhase(waiting) · "猎人正在选择目标" · `—`                                                                                                                   | VotingPhase(waiting) · same · `—`                                                      | VotingPhase(waiting) · same · `—`                                                                                                                              | VotingPhase(active) · Alive player list · `[HUNTER_SHOOT(target)]` `[HUNTER_SKIP]`     | VotingPhase(waiting) · same · `—`                                                                                               | VotingPhase(waiting) · same · `—`                                                      |
| **VOTING / BADGE_HANDOVER**   | VotingPhase · Badge handover UI (if eliminated player was sheriff) · eliminated sheriff: `[BADGE_PASS(target)]` `[BADGE_DESTROY]`; others: `—`            | same                                                                                   | same                                                                                                                                                           | same                                                                                   | same                                                                                                                            | same                                                                                   |
| **GAME_OVER**                 | ResultView · Winner banner, full role reveal table · `—`                                                                                                  | same                                                                                   | same                                                                                                                                                           | same                                                                                   | same                                                                                                                            | same                                                                                   |

> **Notes:**
> - SHERIFF_ELECTION rows apply only when `hasSheriff=true`
> - NIGHT/GUARD_PICK row applies only when `hasGuard=true`
> - NIGHT/SEER_PICK and SEER_RESULT rows apply only when `hasSeer=true`
> - NIGHT/WITCH_ACT row applies only when `hasWitch=true`
> - VOTING/HUNTER_SHOOT row appears only when the eliminated player is a HUNTER
> - VOTING/BADGE_HANDOVER row appears only when the eliminated player held the Sheriff badge

---

## Screen Naming Convention

The following shorthand screen names are used consistently in all scenario files.

| Short Name            | Full Description                                          | Vue Component                      |
|-----------------------|-----------------------------------------------------------|------------------------------------|
| `RoleReveal`          | Role reveal card shown at game start                      | `RoleRevealCard`                   |
| `SheriffElection`     | Sheriff election flow (signup / speech / voting / result) | `SheriffElection`                  |
| `NightPhase(active)`  | Night phase — player's role is currently acting           | `NightPhase`                       |
| `NightPhase(waiting)` | Night phase — player waits for another role to act        | `NightPhase`                       |
| `DayPhase`            | Day phase — result announcement                           | `DayPhase`                         |
| `VotingPhase`         | Voting phase — player selection and tally reveal          | `VotingPhase`                      |
| `ResultView`          | Game over screen with winner and full role table          | `ResultView`                       |
| `Eliminated`          | Spectator view for eliminated players                     | *(overlay on any phase component)* |

### Background conventions

| Screen state | Background token | Hex       |
|--------------|------------------|-----------|
| Day phases   | `--bg`           | `#ede8df` |
| Night phases | `--ink`          | `#2a1f14` |
| Role reveal  | `--ink`          | `#2a1f14` |
| Game over    | `--bg`           | `#ede8df` |

---

## Cross-File References

- Full per-role screen descriptions: [ROLE-PHASE-SCREENS.md](ROLE-PHASE-SCREENS.md)
- Canonical 6-player game (most detailed): [scenario-01](scenario-01-standard-six-wolves-win.md)
- Win condition reference: see individual scenario "Win Check" sections
- Gating / rejection tests: scenario-03 (role check), scenario-07 (guard repeat), scenario-05 (hunter self-shoot)
