# 狼美人 (WOLF_BEAUTY) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the 狼美人 role (a pack wolf that charms one player each night from night 2; when it goes out, the charmed player 殉情) to rooms, the night, every day exit, state, review, tasks, and the UI.

**Architecture:** A new night handler (`WolfBeautyHandler`, `@Order(2)`) stores the charm on `night_phases`. One rule function, `RoleRules.charmVictim`, says who 殉情. A small service, `CharmDeathService`, applies a day 殉情 and runs the W15 "wipe-only" check. Every day exit calls it, except the duel and the declare-out. Night 殉情 goes through `NightOrchestrator.computeKills`. The template for every touchpoint is the gargoyle PR `3bbf4f8` (#203).

**Tech Stack:** Kotlin / Spring Boot 4 / JPA / Flyway (backend), Vue 3 + TypeScript + Pinia + vue-i18n (frontend), JUnit + MockK, Vitest, Playwright (real backend).

**Spec:** `docs/superpowers/specs/2026-11-18-wolf-beauty-demon-hunter-design.md`. Read the sections "狼美人 — rules" (W1–W15), "Role texts", "Night order", the round 2–4 decisions, and "This PR — 狼美人". Rule ids (W*, X*, Q*) below refer to it. 猎魔人 is **out of scope**.

## Global Constraints

- **Migration:** `V38__wolf_beauty.sql`. Never edit an existing V*.sql. Re-create the role CHECKs with the **complete** role set, plus `WOLF_BEAUTY` (V37 pattern).
- **No `!!`** in `backend/src/main`. Use `?: error("…")`.
- **Tunables:** new timing values follow the existing per-role constants in `RoleDelayConfig.kt` (gargoyle pattern). Use no `@Value` fallback defaults.
- **Events:** every new `DomainEvent` subclass needs `@JsonTypeName("<ClassName>")`.
- **Frontend has no game logic:** every rule is enforced on the server. The UI only greys things out or hides them, and the server rejects anyway.
- **E2E:** `getByTestId` only. No fixed sleeps (`waitForTimeout` and `setTimeout` are banned; CI lint enforces it). The new spec goes into a CI shard in `.github/workflows/ci.yml`.
- **Type-check** with `vue-tsc -b`, not `--noEmit`.
- **Role texts** (owner rule): every rule goes in `role.rules.WOLF_BEAUTY` (one per line). `desc` has at most 2 short sentences; `descShort` is one line. Use the exact zh text in spec § "Role texts", traditional characters; rule line 6 **without** 「被獵魔人狩獵」.
- **Existing roles:** change only the `rules` lines in spec § "Existing roles" marked for the 狼美人 PR: 狼人, 獵人, 守墓人. Never change a `desc` or `descShort`.
- **Narration:** commit `wolf_beauty_open_eyes.mp3` and `wolf_beauty_close_eyes.mp3` as 0-byte placeholders. The owner generates the real files (ttsmaker.com, voice 1506-AI 阿乐, then `loudnorm`; script 狼美人请睁眼 / 狼美人请闭眼).
- **Never** query or connect to the production DB, and never deploy.
- **Commits** end with the attribution line the session provides. One commit per task.

## Review Focus

1. **A 殉情 leaking the charm before it happens.** The charm target must not appear in any non-狼美人 state, the pack's state, `WolfSelectionChanged`, or the public log. Task 3 adds a state test: a pack wolf's and the charmed player's `nightPhase` have no `wolfBeauty` key.
2. **The W15 order inverted** (the villagers win where the wolves should). Task 5 and Task 6 each pin "last-wolf 狼美人 + last god" → `WEREWOLF` on their own exit.
3. **A sheriff victim stranding the day.** A dead sheriff never handed over leaves the day stuck. Task 6 pins `BADGE_HANDOVER` for a sheriff victim on X2, X3, X5 and X6. On X4 the victim cannot receive the badge.
4. **A stale context counting the victim alive.** `context.alivePlayers` is loaded before a death. Task 6 tests the X4 `projectedWinner` with the victim excluded explicitly.
5. **Night order shift breaking an existing board.** `@Order` 2…7 → 3…8. Task 3 asserts the full `nightSequence` for a board with every night role, and the existing order tests stay green.

---

### Task 1: Role, room flag, migration, dealing and composition (backend)

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/model/Enums.kt`
  - `PlayerRole`: add `WOLF_BEAUTY(isWolf = true)` (it stays a pack wolf, so `isPackWolf` is unchanged).
  - `NightSubPhase`: add `WOLF_BEAUTY_PICK` right after `WEREWOLF_PICK`.
  - `ActionType`: add `WOLF_BEAUTY_CHARM, WOLF_BEAUTY_SKIP` with a comment.
- Modify: `model/Room.kt` (`hasWolfBeauty`, column `has_wolf_beauty`).
- Modify: `model/NightPhase.kt` (`wolfBeautyCharmUserId`, column `wolf_beauty_charm_user_id`).
- Modify: `model/Game.kt` (`charmDeathUserId`, column `charm_death_user_id`; the day 殉情 banner).
- Create: `backend/src/main/resources/db/migration/V38__wolf_beauty.sql`. It adds:
  - the three columns above;
  - the `game_players`, `elimination_history.eliminated_role` and `elimination_history.hunter_shot_role` CHECKs, re-created with the full list + `'WOLF_BEAUTY'`;
  - `night_phases_sub_phase_check` with `'WOLF_BEAUTY_PICK'` after `'WEREWOLF_PICK'`.
- Modify: `game/RoleDealer.kt` (deal `WOLF_BEAUTY` when `room.hasWolfBeauty`, among the special wolves).
- Modify: `service/RoomService.kt`:
  - the create mapping `:76`;
  - the update `:248`;
  - the role list `:386-400`;
  - the specials list `:505-508`, with the name `"Wolf Beauty"`;
  - the timing map `:539`.
- Modify: `model/RoleDelayConfig.kt`: `WOLF_BEAUTY_DEFAULT = GUARD_DEFAULT` (one pick step, like the guard), plus both maps.
- Create: `audio/impl/WolfBeautyAudioConfig.kt` (`wolf_beauty_open_eyes.mp3` / `wolf_beauty_close_eyes.mp3`, `defaultDelayMs = 5000L`), and register it in `audio/RoleAudioConfiguration.kt`.
- Create: `backend/src/main/resources/static/audio/wolf_beauty_open_eyes.mp3`, `wolf_beauty_close_eyes.mp3` (0 bytes).
- Test files:
  - `integration/FlywayMigrationsIntegrationTest.kt`;
  - `unit/model/PlayerRoleTest.kt`;
  - `unit/game/RoleDealerTest.kt`;
  - `unit/service/RoomServiceTest.kt`;
  - `integration/JsonWireFormatTest.kt`.

**Interfaces:**
- Produces:
  - `PlayerRole.WOLF_BEAUTY`, `NightSubPhase.WOLF_BEAUTY_PICK`;
  - `ActionType.WOLF_BEAUTY_CHARM` / `WOLF_BEAUTY_SKIP`;
  - `Room.hasWolfBeauty: Boolean`;
  - `NightPhase.wolfBeautyCharmUserId: String?`;
  - `Game.charmDeathUserId: String?`.

- [ ] **Step 1: Write the failing tests.**
  - `PlayerRoleTest`: `WOLF_BEAUTY.isWolf`, `isPackWolf`, `!isGod`, `!shootsOnDeath`, `learnedSkill == NONE`.
  - `RoleDealerTest`: a room with `hasWolfBeauty` and `wolfCount = 3` deals exactly one `WOLF_BEAUTY` and two `WEREWOLF`.
  - `RoomServiceTest`:
    - `[WOLF_BEAUTY]` with `wolfCount = 1` throws `InvalidRoleCompositionException` with a message containing "Wolf Beauty";
    - with `wolfCount = 2` it is accepted;
    - `[WOLF_BEAUTY, WHITE_WOLF_KING]` needs 3 wolves;
    - create/update set `hasWolfBeauty`.
  - Flyway: the three columns exist, and inserting `role = 'WOLF_BEAUTY'` and `sub_phase = 'WOLF_BEAUTY_PICK'` succeeds.
- [ ] **Step 2: Run them and see them fail.** `cd backend && ./gradlew test --tests '*PlayerRoleTest' --tests '*RoleDealerTest' --tests '*RoomServiceTest' --tests '*FlywayMigrationsIntegrationTest'` → compile errors / FAIL.
- [ ] **Step 3: Implement the files above, following the gargoyle diff** (`git show 3bbf4f8 -- <file>`) for each one.
- [ ] **Step 4: Run them again.** Same command → PASS. Then run `./gradlew test` → no new failures.
- [ ] **Step 5: Commit.** `feat(backend): 狼美人 role, room flag and V38 migration`

### Task 2: Pack knowledge, knife and self-destruct bans (W8, G1)

**Files:**
- Modify: `game/role/WerewolfHandler.kt:79-83`. A `WOLF_KILL` / `WOLF_SELECT` whose target's role is `WOLF_BEAUTY` → `Rejected("The Wolf Beauty cannot be knifed")`.
- Modify: `service/SelfDestructService.kt:57`. `actor.role == WOLF_BEAUTY` → `Rejected("The Wolf Beauty cannot self-destruct")`.
- Modify: `service/GameService.kt`, the pack teammates block `:183-188`.
  - For a pack wolf, add `base["wolfBeautyUserId"] = <the WOLF_BEAUTY player's userId, or null>`. Do the same in `roleReveal` (`:150-164`).
  - **Only pack wolves** get the key (decision G1 below).
- Tests: `unit/role/WerewolfHandlerPackTest.kt`, `unit/service/SelfDestructServiceTest.kt`, `unit/service/GameServiceNightPhaseTest.kt`.

**Interfaces:**
- Produces: the state key `nightPhase.wolfBeautyUserId: string | null` and `roleReveal.wolfBeautyUserId`, present only for pack wolves.

- [ ] **Step 1: Write the failing tests.**
  - The pack's `WOLF_KILL` on 狼美人 → `Rejected`; its `WOLF_KILL` on another pack wolf → `Success` (unchanged).
  - 狼美人's `WOLF_SELF_DESTRUCT` → `Rejected`, and nothing is saved.
  - A pack wolf's state has `wolfBeautyUserId == beauty`. The seer's, the gargoyle's and the mechanical wolf's states have no such key.
- [ ] **Step 2: Run them and see them fail.** `./gradlew test --tests '*WerewolfHandlerPackTest' --tests '*SelfDestructServiceTest' --tests '*GameServiceNightPhaseTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them again → PASS.**
- [ ] **Step 5: Commit.** `feat(backend): wolves cannot knife 狼美人 and it cannot self-destruct`

### Task 3: The night turn (W2–W4, Q3, Q5) and night order

**Files:**
- Create: `game/role/WolfBeautyHandler.kt`, `@Order(2)`, modelled on `GargoyleHandler`.
  - `nightSubPhases() = listOf(WOLF_BEAUTY_PICK)`.
  - It accepts `WOLF_BEAUTY_CHARM`, `WOLF_BEAUTY_SKIP` in `WOLF_BEAUTY_PICK`.
- Modify `@Order`: `MechanicalWolfHandler` 2→3, `WitchHandler` 3→4, `SeerHandler` 4→5, `MediumHandler` 5→6, `GravekeeperHandler` 6→7, `GuardHandler` 7→8.
- Modify: `game/night/NightOrchestrator.kt` `activeNightRoles`: `if (room.hasWolfBeauty) add(PlayerRole.WOLF_BEAUTY)` right after `WEREWOLF`. Also add `wolfBeautyCharm={}` to `logNightPhaseFields`.
- Modify: `game/action/GameActionDispatcher.kt`:
  - `WOLF_BEAUTY_CHARM, WOLF_BEAUTY_SKIP -> nightTurn(request, context, PlayerRole.WOLF_BEAUTY)` (no private push: the client refetches state);
  - both map to `WOLF_BEAUTY_PICK` in `TURN_SUB_PHASE`.
- Modify: `service/AudioService.kt` `mapSubPhaseToRole`: `WOLF_BEAUTY_PICK -> WOLF_BEAUTY`.
- Modify: `service/GameStateLogger.kt`: the actor for `WOLF_BEAUTY_PICK` is the living `WOLF_BEAUTY`.
- Modify: `service/GameService.kt`. Private state for `myPlayer.role == WOLF_BEAUTY` only:
  `nightPhase.wolfBeauty = { canCharm: Boolean, lastNightTargetUserId: String?, result: null | { kind: "CHARM", targetPlayerId, targetNickname, targetSeatIndex } | { kind: "SKIP" }, history: [{ round, nickname, seatIndex }] }`.
  - `canCharm = np.dayNumber >= 2`.
  - `lastNightTargetUserId` = the charm of night `dayNumber - 1`.
- Tests:
  - Create: `unit/role/WolfBeautyHandlerTest.kt`.
  - Modify: `unit/service/NightOrchestratorTest.kt`, `unit/service/GameActionDispatcherTest.kt`, `unit/service/AudioPhaseMappingTest.kt`, `unit/service/GameServiceNightPhaseTest.kt`, `unit/role/RoleHandlerTest.kt`.

**Handler rules** (reject with these messages):
- not `WOLF_BEAUTY` → "Not the Wolf Beauty";
- dead actor → "Actor is dead";
- wrong sub-phase → "Not in WOLF_BEAUTY_PICK sub-phase";
- `CHARM` when `nightPhase.dayNumber == 1` → "No charm on night 1" (`SKIP` is accepted on night 1: Q3);
- no target → "Target required";
- self → "Cannot charm yourself";
- a dead target → "Target not found or dead";
- a target with `role.isPackWolf` → "Cannot charm a wolf teammate";
- a target equal to night `dayNumber - 1`'s `wolfBeautyCharmUserId` → "Cannot charm the same player two nights in a row".

Accepted:
- a 石像鬼 / 机械狼 target (D);
- tonight's knife target (Q5).

`CHARM` saves `wolfBeautyCharmUserId`; `SKIP` saves nothing.

**Interfaces:**
- Consumes: Task 1 enums and columns.
- Produces: `NightPhase.wolfBeautyCharmUserId`, set only by an accepted `WOLF_BEAUTY_CHARM`.

- [ ] **Step 1: Write the failing tests.**
  - `WolfBeautyHandlerTest`: one test per rule above; the accepted cases save the column; `SKIP` leaves it null.
  - `NightOrchestratorTest`: for a room with gargoyle, 狼美人, seer, witch, gravekeeper and guard, `nightSequence` == `[GARGOYLE_PICK, GARGOYLE_RESULT, WEREWOLF_PICK, WOLF_BEAUTY_PICK, WITCH_ACT, SEER_PICK, SEER_RESULT, GRAVEKEEPER_RESULT, GUARD_PICK]`.
  - `GameServiceNightPhaseTest`:
    - 狼美人's state on night 1 has `canCharm == false`;
    - on night 3 it has `lastNightTargetUserId` = night 2's target;
    - after a skip in `WOLF_BEAUTY_PICK` with no charm, `result == null`; after a charm, `result.kind == "CHARM"`;
    - **(Review Focus 1)** the pack wolf's, the charmed player's and the seer's states have no `wolfBeauty` key.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests above, then `./gradlew test` → PASS.** Existing order tests (e.g. `AudioPhaseMappingTest`'s WITCH_ACT → SEER_PICK) stay green (Review Focus 5).
- [ ] **Step 5: Commit.** `feat(backend): 狼美人 night charm, after the wolves`

### Task 4: The 殉情 rule and the night path (W5–W7, W9, W15, X1, Q2)

**Files:**
- Modify: `game/role/RoleRules.kt`. Add:
  ```kotlin
  /** The player who 殉情 when [outUserId] goes out now: today's night charm, if [outUserId] is the
   *  Wolf Beauty and the charmed player is still alive. Callers never pass a duel or a declare-out. */
  fun charmVictim(outUserId: String, players: List<GamePlayer>, todaysNight: NightPhase?): String?
  ```
  "Today's night" means `night_phases.day_number == game.dayNumber`.
- Modify: `game/night/NightOrchestrator.kt`.
  - `computeKills(nightPhase, immuneUserIds, players: List<GamePlayer>)`: the existing result, plus `charmVictim` when the `WOLF_BEAUTY` is in it. Neither the mechanical wolf's shield, the guard nor the antidote filters the victim (W9, M). The result stays sorted.
  - Every production caller passes the roster:
    - `NightOrchestrator.kt:345, 558, 579`;
    - the instance `computePendingKills(gameId, np)` loads the players itself;
    - `GameReviewBuilder.kt:53`, `PlayerReviewBuilder.kt:55`.
  - `resolveNightKills` (`:360-369`), the W15 order. When the list has a victim, first run `winConditionChecker.check(projectedAlive + beauty, mode, WinCheckTrigger.POST_NIGHT, …)`, where `projectedAlive` already has every death removed. A winner there is final. Otherwise run today's check.
  - The game-over branch also records the `CHARM_DEATH`.
- Modify: `game/phase/GamePhasePipeline.kt:65-68`. After `recordNightDeaths`, if the victim is in `pendingKills`, call `actionLogService.recordCharmDeath(gameId, day, beautyId, victimId)` (Q2).
- Modify: `game/phase/DayRevealAdvancer.kt:64-75`. `nightShooterId` returns null when the wolf target is tonight's 殉情 victim (W6).
- Modify: `service/ActionLogService.kt`. Add `fun recordCharmDeath(gameId: Int, dayNumber: Int, wolfBeautyUserId: String, victimUserId: String)`, event type `"CHARM_DEATH"`, payload `dayNumber, wolfBeautyUserId, victimUserId` (pattern: `recordHunterShot`).
- Tests:
  - Modify: `unit/role/RoleRulesTest.kt`, `unit/night/NightKillMatrixTest.kt`, `unit/service/NightOrchestratorTest.kt`, `unit/service/GamePhasePipelineDayTest.kt`.
  - Create: `unit/phase/NightShooterCharmTest.kt`. Optional: fold it into the existing `DayRevealAdvancer` test if one exists.
  - Update the other test callers of `computeKills` to the new signature.

**Interfaces:**
- Produces:
  - `RoleRules.charmVictim(outUserId, players, todaysNight): String?`;
  - `NightOrchestrator.computeKills(nightPhase, immuneUserIds, players)`;
  - `ActionLogService.recordCharmDeath(...)`;
  - the game event `"CHARM_DEATH"`.

- [ ] **Step 1: Write the failing tests.**
  - `charmVictim`:
    - victim when the out player is the 狼美人 with a charm today;
    - null for another out player;
    - null with no charm today;
    - null when only yesterday's night has a charm (W5);
    - null when the charmed player is dead.
  - `computeKills`:
    - witch-poisoned 狼美人 with charm X → `[beauty, X]`;
    - with X guarded, X saved by the antidote, or X under the mech shield → still contains X;
    - no charm → unchanged.
  - `resolveNightKills` (W15):
    - last-wolf 狼美人 poisoned, X is the last god → `WEREWOLF`;
    - same with X not the last god and the beauty the last wolf → `VILLAGER`;
    - HARD_MODE, 2 good players left, beauty poisoned, X one of them → `VILLAGER` (no shortcut: Q12).
  - Night shooter: a knifed hunter who is also the 殉情 victim → `nightShooterId == null`.
  - `GamePhasePipelineDayTest`: the reveal records `CHARM_DEATH` with the right ids.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the listed tests, then `./gradlew test` → PASS.**
- [ ] **Step 5: Commit.** `feat(backend): 殉情 at night — dawn list, W15 order, CHARM_DEATH`

### Task 5: Day 殉情 service

**Files:**
- Create: `service/CharmDeathService.kt` (`@Service`):
  ```kotlin
  data class CharmOutcome(val victimUserId: String, val winner: WinnerSide?)
  /** Apply the 殉情 for [outUserId] leaving by day: kill the victim, set game.charmDeathUserId,
   *  record CHARM_DEATH, queue PlayerCharmDeath, then run the wipe-only check (W15). null = no 殉情. */
  fun onDayExit(context: GameContext, outUserId: String): CharmOutcome?
  ```
  - Kill = `alive = false`, `diedDay = game.dayNumber`.
  - The wipe-only check = `winConditionChecker.check(aliveWithoutVictimButWithBeauty, room.winCondition, WinCheckTrigger.POST_NIGHT, counterplay, roster)`. Build the list from fresh repository rows, not `context.alivePlayers`.
- Modify: `game/DomainEvent.kt`: `@JsonTypeName("PlayerCharmDeath") data class PlayerCharmDeath(val gameId: Int, val wolfBeautyUserId: String, val victimUserId: String)`, broadcast after commit.
- Modify: `game/night/NightOrchestrator.kt:823-824`: clear `game.charmDeathUserId` at night-init.
- Test: Create `unit/service/CharmDeathServiceTest.kt`.

**Interfaces:**
- Consumes: `RoleRules.charmVictim`, `ActionLogService.recordCharmDeath`.
- Produces: `CharmDeathService.onDayExit(context, outUserId): CharmOutcome?`, and `DomainEvent.PlayerCharmDeath`.

- [ ] **Step 1: Write the failing tests.**
  - no 殉情 → null, and nothing saved;
  - 殉情 → victim saved dead with `diedDay`, `game.charmDeathUserId = victim`, `recordCharmDeath` called, `PlayerCharmDeath` broadcast after commit;
  - last-wolf beauty + victim = last god → `winner == WEREWOLF` (Review Focus 2);
  - HARD_MODE 1 v 1 after the 殉情 → `winner == null` (Q12).
- [ ] **Step 2: Run and see them fail.** **Step 3: Implement.** **Step 4: Run → PASS.**
- [ ] **Step 5: Commit.** `feat(backend): CharmDeathService for day 殉情`

### Task 6: Wire every day exit (X2–X6, Q10, G)

**Files:**
- Modify: `game/voting/VotingPipeline.kt`. At each site, call `charmDeathService.onDayExit(context, target)` at the point below. If `winner != null`, `endGame(…)` and return.
  - **X2** `collectEliminationEvents`:
    - call it right after `:599`;
    - the badge route `:634` becomes `targetId == sheriff || victim == sheriff`.
  - **X3** `votingShot`, non-deferred:
    - call it right after `:284-286`;
    - `afterHunterAct` already routes a dead sheriff.
  - **X4** `votingShot`, sheriff target (Q10):
    - call it **before** the defer decision at `:272`, when the target is the 狼美人;
    - `projectedWinner` gains a parameter, `projectedWinner(context, deadUserIds: Set<String>)`, and gets `{target, victim}` (Review Focus 4).
  - **X5** `nightDeathShot`: call it right after `:488-490`.
- Modify: `service/SelfDestructService.kt` **X6**:
  - when the taken player is the 狼美人, call it after `:115-119`;
  - exclude the victim from the win check `:169-171`;
  - the routing `:144` becomes "taken **or victim** is the sheriff".
- Do **not** touch `KnightDuelService` or `HostDeclareOutService` (X7, X8).
- Tests: `unit/service/VotingPipeline*Test.kt` (pick the existing file per path), `unit/service/SelfDestructServiceTest.kt`, and Create `integration/WolfBeautyDayIntegrationTest.kt`.

**Interfaces:**
- Consumes: `CharmDeathService.onDayExit`.

- [ ] **Step 1: Write the failing tests.**
  - X2:
    - 狼美人 voted out → victim dead, no `elimination_history` row for the victim;
    - victim is the sheriff → sub-phase `BADGE_HANDOVER`;
    - last wolf + last god → `GAME_OVER WEREWOLF`.
  - X3: a hunter shoots the 狼美人 → victim dead; victim is the sheriff → `BADGE_HANDOVER`.
  - X4: the 狼美人 is the sheriff and shot → victim dead **before** `BADGE_HANDOVER`; `BADGE_PASS` to the victim → `Rejected`; victim's death wins → `GAME_OVER` with no handover.
  - X5: same as X3 in `DAY_DISCUSSION`.
  - X6: 白狼王 takes the 狼美人 → victim dead; victim is the sheriff → `BADGE_HANDOVER`.
  - X7 / X8: a duel or a declare-out on the 狼美人 → no victim.
  - Integration: one full game in which 狼美人 charms on N2 and is voted out on D2. Assert the victim dead, `charmDeathUserId`, `CHARM_DEATH` in `game_events`, and the win check.
- [ ] **Step 2: Run and see them fail.** **Step 3: Implement.** **Step 4: Run them, then `./gradlew test` → PASS.**
- [ ] **Step 5: Commit.** `feat(backend): 殉情 on vote-out, shots and the 白狼王 take`

### Task 7: State banner, review, role history, task (B, Q13, Q14, W14, F)

**Files:**
- Modify: `service/GameService.kt`. Add `"charmDeath"` to `dayPhase` (`:401` map) and to `votingPhase` (`:476` map):
  - `game.charmDeathUserId?.let { { beautySeatIndex, beautyNickname, victimSeatIndex, victimNickname } }`;
  - the beauty is the `WOLF_BEAUTY` player.
- Modify: `dto/GameReviewDtos.kt`:
  - `DeathCause` gets `CHARM`;
  - `ReviewNight` gets `wolfBeauty: WolfBeautyTurn? = null`, with `data class WolfBeautyTurn(val actor: ReviewPlayer, val dead: Boolean, val target: ReviewPlayer?)`;
  - `ReviewDayEvent` gets `@JsonTypeName("CHARM_DEATH") data class CharmDeath(val beauty: ReviewPlayer, val victim: ReviewPlayer)`.
- Modify: `service/GameReviewBuilder.kt`:
  - `"CHARM_DEATH"` goes into `DAY_EVENT_TYPES` (`:22`), but only events whose day had no night death of that victim. A night 殉情 shows as `NightDeath(victim, CHARM)` in that dawn.
  - the night turn.
- Modify: `dto/PlayerReviewDtos.kt` + `service/PlayerReviewBuilder.kt`: `@JsonTypeName("WOLF_BEAUTY") data class WolfBeauty(val teammates: List<PlayerSeat>, val target: PlayerSeat?, val selfKill: Boolean, val charm: PlayerSeat?) : MyNightAction`.
- Modify: `service/RoleHistoryBuilder.kt`: `WOLF_BEAUTY` → the pack's kills plus `entry(day, "CHARM", charmId)`.
- Modify: `model/GameTask.kt`: `WOLF_BEAUTY_LINK_GOD(20)`.
- Modify: `service/GameTasks.kt`:
  - eligibility: `WOLF_BEAUTY` → `WOLF_BEAUTY_LINK_GOD`;
  - `Facts.charmDeaths: Map<String, String>` (beauty → victim);
  - `isDone` = `isGod(f.charmDeaths[me])`.
- Modify: `service/RewardSettlementService.kt` `taskFacts`: read `CHARM_DEATH` payloads.
- Tests: `unit/service/GameServiceDayPhaseTest.kt`, voting state test, `GameReviewBuilderTest`, `PlayerReviewBuilderTest`, `RoleHistoryBuilderTest`, `GameTasksTest` (+ the guard table), `integration/GameTasksIntegrationTest.kt`.

- [ ] **Step 1: Write the failing tests.**
  - `charmDeath` present after a day 殉情; absent when 狼美人 is voted out with no charm (Q14); cleared on the next night.
  - The review:
    - a night 殉情 → dawn death `CHARM`, no day event;
    - a day 殉情 → `CharmDeath` day event;
    - the night turn shows the charm target.
  - The task:
    - victim is a god → done, +20;
    - victim is a villager → not done;
    - a night 殉情 counts (Q2).
- [ ] **Step 2: Run and see them fail.** **Step 3: Implement.** **Step 4: Run them, then `./gradlew test` → PASS.**
- [ ] **Step 5: Commit.** `feat(backend): 殉情 banner, review, role history and the 狼美人 task`

### Task 8: Frontend role, texts, night turn

**Files:**
- Modify: `frontend/src/types/index.ts`. Add:
  - `'WOLF_BEAUTY'` to the role union;
  - `'WOLF_BEAUTY_PICK'` to the night sub-phases;
  - `WOLF_BEAUTY_CHARM` / `WOLF_BEAUTY_SKIP` to the actions;
  - the `nightPhase.wolfBeauty` shape (Task 3), `nightPhase.wolfBeautyUserId`, `roleReveal.wolfBeautyUserId`;
  - `charmDeath` on the day and voting states;
  - the review DTOs (Task 7);
  - `'WOLF_BEAUTY_LINK_GOD'` in `TaskCode`.
- Modify: `utils/roleDefinitions.ts`: `{ id: 'WOLF_BEAUTY', emoji: '🌹', required: false, wolf: true }`. Keep `isPackWolfRole` unchanged; no `BLOCKED_WITH` entry.
- Modify: `i18n/locales/zh.ts`, `en.ts`:
  - `role.WOLF_BEAUTY`, `role.desc`, `role.descShort`, `role.rules`, with the exact spec text (rule 6 without 獵魔人);
  - the three existing `rules` lines (狼人, 獵人, 守墓人);
  - turn texts (pick title, 「首夜不能魅惑」 + 確認, skip, the greyed reasons);
  - `day.charmDeath` = 「{beauty}（狼美人）出局，{victim}殉情出局」 and its en version;
  - `task.name.WOLF_BEAUTY_LINK_GOD` = 殉情帶走一名神職 / "Take a god with you through the charm".
- Create: `components/WolfBeautyTurn.vue`, modelled on `GargoyleTurn.vue`:
  - pick + skip;
  - greyed: self, pack wolves (`wolfBeautyUserId` is not needed here: it knows its pack from `teammates`; grey every player the server would reject: self, dead, `lastNightTargetUserId`, and the pack by seat);
  - night 1: only the 確認 button, which sends `WOLF_BEAUTY_SKIP`.
  - testids: `wolf-beauty-turn`, `wolf-beauty-target-<seat>`, `wolf-beauty-confirm`, `wolf-beauty-skip`, `wolf-beauty-n1-confirm`.
- Modify: `components/NightPhase.vue` (mount it for `WOLF_BEAUTY_PICK`), `views/GameView.vue`.
- Modify the narration mapping: `services/audioService.ts`, `composables/useAudioService.ts`, `utils/nightNarration.ts`.
- Modify: `RoleRevealCard.vue`, `mocks/data.ts`, `views/DemoView.vue`.
- Tests:
  - Create: `__tests__/wolfBeautyTurn.test.ts`.
  - Modify: `roleDefinitions.test.ts`, `nightPhase.test.ts`, `nightNarration.test.ts`, `roleCardSheet.test.ts`, `RoomSetupPanelRoles.test.ts`, `taskLine.test.ts` (it fails on a missing `TaskCode`, which is good).

- [ ] **Step 1: Write the failing tests.**
  - The turn renders a pick list with the right players greyed.
  - Night 1 shows only the confirm, which sends `WOLF_BEAUTY_SKIP`.
  - The role page lists 狼美人 as a special wolf (the 9+ players rule).
  - The rules sheet shows 10 lines.
- [ ] **Step 2: Run and see them fail.** `cd frontend && npx vitest run <files>`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `npx vitest run`, `npx vue-tsc -b`, `npm run lint` → PASS, 0 errors.**
- [ ] **Step 5: Commit.** `feat(frontend): 狼美人 role, texts and night turn`

### Task 9: Frontend day banner, kill picker, 自爆 button, review

**Files:**
- Modify: `components/DayPhase.vue`, `components/VotingPhase.vue`: a `charmDeath` banner with testid `charm-death-banner`, text `day.charmDeath`.
- Modify: `components/NightPhase.vue` WEREWOLF_PICK: grey the player whose `userId === nightPhase.wolfBeautyUserId` (pattern: `aria-disabled`). Extend `utils/nightPhaseHelpers.ts` `isWolfTarget(p, myUserId, wolfBeautyUserId?)`.
- Modify: `components/ActionMenu.vue:292-294`: `canSelfDestruct` is false when `myRole === 'WOLF_BEAUTY'`.
- Modify: `components/GameReview.vue`, `PlayerReview.vue`, `RoleHistory.vue`: the 狼美人 night turn, `CHARM` death cause, `CHARM_DEATH` day event, `CHARM` history entries.
- Tests: `dayPhase` / `votingPhase` banner tests, `nightPhase.test.ts` (greyed 狼美人), `actionMenu.test.ts`, `gameReview.test.ts`, `playerReview.test.ts`, `roleHistory.test.ts`.

- [ ] **Step 1: Write the failing tests.** **Step 2: Run and see them fail.** **Step 3: Implement.**
- [ ] **Step 4: Run `npx vitest run`, `npx vue-tsc -b`, `npm run lint` → PASS.**
- [ ] **Step 5: Commit.** `feat(frontend): 殉情 banner, greyed 狼美人 for the pack, review entries`

### Task 10: Scripts, docs, real-backend E2E, CI

**Files:**
- Modify: `scripts/act.sh` (`WOLF_BEAUTY_CHARM <target>`, `WOLF_BEAUTY_SKIP`), `scripts/roles.sh`.
- Modify: `docs/new-role.md` (status row), `docs/narration-scripts.md` (move 狼美人 into the active list), `docs/adr/audio.md` (night order if listed).
- Modify the E2E helpers:
  - `frontend/e2e/real/helpers/audio-manifest.ts`;
  - `role-leak.ts` (WOLF_BEAUTY is a role token);
  - `shell-runner.ts`;
  - `multi-browser.ts`;
  - `state-polling.ts` (if a sub-phase list exists).
- Create: `frontend/e2e/real/wolf-beauty-flow.spec.ts`. Read `.claude/skills/write-real-e2e-test` and `feedback_e2e_ci_vs_local` first.
  - The board: 9 seats; wolves = 2 狼人 + 狼美人; seer, witch, guard, hunter; 2 villagers. Include WITCH (the setupGame trap). No sheriff.
  - Force the host's role with the e2e `swap-roles` endpoint if needed.
  - Flow:
    - N1: the 狼美人 sees the night-1 confirm, and the knife avoids the hunter.
    - N2: the 狼美人 charms a villager.
    - D2: the table votes the 狼美人 out → `charm-death-banner` shows both seats, and the victim is dead in state.
  - Then:
    - no `REJECTED` lines in the backend log;
    - the role-leak scan passes;
    - the review shows the `CHARM_DEATH` event.
- Modify: `.github/workflows/ci.yml`: add the spec to a real-E2E shard. Check the shard's `--grep` with `--list` (whole-title match).

- [ ] **Step 1: Write the spec.**
- [ ] **Step 2: Run it twice locally.** `cd frontend && npx playwright test -c playwright.real.config.ts e2e/real/wolf-beauty-flow.spec.ts --trace off` → PASS twice. Do not run the mocked suite at the same time (port 5174).
- [ ] **Step 3: Run the regression set** that the order shift and the exit changes touch: `gargoyle-flow`, `gravekeeper-flow`, `self-destruct-flow`, `idiot-flow`, `game-flow-d1-outcomes` → PASS.
- [ ] **Step 4: Run the full backend suite, the frontend unit tests, lint and `vue-tsc -b` once more → PASS.**
- [ ] **Step 5: Commit.** `test(e2e): 狼美人 charm and 殉情 on vote-out (CI shard N)`

## Decision G1 (approved by the owner 2026-11-18)

- **G1 (found while planning).** Today the pack sees its teammates' seats and names, not their roles (`GameService.kt:183-188`). To grey the 狼美人 in the kill picker, the pack must be told which teammate it is. This plan assumes **yes**: pack wolves get `wolfBeautyUserId`. The alternative leaks it anyway: the server rejects the knife and the rejection tells them.
