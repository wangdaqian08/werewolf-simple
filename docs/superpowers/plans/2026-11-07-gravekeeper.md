# 守墓人 (Gravekeeper) Implementation Plan

> **Status (2026-11-08): PAUSED, awaiting owner review.** Nothing is implemented. Resume by having the owner review this plan, then execute it subagent-driven on branch `feat/gravekeeper`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `GRAVEKEEPER` god role end to end (backend, frontend, tests), per the spec's G1–G4. This is PR 2 of 2; the knight shipped in #179.

**Architecture:**
- One night sub-phase, `GRAVEKEEPER_RESULT`, owned by a new `GravekeeperHandler` (`@Order(6)`; Guard moves to 7). The night loop walks it every night, alive or dead, exactly like any other role turn.
- The result is computed **once, at night-init**, by a new `RoleHandler.onNightInit` hook that `NightOrchestrator.initNightInternal` calls before it saves the new `NightPhase` row. It reads `elimination_history` for `dayNumber − 1`.
- The result reaches the client through the gravekeeper's private night state in `GameService.getGameState`. There is no push.
- Review and role history read the stored `NightPhase` columns.

**Tech Stack:** Kotlin, Spring Boot 4, JPA, Flyway, JUnit5, Mockito-Kotlin, AssertJ; Vue 3, TypeScript, Pinia, Vitest; Playwright with the real backend.

**Spec:** `docs/superpowers/specs/2026-11-07-knight-gravekeeper-design.md`. Read the GRAVEKEEPER rules (G1–G4), "Interactions with existing roles", "Role descriptions" and the gravekeeper bullets under "Decided parts of the how". Ignore the knight parts: they are done.

## Rulings made while planning (deviations from the spec's wording)

1. **Computed at night-init, not when `GRAVEKEEPER_RESULT` opens.** The value is the same either way:
   - nothing during a night changes who was voted out yesterday;
   - night kills are deferred to the day reveal, so the gravekeeper's `alive` cannot change between night-init and its sub-phase.

   Computing it at init has three benefits:
   - it runs inside the init transaction, not in the coroutine (which has the NightPhase read/merge race);
   - it is in the state the client already fetches on `PhaseChanged`, so no new push is needed;
   - a page refresh shows it.
2. **One extra column, `night_phases.gravekeeper_exiled_user_id`.** History, the player review and the god review all name the exiled player. Storing the id avoids joining `elimination_history` in four places. (The spec leaves column names beyond its list to the builder.)
3. **Night 1 and "no exile" are both stored as `NONE`.** The client shows `首夜無信息` when `nightPhase.dayNumber == 1`, and `昨日無人被放逐` otherwise. This is the spec's "night 1 → NONE, shown as 首夜無信息".
4. **No forced host role in the E2E.** `NightPhase.vue` has no host branch, so `browserRoles: ['GRAVEKEEPER']` gives a page for the gravekeeper whether or not the host holds the role. The host-specific UI risk that needed `swap-roles` for the knight does not exist here.
5. **Default delay:** `GRAVEKEEPER_DEFAULT = RoleDelayConfig(15000L, 8000L)`. The turn has one result-only sub-phase, so the dead-role delay is about half the seer's (which covers two sub-phases). This is the builder's call.

## Global Constraints

- **Kotlin.**
  - No `!!` in `src/main`; use `?: error("...")`.
  - Every new review DTO variant needs `@JsonTypeName`.
- **Migration.**
  - The new migration is `V33__gravekeeper.sql`. Never edit V1–V32.
  - The SQL must be portable (Postgres and H2).
  - Tests use `ddl-auto: create-drop`, so the migration is verified by `FlywayMigrationsIntegrationTest` on a throwaway Postgres (Task 1).
- **Names, verbatim** (run the `verify-enum-values` skill before writing any of these literals):
  - role `GRAVEKEEPER`;
  - action `GRAVEKEEPER_CONFIRM`;
  - sub-phase `GRAVEKEEPER_RESULT`;
  - enum `GravekeeperResult { GOOD, WOLF, NONE }`;
  - columns `rooms.has_gravekeeper`, `night_phases.gravekeeper_result`, `night_phases.gravekeeper_exiled_user_id`;
  - audio files `gravekeeper_open_eyes.mp3` and `gravekeeper_close_eyes.mp3` (already in `backend/src/main/resources/static/audio/`; do not regenerate them).
- **Rule core (G1/G2):** result = `NONE` when there is no `elimination_history` row for `(gameId, dayNumber − 1)` (always on night 1); otherwise `WOLF` if `eliminatedRole.isWolf`, else `GOOD`.
  - Use the real role (`eliminatedRole`), never `learnedRole` or `RoleRules.mediumSees`. A learned mechanical wolf is still `WOLF`.
- **Night order (G4):** WEREWOLF(1) → MECHANICAL_WOLF(2) → WITCH(3) → SEER(4) / MEDIUM(5) → **GRAVEKEEPER(6)** → GUARD(7).
- **Anti-goal:** the sub-phase sequence and timing must not depend on whether the gravekeeper is alive or whether anyone was exiled. There is no per-night special case.
- **Tests.**
  - No new Spring test contexts: `@SpringBootTest @ActiveProfiles("test")` only, no `@MockBean`/`@SpyBean`.
  - Portable SQL and JPQL.
  - NEVER point a test at `localhost:5432` (`werewolf-db`, the owner's dev data). The Postgres Flyway run uses a throwaway container on `127.0.0.1:55432` (Task 1, Step 4); stop it afterwards.
- **Frontend.**
  - `zh.ts` is Traditional Chinese. i18n keys follow the existing `role.*` structure (`role.GRAVEKEEPER`, `role.desc.GRAVEKEEPER`, `role.descShort.GRAVEKEEPER`, `role.rules.GRAVEKEEPER`).
  - `en.ts` and `zh.ts` keep the same keys; `i18nParity` stays green.
  - Type-check with `npx vue-tsc -b` (not `--noEmit`).
  - `npm run lint` and `npm run format:check` must be clean.
  - Every element a test reads has a `data-testid`.
- **E2E.**
  - `getByTestId` only. No sleeps, no retry loops, no `test.skip`.
  - The `roles` list includes `WITCH`.
  - The new spec is listed in a `.github/workflows/ci.yml` shard.
  - Follow the `write-real-e2e-test` skill.
  - Ports: check that 8080 and 5174 are free first. Never touch 5173 (the owner's dev server). Don't kill servers you didn't start.
- **Commits.**
  - Stage individual files.
  - End each message with the `Co-Authored-By` attribution line the session provides.
  - Do not push.
  - Run `graphify update .` after the last code task.

## Review Focus

1. **Page refresh during `GRAVEKEEPER_RESULT`.** The result must come back from `GET /state`, not from component memory or a push.
   - Tests: Task 3, `state serves gravekeeperResult to the gravekeeper`; Task 5, `renders the verdict from the nightPhase prop`.
2. **Night 1 of a sheriff game** (`initNight(withWaiting = true)`, which creates the row in `WAITING`). The hook must still run and store `NONE`.
   - Test: Task 2, `night-init with WAITING still stores NONE for a living gravekeeper`.
3. **The exiled player's camp leaks to someone else.** Only the gravekeeper's state may carry `gravekeeperResult`.
   - Tests: Task 3, `non-gravekeeper state has no gravekeeperResult`; Task 6, an E2E payload scan of the other pages.
4. **A learned mechanical wolf is voted out.** It must read `WOLF`, never the learned role's camp.
   - Test: Task 2, `exiled mechanical wolf that learned SEER reads WOLF`.
5. **A live gravekeeper whose state has no result** (unexpected). The confirm button must still render, or the night waits forever on a turn the player cannot end.
   - Test: Task 5, `confirm button renders even without a result`.

---

### Task 1: Role, room toggle, storage, audio config (V33)

**Files:**
- Modify `backend/src/main/kotlin/com/werewolf/model/Enums.kt`:
  - `PlayerRole.GRAVEKEEPER`, after `KNIGHT`;
  - new `enum class GravekeeperResult { GOOD, WOLF, NONE }`.
- Modify `model/Room.kt`: `var hasGravekeeper: Boolean = false`, mapped to `has_gravekeeper`.
- Modify `model/NightPhase.kt`, next to the medium fields:
  - `@Enumerated(EnumType.STRING) @Column(name = "gravekeeper_result", length = 10) var gravekeeperResult: GravekeeperResult? = null`;
  - `@Column(name = "gravekeeper_exiled_user_id", length = 128) var gravekeeperExiledUserId: String? = null`.
- Modify `service/RoomService.kt`, in the same three places as `hasKnight`:
  - create (~line 74);
  - update (~244);
  - room role list (~391).
- Modify `game/RoleDealer.kt`: add `if (room.hasGravekeeper) roles.add(PlayerRole.GRAVEKEEPER)` after the KNIGHT line.
- Create `audio/impl/GravekeeperAudioConfig.kt` (copy `MediumAudioConfig`, with the two file names above) and register it in `audio/RoleAudioConfiguration.kt`. Startup fails without an audio config for every role, so this must land with the enum.
- Create `backend/src/main/resources/db/migration/V33__gravekeeper.sql` (copy the shape of V30/V32):
  - `ALTER TABLE rooms ADD COLUMN has_gravekeeper BOOLEAN NOT NULL DEFAULT FALSE;`
  - `ALTER TABLE night_phases ADD COLUMN gravekeeper_result VARCHAR(10);`
  - `ALTER TABLE night_phases ADD COLUMN gravekeeper_exiled_user_id VARCHAR(128);`
  - Drop and re-create the three role CHECKs (`game_players_role_check`, `elimination_history_eliminated_role_check`, `elimination_history_hunter_shot_role_check`) with
    `'WEREWOLF','VILLAGER','SEER','WITCH','HUNTER','GUARD','IDIOT','WHITE_WOLF_KING','BLACK_WOLF_KING','MECHANICAL_WOLF','MEDIUM','KNIGHT','GRAVEKEEPER'`.
  - Drop and re-create `night_phases_sub_phase_check` with
    `'WAITING','WEREWOLF_PICK','MECH_WOLF_PICK','MECH_WOLF_RESULT','SEER_PICK','SEER_RESULT','MEDIUM_PICK','MEDIUM_RESULT','GRAVEKEEPER_RESULT','WITCH_ACT','GUARD_PICK','COMPLETE'`.
- Tests:
  - `unit/model/PlayerRoleTest.kt`;
  - `unit/service/RoomServiceTest.kt`;
  - `unit/game/RoleDealerTest.kt`;
  - `unit/role/RoleRulesTest.kt` and `unit/role/MediumHandlerTest.kt` (the interactions);
  - `integration/FlywayMigrationsIntegrationTest.kt`.

**Interfaces:**
- Produces:
  - `PlayerRole.GRAVEKEEPER` (`isGod`, `learnedSkill == NONE`);
  - `GravekeeperResult`;
  - `Room.hasGravekeeper`;
  - `NightPhase.gravekeeperResult`;
  - `NightPhase.gravekeeperExiledUserId`.

- [ ] **Step 1: Write the failing tests.**
  - **PlayerRoleTest:** `GRAVEKEEPER is a god with no learned skill`. Assert `isGod`, `!isWolf`, `!isPackWolf`, `!shootsOnDeath`, `learnedSkill == LearnedSkill.NONE`.
  - **RoomServiceTest** (mirror the KNIGHT cases):
    - `create sets hasGravekeeper`;
    - `update toggles hasGravekeeper`;
    - `room role list includes GRAVEKEEPER when on`.
  - **RoleDealerTest:** `deals exactly one GRAVEKEEPER as a god seat`. Assert the count of `GRAVEKEEPER` == 1, the wolf count == wolfCount, and `RolePickChoice.campOf(GRAVEKEEPER) == GOD`.
  - **Interactions** (spec "Interactions with existing roles"; add each to the existing test of that role):
    - `seer sees GRAVEKEEPER as good`;
    - `medium sees GRAVEKEEPER as GRAVEKEEPER` (`RoleRules.mediumSees`);
    - `mechanical wolf learning GRAVEKEEPER gets no skill`.
  - **FlywayMigrationsIntegrationTest:** `V33 stores GRAVEKEEPER roles, the room flag and the night result columns`. Mirror the V32 test. It must:
    - insert a `GRAVEKEEPER` game_player and elimination_history row;
    - insert a night_phases row with `sub_phase = 'GRAVEKEEPER_RESULT'`, `gravekeeper_result = 'WOLF'` and `gravekeeper_exiled_user_id = 'w'`;
    - assert that `has_gravekeeper` defaults to FALSE;
    - assert that the role CHECK still rejects `'NOT_A_ROLE'`.
- [ ] **Step 2: Run and see them fail.**
  `cd backend && ./gradlew test --tests '*PlayerRoleTest' --tests '*RoomServiceTest' --tests '*RoleDealerTest'`. Expected: compile failure on `GRAVEKEEPER`.
- [ ] **Step 3: Implement** the Files list above.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
  - Postgres Flyway check, against a throwaway container only:

    ```bash
    docker run -d --rm --name werewolf-sdd-pg -p 127.0.0.1:55432:5432 -e POSTGRES_USER=werewolf -e POSTGRES_PASSWORD=werewolf -e POSTGRES_DB=werewolf_test postgres:16-alpine
    cd backend && SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/werewolf_test SPRING_DATASOURCE_USERNAME=werewolf SPRING_DATASOURCE_PASSWORD=werewolf ./gradlew cleanTest test --tests '*FlywayMigrationsIntegrationTest'
    docker stop werewolf-sdd-pg
    ```

    Expected: every test passes, the V33 test included (not skipped).
  - Then `./gradlew test`. Expected: 0 failures.
- [ ] **Step 5: Commit:** `feat(backend): GRAVEKEEPER role, room toggle and night result columns (V33)`.

---

### Task 2: The night turn: handler, night-init hook, sequence, dispatch

**Files:**
- Modify `model/Enums.kt`:
  - `NightSubPhase.GRAVEKEEPER_RESULT`, after `MEDIUM_RESULT`;
  - `ActionType.GRAVEKEEPER_CONFIRM`, in the night group (comment `// Night: gravekeeper`).
- Modify `game/role/RoleHandler.kt`: add
  `fun onNightInit(context: GameContext, nightPhase: NightPhase) {}`
  with a KDoc: "Hook called while a new night's NightPhase row is built, before it is saved. Mutate [nightPhase] to store per-night facts known at dusk."
- Create `game/role/GravekeeperHandler.kt`: `@Order(6) @Component`, constructor `(private val eliminationHistoryRepository: EliminationHistoryRepository)`.
  - `role = GRAVEKEEPER`.
  - `nightSubPhases() = listOf(GRAVEKEEPER_RESULT)`.
  - `acceptedActions`: `GRAVEKEEPER_CONFIRM` only when `phase == NIGHT && subPhase == GRAVEKEEPER_RESULT.name`.
  - `handle`: reject with "Actor not found", "Not the gravekeeper", "Actor is dead", "No active night phase", or "Not in GRAVEKEEPER_RESULT sub-phase"; otherwise `Success()`. Same checks as `MediumHandler`'s `MEDIUM_CONFIRM`.
  - `onNightInit`:
    - return when there is no `GRAVEKEEPER` in `context.players`, or the gravekeeper is not alive (a dead gravekeeper gets nothing stored);
    - otherwise apply the rule core from Global Constraints, using `eliminationHistoryRepository.findByGameIdAndDayNumber(context.gameId, nightPhase.dayNumber - 1)` only when `dayNumber > 1`;
    - set `gravekeeperResult`, and set `gravekeeperExiledUserId` to the row's `eliminatedUserId` (null for `NONE`).
- Modify `game/role/GuardHandler.kt`: change `@Order(6)` to `@Order(7)`.
- Modify `game/night/NightOrchestrator.kt`:
  - `activeNightRoles`: `if (room.hasGravekeeper) add(PlayerRole.GRAVEKEEPER)`. The list order does not matter; the handlers' `@Order` does.
  - `initNightInternal`: after building the `NightPhase` and before `nightPhaseRepository.save(nightPhase)`, call `handlers.filter { it.role in activeNightRoles(context.room) }.forEach { it.onNightInit(context, nightPhase) }`.
- Modify `game/action/GameActionDispatcher.kt`:
  - `ActionType.GRAVEKEEPER_CONFIRM -> nightTurn(request, context, PlayerRole.GRAVEKEEPER)`;
  - in `TURN_SUB_PHASE`, `GRAVEKEEPER_CONFIRM to NightSubPhase.GRAVEKEEPER_RESULT`.
- Modify `service/AudioService.kt`: in `mapSubPhaseToRole`, `GRAVEKEEPER_RESULT -> PlayerRole.GRAVEKEEPER`. Do **not** add it to `RESULT_SUB_PHASES` or `PICK_TO_RESULT`: it is a single-sub-phase turn with its own open and close audio, like `WITCH_ACT`.
- Modify `service/GameStateLogger.kt`: `GRAVEKEEPER_RESULT -> alivePlayers.filter { it.role == PlayerRole.GRAVEKEEPER }.map { it.userId }`.
- Modify `model/RoleDelayConfig.kt`:
  - add `GRAVEKEEPER_DEFAULT = RoleDelayConfig(15000L, 8000L)`;
  - add the `getDefaultForRole` case;
  - add the entry to the `GameConfig.roleDelays` default map.
- Modify `service/RoomService.kt`: in `buildGameConfig`, add `PlayerRole.GRAVEKEEPER to timing.applyTo(PlayerRole.GRAVEKEEPER)`.
- Fix every exhaustive `when` that kotlinc flags.
- Tests:
  - create `unit/role/GravekeeperHandlerTest.kt` (mock pattern of `MediumHandlerTest`);
  - extend `unit/service/NightOrchestratorTest.kt`, `unit/service/GameActionDispatcherTest.kt` and `unit/service/AudioPhaseMappingTest.kt`;
  - add a VotingPipeline unit test if one does not already exist (`grep -rn "idiotRevealed" backend/src/test`);
  - grep for tests that assert Guard's `@Order(6)` or a full night sequence, and update them.

**Interfaces:**
- Consumes Task 1.
- Produces:
  - `NightSubPhase.GRAVEKEEPER_RESULT`;
  - `ActionType.GRAVEKEEPER_CONFIRM`;
  - `RoleHandler.onNightInit(context, nightPhase)`;
  - `NightPhase.gravekeeperResult` and `gravekeeperExiledUserId`, set at night-init.

- [ ] **Step 1: Write the failing tests.**
  - **GravekeeperHandlerTest, `onNightInit`.** Build the `NightPhase` with the given `dayNumber` and stub the repository:
    - `night 1 stores NONE without reading history`: result `NONE`, exiled null, `verify(repo, never()).findByGameIdAndDayNumber(any(), any())`.
    - `wolf exiled yesterday reads WOLF`: parameterised over `WEREWOLF`, `WHITE_WOLF_KING`, `BLACK_WOLF_KING`, `MECHANICAL_WOLF`; night 3 reads the day-2 row. Expect `WOLF` and exiled == row user.
    - `good exiled yesterday reads GOOD`: parameterised over `VILLAGER`, `HUNTER`, `KNIGHT`.
    - `exiled mechanical wolf that learned SEER reads WOLF` (Review Focus 4).
    - `no exile row reads NONE`. This is the G2 case: shots, duels, takes, declare-outs and idiot reveals never write a row.
    - `dead gravekeeper gets nothing stored`: both fields stay null and the repository is never called.
    - `no gravekeeper in the game stores nothing`.
  - **GravekeeperHandlerTest, actions:**
    - `accepts GRAVEKEEPER_CONFIRM only in GRAVEKEEPER_RESULT`;
    - `rejects a non-gravekeeper`;
    - `rejects a dead gravekeeper`;
    - `confirm succeeds in GRAVEKEEPER_RESULT`.
  - **NightOrchestratorTest:**
    - `sequence puts GRAVEKEEPER_RESULT after the seer and before the guard`: room with seer, witch, guard and gravekeeper. Expect `[WEREWOLF_PICK, WITCH_ACT, SEER_PICK, SEER_RESULT, GRAVEKEEPER_RESULT, GUARD_PICK]`.
    - `sequence puts GRAVEKEEPER_RESULT after the medium`: the medium board gives `[..., MEDIUM_PICK, MEDIUM_RESULT, GRAVEKEEPER_RESULT, ...]`.
    - `sequence is identical with or without an exile and with a dead gravekeeper`: the same room, three contexts (alive with no history; alive with a day-1 exile; dead). `nightSequence` is equal for all three.
    - `initNight stores the result on the new night row`: the saved `NightPhase` (captor) has `gravekeeperResult` set.
    - `night-init with WAITING still stores NONE for a living gravekeeper` (Review Focus 2).
  - **GameActionDispatcherTest:** `GRAVEKEEPER_CONFIRM completes the gravekeeper turn once`, following the existing `MEDIUM_CONFIRM` case.
  - **AudioPhaseMappingTest:** `GRAVEKEEPER_RESULT plays gravekeeper open and close audio`, following the existing per-sub-phase cases (`gravekeeper_open_eyes.mp3` / `gravekeeper_close_eyes.mp3`).
  - **VotingPipeline** (only if missing): `idiot reveal writes no elimination_history`.
- [ ] **Step 2: Run and see them fail.**
  `./gradlew test --tests '*GravekeeperHandlerTest' --tests '*NightOrchestratorTest' --tests '*GameActionDispatcherTest' --tests '*AudioPhaseMappingTest'`. Expected: compile failures.
- [ ] **Step 3: Implement** the Files list above.
- [ ] **Step 4: Run** the focused tests, then `./gradlew test`. Expected: 0 failures.
  - Also run `grep -rn "eliminationHistoryRepository.save" backend/src/main`. Expected: exactly one hit, in `VotingPipeline`. This is the structural proof of G2.
- [ ] **Step 5: Commit:** `feat(backend): gravekeeper night turn, computed at night-init`.

---

### Task 3: Private state, role history, player review, god review

**Files:**
- Modify `service/GameService.kt`, in the night state block after the MEDIUM block. When `myPlayer?.role == GRAVEKEEPER && np.gravekeeperResult != null`, set `base["gravekeeperResult"]` to:

  ```
  "result"            to np.gravekeeperResult.name,          // GOOD | WOLF | NONE
  "exiledPlayerId"    to np.gravekeeperExiledUserId,
  "exiledNickname"    to (nickname or the id, null when there is no exile),
  "exiledSeatIndex"   to (seat or null),
  "history"           to [{ "round": dayNumber, "nickname": …, "isWerewolf": result == WOLF }]
  ```

  `history` covers every night whose `gravekeeperExiledUserId != null`, sorted by `dayNumber`, tonight included.
- Modify `service/RoleHistoryBuilder.kt`: add a `GRAVEKEEPER` branch with `"role" to "GRAVEKEEPER"` and entries from the completed nights where `gravekeeperExiledUserId != null`:
  `entry(dayNumber, "GRAVE", gravekeeperExiledUserId) + ("isWerewolf" to (gravekeeperResult == WOLF))`.
  Update the KDoc list.
- Modify `dto/PlayerReviewDtos.kt`: add
  `@JsonTypeName("GRAVEKEEPER") data class Gravekeeper(val exiled: PlayerSeat?, val result: GravekeeperResult?) : MyNightAction`.
- Modify `service/PlayerReviewBuilder.kt`:
  - in the action `when`, `me.role == GRAVEKEEPER -> MyNightAction.Gravekeeper(n.gravekeeperExiledUserId?.let(seat), n.gravekeeperResult)`;
  - in the knowledge pass, `knownCamps[exiled] = WOLF/GOOD` for each lived-through night with both fields set.
- Modify `dto/GameReviewDtos.kt`:
  - `data class GravekeeperTurn(val actor: ReviewPlayer, val dead: Boolean, val exiled: ReviewPlayer?, val result: GravekeeperResult?)`;
  - append `val gravekeeper: GravekeeperTurn? = null` as the **last** parameter of `ReviewNight`, so existing positional constructors still compile;
  - update its KDoc.
- Modify `service/GameReviewBuilder.kt`: `gravekeeper = holder(GRAVEKEEPER)?.let { GravekeeperTurn(refs.getValue(it.userId), deadAt(it), n.gravekeeperExiledUserId?.let(refs::get), n.gravekeeperResult) }`.
- Tests: `unit/service/GameServiceNightPhaseTest.kt`, `RoleHistoryBuilderTest.kt`, `PlayerReviewBuilderTest.kt`, `GameReviewBuilderTest.kt`.

**Interfaces:**
- Consumes Tasks 1–2.
- Produces these JSON shapes, which Tasks 4–5 type and render:
  - `nightPhase.gravekeeperResult = { result: 'GOOD'|'WOLF'|'NONE', exiledPlayerId: string|null, exiledNickname: string|null, exiledSeatIndex: number|null, history: { round: number; nickname: string; isWerewolf: boolean }[] }`;
  - `roleHistory = { role: 'GRAVEKEEPER', entries: [{ round, action: 'GRAVE', target, isWerewolf }] }`;
  - `MyNightAction { type: 'GRAVEKEEPER', exiled: PlayerSeat|null, result: 'GOOD'|'WOLF'|'NONE'|null }`;
  - `ReviewNight.gravekeeper: { actor, dead, exiled: ReviewPlayer|null, result } | null`.

- [ ] **Step 1: Write the failing tests.**
  - **GameServiceNightPhaseTest:**
    - `state serves gravekeeperResult to the gravekeeper`: result `WOLF`, the exiled seat and nickname, and one history entry (Review Focus 1);
    - `non-gravekeeper state has no gravekeeperResult`: a villager's state on the same night has no key (Review Focus 3);
    - `dead gravekeeper's night state has no gravekeeperResult` (the row's fields are null).
  - **RoleHistoryBuilderTest:** `gravekeeper history lists exiles with camp, skips NONE nights and tonight`.
  - **PlayerReviewBuilderTest:**
    - `gravekeeper night action carries the exiled seat and result`;
    - `gravekeeper learns the exiled player's camp`.
  - **GameReviewBuilderTest:**
    - `review night carries the gravekeeper turn`;
    - `gravekeeper turn is null when the role is not in the game`.
- [ ] **Step 2: Run and see them fail:** `./gradlew test --tests '*GameServiceNightPhaseTest' --tests '*RoleHistoryBuilderTest' --tests '*PlayerReviewBuilderTest' --tests '*GameReviewBuilderTest'`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the focused tests, then `./gradlew test`. Expected: 0 failures.
- [ ] **Step 5: Commit:** `feat(backend): gravekeeper result in private state, history and reviews`.

---

### Task 4: Frontend role plumbing, setup toggle, role texts, audio

**Files:**
- `frontend/src/types/index.ts`:
  - `'GRAVEKEEPER'` in the `PlayerRole` union;
  - `'GRAVEKEEPER_RESULT'` in `NightSubPhase`;
  - `NightPhaseState.gravekeeperResult?` (Task 3 shape; new `GravekeeperHistoryEntry` interface);
  - `RoleHistory.role` gains `'GRAVEKEEPER'`;
  - `RoleHistoryEntry.action` gains `'GRAVE'`;
  - the `MyNightAction` union gains the `GRAVEKEEPER` variant;
  - `ReviewNight.gravekeeper`.
- `utils/roleDefinitions.ts`: append `{ id: 'GRAVEKEEPER', emoji: '⚰️', required: false }` **after KNIGHT**, under the existing "Appended last" comment.
- The role maps:
  - `components/RoleComposition.vue` (`GOD_IDS`);
  - `ROLE_META` in `components/NightPhase.vue` and `components/RoleRevealCard.vue` (`team: 'special'`);
  - `views/DemoView.vue` role map (`守墓人`);
  - every other `Record<PlayerRole, …>` that `vue-tsc -b` flags.
- `views/GameView.vue`: add `'GRAVEKEEPER_CONFIRM'` to `NIGHT_TURN_ACTIONS`.
- `services/audioService.ts`: add `'gravekeeper_open_eyes.mp3'` and `'gravekeeper_close_eyes.mp3'` to the preload list, next to the medium files.
- `composables/useAudioService.ts`: add `'GRAVEKEEPER_RESULT'` to `HIGH_VOL_NIGHT_SUBPHASES`.
- `i18n/locales/zh.ts` and `en.ts` (keys sorted):
  - `role.GRAVEKEEPER`: `守墓人` / `Gravekeeper`.
  - `role.desc.GRAVEKEEPER`: `每晚得知前一天被投票放逐的玩家是好人還是狼人。`
  - `role.descShort.GRAVEKEEPER`: `得知昨日放逐者的陣營。` (en: the builder's translation).
  - `role.rules.GRAVEKEEPER`: verbatim, lines joined with `\n`:

    ```
    陣營：好人陣營（神職）。
    每晚得知前一天被投票放逐的玩家是好人還是狼人（白狼王、黑狼王、機械狼都算狼人）；第一晚沒有信息。
    只算投票放逐；被開槍、被決鬥、被白狼王帶走、被房主判定出局、白痴翻牌都不算，此時顯示「昨日無人被放逐」。
    ```

  - `role.rules.MECHANICAL_WOLF`: change `學到騎士沒有技能。` to `學到騎士、守墓人沒有技能。`; in en, change `Learning the Knight gives no skill.` to `Learning the Knight or the Gravekeeper gives no skill.`
- `components/RoomSetupPanel.vue`: no logic change (the toggle comes from `ROLE_DEFINITIONS`). Update the two "at most 6 gods" comments to 7.
- Tests:
  - `__tests__/roleDefinitions.test.ts`;
  - `__tests__/RoomSetupPanelRoles.test.ts`;
  - `__tests__/roleRevealCard.test.ts`;
  - `__tests__/roleComposition.test.ts`;
  - `__tests__/useAudioService.test.ts`;
  - `__tests__/gameViewNightTurns.test.ts`.

**Interfaces:**
- Consumes Task 3's JSON shapes.
- Produces the TS types that Task 5 renders, plus the `night`/`role` i18n namespace that Task 5 extends.

- [ ] **Step 1: Write the failing tests.**
  - `GRAVEKEEPER is defined last, as a non-wolf optional role`.
  - `default boards are unchanged`: the existing regression in `RoomSetupPanelRoles.test.ts` must still pass. Also add `the GRAVEKEEPER toggle turns on and is sent in the room config`.
  - `gravekeeper role card shows the rules text`: it contains `昨日無人被放逐` and `第一晚沒有信息`.
  - `mechanical wolf rules mention 守墓人`: it contains `學到騎士、守墓人沒有技能。`.
  - `RoleComposition counts GRAVEKEEPER as a god`.
  - `GRAVEKEEPER_RESULT is a high-volume night sub-phase`.
  - `GRAVEKEEPER_CONFIRM rejections count as night-turn rejections`, following the existing `MEDIUM_CONFIRM` case in `gameViewNightTurns.test.ts`.
- [ ] **Step 2: Run and see them fail:** `cd frontend && npx vitest run src/__tests__/roleDefinitions.test.ts src/__tests__/RoomSetupPanelRoles.test.ts src/__tests__/roleRevealCard.test.ts`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify:** `npx vitest run`, `npx vue-tsc -b`, `npm run lint`, `npm run format:check`. Expected: all clean.
- [ ] **Step 5: Commit:** `feat(frontend): GRAVEKEEPER role definition, texts and audio preload`.

---

### Task 5: Frontend night result screen, history, reviews

**Files:**
- Create `components/GravekeeperTurn.vue`. Mirror `MediumTurn.vue`'s RESULT block and reuse `assets/night-turn.css`.
  - Props: `gravekeeperResult?: NightPhaseState['gravekeeperResult']`, `dayNumber: number`, `actionPending?: boolean`.
  - Emits: `action: [type: 'GRAVEKEEPER_CONFIRM']`.
  - When `gravekeeperResult` is present, a card `data-testid="gravekeeper-result"` with:
    - `data-verdict`, one of `GOOD | WOLF | NO_EXILE | FIRST_NIGHT`. `NONE` maps to `FIRST_NIGHT` when `dayNumber === 1`, else to `NO_EXILE`;
    - `data-exiled-seat`;
    - for GOOD/WOLF, the exiled player's `common.seatDot` line;
    - the verdict text: `night.gravekeeper.good` `好人`, `.wolf` `狼人`, `.noExile` `昨日無人被放逐`, `.firstNight` `首夜無信息`.
  - The history list, as in the medium (`night.gravekeeper.history`, `.noHistory`).
  - Always, a confirm button `data-testid="gravekeeper-result-confirm"` (`night.gravekeeper.done`). It renders even without a result (Review Focus 5).
- Modify `components/NightPhase.vue`:
  - render `<GravekeeperTurn>` when `subPhase === 'GRAVEKEEPER_RESULT' && myRole === 'GRAVEKEEPER' && me?.isAlive && !hasActed`, passing `nightPhase.gravekeeperResult` and `nightPhase.dayNumber`, with `@action="sendNightAction"`;
  - `sendNightAction` sets `hasActed` for `GRAVEKEEPER_CONFIRM`;
  - `isMyTurn` gets the gravekeeper case;
  - `badgeSub` gets `GRAVEKEEPER_RESULT`: the gravekeeper sees `night.badge.gravekeeper`, everyone else `night.badge.closed`.
- Modify `components/RoleHistory.vue`: a `'GRAVE'` case, `roleHistory.grave` = `{who} 是{result}` (result = `roleHistory.wolf` / `roleHistory.good`). The en wording is the builder's.
- Modify `components/PlayerReview.vue`: a `'GRAVEKEEPER'` case.
  - With `exiled`: `pr.gravekeeperSaw` + name + the camp tag.
  - Otherwise: `pr.gravekeeperNone` (`無人被放逐` / `No exile`).
- Modify `components/GameReview.vue`: `gravekeeperSegs`, in the same style as `mediumSegs` (head `rv.gravekeeperHead`; dead → `rv.dead`; exiled → name + wolf/good; else `rv.gravekeeperNone`). Insert it in `nightLines` after the medium.
- i18n: the keys above in zh and en, sorted.
- Tests:
  - create `__tests__/gravekeeperTurn.test.ts`;
  - extend `nightPhase.test.ts`, `roleHistory.test.ts`, `playerReview.test.ts`, `gameReview.test.ts`.

**Interfaces:**
- Consumes Task 3 and Task 4.
- Produces the testids `gravekeeper-result` (`data-verdict`, `data-exiled-seat`) and `gravekeeper-result-confirm`, which Task 6 relies on.

- [ ] **Step 1: Write the failing tests.**
  - **gravekeeperTurn.test.ts:**
    - `renders the verdict from the nightPhase prop`, parameterised:
      - `{result:'WOLF', day 2}` → `data-verdict="WOLF"`, text `狼人`, exiled seat shown;
      - `{result:'GOOD', day 3}` → `GOOD` / `好人`;
      - `{result:'NONE', day 2}` → `NO_EXILE` / `昨日無人被放逐`;
      - `{result:'NONE', day 1}` → `FIRST_NIGHT` / `首夜無信息`.
    - `confirm emits GRAVEKEEPER_CONFIRM`.
    - `confirm button renders even without a result` (Review Focus 5).
  - **nightPhase.test.ts:**
    - `living gravekeeper sees the result screen in GRAVEKEEPER_RESULT`;
    - `dead gravekeeper sees the sleep screen`;
    - `other roles see the sleep screen in GRAVEKEEPER_RESULT`.
  - **roleHistory.test.ts:** `GRAVE entry reads "{seat} 是狼人"`.
  - **playerReview.test.ts / gameReview.test.ts:** one render case each, plus the dead and no-exile lines in `gameReview`.
- [ ] **Step 2: Run and see them fail:** `npx vitest run src/__tests__/gravekeeperTurn.test.ts src/__tests__/nightPhase.test.ts`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify:** `npx vitest run`, `npx vue-tsc -b`, `npm run lint`, `npm run format:check`. Expected: all clean.
- [ ] **Step 5: Commit:** `feat(frontend): gravekeeper night result screen, history and reviews`.

---

### Task 6: E2E helpers, scripts, real-backend spec

**Files:**
- `frontend/e2e/real/helpers/multi-browser.ts`: add `'GRAVEKEEPER'` to `allOptionalRoles`.
- `frontend/e2e/real/helpers/shell-runner.ts`: add it to the `RoleName` union and to the role regex.
- `frontend/e2e/real/helpers/role-leak.ts`: add it to `PLAYER_ROLES`.
- `frontend/e2e/real/helpers/audio-manifest.ts`:
  - `NightBoard.gravekeeper?: boolean`;
  - `nightRoleOrder` inserts `'gravekeeper'` after seer/medium and before guard;
  - update the order comment.
- `scripts/act.sh`: add `GRAVEKEEPER_CONFIRM` to the usage text and to the actor-only action case (next to `SEER_CONFIRM`).
- `scripts/roles.sh`: add the hint `"GRAVEKEEPER": "← use for GRAVEKEEPER_CONFIRM"`.
- Create `frontend/e2e/real/gravekeeper-flow.spec.ts`. Use `mech-wolf-medium-flow.spec.ts` as the template: its local `only` / `page` / `reach` / `bot` helpers, `captureSnapshot`, and the `recordPayloads` leak scan.
- `.github/workflows/ci.yml`: add the spec to integration shard 8 (`grep: ''`, so no grep change). Confirm with `npx playwright test --config=playwright.real.config.ts --list` under bash, filtered to the shard's specs, that it is collected. Add its measured runtime to the shard comment.

**Interfaces:**
- Consumes Task 5's testids and the backend sub-phase and action names.

**The spec**, one describe `Gravekeeper (real backend)` with one test, `gravekeeper sees 首夜無信息, then no exile, then the exiled wolf`:
- **Board:**
  - `totalPlayers: 9`, `hasSheriff: false`, `noKillAllowed: true`;
  - roles `['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD', 'GRAVEKEEPER']`;
  - `browserRoles: ['GRAVEKEEPER']`;
  - `onPage` records payloads for every page.

  The wolves `WOLF_SKIP` every night, so nobody dies at night and the game cannot end early.
- **Every night**, bots drive the turns in order:
  - `WOLF_SKIP` by a wolf;
  - `WITCH_ACT` with `{"useAntidote":false}` (or the payload the existing specs use for a pass);
  - `SEER_CHECK` on a non-wolf, then `SEER_CONFIRM`;
  - the gravekeeper page's step (below);
  - `GUARD_SKIP`, or `GUARD_PROTECT` on a different seat each night.

  Pick the target `wolf` as a non-host `WEREWOLF`.
- **N1:**
  1. `reach(ctx, 'GRAVEKEEPER_RESULT')`.
  2. Expect `gravekeeper-result` with `data-verdict="FIRST_NIGHT"`, then snapshot it.
  3. Click `gravekeeper-result-confirm`.
- **D1:** `completeDay(ctx, testInfo, { targetSeat: null, label: 'gk-d1' })`, so nobody is exiled.
- **N2:** expect `data-verdict="NO_EXILE"`, then confirm.
- **D2:** `completeDay(..., { targetSeat: wolf.seat, label: 'gk-d2' })`. Assert that the wolf is dead in the backend state.
- **N3:** expect `data-verdict="WOLF"` and `data-exiled-seat == wolf.seat`, then confirm.
- **After N3:**
  - `findNightOrderViolations(lines, ctx.gameId, 3, { gravekeeper: true })` returns `[]`. This proves that the step ran on every night, night 1 included, in G4 order.
  - The leak scan:
    - every non-gravekeeper page's recorded bodies contain no `"gravekeeperResult"`;
    - `scanForRoleLeaks` (or whatever the template uses) finds 0 leaks.
- `test.setTimeout(process.env.CI ? 600_000 : 300_000)`.
- Log one structured line per night: `[gravekeeper] night=N verdict=… backend={phase, subPhase, aliveCount}`.

- [ ] **Step 1: Make the helper and script edits;** run `npx tsc -p tsconfig.e2e.json`.
- [ ] **Step 2: Write the spec.**
- [ ] **Step 3: Check ports, then run it twice:**
  `lsof -iTCP:8080 -sTCP:LISTEN; lsof -iTCP:5174 -sTCP:LISTEN` (expect nothing), then
  `cd frontend && npx playwright test --config=playwright.real.config.ts e2e/real/gravekeeper-flow.spec.ts`, twice. Expected: it passes both times.
  - If it fails, follow the `debug-failed-integration-test` skill. Do not retry or skip.
  - If you find a product bug, stop and report it with evidence.
- [ ] **Step 4: Run the gates:**
  - `npx eslint -c eslint.e2e-waits.config.js e2e`;
  - `npx tsc -p tsconfig.e2e.json`;
  - `npm run lint`;
  - `npm run format:check`.
  - Re-run `mech-wolf-medium-flow.spec.ts` once: it uses `nightRoleOrder`, which this task changed.
- [ ] **Step 5: Run** `graphify update .` from the repo root.
- [ ] **Step 6: Commit:** `test(e2e): gravekeeper real-backend flow, helpers and scripts`.
