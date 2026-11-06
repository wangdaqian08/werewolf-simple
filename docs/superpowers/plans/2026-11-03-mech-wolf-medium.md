# 机械狼 + 通灵师 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `MECHANICAL_WOLF` and `MEDIUM` roles to the game, following the confirmed rules in the spec, end to end: backend, frontend and tests.

**Architecture:**
- Each role is a new `RoleHandler` strategy, the existing per-role pattern. Night order comes from `@Order`.
- Rules that depend on a player and not only on a role sit in one pure domain object, `RoleRules`. These are: who shoots on death, what the medium sees, who holds the wolf kill, and which skill the mechanical wolf can use tonight. Every call site asks `RoleRules` and none re-derives these rules.
- A learned role maps to its skill through a table (`PlayerRole.learnedSkill`) instead of a class per skill.
- Kill resolution stays in the single static `NightOrchestrator.computeKills`.
- Private results reach their owner the way `SeerResult` does: an after-commit private push, after which the frontend refetches state.

**Tech Stack:** Kotlin, Spring Boot 4, JPA, Flyway, JUnit5, Mockito-Kotlin, AssertJ; Vue 3, TypeScript, Pinia, Vitest; Playwright with the real backend.

**Spec:** `docs/superpowers/specs/2026-11-03-mech-wolf-medium-design.md`. Read it first. Rule ids such as W5 and M3 refer to it.

## Global Constraints

- **Kotlin.** No `!!` in `src/main`; use `?: error("...")` instead. Every new `DomainEvent` subclass needs `@JsonTypeName("<ClassName>")`.
- **Migrations.**
  - New migration is `V30__mechanical_wolf_medium.sql`. Never edit V1–V29.
  - The SQL must run on both Postgres (prod, CI) and H2. Tests use `ddl-auto: create-drop`, so a test run never executes the migration; it gets its own verification step.
- **Role and enum names, verbatim:**
  - Roles: `MECHANICAL_WOLF`, `MEDIUM`.
  - Sub-phases: `MECH_WOLF_PICK`, `MECH_WOLF_RESULT`, `MEDIUM_PICK`, `MEDIUM_RESULT`.
  - Actions: `MECH_LEARN`, `MECH_CHECK`, `MECH_POISON`, `MECH_PROTECT`, `MECH_SKIP`, `MECH_CONFIRM`, `MEDIUM_CHECK`, `MEDIUM_CONFIRM`.
  - Before writing any of these literals, run the `verify-enum-values` skill.
- **Audio files, verbatim:** `mech_wolf_open_eyes.mp3`, `mech_wolf_close_eyes.mp3`, `medium_open_eyes.mp3`, `medium_close_eyes.mp3`. They are silent placeholders; the owner replaces them in place later.
- **Night order:** WEREWOLF → MECHANICAL_WOLF → WITCH → SEER or MEDIUM → GUARD. That gives handler `@Order` values Werewolf 1, MechanicalWolf 2, Witch 3, Seer 4, Medium 5, Guard 6. Villager 10, Hunter 11 and Idiot 12 stay as they are.
- **i18n.** `zh.ts` is Traditional Chinese. `en.ts` and `zh.ts` keep the same keys, and the existing `i18nParity.test.ts` must stay green.
- **E2E.** Locate elements with `getByTestId` only, never `getByText`. Every new real-backend spec must be listed in a `.github/workflows/ci.yml` shard. The `roles` list must include `WITCH` (the setupGame trap).
- **Old rooms are out of scope.** Add no fallback for stored configs that lack the new roles.
- **Commits.** End each commit message with the `Co-Authored-By` attribution line the session provides.

## Review Focus

These are the failure modes a player would hit that no single task's happy path covers. Each one has its test in the task named.

1. **The mechanical wolf learns a player who dies that same night.** It still keeps the learned role. The learned role is the target's role at the moment of learning. Test: Task 5, `learn keeps the role after the target dies`.
2. **The medium checks the mechanical wolf on the night it learns.** The medium must see the learned role, because the mechanical wolf's turn comes first. Test: Task 4, `medium sees the role learned earlier the same night`.
3. **The only remaining pack wolf self-destructs by day.** That same night the mechanical wolf must get the kill: `WEREWOLF_PICK` waits for it and accepts its `WOLF_KILL`. Test: Task 2, `lone mechanical wolf kills once the pack is gone`.
4. **A double tap on a mechanical-wolf action** (learn, then learn again in the same turn). The second tap must be rejected by `NightTurnLedger`, and only one learning must be recorded. Test: Task 5, `second MECH_LEARN in the same turn is rejected`.
5. **The mechanical wolf protects the player whom the wolves kill and the witch also saves.** The player lives: the mechanical guard is absolute and there is no 奶穿 with it. Test: Task 3, `mech guard plus antidote on the wolf target - target lives`.

---

### Task 1: Domain model, storage and `RoleRules`

**Files:**
- Modify `backend/src/main/kotlin/com/werewolf/model/Enums.kt`:
  - add to `PlayerRole`, `NightSubPhase` and `ActionType`;
  - add a new `LearnedSkill` enum.
- Modify `model/GamePlayer.kt`: add `learnedRole`.
- Modify `model/NightPhase.kt`: add the seven new columns.
- Modify `model/Room.kt`: add `hasMechanicalWolf` and `hasMedium`.
- Create `backend/src/main/resources/db/migration/V30__mechanical_wolf_medium.sql`.
- Create `backend/src/main/kotlin/com/werewolf/game/role/RoleRules.kt`.
- Test, modify: `backend/src/test/kotlin/com/werewolf/unit/model/PlayerRoleTest.kt`.
- Test, create: `backend/src/test/kotlin/com/werewolf/unit/role/RoleRulesTest.kt`.

**Interfaces (Produces):**

```kotlin
enum class PlayerRole(val isWolf: Boolean = false) {
    WEREWOLF(true), VILLAGER, SEER, WITCH, HUNTER, GUARD, IDIOT,
    WHITE_WOLF_KING(true), BLACK_WOLF_KING(true), MECHANICAL_WOLF(true), MEDIUM;
    val isGod: Boolean            // unchanged rule → MEDIUM is a god, MECHANICAL_WOLF is not
    val shootsOnDeath: Boolean    // unchanged: HUNTER, BLACK_WOLF_KING (role-level only)
    val isPackWolf: Boolean get() = isWolf && this != MECHANICAL_WOLF
    val learnedSkill: LearnedSkill // MEDIUM→CHECK_ROLE, SEER→CHECK_ALIGNMENT, WITCH→POISON, GUARD→PROTECT,
                                   // HUNTER/BLACK_WOLF_KING→SHOOT, everything else→NONE
}
enum class LearnedSkill { NONE, CHECK_ROLE, CHECK_ALIGNMENT, POISON, PROTECT, SHOOT }

// GamePlayer
@Enumerated(EnumType.STRING) @Column(name = "learned_role", length = 20) var learnedRole: PlayerRole? = null

// NightPhase (all nullable, VARCHAR(128) for user ids, VARCHAR(20) STRING enums for roles)
var mechLearnTargetUserId: String?; var mechCheckTargetUserId: String?; var mechCheckResultRole: PlayerRole?
var mechPoisonTargetUserId: String?; var mechGuardTargetUserId: String?
var mediumCheckedUserId: String?; var mediumResultRole: PlayerRole?

// Room
var hasMechanicalWolf: Boolean = false   // has_mechanical_wolf
var hasMedium: Boolean = false           // has_medium

object RoleRules {
    fun shootsOnDeath(p: GamePlayer): Boolean
    fun mediumSees(p: GamePlayer): PlayerRole
    fun wolfKillers(alive: List<GamePlayer>): List<GamePlayer>
    /** "LEARN" until learned, else the skill usable tonight or NONE (SHOOT is never a night action). */
    fun mechNightSkill(mech: GamePlayer, tonight: Int, allNights: List<NightPhase>): MechNightSkill
}
enum class MechNightSkill { LEARN, CHECK_ROLE, CHECK_ALIGNMENT, POISON, PROTECT, NONE }
```

- [ ] **Step 1: Write the failing tests.**
  - `PlayerRoleTest`:
    - Update the wolf-camp test to `containsExactlyInAnyOrder(WEREWOLF, WHITE_WOLF_KING, BLACK_WOLF_KING, MECHANICAL_WOLF)`.
    - Update the gods test so it adds `MEDIUM`.
    - New `isPackWolf excludes only the mechanical wolf`: the set is `WEREWOLF, WHITE_WOLF_KING, BLACK_WOLF_KING`.
    - New `learnedSkill table`: one `assertThat` per row of spec W5.
  - `RoleRulesTest`:
    - `shootsOnDeath`: true for a mechanical wolf with `learnedRole = HUNTER`; true for `learnedRole = BLACK_WOLF_KING`; false with `learnedRole = SEER` and with `null`; true for a plain HUNTER.
    - `mediumSees`: `MECHANICAL_WOLF` before learning, `WITCH` after learning a witch; a plain player's own role.
    - `wolfKillers`: the alive pack when the pack is non-empty, and the mechanical wolf is not in it; `[mech]` when only the mechanical wolf and good players are alive; empty when no wolf is alive.
    - `mechNightSkill`:
      - `LEARN` when `learnedRole == null`;
      - `NONE` on the learning night, even after learning a witch;
      - `POISON` the next night;
      - `NONE` after an earlier night has a `mechPoisonTargetUserId`;
      - the same three cases for `PROTECT` with `mechGuardTargetUserId`;
      - `CHECK_ROLE` every night after learning;
      - `NONE` for a learned HUNTER.
    - The learning night is `allNights.first { it.mechLearnTargetUserId != null }.dayNumber`.

- [ ] **Step 2: Run the tests and see them fail to compile.**
  `cd backend && ./gradlew test --tests '*PlayerRoleTest' --tests '*RoleRulesTest'`.
  Expected: compilation errors for `MECHANICAL_WOLF` and `RoleRules`.

- [ ] **Step 3: Implement the enums, the entity fields and `RoleRules`** with the signatures above.

- [ ] **Step 4: Write `V30__mechanical_wolf_medium.sql`** using the V26 pattern.
  - `ALTER TABLE rooms ADD COLUMN has_mechanical_wolf BOOLEAN NOT NULL DEFAULT FALSE`, and the same for `has_medium`.
  - `ALTER TABLE game_players ADD COLUMN learned_role VARCHAR(20)`.
  - Add the seven `night_phases` columns.
  - Drop and re-create `game_players_role_check`, `elimination_history_eliminated_role_check` and `elimination_history_hunter_shot_role_check` with all 11 roles.
  - Drop and re-create `night_phases_sub_phase_check` with `WAITING, WEREWOLF_PICK, MECH_WOLF_PICK, MECH_WOLF_RESULT, SEER_PICK, SEER_RESULT, MEDIUM_PICK, MEDIUM_RESULT, WITCH_ACT, GUARD_PICK, COMPLETE`.

- [ ] **Step 5: Run the tests again.**
  Same command as Step 2. Expected: PASS. Then run `./gradlew test` and expect 0 failures.

- [ ] **Step 6: Verify the migration on real Postgres.** This follows the memory note `ci-pg-connection-budget`.
  1. Run `docker run -d --name ww-v30 -e POSTGRES_PASSWORD=pw -p 55432:5432 postgres:16-alpine`.
  2. Apply `backend/docker/init.sql`.
  3. Start the backend with the dev profile against it, setting `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/...`.
  4. Expected log: `Successfully applied 30 migrations`, and no Hibernate `validate` error.
  5. Remove the container.

- [ ] **Step 7: Commit:** `feat: mechanical wolf and medium domain model, V30 migration, RoleRules`.

### Task 2: Room setup and keeping the pack apart (backend)

**Files:**
- Modify `service/RoomService.kt`: create (around line 65), update (around line 232), the role list (around line 372), `validateRoleComposition` and `buildGameConfig`.
- Modify `service/GameService.kt`:
  - `buildRoleList`;
  - the three wolf-teammate blocks (around lines 133, 158 and 421);
  - the night `selectedTargetId` block.
- Modify `game/role/WerewolfHandler.kt`.
- Modify `game/action/GameActionDispatcher.kt`: `nightTurn.holdsRole` and the `WOLF_SELECT` broadcast.
- Modify `service/SelfDestructService.kt`: lines 57 and 88.
- Modify `service/GameStateLogger.kt:125`.
- Modify `model/RoleDelayConfig.kt`: add defaults for the two roles, copying the Seer default.
- Modify `game/night/NightOrchestrator.kt:681`: `isAlive` for WEREWOLF becomes `RoleRules.wolfKillers(alive).isNotEmpty()`.
- Tests: `unit/phase/WinConditionCheckerTest.kt`, `unit/service/RoomServiceTest.kt`, `unit/service/GameServiceStartTest.kt`, `unit/service/GameServiceNightPhaseTest.kt`, `unit/service/GameActionDispatcherTest.kt`, `unit/service/SelfDestructServiceTest.kt`, and a new `unit/role/WerewolfHandlerPackTest.kt`.

**Interfaces:**
- Consumes `PlayerRole.isPackWolf` and `RoleRules.wolfKillers` from Task 1.
- Produces:
  - the private night-state field `"wolfKiller": Boolean`, true when the viewer is in `wolfKillers(alive)`;
  - `wolfTeammates` and `teammates` that contain only pack wolves, and are empty for the mechanical wolf.

- [ ] **Step 1: Write the failing tests.**
  - **RoomServiceTest:**
    - `seer and medium together is rejected` → `InvalidRoleCompositionException`.
    - `mechanical wolf needs a plain wolf`: wolfCount 1 with the mechanical wolf is rejected; wolfCount 2 is accepted.
    - `mech + both kings needs 4 wolves`: 3 is rejected, 4 is accepted.
    - `create sets hasMechanicalWolf and hasMedium`.
  - **GameServiceStartTest:** `role list deals one MECHANICAL_WOLF inside wolfCount and one MEDIUM`. The counts are wolf side == wolfCount and MEDIUM == 1.
  - **WerewolfHandlerPackTest:**
    - `mechanical wolf cannot WOLF_KILL while a pack wolf lives` → Rejected.
    - `lone mechanical wolf kills once the pack is gone` → Success, and `wolfTargetUserId` is set (Review Focus 3).
    - `pack wolf still kills`.
  - **GameActionDispatcherTest:**
    - `WOLF_SELECT is pushed to pack wolves only`: verify `sendPrivateAfterCommit` is never called with the mechanical wolf's id.
    - `mechanical wolf does not hold the WEREWOLF turn while the pack lives`.
  - **GameServiceNightPhaseTest:**
    - `mechanical wolf gets no teammates and no selectedTargetId during WEREWOLF_PICK`.
    - `pack wolf teammates exclude the mechanical wolf`.
    - `wolfKiller is true only for killers`.
  - **WinConditionCheckerTest** (W3): `villagers do not win while the mechanical wolf lives`, and `wolves win (屠边) when every god incl. MEDIUM is dead`.
  - **SelfDestructServiceTest:**
    - `mechanical wolf cannot self-destruct` → Rejected.
    - `last-wolf rule counts only the pack`.

- [ ] **Step 2: Run the tests and see them fail.**
  `./gradlew test --tests '*WinConditionCheckerTest' --tests '*RoomServiceTest' --tests '*GameServiceStartTest' --tests '*WerewolfHandlerPackTest' --tests '*GameActionDispatcherTest' --tests '*GameServiceNightPhaseTest' --tests '*SelfDestructServiceTest'`.
  Expected: the new tests FAIL.

- [ ] **Step 3: Implement.**
  - Validation: special wolves are `{WHITE_WOLF_KING, BLACK_WOLF_KING, MECHANICAL_WOLF} ∩ roles`, and the rule is `wolfCount ≥ specials + 1`. `SEER in roles && MEDIUM in roles` throws.
  - Every "the pack" use switches to `isPackWolf`, and every "who kills" use switches to `RoleRules.wolfKillers`.
  - `isWolf` stays in the win checks, `PerkService` and `RewardBreakdown`.

- [ ] **Step 4: Run the same tests again.** Expected: PASS. Then run `./gradlew test` and expect 0 failures.

- [ ] **Step 5: Commit:** `feat: room setup for mech wolf + medium; pack excludes the mechanical wolf`.

### Task 3: Night resolution and shooting

**Files:**
- Modify `game/night/NightOrchestrator.kt`: `computeKills` (line 71).
- Modify `game/phase/DayRevealAdvancer.kt`: `nightShooterId` (line 64).
- Modify `game/voting/VotingPipeline.kt`: lines 264, 506 and 627.
- Tests: `unit/night/NightKillMatrixTest.kt`, `unit/service/DayRevealAdvancerTest.kt`, `unit/service/VotingPipelineTest.kt`.

**Interfaces:** Consumes the `NightPhase.mech*` fields and `RoleRules.shootsOnDeath`.

- [ ] **Step 1: Write the failing tests.**
  - **NightKillMatrixTest:** extend `night(...)` with `mechGuard` and `mechPoison`. Then:
    - `mech guard blocks the wolf kill` → `[]`.
    - `mech guard blocks the witch poison` → `[]`.
    - `mech guard plus antidote on the wolf target - target lives` → `[]` (Review Focus 5).
    - `mech poison kills` → `["u2"]`.
    - `wolf kill, witch poison and mech poison on three players` → `containsExactlyInAnyOrder("u1","u2","u3")`.
    - `kill list order does not reveal the source`: wolf "u9", poison "u1" gives `containsExactly("u1","u9")`, sorted.
  - **DayRevealAdvancerTest:**
    - `mechanical wolf that learned HUNTER and died to wolves shoots`.
    - `mech-poisoned hunter wolf target cannot shoot`: the target is both `wolfTarget` and `mechPoisonTargetUserId`, and the result is null.
  - **VotingPipelineTest:**
    - `voted-out mechanical wolf with learned BLACK_WOLF_KING gets the shot`: `pendingShooterUserId` is set.
    - `voted-out mechanical wolf with learned SEER gets no shot`.

- [ ] **Step 2: Run the tests and see them fail.**
  `./gradlew test --tests '*NightKillMatrixTest' --tests '*DayRevealAdvancerTest' --tests '*VotingPipelineTest'`.
  Expected: the new tests FAIL.

- [ ] **Step 3: Implement.**
  - `computeKills`: let `shield = nightPhase.mechGuardTargetUserId`. The wolf kill lands as today unless the target is `shield`. Both poisons kill their targets unless the target is `shield`. Return `kills.distinct().sorted()`.
  - `nightShooterId`: treat "poisoned" as `wolfTarget in setOf(witchPoisonTargetUserId, mechPoisonTargetUserId)`, and replace `player.role.shootsOnDeath` with `RoleRules.shootsOnDeath(player)`.
  - Replace the three `VotingPipeline` sites the same way.
  - Existing tests that assert the old wolf-then-poison order switch to `containsExactlyInAnyOrder`, or to the sorted order.

- [ ] **Step 4: Run the tests again.** Expected: PASS. Then run `./gradlew test` and expect 0 failures.

- [ ] **Step 5: Commit:** `feat: resolve mech guard/poison; learned shooters shoot`.

### Task 4: `MediumHandler`, night wiring, audio

**Files:**
- Create `game/role/MediumHandler.kt` (`@Order(5)`).
- Renumber `@Order` in `WitchHandler` (3), `SeerHandler` (4) and `GuardHandler` (6).
- Modify `game/night/NightOrchestrator.kt`:
  - extract `fun activeNightRoles(room: Room): List<PlayerRole>`;
  - use it in both `nightSequence` and `nightRoleLoop`;
  - add MEDIUM when `room.hasMedium`, and MECHANICAL_WOLF when `room.hasMechanicalWolf` (that handler is added in Task 5).
- Modify `game/DomainEvent.kt`: add `MediumResult` and `MechWolfResult`.
- Modify `game/action/GameActionDispatcher.kt`: add `MEDIUM_CHECK` (private push) and `MEDIUM_CONFIRM`, both in `TURN_SUB_PHASE`.
- Modify `service/GameService.kt`: add the `mediumResult` block.
- Create `audio/impl/MediumAudioConfig.kt` and `audio/impl/MechanicalWolfAudioConfig.kt`.
- Register both in `audio/RoleAudioConfiguration.kt`.
- Modify `service/AudioService.kt`:
  - `mapSubPhaseToRole` gets the four new sub-phases;
  - replace the `SEER_RESULT` special cases (lines 123 and 165) with a set `RESULT_SUB_PHASES = setOf(SEER_RESULT, MECH_WOLF_RESULT, MEDIUM_RESULT)`;
  - the dead-role skip logic around line 257 applies to the medium and mechanical-wolf PICK/RESULT pairs the same way it applies to the seer's.
- Create the 4 placeholder mp3s in `backend/src/main/resources/static/audio/`.
- Modify `frontend/src/services/audioService.ts`: add the 4 files to `KNOWN_NARRATION_FILES`.
- Tests: a new `unit/role/MediumHandlerTest.kt`; modify `unit/service/NightOrchestratorTest.kt`, `unit/service/AudioPhaseMappingTest.kt` and `unit/service/GameServiceNightPhaseTest.kt`.

**Interfaces (Produces):**

```kotlin
@JsonTypeName("MediumResult") data class MediumResult(val gameId: Int, val checkedUserId: String, val role: PlayerRole) : DomainEvent()
@JsonTypeName("MechWolfResult") data class MechWolfResult(val gameId: Int) : DomainEvent()   // ping; client refetches
fun NightOrchestrator.activeNightRoles(room: Room): List<PlayerRole>
```

The state field `mediumResult = { checkedPlayerId, checkedNickname, checkedSeatIndex, role, history: [{ round, nickname, role }] }` is sent only to the medium, once `mediumCheckedUserId != null`.

- [ ] **Step 1: Write the failing tests.**
  - **MediumHandlerTest** (copy the `WitchHandlerTest` helpers):
    - `MEDIUM_CHECK stores the exact role`: the target is a WITCH, so `mediumResultRole == WITCH` and the result has a `MediumResult` event.
    - `cannot check self`, `cannot check a dead player`, `rejected outside MEDIUM_PICK`, `non-medium rejected`.
    - `unlearned mechanical wolf shows MECHANICAL_WOLF`.
    - `medium sees the role learned earlier the same night`: the mechanical wolf has `learnedRole = GUARD`, so the stored role is `GUARD` (Review Focus 2).
    - `MEDIUM_CONFIRM only in MEDIUM_RESULT`.
  - **NightOrchestratorTest:**
    - `medium board sequence` equals `[WEREWOLF_PICK, WITCH_ACT, MEDIUM_PICK, MEDIUM_RESULT, GUARD_PICK]`.
    - `existing board sequence unchanged` equals `[WEREWOLF_PICK, WITCH_ACT, SEER_PICK, SEER_RESULT, GUARD_PICK]`.
  - **AudioPhaseMappingTest:**
    - `MEDIUM_PICK opens with medium_open_eyes.mp3`.
    - `MEDIUM_RESULT has no open-eyes audio`.
    - `MECH_WOLF_PICK opens with mech_wolf_open_eyes.mp3`.
  - **GameServiceNightPhaseTest:**
    - `medium state carries role and history`.
    - `non-medium never gets mediumResult`.

- [ ] **Step 2: Run the tests and see them fail.**
  `./gradlew test --tests '*MediumHandlerTest' --tests '*NightOrchestratorTest' --tests '*AudioPhaseMappingTest' --tests '*GameServiceNightPhaseTest'`.

- [ ] **Step 3: Implement `MediumHandler`**, modelled on `SeerHandler`. It uses `RoleRules.mediumSees(target)` and the same `[race]` log line pattern.

- [ ] **Step 4: Wire the rest.**
  - The dispatcher routes with an after-commit private push, copying the `SEER_CHECK` branch.
  - Add the GameService block, the audio configs and the AudioService set.
  - Generate the placeholders: `for f in mech_wolf_open_eyes mech_wolf_close_eyes medium_open_eyes medium_close_eyes; do ffmpeg -y -f lavfi -i anullsrc=r=44100:cl=mono -t 0.5 -q:a 9 -acodec libmp3lame backend/src/main/resources/static/audio/$f.mp3; done`.

- [ ] **Step 5: Run the tests again.** Expected: PASS. Then run `./gradlew test` and `cd frontend && npx vitest run`; both green.

- [ ] **Step 6: Commit:** `feat: medium role, night wiring, placeholder narration`.

### Task 5: `MechanicalWolfHandler`

**Files:**
- Create `game/role/MechanicalWolfHandler.kt` (`@Order(2)`). Its dependencies are `NightPhaseRepository` and `GamePlayerRepository`.
- Modify `game/action/GameActionDispatcher.kt`:
  - route `MECH_LEARN`, `MECH_CHECK`, `MECH_POISON`, `MECH_PROTECT` and `MECH_SKIP` to `nightTurn(..., MECHANICAL_WOLF)` with a private push of the result events;
  - route `MECH_CONFIRM` the same way, without a push;
  - map the first five to `MECH_WOLF_PICK` and `MECH_CONFIRM` to `MECH_WOLF_RESULT` in `TURN_SUB_PHASE`.
- Modify `service/GameService.kt`: add the `mechWolf` block.
- Tests: a new `unit/role/MechanicalWolfHandlerTest.kt`; modify `unit/service/GameActionDispatcherTest.kt`, `unit/service/GameServiceNightPhaseTest.kt` and `unit/service/NightOrchestratorTest.kt`.

**Interfaces:**
- Consumes `RoleRules.mechNightSkill`, `RoleRules.mediumSees`, `PlayerRole.learnedSkill` and `DomainEvent.MechWolfResult`.
- Produces the state field `mechWolf`, sent only to the mechanical wolf:

  ```
  { learnedRole: String?, learnedPlayerId: String?, skillTonight: MechNightSkill name,
    result: { kind: "LEARN"|"CHECK"|"POISON"|"PROTECT"|"SKIP", targetPlayerId?, targetNickname?, targetSeatIndex?,
              role?: String /* LEARN and CHECK_ROLE */, isWerewolf?: Boolean /* CHECK_ALIGNMENT */ }? }
  ```

  `result` is the action taken this night, or null before it acts. The result screen shows `kind = SKIP` as "no action tonight".

- [ ] **Step 1: Write the failing tests** in `MechanicalWolfHandlerTest`, with one test per rule:
  - **Learning:**
    - `MECH_LEARN sets learnedRole to the target's role and mechLearnTargetUserId`, and the result has `MechWolfResult`.
    - `learn keeps the role after the target dies`: after learning a WITCH, mark the target dead; `learnedRole` stays `WITCH` (Review Focus 1).
    - `cannot learn twice in a game`: an earlier night has `mechLearnTargetUserId` → Rejected.
    - `cannot learn self or a dead player`.
  - **Learned skills:**
    - `MECH_CHECK with learned MEDIUM stores mediumSees(target)`.
    - `MECH_CHECK with learned SEER stores the target role`; the state exposes only `isWerewolf`.
    - `MECH_CHECK rejected on the learning night`.
    - `MECH_POISON allowed the night after learning WITCH, then never again`.
    - `MECH_POISON on self rejected`.
    - `MECH_PROTECT allowed once, self allowed`.
    - `MECH_PROTECT rejected when learned WITCH`: the action must match `mechNightSkill`.
  - **Skip, confirm and sub-phase rules:**
    - `MECH_SKIP always allowed in MECH_WOLF_PICK`.
    - `MECH_CONFIRM only in MECH_WOLF_RESULT`.
    - `every action rejected for a non-mech or a dead mech`.
  - In `GameActionDispatcherTest`: `second MECH_LEARN in the same turn is rejected` (Review Focus 4).
  - In `NightOrchestratorTest`: `night sequence with every role` equals `[WEREWOLF_PICK, MECH_WOLF_PICK, MECH_WOLF_RESULT, WITCH_ACT, MEDIUM_PICK, MEDIUM_RESULT, GUARD_PICK]` for a room with hasMechanicalWolf, hasWitch, hasMedium and hasGuard.
  - In `GameServiceNightPhaseTest`:
    - `mechWolf state: skillTonight LEARN before learning, POISON the night after learning WITCH`.
    - `pack wolves never get mechWolf`.

- [ ] **Step 2: Run the tests and see them fail.**
  `./gradlew test --tests '*MechanicalWolfHandlerTest' --tests '*GameActionDispatcherTest' --tests '*GameServiceNightPhaseTest' --tests '*NightOrchestratorTest'`.

- [ ] **Step 3: Implement the handler.**
  - `acceptedActions`: PICK accepts the five pick actions and RESULT accepts `MECH_CONFIRM`.
  - `handle` validates the actor and the sub-phase. It then checks the action against `RoleRules.mechNightSkill(actor, nightPhase.dayNumber, context.allNightPhases)`: `MECH_LEARN` requires `LEARN`, `MECH_CHECK` requires `CHECK_*`, `MECH_POISON` requires `POISON`, and `MECH_PROTECT` requires `PROTECT`.
  - `MECH_LEARN` saves the `GamePlayer` and the `NightPhase`. Every other action saves only the `NightPhase`.

- [ ] **Step 4: Wire the dispatcher and the GameService block.**

- [ ] **Step 5: Run the tests again.** Expected: PASS. Then run `./gradlew test` and expect 0 failures.

- [ ] **Step 6: Commit:** `feat: mechanical wolf night turn (learn + learned skills)`.

### Task 6: Night-flow integration (no tells, lone kill, no leaks)

**Files:** create `backend/src/test/kotlin/com/werewolf/integration/MechWolfMediumNightIntegrationTest.kt`. Copy the setup from an existing night integration test, for example `NightAudioSequenceGuaranteeIntegrationTest`: a real Spring context and short `werewolf.timing.*` values.

To stay within the CI Postgres connection budget, reuse an existing test context configuration. Add no `@MockitoSpyBean` and no new `properties`.

- [ ] **Step 1: Write the failing tests.**
  - `sub-phase broadcast sequence is identical whether the mechanical wolf learns, skips or is dead`:
    1. Run three nights in a room with every role.
    2. Capture the `NightSubPhaseChanged` and `AudioSequence` events broadcast to `/topic/game/{id}`.
    3. Assert the three sequences are equal.
  - `lone mechanical wolf kills`:
    1. Kill the pack.
    2. The next night `WEREWOLF_PICK` waits for and accepts the mechanical wolf's `WOLF_KILL`.
    3. The target dies at dawn.
  - `state API: pack wolves never see the mechanical wolf and the reverse`: `GET /api/game/{id}/state` as each wolf; the `wolfTeammates` and `teammates` fields never cross.
  - `medium checks the mechanical wolf before and after learning`: the medium sees `MECHANICAL_WOLF`, then the learned role.

- [ ] **Step 2: Run the tests.**
  `./gradlew test --tests '*MechWolfMediumNightIntegrationTest'`.
  Expected: PASS if Tasks 1–5 are right. A failure here is a real bug: fix it in the owning task's code, not in the test.

- [ ] **Step 3: Commit:** `test: night-flow integration for mech wolf + medium`.

### Task 7: Review and role history (backend)

**Files:**
- Modify `service/RoleHistoryBuilder.kt`: add `MEDIUM` and `MECHANICAL_WOLF` branches before the `isWolf` branch.
- Modify `dto/PlayerReviewDtos.kt`: add `MyNightAction.Medium` and `MyNightAction.MechWolf`.
- Modify `service/PlayerReviewBuilder.kt`:
  - line 97, `knownRoles`: the pack knows only the pack; the mechanical wolf knows only its learned target;
  - line 160, the action variant;
  - line 176, `knewOfAttack`.
- Modify `service/GameReviewBuilder.kt`:
  - line 79, actors are `wolfKillers` at that night;
  - add `MediumTurn` and `MechWolfTurn` to the night record next to `SeerTurn`.
- Tests: `unit/service/RoleHistoryBuilderTest.kt`, `unit/service/PlayerReviewBuilderTest.kt`, `unit/service/GameReviewBuilderTest.kt`.

**Interfaces (Produces):**

```kotlin
@JsonTypeName("MEDIUM") data class Medium(val target: PlayerSeat?, val role: PlayerRole?) : MyNightAction
@JsonTypeName("MECHANICAL_WOLF") data class MechWolf(
    val learned: PlayerSeat?, val learnedRole: PlayerRole?, val check: PlayerSeat?, val checkRole: PlayerRole?,
    val poison: PlayerSeat?, val protect: PlayerSeat?, val kill: PlayerSeat?) : MyNightAction
data class MediumTurn(val actor: PlayerRef, val dead: Boolean, val target: PlayerRef?, val role: PlayerRole?)
data class MechWolfTurn(val actor: PlayerRef, val dead: Boolean, val learned: PlayerRef?, val learnedRole: PlayerRole?,
    val check: PlayerRef?, val poison: PlayerRef?, val protect: PlayerRef?)
```

In `MechWolf`, `kill` is set only on nights when it was the lone killer. Role-history entry kinds are `LEARN`, `CHECK`, `POISON`, `PROTECT` and `KILL` for the mechanical wolf, and `CHECK` with `role` for the medium.

- [ ] **Step 1: Write the failing tests.**
  - **RoleHistoryBuilderTest:**
    - `mechanical wolf history shows its own actions, never the pack's kills`: a pack kill on night 1 does not appear.
    - `medium history carries roles`.
  - **PlayerReviewBuilderTest:**
    - `mechanical wolf review knows only its learned target`.
    - `pack review does not reveal the mechanical wolf`.
  - **GameReviewBuilderTest:**
    - `wolf actors exclude the mechanical wolf while the pack lives`.
    - `mech and medium turns appear per night`.

- [ ] **Step 2: Run the tests and see them fail.**
  `./gradlew test --tests '*RoleHistoryBuilderTest' --tests '*PlayerReviewBuilderTest' --tests '*GameReviewBuilderTest'`.

- [ ] **Step 3: Implement the changes.**

- [ ] **Step 4: Run the tests again.** Expected: PASS. Then run `./gradlew test` and expect 0 failures.

- [ ] **Step 5: Commit:** `feat: review and role history for mech wolf + medium`.

### Task 8: Frontend roles, setup and role cards

**Files:**
- Modify `frontend/src/types/index.ts`:
  - add to the `PlayerRole`, `NightSubPhase` and `ActionType` unions;
  - add to `NightPhaseState`: `wolfKiller?: boolean`, `mediumResult?`, `mechWolf?`, using the shapes from Tasks 2, 4 and 5.
- Modify `frontend/src/utils/roleDefinitions.ts`:
  - `{ id: 'MECHANICAL_WOLF', emoji: '🤖', required: false, wolf: true }`, placed after BLACK_WOLF_KING;
  - `{ id: 'MEDIUM', emoji: '👁️', required: false }`, placed after SEER;
  - `export function isPackWolfRole(id): boolean`.
- Modify `frontend/src/components/RoomSetupPanel.vue`:
  - `KING_IDS` already picks up every optional wolf, so the mechanical wolf follows the same ≥9-player and `wolves ≥ specials + 2`-to-enable rules;
  - turning SEER on turns MEDIUM off, and the reverse.
- Modify `frontend/src/components/RoleRevealCard.vue`: add both roles.
- Modify `NightPhase.vue`: add both roles to the `ROLE_META` map at line 622.
- Modify `GameReview.vue`: add both roles to its role maps.
- Modify `frontend/src/i18n/locales/zh.ts` and `en.ts`:
  - `role.MECHANICAL_WOLF` / `role.MEDIUM`;
  - `role.desc.*` / `role.descShort.*`, written from spec W1–W7 and M1–M3;
  - `setup.mediumSeerExclusive`.
- Tests: `src/__tests__/roleDefinitions.test.ts`, `RoomSetupPanelRoles.test.ts`, `roleRevealCard.test.ts`.

- [ ] **Step 1: Write the failing tests.**
  - **roleDefinitions:**
    - `isPackWolfRole('MECHANICAL_WOLF') === false`, `isWolfRole('MECHANICAL_WOLF') === true`, `isPackWolfRole('WEREWOLF') === true`.
  - **RoomSetupPanelRoles:**
    - `enabling MEDIUM turns SEER off` and the reverse.
    - `mechanical wolf needs 9+ players and enough wolves`, following the existing king tests.
  - **roleRevealCard:**
    - `mechanical wolf card shows no teammates and its rule text`.

- [ ] **Step 2: Run the tests and see them fail.**
  `cd frontend && npx vitest run src/__tests__/roleDefinitions.test.ts src/__tests__/RoomSetupPanelRoles.test.ts src/__tests__/roleRevealCard.test.ts`.

- [ ] **Step 3: Implement the changes.**

- [ ] **Step 4: Run the checks.**
  `npx vitest run && npx vue-tsc --noEmit && npx prettier --check src`. Expected: all green, including `i18nParity`.

- [ ] **Step 5: Commit:** `feat(frontend): mech wolf + medium roles, setup, role cards`.

### Task 9: Frontend night turns

**Files:**
- Create `frontend/src/components/MechWolfTurn.vue`:
  - props: `subPhase`, `mechWolf`, `players`, `myUserId`;
  - emits: `action: [type: 'MECH_LEARN'|'MECH_CHECK'|'MECH_POISON'|'MECH_PROTECT'|'MECH_SKIP'|'MECH_CONFIRM', targetUserId?: string]`.
- Create `frontend/src/components/MediumTurn.vue`:
  - props: `subPhase`, `mediumResult`, `players`, `myUserId`;
  - emits: `action: [type: 'MEDIUM_CHECK'|'MEDIUM_CONFIRM', targetUserId?: string]`.
- Modify `frontend/src/components/NightPhase.vue`:
  - mount both components on their sub-phases;
  - re-emit a new `nightAction: [type: ActionType, targetUserId?: string]`;
  - in the `WEREWOLF_PICK` template (line 39), the `isMyTurn` check (line 581) and the badge (line 639), replace `isWolfRole(myRole)` with `nightPhase?.wolfKiller === true`;
  - add the new sub-phases to the badge switch.
- Modify `frontend/src/views/GameView.vue`:
  - forward `nightAction` to `gameService.submitAction`;
  - handle the `MediumResult` and `MechWolfResult` STOMP events next to `SeerResult` (line 1218): refetch state.
- Modify i18n: add the `night.mech.*`, `night.medium.*` and `night.badge.*` keys.
- Tests: create `src/__tests__/mechWolfTurn.test.ts` and `src/__tests__/mediumTurn.test.ts`; modify `src/__tests__/nightPhase.test.ts`.

**Required test ids** (the E2E in Task 11 depends on them):
- `mech-learn-target-{seat}`, `mech-learn-confirm`
- `mech-skill-target-{seat}`, `mech-skill-confirm`
- `mech-skip`
- `mech-result` with `data-kind` and `data-role`
- `mech-result-confirm`
- `medium-target-{seat}`, `medium-confirm`
- `medium-result` with `data-role` and `data-checked-seat`
- `medium-result-confirm`

- [ ] **Step 1: Write the failing tests.**
  - **mechWolfTurn:**
    - `LEARN shows living other players and emits MECH_LEARN with the target`.
    - `NONE shows only skip`.
    - `POISON emits MECH_POISON`.
    - `PROTECT lists self as a target`.
    - `result shows learned role name`.
    - `CHECK_ALIGNMENT result shows wolf/good only`.
  - **mediumTurn:**
    - `emits MEDIUM_CHECK`, `cannot pick self`.
    - `result shows role name and history`.
  - **nightPhase:**
    - `mechanical wolf sees closed eyes in WEREWOLF_PICK when wolfKiller is false`.
    - `lone mechanical wolf sees the kill UI when wolfKiller is true`.
    - `other players see the closed badge during MECH_WOLF_PICK and MEDIUM_PICK`.

- [ ] **Step 2: Run the tests and see them fail.**
  `npx vitest run src/__tests__/mechWolfTurn.test.ts src/__tests__/mediumTurn.test.ts src/__tests__/nightPhase.test.ts`.

- [ ] **Step 3: Implement.**
  - Use the house buttons (`btn-primary` and the others) and the seer result card styling.
  - Reusing the seer's result markup is the builder's choice.

- [ ] **Step 4: Run the checks.** `npx vitest run && npx vue-tsc --noEmit`, all green.

- [ ] **Step 5: Commit:** `feat(frontend): mech wolf and medium night turns`.

### Task 10: Frontend review display

**Files:**
- Modify `frontend/src/components/GameReview.vue`, `PlayerReview.vue` and `RoleHistory.vue`, plus the matching types for `MechWolfTurn`, `MediumTurn`, `MyNightAction.Medium` / `MechWolf`, and the history kinds.
- Add i18n keys.
- Tests: `src/__tests__/gameReview.test.ts`, `playerReview.test.ts`, `roleHistory.test.ts`.

- [ ] **Step 1: Write the failing tests.**
  - `game review lists the mech learn/check/poison/protect and medium checks per night`.
  - `player review shows MechWolf and Medium night actions`.
  - `role history renders LEARN/CHECK/POISON/PROTECT/KILL entries`.

- [ ] **Step 2: Run the tests and see them fail.**
  `npx vitest run src/__tests__/gameReview.test.ts src/__tests__/playerReview.test.ts src/__tests__/roleHistory.test.ts`.

- [ ] **Step 3: Implement the changes.**

- [ ] **Step 4: Run the tests again.** `npx vitest run && npx vue-tsc --noEmit`, all green.

- [ ] **Step 5: Commit:** `feat(frontend): review shows mech wolf + medium`.

### Task 11: Real-backend E2E and CI wiring

Load the `write-real-e2e-test` skill first and read `feedback_e2e_ci_vs_local.md`.

**Files:**
- Create `frontend/e2e/real/mech-wolf-medium-flow.spec.ts`.
- Modify `frontend/e2e/real/helpers/audio-manifest.ts`: `NIGHT_ROLE_ORDER` follows the new order whenever a room has these roles. Keep the existing order for the boards that lack them, and update `audioManifest.test.ts` to match.
- Modify `frontend/e2e/real/helpers/role-leak.ts`:
  - add both roles to `PLAYER_ROLES`;
  - exempt a player's own `mediumResult` and `mechWolf` fields.
- Modify `frontend/e2e/real/helpers/shell-runner.ts`: add the two roles to the `RoleName` union.
- Modify `.github/workflows/ci.yml`: add the spec to the lightest integration shard.

- [ ] **Step 1: Write the spec.**
  - Setup: `setupGame` with 9 players, `hasSheriff: false`, roles `WEREWOLF, MECHANICAL_WOLF, VILLAGER, MEDIUM, WITCH, GUARD`, and `browserRoles: ['MECHANICAL_WOLF', 'MEDIUM']`.
  - **Night 1:**
    1. The mechanical wolf page clicks `mech-learn-target-{witchSeat}` then `mech-learn-confirm`.
    2. `mech-result` has `data-role="WITCH"`.
    3. The medium page checks the mechanical wolf's seat, and `medium-result` has `data-role="WITCH"`.
  - **Night 2:**
    1. The mechanical wolf page shows `mech-skill-target-*`, so the poison is available.
    2. It poisons a villager seat.
    3. At dawn the villager is dead: assert through `fetchGameState`, not the UI text.
  - Gate every step on the sub-phase with `waitForSubPhase`. Use no `waitForTimeout`.

- [ ] **Step 2: Run the spec locally, twice.**
  `cd frontend && npx playwright test --config=playwright.real.config.ts e2e/real/mech-wolf-medium-flow.spec.ts`, run twice.
  Expected: PASS both times, and the backend log has no `Rejected`.

- [ ] **Step 3: Run the existing real specs touching nights and audio.** Run `game-flow`, `night-rules-flow`, `audio-reconnect-recovery` and `hunter-night-death-flow`. Expected: PASS, so no regression on the old boards.

- [ ] **Step 4: Commit:** `test(e2e): mech wolf learns witch, poisons; medium sees learned role`.

---

## Done when

- `./gradlew test` passes, with 0 failures.
- `npx vitest run`, `vue-tsc` and `prettier --check` are green.
- The new E2E passes twice locally.
- CI is green on the pushed branch.
- The 4 placeholder mp3s exist under the exact names.
