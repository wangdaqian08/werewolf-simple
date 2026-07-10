# Sheriff Speech Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sheriff-campaign candidates speak in seat-index order (ascending or descending), with the host choosing the direction during SIGNUP (default ASC); the random shuffle is removed.

**Architecture:** Direction is stored on the existing `sheriff_elections` row (new enum column, default `ASC`). A new host-only action `SHERIFF_SET_SPEECH_ORDER` (payload `{"direction":"ASC"|"DESC"}`) updates it during SIGNUP. Both SIGNUP→SPEECH transition paths (`finishSignupDecision`, legacy `startSpeech`) build the speaking order by sorting running candidates by `GamePlayer.seatIndex` per the stored direction. Frontend adds a host-only toggle in the SIGNUP view; server-authoritative — the client never computes order.

**Tech Stack:** Spring Boot 3 / Kotlin / JPA / Flyway (backend), Vue 3 + TS + Vitest (frontend), Playwright real-backend E2E.

**Spec:** `docs/superpowers/specs/2026-07-11-sheriff-speech-order-design.md`

## Global Constraints

- Enum literals must match `backend/src/main/kotlin/com/werewolf/model/Enums.kt` exactly (source of truth). Verified 2026-07-11: `SHERIFF_SET_SPEECH_ORDER` and `SpeechOrderDirection` do not yet exist anywhere; `ElectionSubPhase = { SIGNUP, SPEECH, VOTING, RESULT, TIED }`.
- Backend tests run on **H2 locally, PostgreSQL in CI** — migration SQL must be vendor-neutral.
- No `!!` in backend production Kotlin (`src/main`); use `?: error("...")`.
- Playwright locators: `getByTestId` only, never `getByText`.
- No `waitForTimeout` in real E2E specs; poll state or wait on DOM.
- Rejected actions return **HTTP 400** with `{"success": false, "error": "<reason>"}` (`GameController.submitAction`).
- Frontend action payload passes through verbatim: `GameView.action()` → `gameService.submitAction` → POST `/game/action` → `GameActionRequestDto.payload`.
- New-value parity checklist (verify-enum-values): Kotlin enum, dispatcher branch, DB migration, frontend types, `frontend/src/mocks/index.ts`, `scripts/act.sh`, docs.

---

### Task 1: Backend — deterministic ascending speaking order (default)

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/model/Enums.kt` (add `SpeechOrderDirection` after `CandidateStatus`, line ~24)
- Modify: `backend/src/main/kotlin/com/werewolf/model/SheriffElection.kt`
- Create: `backend/src/main/resources/db/migration/V21__sheriff_speech_order.sql`
- Modify: `backend/src/main/kotlin/com/werewolf/service/SheriffService.kt` (`finishSignupDecision` line ~247, `startSpeech` line ~300)
- Test: `backend/src/test/kotlin/com/werewolf/integration/SheriffElectionIntegrationTest.kt`

**Interfaces:**
- Consumes: existing `SheriffElection` entity, `GameContext.players` (`GamePlayer.seatIndex`), `SheriffCandidate`.
- Produces: `enum class SpeechOrderDirection { ASC, DESC }`; `SheriffElection.speechOrderDirection: SpeechOrderDirection` (default `ASC`); private `SheriffService.buildSpeakingOrder(election, running, context): String`. Task 2 relies on all three.

- [ ] **Step 1: Write the failing test**

Append to `SheriffElectionIntegrationTest.kt` (after the last test):

```kotlin
    // ── Speech order: default ASC ─────────────────────────────────────────────

    @Test
    fun `sheriff speeches - speaking order defaults to ascending seat order`() {
        val r = setupSheriffRoom("SOA")
        val gameId = startGameAndOpenSheriffElection(
            r.host, listOf(r.host, r.g1, r.g2, r.g3, r.g4, r.g5), r.roomId
        )

        // All five guests campaign, deliberately in scrambled order so a pass
        // can only come from seat sorting (not signup/insertion order). With 5
        // candidates the old shuffled() order matches ASC with p = 1/120.
        listOf(r.g5, r.g2, r.g4, r.g1, r.g3).forEach { p ->
            assertThat(action(p.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        }
        // Host passes → every alive player has decided → auto-advance to SPEECH
        assertThat(action(r.host.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)

        val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.SPEECH)
        // Seats: host=0, g1=1 … g5=5 (setupSheriffRoom). Ascending seat order:
        assertThat(election.speakingOrder).isEqualTo(
            listOf(r.g1, r.g2, r.g3, r.g4, r.g5).joinToString(",") { it.userId }
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.werewolf.integration.SheriffElectionIntegrationTest' 2>&1 | tail -20`
Expected: the new test FAILS (speakingOrder is shuffled, ≠ ascending) — all pre-existing tests still pass.

- [ ] **Step 3: Write minimal implementation**

`Enums.kt` — after `enum class CandidateStatus { RUNNING, QUIT }` (line 24):

```kotlin
enum class SpeechOrderDirection { ASC, DESC }
```

`SheriffElection.kt` — add constructor property after `currentSpeakerIdx`:

```kotlin
    // Host-chosen speech order direction (set during SIGNUP, default ASC).
    @Enumerated(EnumType.STRING)
    @Column(name = "speech_order_direction", nullable = false, length = 4)
    var speechOrderDirection: SpeechOrderDirection = SpeechOrderDirection.ASC,
```

`V21__sheriff_speech_order.sql`:

```sql
ALTER TABLE sheriff_elections
    ADD COLUMN speech_order_direction VARCHAR(4) NOT NULL DEFAULT 'ASC';
```

`SheriffService.kt` — add private helper (near `electSheriff`):

```kotlin
    /**
     * Speaking order = running candidates sorted by seat index, ascending or
     * descending per the host's SIGNUP-time choice (default ASC). Replaces
     * the historical shuffled() order.
     */
    private fun buildSpeakingOrder(
        election: SheriffElection,
        running: List<SheriffCandidate>,
        context: GameContext,
    ): String {
        val seatByUserId = context.players.associate { it.userId to it.seatIndex }
        val asc = running.sortedBy { seatByUserId[it.userId] ?: Int.MAX_VALUE }
        val ordered =
            if (election.speechOrderDirection == SpeechOrderDirection.DESC) asc.asReversed() else asc
        return ordered.joinToString(",") { it.userId }
    }
```

In `finishSignupDecision` replace
`election.speakingOrder = running.map { it.userId }.shuffled().joinToString(",")`
with
`election.speakingOrder = buildSpeakingOrder(election, running, context)`.

In `startSpeech` replace
`election.speakingOrder = candidates.map { it.userId }.shuffled().joinToString(",")`
with
`election.speakingOrder = buildSpeakingOrder(election, candidates, context)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.werewolf.integration.SheriffElectionIntegrationTest' 2>&1 | tail -20`
Expected: ALL PASS (existing tests use `advanceSpeechUntilVoting`, which is order-agnostic).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/werewolf/model/Enums.kt \
        backend/src/main/kotlin/com/werewolf/model/SheriffElection.kt \
        backend/src/main/resources/db/migration/V21__sheriff_speech_order.sql \
        backend/src/main/kotlin/com/werewolf/service/SheriffService.kt \
        backend/src/test/kotlin/com/werewolf/integration/SheriffElectionIntegrationTest.kt
git commit -m "feat(sheriff): deterministic ascending-seat speaking order (replaces shuffle)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Backend — `SHERIFF_SET_SPEECH_ORDER` host action + state exposure

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/model/Enums.kt` (ActionType sheriff block, line ~71-78)
- Modify: `backend/src/main/kotlin/com/werewolf/game/action/GameActionDispatcher.kt:106-111`
- Modify: `backend/src/main/kotlin/com/werewolf/service/SheriffService.kt` (`handle` when-branch line ~40, new `setSpeechOrder`, `buildState` map line ~129)
- Test: `backend/src/test/kotlin/com/werewolf/integration/SheriffElectionIntegrationTest.kt`

**Interfaces:**
- Consumes: `SpeechOrderDirection`, `SheriffElection.speechOrderDirection`, `buildSpeakingOrder` (Task 1).
- Produces: `ActionType.SHERIFF_SET_SPEECH_ORDER` accepting payload `{"direction": "ASC"|"DESC"}`; game-state key `sheriffElection.speechOrderDirection` (string `"ASC"`/`"DESC"`). Tasks 4-5 rely on both.

- [ ] **Step 1: Extend the test `action` helper with payload support**

In `SheriffElectionIntegrationTest.kt` replace the existing `action` helper with:

```kotlin
    private fun action(
        token: String,
        gameId: Int,
        actionType: String,
        targetUserId: String? = null,
        payload: Map<String, Any?>? = null,
    ) =
        restTemplate.postForEntity(
            ACTION_URL,
            HttpEntity(
                mapOf(
                    "gameId" to gameId,
                    "actionType" to actionType,
                    "targetUserId" to targetUserId,
                    "payload" to payload,
                ),
                headers(token)
            ),
            Map::class.java
        )
```

- [ ] **Step 2: Write the failing tests**

Append to `SheriffElectionIntegrationTest.kt`:

```kotlin
    // ── Speech order: host-selected DESC + guards ─────────────────────────────

    @Test
    fun `sheriff speeches - host sets DESC during SIGNUP, speaking order is descending seat order`() {
        val r = setupSheriffRoom("SOD")
        val gameId = startGameAndOpenSheriffElection(
            r.host, listOf(r.host, r.g1, r.g2, r.g3, r.g4, r.g5), r.roomId
        )

        assertThat(
            action(r.host.token, gameId, "SHERIFF_SET_SPEECH_ORDER", payload = mapOf("direction" to "DESC")).statusCode
        ).isEqualTo(HttpStatus.OK)

        // State must expose the host's choice (UI reflects it after reconnect)
        @Suppress("UNCHECKED_CAST")
        val state = restTemplate.exchange(
            "/api/game/$gameId/state",
            org.springframework.http.HttpMethod.GET,
            HttpEntity<Void>(headers(r.host.token)),
            Map::class.java,
        ).body!! as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sheriffState = state["sheriffElection"] as Map<String, Any?>
        assertThat(sheriffState["speechOrderDirection"]).isEqualTo("DESC")

        listOf(r.g5, r.g2, r.g4, r.g1, r.g3).forEach { p ->
            assertThat(action(p.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        }
        assertThat(action(r.host.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)

        val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.SPEECH)
        assertThat(election.speakingOrder).isEqualTo(
            listOf(r.g5, r.g4, r.g3, r.g2, r.g1).joinToString(",") { it.userId }
        )
    }

    @Test
    fun `sheriff speech order - non-host is rejected`() {
        val r = setupSheriffRoom("SON")
        val gameId = startGameAndOpenSheriffElection(
            r.host, listOf(r.host, r.g1, r.g2, r.g3, r.g4, r.g5), r.roomId
        )
        val resp = action(r.g1.token, gameId, "SHERIFF_SET_SPEECH_ORDER", payload = mapOf("direction" to "DESC"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(election.speechOrderDirection).isEqualTo(SpeechOrderDirection.ASC)
    }

    @Test
    fun `sheriff speech order - rejected outside SIGNUP sub-phase`() {
        val r = setupSheriffRoom("SOS")
        val gameId = startGameAndOpenSheriffElection(
            r.host, listOf(r.host, r.g1, r.g2, r.g3, r.g4, r.g5), r.roomId
        )
        // Drive to SPEECH: one candidate, everyone else passes
        assertThat(action(r.g1.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        listOf(r.host, r.g2, r.g3, r.g4, r.g5).forEach { p ->
            assertThat(action(p.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        }
        val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.SPEECH)

        val resp = action(r.host.token, gameId, "SHERIFF_SET_SPEECH_ORDER", payload = mapOf("direction" to "DESC"))
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `sheriff speech order - invalid or missing direction is rejected`() {
        val r = setupSheriffRoom("SOI")
        val gameId = startGameAndOpenSheriffElection(
            r.host, listOf(r.host, r.g1, r.g2, r.g3, r.g4, r.g5), r.roomId
        )
        assertThat(
            action(r.host.token, gameId, "SHERIFF_SET_SPEECH_ORDER", payload = mapOf("direction" to "RANDOM")).statusCode
        ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(
            action(r.host.token, gameId, "SHERIFF_SET_SPEECH_ORDER").statusCode
        ).isEqualTo(HttpStatus.BAD_REQUEST)
    }
```

Add import `com.werewolf.model.SpeechOrderDirection` to the test file's import block.

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.werewolf.integration.SheriffElectionIntegrationTest' 2>&1 | tail -20`
Expected: compile FAILS (`SHERIFF_SET_SPEECH_ORDER` unknown enum value is a runtime string here, so instead: the 4 new tests FAIL — dispatcher rejects unknown action / DTO deserialization rejects the actionType string with 400 on the *DESC-success* assertion).

- [ ] **Step 4: Write minimal implementation**

`Enums.kt` ActionType sheriff block — change line 73 from `SHERIFF_APPOINT,` to:

```kotlin
    SHERIFF_APPOINT,
    // Host sets candidate speaking order direction during SIGNUP (payload.direction = ASC|DESC)
    SHERIFF_SET_SPEECH_ORDER,
```

`GameActionDispatcher.kt` — extend the sheriff when-branch list (line ~111):

```kotlin
            ActionType.SHERIFF_ABSTAIN, ActionType.SHERIFF_END_RESULT,
            ActionType.SHERIFF_SET_SPEECH_ORDER
```

`SheriffService.handle` — add branch before the `else`:

```kotlin
        ActionType.SHERIFF_SET_SPEECH_ORDER -> setSpeechOrder(request, context)
```

`SheriffService` — new private action (place after `startSpeech`):

```kotlin
    private fun setSpeechOrder(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can set the speech order")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Speech order can only be set during SIGNUP")

        val direction = (request.payload["direction"] as? String)
            ?.let { d -> SpeechOrderDirection.entries.firstOrNull { it.name == d } }
            ?: return GameActionResult.Rejected("payload.direction must be ASC or DESC")

        election.speechOrderDirection = direction
        sheriffElectionRepository.save(election)
        broadcastSignupUpdate(context.gameId)
        return GameActionResult.Success()
    }
```

`SheriffService.buildState` — add to the returned map (next to `"speakingOrder"`):

```kotlin
            "speechOrderDirection" to election.speechOrderDirection.name,
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.werewolf.integration.SheriffElectionIntegrationTest' 2>&1 | tail -20`
Expected: ALL PASS.

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && ./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 7: Commit**

```bash
git add backend/src backend/src/test
git commit -m "feat(sheriff): SHERIFF_SET_SPEECH_ORDER host action + speechOrderDirection in state

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: CLI + docs parity (verify-enum-values checklist)

**Files:**
- Modify: `scripts/act.sh` (usage comment block ~line 38-48, examples ~line 105, validation case ~line 133-135 and ~line 194-196)
- Modify: `docs/real-backend-testing.md` (only if it enumerates sheriff actions — grep first)

**Interfaces:**
- Consumes: `SHERIFF_SET_SPEECH_ORDER` (Task 2). act.sh already supports `--payload` (WITCH_ACT pattern).
- Produces: `./scripts/act.sh SHERIFF_SET_SPEECH_ORDER --payload '{"direction":"DESC"}'` works for E2E/manual debugging.

- [ ] **Step 1: Add the action to act.sh**

In the usage comment block after the `SHERIFF_APPOINT` line add:

```
#     SHERIFF_SET_SPEECH_ORDER  host sets speaking order direction (requires --payload '{"direction":"ASC"}' or DESC; SIGNUP phase)
```

In the examples section add:

```
#   ./scripts/act.sh SHERIFF_SET_SPEECH_ORDER --payload '{"direction":"DESC"}'   # host: speak in descending seat order
```

In BOTH validation case lists, extend the sheriff line:

```
            SHERIFF_START_SPEECH SHERIFF_ADVANCE_SPEECH SHERIFF_REVEAL_RESULT SHERIFF_APPOINT SHERIFF_SET_SPEECH_ORDER
```

and

```
  SHERIFF_START_SPEECH|SHERIFF_ADVANCE_SPEECH|SHERIFF_REVEAL_RESULT|SHERIFF_APPOINT|SHERIFF_SET_SPEECH_ORDER) ;;
```

Also add a payload-required guard next to the WITCH_ACT one:

```bash
case "$ACTION_TYPE" in
  SHERIFF_SET_SPEECH_ORDER)
    [ -z "$PAYLOAD_JSON" ] && fail "'SHERIFF_SET_SPEECH_ORDER' requires --payload (e.g. {\"direction\":\"DESC\"})" ;;
esac
```

- [ ] **Step 2: Verify shell syntax**

Run: `bash -n scripts/act.sh && grep -c "SHERIFF_SET_SPEECH_ORDER" scripts/act.sh`
Expected: no syntax error; count ≥ 4.

- [ ] **Step 3: Check docs for sheriff action lists**

Run: `grep -rln "SHERIFF_START_SPEECH" docs/ README.md`
For each hit that enumerates sheriff actions, add a `SHERIFF_SET_SPEECH_ORDER` line citing `Enums.kt` as source of truth.

- [ ] **Step 4: Commit**

```bash
git add scripts/act.sh docs/ README.md
git commit -m "chore(sheriff): act.sh + docs parity for SHERIFF_SET_SPEECH_ORDER

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Frontend — host toggle in SIGNUP + unit tests

**Files:**
- Modify: `frontend/src/types/index.ts` (`SheriffElectionState`, line ~340)
- Modify: `frontend/src/components/SheriffElection.vue` (SIGNUP template block line ~37-90, emits line ~620, computed, scoped CSS)
- Modify: `frontend/src/views/GameView.vue` (sheriff emit bindings line ~78-84, handlers line ~705-735)
- Modify: `frontend/src/mocks/index.ts` (action switch, after `SHERIFF_ADVANCE_SPEECH` branch line ~654)
- Test: `frontend/src/__tests__/sheriffElection.test.ts`

**Interfaces:**
- Consumes: state key `speechOrderDirection` and action `SHERIFF_SET_SPEECH_ORDER` with `payload: { direction }` (Task 2).
- Produces: testids `speech-order-asc`, `speech-order-desc` (host-only, SIGNUP-only); component emit `'set-speech-order': [direction: 'ASC' | 'DESC']`; active button carries class `order-active`. Task 5's E2E relies on the testids + class.

- [ ] **Step 1: Write the failing unit tests**

Append a describe block to `frontend/src/__tests__/sheriffElection.test.ts`:

```typescript
describe('SheriffElection — speech order toggle (SIGNUP)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('host sees ASC/DESC toggle during SIGNUP, ASC active by default', () => {
    const wrapper = mount(SheriffElection, {
      props: { ...DEFAULT_PROPS, isHost: true, election: makeElection({ subPhase: 'SIGNUP' }) },
    })
    const asc = wrapper.find('[data-testid="speech-order-asc"]')
    const desc = wrapper.find('[data-testid="speech-order-desc"]')
    expect(asc.exists()).toBe(true)
    expect(desc.exists()).toBe(true)
    expect(asc.classes()).toContain('order-active')
    expect(desc.classes()).not.toContain('order-active')
  })

  it('toggle reflects DESC from game state', () => {
    const wrapper = mount(SheriffElection, {
      props: {
        ...DEFAULT_PROPS,
        isHost: true,
        election: makeElection({ subPhase: 'SIGNUP', speechOrderDirection: 'DESC' }),
      },
    })
    expect(wrapper.find('[data-testid="speech-order-desc"]').classes()).toContain('order-active')
    expect(wrapper.find('[data-testid="speech-order-asc"]').classes()).not.toContain('order-active')
  })

  it('clicking DESC emits set-speech-order', async () => {
    const wrapper = mount(SheriffElection, {
      props: { ...DEFAULT_PROPS, isHost: true, election: makeElection({ subPhase: 'SIGNUP' }) },
    })
    await wrapper.find('[data-testid="speech-order-desc"]').trigger('click')
    expect(wrapper.emitted('set-speech-order')).toEqual([['DESC']])
  })

  it('non-host never sees the toggle', () => {
    const wrapper = mount(SheriffElection, {
      props: { ...DEFAULT_PROPS, isHost: false, election: makeElection({ subPhase: 'SIGNUP' }) },
    })
    expect(wrapper.find('[data-testid="speech-order-asc"]').exists()).toBe(false)
  })

  it('toggle is not rendered outside SIGNUP', () => {
    const wrapper = mount(SheriffElection, {
      props: {
        ...DEFAULT_PROPS,
        isHost: true,
        election: makeElection({ subPhase: 'SPEECH', currentSpeakerId: 'u2' }),
      },
    })
    expect(wrapper.find('[data-testid="speech-order-asc"]').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/__tests__/sheriffElection.test.ts 2>&1 | tail -15`
Expected: TS error on `speechOrderDirection` (unknown property) or the 3 toggle-presence tests FAIL; pre-existing tests pass.

- [ ] **Step 3: Implement**

`frontend/src/types/index.ts` — in `SheriffElectionState` after `speakingOrder`:

```typescript
  // Host-chosen speaking order direction (default ASC); set during SIGNUP
  speechOrderDirection?: 'ASC' | 'DESC'
```

`SheriffElection.vue` — in the SIGNUP template, insert between the `signup-stats` div (ends line ~53) and `<div class="spacer" />`:

```html
      <!-- Host-only: candidate speaking order for the SPEECH sub-phase.
           Server-authoritative — this only fires SHERIFF_SET_SPEECH_ORDER;
           the backend stores it and builds the order at SIGNUP→SPEECH. -->
      <div v-if="isHost" class="order-toggle">
        <div class="order-label">发言顺序 / Speaking order</div>
        <div class="order-btns">
          <button
            class="btn btn-outline order-btn"
            :class="{ 'order-active': speechOrderDirection === 'ASC' }"
            data-testid="speech-order-asc"
            :disabled="actionPending"
            @click="emit('set-speech-order', 'ASC')"
          >
            正序 / Seat ↑
          </button>
          <button
            class="btn btn-outline order-btn"
            :class="{ 'order-active': speechOrderDirection === 'DESC' }"
            data-testid="speech-order-desc"
            :disabled="actionPending"
            @click="emit('set-speech-order', 'DESC')"
          >
            倒序 / Seat ↓
          </button>
        </div>
      </div>
```

Add to the `defineEmits` block:

```typescript
  'set-speech-order': [direction: 'ASC' | 'DESC']
```

Add computed (near `iAmCandidate`):

```typescript
const speechOrderDirection = computed(() => props.election.speechOrderDirection ?? 'ASC')
```

Add scoped CSS (match the ink-paper tokens used in the file — inspect existing `.btn-outline` usage and reuse; only additions):

```css
.order-toggle {
  margin-top: 12px;
}
.order-label {
  font-size: 12px;
  color: var(--muted, #8a7a65);
  margin-bottom: 6px;
}
.order-btns {
  display: flex;
  gap: 8px;
}
.order-btn {
  flex: 1;
}
.order-btn.order-active {
  border-color: var(--gold, #a07830);
  color: var(--gold, #a07830);
  font-weight: 700;
}
```

`GameView.vue` — on the `<SheriffElection>` element add:

```html
        @set-speech-order="handleSheriffSetSpeechOrder"
```

and with the other sheriff handlers:

```typescript
async function handleSheriffSetSpeechOrder(direction: 'ASC' | 'DESC') {
  await action({ actionType: 'SHERIFF_SET_SPEECH_ORDER', payload: { direction } })
}
```

`frontend/src/mocks/index.ts` — after the `SHERIFF_ADVANCE_SPEECH` branch add:

```typescript
      } else if (actionType === 'SHERIFF_SET_SPEECH_ORDER') {
        const direction =
          (payload as { direction?: 'ASC' | 'DESC' } | undefined)?.direction ?? 'ASC'
        mockGameState = {
          ...mockGameState,
          sheriffElection: { ...e, speechOrderDirection: direction },
        }
        pushGameStateUpdate()
```

- [ ] **Step 4: Run unit tests + typecheck**

Run: `cd frontend && npx vitest run src/__tests__/sheriffElection.test.ts 2>&1 | tail -10 && npx vue-tsc --noEmit 2>&1 | tail -5`
Expected: all sheriffElection tests PASS (if the pre-existing "SIGNUP renders only decision progress" test asserts absence of extra elements and now fails, update that test to allow the host toggle — it is host-visible chrome, not candidate identity leakage); typecheck clean.

- [ ] **Step 5: Run the full frontend unit suite + lint**

Run: `cd frontend && npx vitest run 2>&1 | tail -5 && npx eslint src/components/SheriffElection.vue src/views/GameView.vue src/mocks/index.ts src/types/index.ts 2>&1 | tail -5`
Expected: all PASS, no lint errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(sheriff): host ASC/DESC speech-order toggle in SIGNUP view

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Real E2E — verify DESC order end-to-end

**Files:**
- Modify: `frontend/e2e/real/sheriff-flow.spec.ts` (test 2 line ~98-202, test 3 line ~219-302)

**Interfaces:**
- Consumes: testids `speech-order-desc` + class `order-active` (Task 4); state keys `sheriffElection.speechOrderDirection`, `sheriffElection.speakingOrder`, `players[].seatIndex` (Task 2).
- Produces: CI-gated proof that host-selected DESC ordering reaches the DB-built speaking order. Spec is already in the ci.yml shard matrix — extending it (not adding a new spec) avoids the silent-never-runs trap.

- [ ] **Step 1: Read the write-real-e2e-test skill, then extend test 2 (host sets DESC during SIGNUP)**

In test 2, after the decision-progress assertion (line ~192) and before `test2CampaignerUserIds` is recorded, add a DOM-driven host action:

```typescript
    // Host chooses DESCENDING speaking order while still in SIGNUP. DOM-driven
    // (e2e principle 1): click the toggle on the host page, then assert both
    // the active-state class flip AND the state round-trip — either alone can
    // false-pass (class could be local-only; state could change without UI).
    const descBtn = ctx.hostPage.getByTestId('speech-order-desc')
    await expect(descBtn).toBeVisible({ timeout: 10_000 })
    await descBtn.click()
    await expect(descBtn).toHaveClass(/order-active/, { timeout: 10_000 })
    await waitForCondition(
      async () => {
        const state = await ctx.hostPage.evaluate(async (id: string) => {
          const token = localStorage.getItem('jwt')
          const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          return res.ok ? res.json() : null
        }, ctx.gameId)
        return state?.sheriffElection?.speechOrderDirection === 'DESC'
      },
      'sheriffElection.speechOrderDirection to round-trip as DESC',
      10_000,
    )
```

- [ ] **Step 2: Extend test 3 (assert descending seat order in SPEECH)**

In test 3, right after the SPEECH `waitForCondition` (line ~268), add:

```typescript
    // Host chose DESC in test 2 → speakingOrder must be strictly descending
    // by seat index. Read order + seats in one state fetch and assert the
    // mapped seat sequence. This is the feature's core end-to-end contract:
    // DOM toggle → REST action → DB column → order builder → state.
    const { orderSeats } = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      const state = await res.json()
      const seatByUserId = new Map(
        ((state?.players ?? []) as Array<{ userId: string; seatIndex: number }>).map((p) => [
          p.userId,
          p.seatIndex,
        ]),
      )
      const order = (state?.sheriffElection?.speakingOrder ?? []) as string[]
      return { orderSeats: order.map((uid) => seatByUserId.get(uid) ?? -1) }
    }, ctx.gameId)
    expect(orderSeats.length, 'speaking order must not be empty').toBeGreaterThan(1)
    expect(orderSeats, 'no unmapped userIds in speaking order').not.toContain(-1)
    const sortedDesc = [...orderSeats].sort((a, b) => b - a)
    expect(orderSeats, 'speaking order must be descending by seat').toEqual(sortedDesc)

    // And the first current speaker shown must be the highest-seat candidate.
    const firstSpeakerSeat = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      const state = await res.json()
      const cur = state?.sheriffElection?.currentSpeakerId as string | undefined
      const players = (state?.players ?? []) as Array<{ userId: string; seatIndex: number }>
      return players.find((p) => p.userId === cur)?.seatIndex ?? -1
    }, ctx.gameId)
    expect(firstSpeakerSeat).toBe(orderSeats[0])
```

Note: `expect(orderSeats.length).toBeGreaterThan(1)` — test 2 produces ≥ 2 running candidates (1 DOM signup + ≥1 bot campaigners), so a descending-vs-ascending distinction is real, not vacuous.

- [ ] **Step 3: Run the spec locally against the real backend**

Follow `memory/reference_real_e2e_testing.md` for the local harness (start backend + DB first; check no stale dev backend on 8080). Then:

Run: `cd frontend && npx playwright test e2e/real/sheriff-flow.spec.ts 2>&1 | tail -20`
Expected: all 5 tests PASS. If a failure occurs, invoke the `debug-failed-integration-test` skill — do NOT label it flake, do NOT add retries or timeouts.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/real/sheriff-flow.spec.ts
git commit -m "test(e2e): verify host-selected DESC sheriff speech order end-to-end

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Full verification + graph update

**Files:** none new.

- [ ] **Step 1: Full backend suite**

Run: `cd backend && ./gradlew test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full frontend unit suite + typecheck + lint**

Run: `cd frontend && npx vitest run 2>&1 | tail -5 && npx vue-tsc --noEmit 2>&1 | tail -3`
Expected: all PASS.

- [ ] **Step 3: Update knowledge graph**

Run: `graphify update .`
Expected: graph updated without errors.

- [ ] **Step 4: Push branch + open PR**

Use the superpowers:finishing-a-development-branch skill. PR title: `feat(sheriff): host-chosen ASC/DESC candidate speaking order`. Note in the body: sheriff mock-UI specs are known-flaky on local Mac (memory: sheriff-ui-specs-fail-locally) — CI is the gate.
