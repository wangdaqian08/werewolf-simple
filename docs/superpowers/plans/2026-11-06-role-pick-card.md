# 點將卡 (Role-pick card) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before Ready, a player in a perks-allowed room picks 不使用 / 狼人 / 神 / 村民. Dealing honors paid picks: random draw when a camp is over-subscribed. A pick is charged only if it took effect.

**Architecture:**
- A pure `RoleDealer` replaces `roles.shuffle()`.
- Paid picks are `perk_activations` rows with codes `ROLE_PICK_*`, so the existing exactly-once refund/settle gates and sweeps cover them.
- A pick that took effect is marked with the existing `triggered_at`.
- The choice itself, `NONE` included, lives on a new nullable `room_players.role_pick`. Ready and Start are gated on it.
- Picks never appear in room-wide DTOs or broadcasts.

**Tech Stack:** Kotlin / Spring Boot 4 / JPA / Flyway (Postgres prod, H2 tests), Vue 3 + TS + Pinia, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-06-role-pick-card-design.md`

## Global Constraints

- Prices: `ROLE_PICK_WOLF`, `ROLE_PICK_GOD`, `ROLE_PICK_VILLAGER`, 20 credits each. Price is read from the `perks` row, never hard-coded in logic.
- Camps:
  - WOLF = `PlayerRole.isWolf`
  - GOD = `PlayerRole.isGod`
  - VILLAGER = `PlayerRole.VILLAGER`
  - No second role list.
- Gate condition everywhere: `room.config?.perksAllowed != false` (same meaning as `PerkService.activate`).
- Migration file: `backend/src/main/resources/db/migration/V31__role_pick_card.sql`. Never edit V1–V30.
- No `!!` in `backend/src/main`. Use `?: error("...")`.
- No new Spring test contexts. Put new integration tests in an existing `@SpringBootTest @ActiveProfiles("test")` class, or in a new class with exactly that annotation pair and no `@MockBean` / `@SpyBean`.
- Backend tests run on H2 locally and Postgres in CI. Use no vendor-specific SQL in tests. Flyway is off on H2, so a test seeds the `perks` rows it needs (see `PerkServiceIntegrationTest.ensurePerk`).
- Frontend:
  - `zh.ts` is **Traditional Chinese**. Convert every spec copy line (written in Simplified) to Traditional there. `en.ts` gets English.
  - The DB `perks.name` stays Simplified, like the existing row.
- Every new UI control gets a `data-testid`. E2E uses `getByTestId` only. No `waitForTimeout` / `setTimeout` sleeps, no retry loops, no `test.skip`.
- Type-check with `npx vue-tsc -b`.
- Picks are private:
  - Never put `ROLE_PICK_*` data in `RoomDto`, `PERK_UPDATE`, `ROOM_UPDATE`, `GAME_STARTED` or any `/topic/*` broadcast.
  - Own-pick data travels only in the response to a request the owner made.
- Commit trailer: the `Co-Authored-By` attribution line the session provides. Stage individual files, never `git add -A`.

## Review Focus

1. **Switching to a card you cannot afford** (holding WOLF, choosing GOD with too little balance): the call fails and the player still holds WOLF with the same balance (the refund and the debit roll back together). Test in Task 3.
2. **Leaving or being kicked while holding a card**: the 20 come back at once (via `refundActiveForUser`). Test in Task 3.
3. **Orphaned-game recovery** puts the room back to WAITING: `role_pick` is cleared, the cancelled game's cards are REFUNDED, and the same player can choose again. Test in Task 4.
4. **The host edits the board after a pick, so the camp has 0 seats**: the game still starts, the card does not take effect, and settlement refunds it. Test in Task 1 (dealer) and Task 4 (start).
5. **Buying a role card through the generic perk endpoint** (`/api/room/perk/activate` with `ROLE_PICK_WOLF`): rejected. Test in Task 2.

---

### Task 1: `RolePickChoice` + pure `RoleDealer`

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/model/Enums.kt` (add enum after `PlayerRole`)
- Create: `backend/src/main/kotlin/com/werewolf/game/RoleDealer.kt`
- Modify: `backend/src/main/kotlin/com/werewolf/service/GameService.kt:517-531` (delete private `buildRoleList`, call `RoleDealer.roleList`)
- Test: `backend/src/test/kotlin/com/werewolf/unit/game/RoleDealerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class RolePickChoice { NONE, WOLF, GOD, VILLAGER;
      val perkCode: String?            // NONE -> null, WOLF -> "ROLE_PICK_WOLF", GOD -> "ROLE_PICK_GOD", VILLAGER -> "ROLE_PICK_VILLAGER"
      fun matches(role: PlayerRole): Boolean   // NONE -> false
      companion object {
          fun fromPerkCode(code: String): RolePickChoice?   // null for non-role-pick codes
          fun campOf(role: PlayerRole): RolePickChoice      // WOLF / GOD / VILLAGER
      }
  }
  object RoleDealer {
      fun roleList(room: Room, playerCount: Int): List<PlayerRole>          // body moved verbatim from GameService.buildRoleList
      fun campSeats(roles: List<PlayerRole>): Map<RolePickChoice, Int>      // keys WOLF, GOD, VILLAGER always present (0 allowed)
      fun deal(roles: List<PlayerRole>, playerIds: List<String>,
               picks: Map<String, RolePickChoice>, random: Random): Map<String, PlayerRole>
  }
  ```
  `random` is `kotlin.random.Random`. A player missing from `picks`, or mapped to `NONE`, is a non-picker.

- [ ] **Step 1: Write the failing tests** in `RoleDealerTest`. Use `Random(seed)` and loop seeds `0..199` where a property must hold for every draw:
  - `every role is dealt exactly once`: for 9 roles (3 WEREWOLF, SEER, WITCH, HUNTER, 3 VILLAGER) and 9 players with mixed picks, `deal(...).values.sorted() == roles.sorted()`, and the keys equal the player set.
  - `no picks is a uniform shuffle`: with empty picks, over 2000 seeds each player gets WEREWOLF between 25% and 41% of the time (expected 1/3).
  - `under-subscribed camp gives every holder that camp`: 2 WOLF pickers on a 3-wolf board → both get `isWolf` roles, for every seed.
  - `over-subscribed camp draws exactly seats-many winners at random`: 5 WOLF pickers on 3 wolf seats:
    - exactly 3 of them get wolves, for every seed;
    - each picker wins in 40–80% of 2000 seeds (expected 60%), which proves the winners are not first-come;
    - the non-wolf seats go to the 2 losers and the non-pickers.
  - `zero-seat camp never matches`: a VILLAGER picker on a board with no VILLAGER gets a non-villager role, and `RolePickChoice.VILLAGER.matches(role) == false`.
  - `exact role inside a camp is random`: one GOD picker, board with SEER + WITCH + HUNTER → over 600 seeds each of the three appears at least 120 times.
  - `campSeats counts each camp`: board [WHITE_WOLF_KING, MECHANICAL_WOLF, WEREWOLF, SEER, MEDIUM, VILLAGER] → `{WOLF=3, GOD=2, VILLAGER=1}`.
  - `matches and campOf`: MECHANICAL_WOLF → WOLF, MEDIUM → GOD, IDIOT → GOD, VILLAGER → VILLAGER. `NONE.matches(any) == false`.

- [ ] **Step 2: Run, expect a compile failure**
  `cd backend && ./gradlew test --tests 'com.werewolf.unit.game.RoleDealerTest'`

- [ ] **Step 3: Implement.** `deal` algorithm:
  1. Group role slots by `campOf`.
  2. For each camp, shuffle its holders with `random`. The first `min(seats, holders)` holders are winners. Each winner gets a random role from that camp's remaining slots.
  3. Shuffle all leftover roles and give them to everyone else (non-pickers + losers + zero-seat holders).

  In `GameService.startGame`, keep today's behavior for now: `RoleDealer.deal(RoleDealer.roleList(room, n), ids, emptyMap(), Random.Default)`. Real picks come in Task 4.

- [ ] **Step 4: Run** `RoleDealerTest` and `com.werewolf.unit.service.GameServiceStartTest`. Expect PASS.

- [ ] **Step 5: Commit** `feat(backend): RolePickChoice and pure RoleDealer`

---

### Task 2: Schema, entity, and the perk paths that must learn about role picks

**Files:**
- Create: `backend/src/main/resources/db/migration/V31__role_pick_card.sql`
- Modify: `backend/src/main/kotlin/com/werewolf/model/RoomPlayer.kt` (add `rolePick`)
- Modify: `backend/src/main/kotlin/com/werewolf/repository/Repositories.kt:246-253` (`voidWolfHolders` gets a `perkCode` param)
- Modify: `backend/src/main/kotlin/com/werewolf/service/PerkService.kt`
- Modify: `backend/src/main/kotlin/com/werewolf/service/PerkSettlementService.kt:55-58`
- Modify: `backend/src/main/kotlin/com/werewolf/service/RoomService.kt:392-395` (filter `perkActivations`)
- Test: `backend/src/test/kotlin/com/werewolf/integration/PerkServiceIntegrationTest.kt`, `PerkSettlementServiceIntegrationTest.kt`

**Interfaces:**
- Consumes: `RolePickChoice` (Task 1).
- Produces:
  - `RoomPlayer.rolePick: RolePickChoice?`. Column `role_pick`, `@Enumerated(STRING)`, length 10, nullable, default null.
  - `internal fun PerkService.refundActivation(activation: PerkActivation)`. This is today's private `refund`, renamed and made internal. The behavior is unchanged.
  - `fun PerkService.liveRolePicks(roomId: Int): Map<String, RolePickChoice>`: ACTIVE, unbound (`gameId == null`), `ROLE_PICK_*` rows in the room, mapped userId → choice.
  - `fun PerkService.myRolePick(gameId: Int, userId: String): Map<String, Any>?`: the caller's ROLE_PICK row for the game, any status. Returns `mapOf("choice" to choice.name, "tookEffect" to (triggeredAt != null), "price" to pricePaid)`, or null when there is no row.
  - `onGameStart(roomId, gameId, players)` additionally calls `markTriggered(gameId, code, userIds)` for each `ROLE_PICK_*` code, with the bound holders whose dealt role `matches` the choice.

- [ ] **Step 1: Write the migration** with exactly this content:
  ```sql
  INSERT INTO perks (perk_code, name, description, price_credits) VALUES
   ('ROLE_PICK_WOLF', '点将卡·狼人', '发牌时优先进入狼人阵营；想当狼的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。', 20),
   ('ROLE_PICK_GOD', '点将卡·神', '发牌时优先成为神职；想当神的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。', 20),
   ('ROLE_PICK_VILLAGER', '点将卡·村民', '发牌时优先成为村民；想当村民的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。', 20);

  -- Role-pick cards are many per room (one per player), not one per room.
  DROP INDEX ux_one_active_perk_per_room;
  CREATE UNIQUE INDEX ux_one_active_perk_per_room ON perk_activations (room_id, perk_code)
      WHERE status = 'ACTIVE' AND perk_code NOT LIKE 'ROLE_PICK_%';
  CREATE UNIQUE INDEX ux_one_role_pick_per_player ON perk_activations (room_id, user_id)
      WHERE status = 'ACTIVE' AND perk_code LIKE 'ROLE_PICK_%';

  ALTER TABLE room_players ADD COLUMN role_pick VARCHAR(10)
      CHECK (role_pick IN ('NONE', 'WOLF', 'GOD', 'VILLAGER'));
  ```

- [ ] **Step 2: Write the failing tests** in `PerkServiceIntegrationTest`. Add an `ensureRolePickPerks()` seeder next to `ensurePerk()`.
  - `wolf-dealt role-pick WOLF is not voided and is marked triggered`:
    - Save a ROLE_PICK_WOLF activation for user A and a NIGHT1_IMMUNITY activation for A.
    - Call `onGameStart` with A dealt WEREWOLF.
    - Expect the ROLE_PICK row ACTIVE with `triggeredAt != null`, and the immunity row VOID.
  - `role-pick that did not match stays ACTIVE and untriggered`: a GOD pick, A dealt VILLAGER.
  - `catalog excludes role-pick perks`: `catalog().map { it["perkCode"] }` contains no `ROLE_PICK_` code.
  - `generic activate rejects a role-pick code`: `activate(user, room, "ROLE_PICK_WOLF")` throws `PerkNotFoundException`. `withdraw` with a role-pick code throws `PerkNotFoundException`.
  - `room DTO and PERK_UPDATE omit role picks`:
    - With one immunity and one ROLE_PICK activation live, `roomService.getRoom(...)` / the DTO builder returns `perkActivations` with only the immunity.
    - For the broadcast: the integration class cannot capture `StompPublisher` without a new context, so assert through the DTO only. Make `broadcastPerkUpdate` use the same filter function as the DTO, so one test covers both. Name the filter `isRolePickCode(code: String): Boolean = code.startsWith("ROLE_PICK_")`.
  - `myRolePick returns choice, tookEffect and price for the caller only`: user B, who has no row, gets `null`.

  In `PerkSettlementServiceIntegrationTest`:
  - `triggered role-pick is CONSUMED, no credit`.
  - `untriggered role-pick is REFUNDED with +20 and a REFUND ledger row`.
  - `cancelled game refunds a triggered role-pick`.
  - `wolf pick that took effect plus wolf-voided immunity → pick CONSUMED, immunity REFUNDED +30`.

- [ ] **Step 3: Run, expect failures**
  `./gradlew test --tests 'com.werewolf.integration.PerkServiceIntegrationTest' --tests 'com.werewolf.integration.PerkSettlementServiceIntegrationTest'`

- [ ] **Step 4: Implement**
  - `voidWolfHolders(gameId, perkCode, wolfUserIds)` with `AND a.perkCode = :perkCode`. `onGameStart` passes `PERK_NIGHT1_IMMUNITY`.
  - `tookEffect`: `PERK_NIGHT1_IMMUNITY` and the three role-pick codes all use `a.status == ACTIVE && a.triggeredAt != null`.
  - `activate` / `withdraw` throw `PerkNotFoundException` for `isRolePickCode`.
  - `catalog()`, `broadcastPerkUpdate`, and `RoomService.buildRoomDto` filter out `isRolePickCode`.

- [ ] **Step 5: Run** the two classes plus `./gradlew test --tests 'com.werewolf.integration.FlywayMigrationsIntegrationTest'` (it runs on Postgres in CI). Expect PASS.

- [ ] **Step 6: Commit** `feat(backend): role-pick perk rows, private-by-default perk paths, settlement`

---

### Task 3: `RolePickService` + endpoints

**Files:**
- Create: `backend/src/main/kotlin/com/werewolf/service/RolePickService.kt`
- Modify: `backend/src/main/kotlin/com/werewolf/controller/PerkController.kt`
- Test: `backend/src/test/kotlin/com/werewolf/integration/RolePickServiceIntegrationTest.kt` (`@SpringBootTest @ActiveProfiles("test")` only)
- Test: `backend/src/test/kotlin/com/werewolf/integration/controller/PerkControllerTest.kt` (add cases)

**Interfaces:**
- Consumes: `RoleDealer.roleList`, `RoleDealer.campSeats`, `PerkService.refundActivation`, `WalletService.debit`, `RoomPlayer.rolePick`.
- Produces:
  ```kotlin
  data class RolePickView(
      val choice: RolePickChoice?,            // null = not chosen yet
      val frozenCredits: Int,                 // sum of pricePaid of the caller's ACTIVE, unbound activations in this room (immunity included)
      val prices: Map<RolePickChoice, Int>,   // only paid choices whose perks row exists and is active
      val seats: Map<RolePickChoice, Int>,    // campSeats(roleList(room, room.totalPlayers))
  )
  class RolePickService { fun myPick(userId: String, roomId: Int): RolePickView
                          fun choose(userId: String, roomId: Int, choice: RolePickChoice): RolePickView }
  class RolePickLockedException(message: String) : RuntimeException(message)
  class RolePickUnavailableException(message: String) : RuntimeException(message)
  ```
  - `GET /api/room/{roomId}/role-pick` returns a `RolePickView` as JSON (enum keys serialize as names).
  - `POST /api/room/role-pick` with body `RolePickRequest(roomId: Int, choice: RolePickChoice)` returns a `RolePickView`.
  - Every exception maps to `400 {"error": message}`, same style as `activate`.

- [ ] **Step 1: Write the failing tests** (`RolePickServiceIntegrationTest`):
  - `choosing WOLF debits 20 and myPick shows choice WOLF, frozenCredits 20`.
  - `choosing NONE charges nothing and records NONE`.
  - `switching WOLF to GOD refunds 20 then debits 20`. The ledger gains REFUND +20 then PERK_SPEND −20. There is exactly one ACTIVE role-pick row, and it is GOD.
  - `switching WOLF to NONE refunds 20`.
  - `same choice again is a no-op`. There is no new ledger row.
  - `insufficient credits for the new card keeps the old card` (Review Focus 1). Balance 25 → WOLF (balance 5) → GOD throws `InsufficientCreditsException`. Afterwards the choice is still WOLF, the balance is 5, and the WOLF row is still ACTIVE.
  - `choose while READY throws RolePickLockedException`.
  - `paid choice for a camp with 0 seats throws RolePickUnavailableException`. Use a board with no villagers and choose VILLAGER.
  - `perks disabled throws PerksDisabledException`. `room not WAITING throws RoomNotOpenException`. `not in room throws PlayerNotInRoomException`.
  - `two players may both hold WOLF in one room`.
  - `leaving the room refunds a held card immediately` (Review Focus 2): call `roomService.leaveRoom` and expect balance restored and the row REFUNDED.
  - `myPick prices omit a card whose perks row is missing`.

  `PerkControllerTest`: `POST /api/room/role-pick` returns 200 with a JSON body that has `choice`, `frozenCredits`, `prices.WOLF == 20`, `seats.WOLF`. An unknown choice string returns 400.

- [ ] **Step 2: Run, expect failures.**

- [ ] **Step 3: Implement `choose`**, in this order, inside one `@Transactional`:
  1. `roomRepository.findByIdForUpdate`.
  2. Run the checks in the order the tests list them.
  3. If the choice equals the current one, return.
  4. `refundActivation` on the caller's live unbound role-pick row, if any.
  5. If the choice is paid: save a `PerkActivation(perkCode = choice.perkCode, pricePaid = perk.priceCredits)` and `walletService.debit(..., CreditTxType.PERK_SPEND, perkActivationId, note = code)`. When `debit` returns null, throw `InsufficientCreditsException`; the exception rolls back step 4.
  6. Set `rolePick`, save, and return `myPick`.

  Send no broadcast.

- [ ] **Step 4: Run.** Expect PASS.

- [ ] **Step 5: Commit** `feat(backend): role-pick choose/view service and endpoints`

---

### Task 4: Gates, dealing with picks, recovery reset, own-state field — and keep every existing backend test green

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/service/RoomService.kt:258-275` (`setReady`)
- Modify: `backend/src/main/kotlin/com/werewolf/controller/RoomController.kt` (map `RolePickRequiredException` → 400 `{error}`)
- Modify: `backend/src/main/kotlin/com/werewolf/service/GameService.kt` (`startGame`, `getGameState` near line 475)
- Modify: `backend/src/main/kotlin/com/werewolf/service/OrphanedGameRecovery.kt:~103` (clear `rolePick` where ready is reset)
- Modify: every existing backend test that readies players or starts games (about 20 files under `backend/src/test/kotlin/com/werewolf/integration/**` that use `/api/room/ready` or `/api/game/start`, plus unit tests that build `RoomPlayer` fixtures for `startGame`)
- Test: `RoomServiceReadyTest`, `GameServiceStartTest`, `OrphanedGameRecoveryIntegrationTest`, `RolePickServiceIntegrationTest`

**Interfaces:**
- Consumes: `PerkService.liveRolePicks`, `PerkService.myRolePick`, `RoleDealer.deal`.
- Produces:
  - `class RolePickRequiredException(message: String) : RuntimeException(message)` in `RoomService.kt`.
  - `getGameState` gains `"myRolePick" to perkService.myRolePick(gameId, requestingUserId)`, in the requester-only part next to `"myRole"`.

- [ ] **Step 1: Write the failing tests**
  - `RoomServiceReadyTest`:
    - `ready rejected with RolePickRequiredException when perks allowed and rolePick null`;
    - `ready allowed with rolePick NONE`;
    - `ready allowed with rolePick null when perksAllowed=false`;
    - `undo ready is never gated`.
  - `GameServiceStartTest`:
    - `startGame rejects when perks allowed and any player (host included) has rolePick null`. The message is `"Every player must choose a role-pick card"`.
    - `startGame with perksAllowed=false ignores rolePick`.
    - `startGame deals picks`: mock `liveRolePicks` → `{guest1: WOLF}` on a board with one wolf, and expect guest1 dealt a wolf role.
  - `RolePickServiceIntegrationTest`: `host board edit to zero villagers after a VILLAGER pick: game starts, pick untriggered, settlement refunds 20` (Review Focus 4).
  - `OrphanedGameRecoveryIntegrationTest` (Review Focus 3): after recovery, every `rolePick` is null, the cancelled game's role-pick rows are REFUNDED, and `rolePickService.choose(sameUser, room, WOLF)` succeeds.

- [ ] **Step 2: Run, expect failures.**

- [ ] **Step 3: Implement**
  - `setReady(ready = true)`: load the `RoomPlayer`. If the gate condition holds and `rolePick == null`, throw.
  - `startGame`: put the gate right after the not-ready check. Then:
    ```kotlin
    val assignment = RoleDealer.deal(roles, ids, if (perksOn) perkService.liveRolePicks(roomId) else emptyMap(), Random.Default)
    ```
    Build each `GamePlayer` with `assignment[rp.userId]`.
  - `OrphanedGameRecovery`: set `rp.rolePick = null` in the same loop that resets ready.

- [ ] **Step 4: Fix existing tests.** For each test that creates a room over REST and is not about perks, add `"perksAllowed" to false` to its room-create config map. For tests that build `Room` / `RoomPlayer` entities directly, set `rolePick = RolePickChoice.NONE` on every player fixture, or `config.perksAllowed = false`. Do not loosen the gates.
  Run: `cd backend && ./gradlew test`. Expect the whole suite PASS.

- [ ] **Step 5: Commit** `feat(backend): gate ready/start on a role-pick choice and deal honoring picks`

---

### Task 5: Review shows every pick

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/dto/GameReviewDtos.kt` (`GameReview` gains `rolePicks`)
- Modify: `backend/src/main/kotlin/com/werewolf/service/GameReviewService.kt`, `GameReviewBuilder.kt`
- Modify: `frontend/src/types/index.ts`, `frontend/src/components/GameReview.vue`, `frontend/src/i18n/locales/{zh,en}.ts`
- Test: `backend/src/test/kotlin/com/werewolf/integration/GameReviewIntegrationTest.kt`, a `GameReview` component test under `frontend/src/__tests__/`

**Interfaces:**
- Produces:
  - Backend: `data class ReviewRolePick(val player: ReviewPlayer, val choice: RolePickChoice, val tookEffect: Boolean)` and `GameReview.rolePicks: List<ReviewRolePick>`, ordered by seat. The list includes only players with a role-pick row; NONE players are absent.
  - Frontend: `ReviewRolePick { player: ReviewPlayer; choice: 'WOLF' | 'GOD' | 'VILLAGER'; tookEffect: boolean }`, and `GameReview.rolePicks`.
  - UI: a section `data-testid="review-role-picks"` with one row per pick, `data-testid="review-role-pick-{seatIndex}"`, showing seat · name · camp · 生效/未抽中 (Traditional in zh).
  - The section is hidden when the list is empty.

- [ ] **Step 1: Write the failing tests.**
  - Backend: a finished game with A (WOLF, took effect) and B (GOD, not drawn) → `rolePicks` has 2 entries with the right flags. `getPlayerReview` is untouched.
  - Frontend: render `GameReview` with `rolePicks` and expect two rows; with `[]`, expect no section.

- [ ] **Step 2: Run, expect failures.**

- [ ] **Step 3: Implement.** Read the rows from `perkActivationRepository.findByGameId` in `GameReviewService.load` (already loaded there). `tookEffect = triggeredAt != null`.

- [ ] **Step 4: Run** `./gradlew test --tests '*GameReview*'` and `cd frontend && npx vitest run src/__tests__/<file>`. Expect PASS.

- [ ] **Step 5: Commit** `feat: show role-pick cards in the post-game review`

---

### Task 6: Room page panel (frontend)

**Files:**
- Create: `frontend/src/components/RolePickPanel.vue`
- Modify: `frontend/src/types/index.ts`, `frontend/src/services/roomService.ts`, `frontend/src/mocks/index.ts`, `frontend/src/views/RoomView.vue`, `frontend/src/i18n/locales/{zh,en}.ts`
- Test: `frontend/src/__tests__/RolePickPanel.test.ts`, `frontend/src/__tests__/RoomViewRolePick.test.ts`, `frontend/src/__tests__/roomServicePerks.test.ts` (add cases), `frontend/e2e/room.spec.ts` (mocked: choose NONE before ready)

**Interfaces:**
- Consumes: `GET /api/room/{roomId}/role-pick`, `POST /api/room/role-pick` (Task 3).
- Produces:
  ```ts
  export type RolePickChoice = 'NONE' | 'WOLF' | 'GOD' | 'VILLAGER'
  export type PaidRolePick = Exclude<RolePickChoice, 'NONE'>
  export interface RolePickView { choice: RolePickChoice | null; frozenCredits: number;
    prices: Partial<Record<PaidRolePick, number>>; seats: Record<PaidRolePick, number> }
  roomService.getRolePick(roomId: string): Promise<RolePickView>
  roomService.chooseRolePick(roomId: string, choice: RolePickChoice): Promise<RolePickView>
  ```
  - `RolePickPanel` props: `view: RolePickView`, `balance: number | null`, `locked: boolean`, `busy: boolean`, `immunityPrice: number | null` (non-null when the caller holds 首夜免死). Emits `choose(choice: RolePickChoice)`.
  - Test ids: `role-pick-panel`, `role-pick-NONE`, `role-pick-WOLF`, `role-pick-GOD`, `role-pick-VILLAGER`, `role-pick-frozen`, `role-pick-draw-note`, `role-pick-immunity-hint`, `role-pick-locked`, `role-pick-status`.

- [ ] **Step 1: Write the failing tests** (`RolePickPanel.test.ts`):
  - Four cards render. Paid cards show `◈20` and `本局 {n} 個名額` from `seats`.
  - A card with `seats[c] === 0` is disabled and shows `本局無此陣營`.
  - A paid card whose price exceeds `balance` (and is not the current choice) is disabled and shows `餘額不足` plus a link to the `account` route.
  - A card missing from `prices` is disabled and shows `暫不可用`.
  - `NONE` is never disabled except when `locked` or `busy`.
  - Clicking an enabled card emits `choose` with its value.
  - `locked` disables all cards and shows `role-pick-locked`.
  - `frozenCredits > 0` shows `role-pick-frozen` as `（凍結 {n}）`.
  - A paid choice shows `role-pick-draw-note` with that camp's name, seat count and price.
  - `choice === 'WOLF' && immunityPrice !== null` shows `role-pick-immunity-hint` with the immunity price.

  `RoomViewRolePick.test.ts`:
  - With perks allowed and `choice: null`, `ready-btn` is disabled and `role-pick-status` asks for a choice. After choosing NONE, `ready-btn` is enabled.
  - With `perksAllowed: false`, no panel renders and ready is not gated.
  - For the host, the start button stays disabled while the host's `choice` is null.
  - While `iAmReady`, the panel is `locked`.
  - After a choose call, the credits balance refreshes the same way `handleActivatePerk` does.

- [ ] **Step 2: Run, expect failures:** `cd frontend && npx vitest run src/__tests__/RolePickPanel.test.ts src/__tests__/RoomViewRolePick.test.ts`

- [ ] **Step 3: Implement**
  - Layout per the approved mockup: a 2×2 grid placed above the existing perk panel, Ink & Paper tokens. Copy per spec §7, converted to Traditional in `zh.ts`.
  - `RoomView` loads the view on mount when perks are enabled, and re-fetches it after the room's config changes (the host may edit the board).
  - Add the gate to `ready-btn` `:disabled` and to the host start condition.
  - In `mocks/index.ts`, handlers for both endpoints keep an in-memory choice.

- [ ] **Step 4: Run** the vitest files, `npx vue-tsc -b`, `npm run lint`, and `npx playwright test e2e/room.spec.ts` (mocked, port 5174; do not run while real E2E is running). Expect PASS.

- [ ] **Step 5: Commit** `feat(frontend): role-pick panel gates ready and start`

---

### Task 7: Role-reveal line (frontend)

**Files:**
- Modify: `frontend/src/types/index.ts` (`GameState.myRolePick?: { choice: PaidRolePick; tookEffect: boolean; price: number } | null`)
- Modify: `frontend/src/views/GameView.vue` (under `<RoleRevealCard>`, near line 35)
- Modify: `frontend/src/i18n/locales/{zh,en}.ts`
- Test: a GameView role-reveal test under `frontend/src/__tests__/` (reuse an existing GameView role-reveal test file if one exists)

- [ ] **Step 1: Write the failing tests.** With phase `ROLE_REVEAL`, `data-testid="role-pick-result"`:
  - `{tookEffect: true, price: 20}` shows `點將卡生效，遊戲結束扣除 20◈`.
  - `{tookEffect: false, price: 20}` shows `點將卡未抽中，20◈ 將在遊戲結束時退回`.
  - `null` / absent renders no element.

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement.** Read only `gameStore.state.myRolePick`. Never derive it from the role on the client.

- [ ] **Step 4: Run** vitest and `npx vue-tsc -b`. Expect PASS.

- [ ] **Step 5: Commit** `feat(frontend): show the holder whether their role-pick card took effect`

---

### Task 8: Scripts, E2E harness, and one real-backend E2E

**Files:**
- Modify: `scripts/join-room.sh:152,206`. Before each `/room/ready` POST, add `post "$TOKEN" "/room/role-pick" "{\"roomId\":$ROOM_ID,\"choice\":\"NONE\"}"`.
- Modify: `frontend/e2e/real/helpers/multi-browser.ts` (`setupGame`: the host clicks `role-pick-NONE` before Start)
- Modify: `frontend/e2e/real/ghost-member.spec.ts` (click `role-pick-NONE` before `ready-btn`), `frontend/e2e/real/room-ready-sync.spec.ts` (choose NONE via the API before `/room/ready`), and any other file that `grep -rn "ready-btn\|/room/ready\|game-start" frontend/e2e scripts` turns up
- Modify: `backend/src/main/kotlin/com/werewolf/controller/TestSupportController.kt` (+ `TestSupportService`): `POST /api/test-support/wallet/grant {userId, amount}` → `walletService.credit(userId, amount, CreditTxType.ADMIN_GRANT, note = "e2e")`. The existing `(e2e | test) & !prod` profile gate applies.
- Create: `backend/src/main/kotlin/com/werewolf/config/E2ePerkSeeder.kt`. `@Component @Profile("e2e & !prod")` `ApplicationRunner` that inserts the 4 `perks` rows (NIGHT1_IMMUNITY + 3 role picks, same values as V19/V31) when they are missing. The e2e profile is H2 create-drop with Flyway off, so the catalog is empty otherwise.
- Modify: `frontend/e2e/real/helpers/test-support.ts` (`grantCredits(page, userId, amount)`)
- Create: `frontend/e2e/real/role-pick-card.spec.ts`
- Modify: `.github/workflows/ci.yml` (add the spec to integration shard 9's `specs`, `grep: ''`)

**Interfaces:**
- Consumes: the testids from Tasks 6–7, and the endpoints from Task 3.

- [ ] **Step 1: Update the harness and scripts.** Then run one existing real spec locally to prove the harness still reaches the game: `cd frontend && npx playwright test --config=playwright.real.config.ts e2e/real/game-flow.spec.ts`. Expect PASS.

- [ ] **Step 2: Write `role-pick-card.spec.ts`.** Use one `test.describe` with `test.describe.configure({ mode: 'serial' })` and title `Role-pick card`. Build it from the helpers `setupGame` uses; do not add options to `setupGame`.
  - `host buys WOLF, the panel shows the frozen credits and gates nothing else`:
    - `grantCredits(host, 100)`.
    - Click `role-pick-WOLF`.
    - Expect `role-pick-frozen` to contain `20`, and `role-pick-draw-note` to be visible.
  - `bots join and ready with NONE, host starts`: run `join-room.sh <code> N --ready`, then wait for the start button to be enabled (`toBeEnabled`) and click it.
  - `host is dealt a wolf and sees the took-effect line`:
    - `waitForPhase(... 'ROLE_REVEAL')`.
    - `fetchGameState` → `myRole` is one of WEREWOLF / WHITE_WOLF_KING / BLACK_WOLF_KING / MECHANICAL_WOLF.
    - `role-pick-result` is visible on the host page.
    - A bot's `getGameState` has `myRolePick == null`, which checks privacy.
  - Ruling: settlement at game end is proven by the backend tests in Tasks 2–4, so this spec does not drive a whole game.

- [ ] **Step 3: Run it twice locally.** `npx playwright test --config=playwright.real.config.ts e2e/real/role-pick-card.spec.ts`. Expect PASS both times. Verify the shard grep with `--list`.

- [ ] **Step 4: Commit** `test(e2e): role-pick card harness updates and flow spec`

---

## After all tasks

- [ ] Full checks:
  - `cd backend && ./gradlew test`
  - `cd frontend && npx vitest run && npx vue-tsc -b && npm run lint && npx eslint -c eslint.e2e-waits.config.js e2e`
- [ ] `graphify update .`
- [ ] Do not push or open a PR until the owner asks.
