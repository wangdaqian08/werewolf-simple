# In-Game Room Info Modal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Any player in a running game can tap an ℹ️ button (day/voting phases) to open a modal showing the room code and the full game settings; the data is served on game state only to players of that game.

**Architecture:** Server-authoritative. `GameService.getGameState` gains a nested `gameSettings` map (room code, player count, wolf count, role list, sheriff flag, witch self-save, win condition) built from the already-loaded `Room`, included only when the requester is a game player. The frontend adds a display-only `GameSettingsModal.vue` (cloned from the `ActionLogDrawer` Teleport/Transition pattern) triggered by a `settings-fab` button beside the existing `log-fab` in `DayPhase.vue` and `VotingPhase.vue`.

**Tech Stack:** Spring Boot 3 / Kotlin, Mockito service tests; Vue 3 + TS, Vitest + @vue/test-utils; Playwright real-backend E2E.

**Spec:** `docs/superpowers/specs/2026-07-11-game-info-modal-design.md`

## Global Constraints

- Server-authoritative: the modal is pure presentation; no game logic, no client-side derivation of settings.
- `gameSettings` is included in the state response **only when the requester is a player in the game** (`myPlayer != null`); non-members get `null` for the key — never a 403.
- Enum literals (`PlayerRole` names, `WinConditionMode` `CLASSIC | HARD_MODE`) must match `backend/src/main/kotlin/com/werewolf/model/Enums.kt` exactly.
- E2E locators: `getByTestId` only. No `waitForTimeout`. Positive assertions.
- Surgical changes: do not restyle/refactor `log-fab`, `ActionLogDrawer`, or phase components beyond inserting the button + modal mount.
- No new endpoint, no DB migration, no new ActionType.
- Frontend testids (exact): `settings-fab`, `settings-modal`, `settings-backdrop`, `settings-close`, `settings-room-code`, `settings-player-count`, `settings-roles`, `settings-role-{ROLEID}`, `settings-sheriff`, `settings-witch-self-save`, `settings-win-condition`, `settings-empty`.
- Backend `gameSettings` keys (exact): `roomCode`, `totalPlayers`, `wolfCount`, `roles`, `hasSheriff`, `witchSelfSaveAllowed`, `winCondition`.
- Commands: backend tests `cd backend && ./gradlew test --tests '<Class>'`; frontend unit `cd frontend && npx vitest run <file>`; typecheck `cd frontend && npx vue-tsc --noEmit`.

---

### Task 1: Backend — `gameSettings` block on game state

**Files:**
- Create: `backend/src/test/kotlin/com/werewolf/integration/GameSettingsStateTest.kt`
- Modify: `backend/src/main/kotlin/com/werewolf/service/GameService.kt` (map built at ~line 357; helper `buildRoleList` at ~line 416)

**Interfaces:**
- Consumes: existing `GameService.getGameState(gameId, requestingUserId): Map<String, Any?>`; `room` local (nullable `Room`) and `myPlayer` local (nullable `GamePlayer`) already in scope in `getGameState`; existing `buildRoleList(room: Room, playerCount: Int): MutableList<PlayerRole>`.
- Produces: `"gameSettings"` key in the state map — `null` for non-members; for members a `Map<String, Any?>` with keys `roomCode: String`, `totalPlayers: Int`, `wolfCount: Int`, `roles: List<String>`, `hasSheriff: Boolean`, `witchSelfSaveAllowed: Boolean`, `winCondition: String`. Tasks 2–4 depend on these exact key names.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/kotlin/com/werewolf/integration/GameSettingsStateTest.kt`. The harness (mocks + `GameService` construction) is cloned from `DayPhaseResultRevealTest.kt` — same package, same lenient Mockito settings:

```kotlin
package com.werewolf.integration

import com.werewolf.audio.AudioReplayCache
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.DayRevealAdvancer
import com.werewolf.game.timer.HostTimerService
import com.werewolf.game.timer.TimerSnapshot
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.GameService
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.*

/**
 * getGameState "gameSettings" block: room code + full game config,
 * visible only to players who are in the game (spec
 * docs/superpowers/specs/2026-07-11-game-info-modal-design.md).
 */
@ExtendWith(MockitoExtension::class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GameSettingsStateTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var sheriffService: SheriffService
    @Mock lateinit var audioReplayCache: AudioReplayCache
    @Mock lateinit var hostTimerService: HostTimerService
    @Mock lateinit var dayRevealAdvancer: DayRevealAdvancer

    private lateinit var gameService: GameService

    private val gameId = 1
    private val hostId = "host:001"

    @BeforeEach
    fun setUp() {
        gameService = GameService(
            gameRepository = gameRepository,
            roomRepository = roomRepository,
            roomPlayerRepository = roomPlayerRepository,
            gamePlayerRepository = gamePlayerRepository,
            stompPublisher = stompPublisher,
            nightOrchestrator = nightOrchestrator,
            userRepository = userRepository,
            sheriffService = sheriffService,
            nightPhaseRepository = nightPhaseRepository,
            voteRepository = voteRepository,
            eliminationHistoryRepository = eliminationHistoryRepository,
            audioReplayCache = audioReplayCache,
            hostTimerService = hostTimerService,
            dayRevealAdvancer = dayRevealAdvancer,
            creditTransactionRepository = mock(),
            walletService = mock(),
            perkService = mock(),
        )
        whenever(hostTimerService.snapshot(any())).thenReturn(TimerSnapshot(0L, 0L, false))
        whenever(roomPlayerRepository.findByRoomId(any())).thenReturn(emptyList())
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(any(), any()))
            .thenReturn(Optional.empty())
        whenever(nightOrchestrator.computePendingKills(any(), any())).thenReturn(emptyList())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun game() = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.DAY_DISCUSSION
        it.subPhase = DaySubPhase.RESULT_REVEALED.name
        it.dayNumber = 2
    }

    private fun room(
        winCondition: WinConditionMode = WinConditionMode.CLASSIC,
        config: GameConfig? = null,
    ) = Room(
        roomCode = "358",
        hostUserId = hostId,
        totalPlayers = 6,
        wolfCount = 2,
        hasSeer = true,
        hasWitch = true,
        hasHunter = false,
        hasGuard = true,
        hasIdiot = false,
        hasSheriff = true,
        winCondition = winCondition,
        config = config,
    )

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role)

    /** 6 players so buildRoleList pads with exactly one VILLAGER: 2×WOLF + SEER + WITCH + GUARD + VILLAGER. */
    private fun sixPlayers() = listOf(
        player(hostId, 0, PlayerRole.WEREWOLF),
        player("g1", 1, PlayerRole.WEREWOLF),
        player("g2", 2, PlayerRole.SEER),
        player("g3", 3, PlayerRole.WITCH),
        player("g4", 4, PlayerRole.GUARD),
        player("g5", 5, PlayerRole.VILLAGER),
    )

    private fun stubGame(room: Room, players: List<GamePlayer>) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game()))
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(userRepository.findAllById(any()))
            .thenReturn(players.map { User(userId = it.userId, nickname = it.userId) })
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `gameSettings present with room code and full config for a player in the game`() {
        stubGame(room(), sixPlayers())

        val result = gameService.getGameState(gameId, hostId)

        val gs = result["gameSettings"] as? Map<*, *>
        assertThat(gs).isNotNull
        assertThat(gs?.get("roomCode")).isEqualTo("358")
        assertThat(gs?.get("totalPlayers")).isEqualTo(6)
        assertThat(gs?.get("wolfCount")).isEqualTo(2)
        assertThat(gs?.get("hasSheriff")).isEqualTo(true)
        // config is null → defaults to true
        assertThat(gs?.get("witchSelfSaveAllowed")).isEqualTo(true)
        assertThat(gs?.get("winCondition")).isEqualTo("CLASSIC")

        @Suppress("UNCHECKED_CAST")
        val roles = (gs?.get("roles") as List<String>).sorted()
        assertThat(roles).isEqualTo(
            listOf("GUARD", "SEER", "VILLAGER", "WEREWOLF", "WEREWOLF", "WITCH"),
        )
    }

    @Test
    fun `gameSettings reflects witchSelfSaveAllowed=false and HARD_MODE win condition`() {
        stubGame(
            room(
                winCondition = WinConditionMode.HARD_MODE,
                config = GameConfig(witchSelfSaveAllowed = false),
            ),
            sixPlayers(),
        )

        val result = gameService.getGameState(gameId, hostId)

        val gs = result["gameSettings"] as? Map<*, *>
        assertThat(gs).isNotNull
        assertThat(gs?.get("witchSelfSaveAllowed")).isEqualTo(false)
        assertThat(gs?.get("winCondition")).isEqualTo("HARD_MODE")
    }

    @Test
    fun `gameSettings is null for an authenticated user who is not in the game`() {
        stubGame(room(), sixPlayers())

        val result = gameService.getGameState(gameId, "stranger:999")

        // Response still succeeds — public state is served, only the settings block is withheld.
        assertThat(result["phase"]).isEqualTo("DAY_DISCUSSION")
        assertThat(result["gameSettings"]).isNull()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.werewolf.integration.GameSettingsStateTest'`
Expected: FAIL — all three tests; first two because `gameSettings` is absent (`gs` is null), third passes trivially? No — assertion `result["gameSettings"]` is null passes, but the first two fail. Confirm at least the two member tests fail with `Expecting actual not to be null`.

- [ ] **Step 3: Implement — add the block to `getGameState`**

In `backend/src/main/kotlin/com/werewolf/service/GameService.kt`, inside the returned `mapOf(...)` (starts ~line 357), add one entry after `"witchSelfSaveAllowed" to ...` (line ~376):

```kotlin
            // Room code + full game config for the in-game info modal.
            // Members only: non-members get null (never a 403) so existing
            // bots/scripts that poll state as outsiders keep working.
            "gameSettings" to if (myPlayer != null && room != null) mapOf(
                "roomCode" to room.roomCode,
                "totalPlayers" to room.totalPlayers,
                "wolfCount" to room.wolfCount,
                "roles" to buildRoleList(room, players.size).map { it.name },
                "hasSheriff" to room.hasSheriff,
                "witchSelfSaveAllowed" to (room.config?.witchSelfSaveAllowed ?: true),
                "winCondition" to room.winCondition.name,
            ) else null,
```

Note: `room` is the nullable local loaded at ~line 113; `myPlayer` the nullable local at ~line 115; `players` the full player list. Use the exact local names found in the file.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.werewolf.integration.GameSettingsStateTest'`
Expected: PASS (3/3)

- [ ] **Step 5: Run the full backend suite (regression)**

Run: `cd backend && ./gradlew test`
Expected: PASS. (Tests run on H2 locally, Postgres in CI — no SQL added here, so no vendor concern.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/kotlin/com/werewolf/integration/GameSettingsStateTest.kt backend/src/main/kotlin/com/werewolf/service/GameService.kt
git commit -m "feat(backend): gameSettings block on game state for in-game players"
```

---

### Task 2: Frontend — `GameSettings` type + `GameSettingsModal.vue` + unit tests

**Files:**
- Modify: `frontend/src/types/index.ts` (add `GameSettings` interface near `RoomConfig` ~line 118; add field to `GameState` ~line 242)
- Create: `frontend/src/components/GameSettingsModal.vue`
- Test: `frontend/src/__tests__/gameSettingsModal.test.ts`

**Interfaces:**
- Consumes: Task 1's wire keys (`roomCode`, `totalPlayers`, `wolfCount`, `roles`, `hasSheriff`, `witchSelfSaveAllowed`, `winCondition`); `ROLE_DEFINITIONS` from `frontend/src/utils/roleDefinitions.ts` (`{ id, nameZh, nameEn, emoji, required }[]`); existing `WinConditionMode` type (`types/index.ts:116`).
- Produces: `interface GameSettings`; `GameState.gameSettings?: GameSettings | null`; component `GameSettingsModal` with props `{ settings: GameSettings | null | undefined; open: boolean }` and emit `close: []`. Task 3 mounts it.

- [ ] **Step 1: Add the type (small, no test of its own — exercised by component tests)**

In `frontend/src/types/index.ts`, after the `RoomConfig` interface (~line 128), add:

```ts
/**
 * Room code + game config surfaced on game state for the in-game info modal.
 * Backend: GameService.getGameState "gameSettings" — present only when the
 * requesting user is a player in the game.
 */
export interface GameSettings {
  roomCode: string
  totalPlayers: number
  wolfCount: number
  /** Full per-seat role multiset, PlayerRole names (e.g. ["WEREWOLF","WEREWOLF","SEER",...]). */
  roles: string[]
  hasSheriff: boolean
  witchSelfSaveAllowed: boolean
  winCondition: WinConditionMode
}
```

In `GameState` (after `witchSelfSaveAllowed?: boolean`, ~line 242), add:

```ts
  /** Room code + config for the in-game info modal; null/absent for non-members. */
  gameSettings?: GameSettings | null
```

- [ ] **Step 2: Write the failing unit tests**

Create `frontend/src/__tests__/gameSettingsModal.test.ts`:

```ts
/**
 * Unit tests for GameSettingsModal.vue — in-game room info modal.
 *
 * Covers: room code + every config field rendered, role aggregation to
 * counts, close emits (backdrop + ✕), null-settings placeholder.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import GameSettingsModal from '@/components/GameSettingsModal.vue'
import type { GameSettings } from '@/types'

const SETTINGS: GameSettings = {
  roomCode: '358',
  totalPlayers: 9,
  wolfCount: 3,
  roles: [
    'WEREWOLF', 'WEREWOLF', 'WEREWOLF',
    'SEER', 'WITCH', 'GUARD',
    'VILLAGER', 'VILLAGER', 'VILLAGER',
  ],
  hasSheriff: false,
  witchSelfSaveAllowed: true,
  winCondition: 'HARD_MODE',
}

function mountModal(overrides: Partial<{ settings: GameSettings | null; open: boolean }> = {}) {
  return mount(GameSettingsModal, {
    props: { settings: SETTINGS, open: true, ...overrides },
    attachTo: document.body,
  })
}

const bodyEl = (testid: string) => document.body.querySelector(`[data-testid="${testid}"]`)

describe('GameSettingsModal — content', () => {
  it('renders room code and all config fields when open', () => {
    const wrapper = mountModal()

    expect(bodyEl('settings-modal')).not.toBeNull()
    expect(bodyEl('settings-room-code')?.textContent).toContain('358')
    expect(bodyEl('settings-player-count')?.textContent).toContain('9')
    expect(bodyEl('settings-sheriff')?.textContent).toContain('无')
    expect(bodyEl('settings-witch-self-save')?.textContent).toContain('允许')
    expect(bodyEl('settings-win-condition')?.textContent).toContain('屠城')

    wrapper.unmount()
  })

  it('aggregates the roles multiset into per-role counts', () => {
    const wrapper = mountModal()

    expect(bodyEl('settings-role-WEREWOLF')?.textContent).toContain('狼人')
    expect(bodyEl('settings-role-WEREWOLF')?.textContent).toContain('×3')
    expect(bodyEl('settings-role-SEER')?.textContent).toContain('×1')
    expect(bodyEl('settings-role-VILLAGER')?.textContent).toContain('×3')
    // Roles not in the game are not listed
    expect(bodyEl('settings-role-HUNTER')).toBeNull()
    expect(bodyEl('settings-role-IDIOT')).toBeNull()

    wrapper.unmount()
  })

  it('renders CLASSIC win condition as 屠边 and sheriff-enabled as 有', () => {
    const wrapper = mountModal({
      settings: { ...SETTINGS, hasSheriff: true, winCondition: 'CLASSIC' },
    })

    expect(bodyEl('settings-sheriff')?.textContent).toContain('有')
    expect(bodyEl('settings-win-condition')?.textContent).toContain('屠边')

    wrapper.unmount()
  })

  it('renders a placeholder (no crash) when settings is null', () => {
    const wrapper = mountModal({ settings: null })

    expect(bodyEl('settings-modal')).not.toBeNull()
    expect(bodyEl('settings-empty')).not.toBeNull()
    expect(bodyEl('settings-room-code')).toBeNull()

    wrapper.unmount()
  })

  it('renders nothing when closed', () => {
    const wrapper = mountModal({ open: false })
    expect(bodyEl('settings-modal')).toBeNull()
    wrapper.unmount()
  })
})

describe('GameSettingsModal — close behaviour', () => {
  it('emits close when the ✕ button is clicked', async () => {
    const wrapper = mountModal()
    ;(bodyEl('settings-close') as HTMLElement).click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('emits close when the backdrop is clicked', async () => {
    const wrapper = mountModal()
    ;(bodyEl('settings-backdrop') as HTMLElement).click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/__tests__/gameSettingsModal.test.ts`
Expected: FAIL — cannot resolve `@/components/GameSettingsModal.vue`.

- [ ] **Step 4: Create the component**

Create `frontend/src/components/GameSettingsModal.vue` (pattern cloned from `ActionLogDrawer.vue` — Teleport, fade backdrop, slide-up panel, ink-paper tokens):

```vue
<template>
  <Teleport to="body">
    <!-- Backdrop -->
    <Transition name="fade">
      <div
        v-if="open"
        class="settings-backdrop"
        data-testid="settings-backdrop"
        @click="$emit('close')"
      />
    </Transition>

    <!-- Panel -->
    <Transition name="slide-up">
      <div v-if="open" class="game-settings-modal" data-testid="settings-modal">
        <div class="modal-handle" />

        <div class="modal-header">
          <span class="modal-title">房间信息</span>
          <button class="modal-close" data-testid="settings-close" @click="$emit('close')">
            ✕
          </button>
        </div>

        <div class="modal-body">
          <template v-if="settings">
            <!-- Room code — displayed large so other players can be invited to watch -->
            <div class="room-code-block">
              <div class="block-label">房间号 · Room Code</div>
              <div class="room-code-value" data-testid="settings-room-code">
                {{ settings.roomCode }}
              </div>
            </div>

            <!-- Role composition -->
            <div class="settings-section">
              <div class="section-title">角色配置 · Roles</div>
              <div class="role-rows" data-testid="settings-roles">
                <div
                  v-for="rc in roleCounts"
                  :key="rc.id"
                  class="role-row"
                  :data-testid="`settings-role-${rc.id}`"
                >
                  <span class="role-emoji" aria-hidden="true">{{ rc.emoji }}</span>
                  <span class="role-name">{{ rc.nameZh }}</span>
                  <span class="role-count">×{{ rc.count }}</span>
                </div>
              </div>
            </div>

            <!-- Rules -->
            <div class="settings-section">
              <div class="section-title">规则 · Rules</div>
              <div class="rule-row" data-testid="settings-player-count">
                <span class="rule-label">玩家人数</span>
                <span class="rule-value">{{ settings.totalPlayers }}</span>
              </div>
              <div class="rule-row" data-testid="settings-sheriff">
                <span class="rule-label">警长</span>
                <span class="rule-value">{{ settings.hasSheriff ? '有' : '无' }}</span>
              </div>
              <div class="rule-row" data-testid="settings-witch-self-save">
                <span class="rule-label">女巫自救</span>
                <span class="rule-value">{{ settings.witchSelfSaveAllowed ? '允许' : '禁止' }}</span>
              </div>
              <div class="rule-row" data-testid="settings-win-condition">
                <span class="rule-label">胜利条件</span>
                <span class="rule-value">
                  {{ settings.winCondition === 'HARD_MODE' ? '屠城' : '屠边' }}
                </span>
              </div>
            </div>
          </template>

          <div v-else class="settings-empty" data-testid="settings-empty">暂无数据</div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import type { GameSettings } from '@/types'
import { ROLE_DEFINITIONS } from '@/utils/roleDefinitions'

const props = defineProps<{ settings: GameSettings | null | undefined; open: boolean }>()
defineEmits<{ close: [] }>()

interface RoleCount {
  id: string
  nameZh: string
  emoji: string
  count: number
}

// Aggregate the per-seat multiset (["WEREWOLF","WEREWOLF","SEER",...]) into
// display rows, ordered by ROLE_DEFINITIONS; unknown ids appended raw so a
// future backend role never renders as a blank row.
const roleCounts = computed<RoleCount[]>(() => {
  const counts = new Map<string, number>()
  for (const r of props.settings?.roles ?? []) counts.set(r, (counts.get(r) ?? 0) + 1)

  const ordered: RoleCount[] = []
  for (const def of ROLE_DEFINITIONS) {
    const count = counts.get(def.id)
    if (count) {
      ordered.push({ id: def.id, nameZh: def.nameZh, emoji: def.emoji, count })
      counts.delete(def.id)
    }
  }
  for (const [id, count] of counts) ordered.push({ id, nameZh: id, emoji: '❓', count })
  return ordered
})
</script>

<style scoped>
.settings-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 200;
}

.game-settings-modal {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  max-height: 70vh;
  background: var(--paper, #f5f0e8);
  border-radius: 16px 16px 0 0;
  z-index: 201;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-handle {
  width: 36px;
  height: 4px;
  background: var(--border, #ccc2b0);
  border-radius: 2px;
  margin: 10px auto 0;
  flex-shrink: 0;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px 8px;
  border-bottom: 1px solid var(--border-l, #ddd6c6);
}

.modal-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 17px;
  font-weight: 700;
  color: var(--text, #1a140c);
}

.modal-close {
  background: none;
  border: none;
  font-size: 18px;
  color: var(--muted, #8a7a65);
  cursor: pointer;
  padding: 4px 8px;
}

.modal-body {
  overflow-y: auto;
  padding: 14px 16px 24px;
}

.room-code-block {
  text-align: center;
  padding: 8px 0 14px;
}

.block-label {
  font-size: 12px;
  color: var(--muted, #8a7a65);
  margin-bottom: 4px;
}

.room-code-value {
  font-family: 'Noto Serif SC', serif;
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 8px;
  color: var(--red, #b5251a);
}

.settings-section {
  margin-top: 12px;
}

.section-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 14px;
  font-weight: 700;
  color: var(--gold, #a07830);
  margin-bottom: 6px;
}

.role-rows {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
}

.role-row {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: var(--card, #ffffff);
  border: 1px solid var(--border-l, #ddd6c6);
  border-radius: 999px;
  font-size: 14px;
  color: var(--text, #1a140c);
}

.role-count {
  color: var(--muted, #8a7a65);
}

.rule-row {
  display: flex;
  justify-content: space-between;
  padding: 7px 2px;
  border-bottom: 1px solid var(--border-l, #ddd6c6);
  font-size: 14px;
}

.rule-label {
  color: var(--muted, #8a7a65);
}

.rule-value {
  color: var(--text, #1a140c);
  font-weight: 500;
}

.settings-empty {
  text-align: center;
  color: var(--muted, #8a7a65);
  padding: 24px 0;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.slide-up-enter-active,
.slide-up-leave-active {
  transition: transform 0.25s ease;
}
.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(100%);
}
</style>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/__tests__/gameSettingsModal.test.ts`
Expected: PASS (7/7)

- [ ] **Step 6: Typecheck**

Run: `cd frontend && npx vue-tsc --noEmit`
Expected: exit 0

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/components/GameSettingsModal.vue frontend/src/__tests__/gameSettingsModal.test.ts
git commit -m "feat(frontend): GameSettingsModal component + GameSettings type"
```

---

### Task 3: Wire the modal into DayPhase, VotingPhase, GameView, and mocks

**Files:**
- Modify: `frontend/src/components/DayPhase.vue` (template ~line 25-45 `right-stack`; props ~line 388; `showLog` ref ~line 430; drawer mount ~line 236; `.log-fab` styles ~line 648)
- Modify: `frontend/src/components/VotingPhase.vue` (template ~line 30-52 `right-stack`; props ~line 598; `showLog` ref ~line 799; drawer mount ~line 585)
- Modify: `frontend/src/views/GameView.vue` (`<VotingPhase>` bindings ~line 117; `<DayPhase>` bindings ~line 147)
- Modify: `frontend/src/mocks/data.ts` (`MOCK_GAME_STATE` ~line 129)
- Test: `frontend/src/__tests__/dayPhase.test.ts`, `frontend/src/__tests__/votingPhase.test.ts`

**Interfaces:**
- Consumes: `GameSettingsModal` (props `{ settings, open }`, emit `close`) and `GameSettings` type from Task 2; wire data from Task 1 arrives at `gameStore.state.gameSettings`.
- Produces: new optional prop `gameSettings?: GameSettings | null` on `DayPhase` and `VotingPhase`; `settings-fab` button (testid) in both; GameView binds `:game-settings="gameStore.state?.gameSettings ?? null"`. Task 4's E2E clicks `settings-fab`.

- [ ] **Step 1: Write the failing unit tests**

Append to `frontend/src/__tests__/dayPhase.test.ts` (reuse the file's existing `BASE_PROPS`/`makeDay` helpers and imports; add `import type { GameSettings } from '@/types'`):

```ts
const GAME_SETTINGS: GameSettings = {
  roomCode: '358',
  totalPlayers: 6,
  wolfCount: 2,
  roles: ['WEREWOLF', 'WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'],
  hasSheriff: true,
  witchSelfSaveAllowed: true,
  winCondition: 'CLASSIC',
}

describe('DayPhase — room info button (settings-fab)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders the settings-fab and opens the room info modal on click', async () => {
    const wrapper = mount(DayPhase, {
      props: {
        ...BASE_PROPS,
        dayPhase: makeDay('RESULT_REVEALED'),
        gameSettings: GAME_SETTINGS,
      },
      attachTo: document.body,
    })

    const fab = wrapper.find('[data-testid="settings-fab"]')
    expect(fab.exists()).toBe(true)

    await fab.trigger('click')
    await wrapper.vm.$nextTick()

    const modal = document.body.querySelector('[data-testid="settings-modal"]')
    expect(modal).not.toBeNull()
    expect(
      document.body.querySelector('[data-testid="settings-room-code"]')?.textContent,
    ).toContain('358')

    wrapper.unmount()
  })

  it('RESULT_HIDDEN: settings-fab is still rendered (no night-result spoiler in settings)', () => {
    const wrapper = mount(DayPhase, {
      props: { ...BASE_PROPS, dayPhase: makeDay('RESULT_HIDDEN'), gameSettings: GAME_SETTINGS },
    })
    expect(wrapper.find('[data-testid="settings-fab"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
```

Append to `frontend/src/__tests__/votingPhase.test.ts` a self-contained describe (the file's existing helpers are scoped inside another describe — do not reach into them). Extend the existing type-only import with `GameSettings` (`import type { GamePlayer, GameSettings, VotingState } from '@/types'`), then append:

```ts
describe('VotingPhase — room info button (settings-fab)', () => {
  let pinia: ReturnType<typeof createPinia>
  let router: ReturnType<typeof createRouter>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div></div>' } }],
    })
  })

  const GAME_SETTINGS: GameSettings = {
    roomCode: '358',
    totalPlayers: 6,
    wolfCount: 2,
    roles: ['WEREWOLF', 'WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'],
    hasSheriff: true,
    witchSelfSaveAllowed: true,
    winCondition: 'CLASSIC',
  }

  const votingPhase: VotingState = {
    dayNumber: 1,
    subPhase: 'VOTING',
    phaseStarted: Date.now() - 10000,
    phaseDeadline: Date.now() + 60000,
    canVote: true,
    myVoteSkipped: false,
    votesSubmitted: 0,
    totalVoters: 2,
    tallyRevealed: false,
    tally: [],
    badgeDestroyed: false,
  } as VotingState

  const players: GamePlayer[] = [
    {
      userId: 'u1',
      nickname: 'Alice',
      seatIndex: 1,
      isAlive: true,
      isSheriff: false,
      canVote: true,
      idiotRevealed: false,
    },
    {
      userId: 'u2',
      nickname: 'Bob',
      seatIndex: 2,
      isAlive: true,
      isSheriff: false,
      canVote: true,
      idiotRevealed: false,
    },
  ]

  it('renders the settings-fab and opens the room info modal on click', async () => {
    const wrapper = mount(VotingPhase, {
      global: { plugins: [pinia, router] },
      props: {
        gameId: 1,
        votingPhase,
        players,
        myUserId: 'u1',
        isHost: false,
        // myRole present so the role-history-row (which hosts the right-stack) renders
        myRole: 'VILLAGER',
        isAlive: true,
        gameSettings: GAME_SETTINGS,
      },
      attachTo: document.body,
    })

    const fab = wrapper.find('[data-testid="settings-fab"]')
    expect(fab.exists()).toBe(true)

    await fab.trigger('click')
    await wrapper.vm.$nextTick()

    expect(document.body.querySelector('[data-testid="settings-modal"]')).not.toBeNull()
    expect(
      document.body.querySelector('[data-testid="settings-room-code"]')?.textContent,
    ).toContain('358')

    wrapper.unmount()
  })
})
```

If the `VotingState` literal above is missing newly-required fields, fix the literal (compare against `VotingState` in `frontend/src/types/index.ts`) rather than loosening the cast further.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/__tests__/dayPhase.test.ts src/__tests__/votingPhase.test.ts`
Expected: FAIL — `settings-fab` not found.

- [ ] **Step 3: Implement the wiring**

**`DayPhase.vue`:**

1. Template — inside `<div class="right-stack">` (~line 25), immediately **after** the closing `</button>` of the log-fab (~line 35), insert:

```html
        <button
          class="log-fab"
          aria-label="房间信息"
          data-testid="settings-fab"
          @click="showSettings = true"
        >
          <span class="log-fab-icon" aria-hidden="true">ℹ️</span>
          <span class="log-fab-label">房间信息</span>
        </button>
```

(Reuses the existing `.log-fab` pill styles — no new CSS. No `v-if`: unlike the game log, settings contain no night-result spoilers, so the button shows in every day sub-phase.)

2. Template — next to the `<ActionLogDrawer ... />` mount (~line 236), add:

```html
    <GameSettingsModal :settings="gameSettings" :open="showSettings" @close="showSettings = false" />
```

3. Script — add import after the ActionLogDrawer import (~line 384):

```ts
import GameSettingsModal from '@/components/GameSettingsModal.vue'
```

Add to the `defineProps` block (~line 388-400):

```ts
  gameSettings?: GameSettings | null
```

and extend the type-only import at ~line 381 with `GameSettings`. Add next to `const showLog = ref(false)` (~line 430):

```ts
const showSettings = ref(false)
```

**`VotingPhase.vue`:** same four edits — button after the log-fab (~line 42, same markup as above), `<GameSettingsModal ... />` next to the ActionLogDrawer mount (~line 585), import + `gameSettings?: GameSettings | null` prop (props block ~line 598, type import at the file's `@/types` import), `const showSettings = ref(false)` next to `showLog` (~line 799).

**`GameView.vue`:** add to the `<VotingPhase>` bindings (~line 117-130) and the `<DayPhase>` bindings (~line 147-161):

```html
        :game-settings="gameStore.state?.gameSettings ?? null"
```

**`frontend/src/mocks/data.ts`:** in `MOCK_GAME_STATE` (~line 129), after `hasSheriff: true,` add:

```ts
  gameSettings: {
    roomCode: '358',
    totalPlayers: 6,
    wolfCount: 2,
    roles: ['WEREWOLF', 'WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'],
    hasSheriff: true,
    witchSelfSaveAllowed: true,
    winCondition: 'CLASSIC',
  },
```

(Every mock branch spreads `MOCK_GAME_STATE`/`mockGameState`, so the block flows to all mock phases automatically.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/__tests__/dayPhase.test.ts src/__tests__/votingPhase.test.ts src/__tests__/gameSettingsModal.test.ts`
Expected: PASS

- [ ] **Step 5: Full frontend unit suite + typecheck**

Run: `cd frontend && npx vitest run && npx vue-tsc --noEmit`
Expected: PASS / exit 0

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/DayPhase.vue frontend/src/components/VotingPhase.vue frontend/src/views/GameView.vue frontend/src/mocks/data.ts frontend/src/__tests__/dayPhase.test.ts frontend/src/__tests__/votingPhase.test.ts
git commit -m "feat(frontend): settings-fab opens room info modal in day/voting phases"
```

---

### Task 4: Real-backend E2E — room info modal shows correct data mid-game

**Files:**
- Modify: `frontend/e2e/real/game-flow.spec.ts` (insert a new `test(...)` between test 5 ~line 344-392 and test 6 ~line 393; `game-flow.spec.ts` is already in the CI shard matrix, so no `ci.yml` change)

**Interfaces:**
- Consumes: `settings-fab` / `settings-modal` / `settings-room-code` / `settings-player-count` / `settings-sheriff` / `settings-close` testids from Task 3; `ctx: GameContext` (`ctx.roomCode`, `ctx.pages`, `ctx.hostPage`, `ctx.gameId`) from `./helpers/multi-browser`; `waitForCondition` from `./helpers/state-polling` (already imported in this spec); the spec's game setup: `totalPlayers: 9`, `hasSheriff: false`.
- Produces: nothing downstream; the new test must leave the game state untouched (open modal → assert → close) so test 6 ("host starts vote") is unaffected.

- [ ] **Step 1: Write the test (against the running local stack it should pass immediately after Tasks 1-3; against pre-change code it fails — TDD is satisfied at the branch level)**

Insert after test 5's closing `})` (~line 392):

```ts
  test('5b. Day — room info modal shows room code + settings on a player browser', async ({}, testInfo) => {
    // Any in-game player can open it — use the villager browser (not host)
    // to prove it is not host-gated.
    const villagerPage = ctx.pages.get('VILLAGER')
    if (!villagerPage) throw new Error('VILLAGER browser page missing from ctx.pages')

    // The backend serves gameSettings only to game members; wait until the
    // villager's own state poll carries it (guards against a stale client).
    let wolfCount = 0
    await waitForCondition(
      async () => {
        const state = await villagerPage.evaluate(async (id: string) => {
          const token = localStorage.getItem('jwt')
          const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          return res.ok ? res.json() : null
        }, ctx.gameId)
        if (!state?.gameSettings) return false
        wolfCount = state.gameSettings.wolfCount
        return state.gameSettings.roomCode === ctx.roomCode
      },
      'villager state carries gameSettings with the harness room code',
      15_000,
    )

    // Open the modal via the fab
    await villagerPage.getByTestId('settings-fab').click()
    await expect(villagerPage.getByTestId('settings-modal')).toBeVisible()

    // Room code matches the code the harness created the room with
    await expect(villagerPage.getByTestId('settings-room-code')).toHaveText(ctx.roomCode)

    // Settings match this spec's setupGame options (9 players, no sheriff)
    await expect(villagerPage.getByTestId('settings-player-count')).toContainText('9')
    await expect(villagerPage.getByTestId('settings-sheriff')).toContainText('无')

    // Role composition reflects the server-reported wolf count
    await expect(villagerPage.getByTestId('settings-role-WEREWOLF')).toContainText(`×${wolfCount}`)

    // Close and confirm the game screen is intact for the next test
    await villagerPage.getByTestId('settings-close').click()
    await expect(villagerPage.getByTestId('settings-modal')).not.toBeVisible()

    await captureSnapshot(testInfo, villagerPage, 'room-info-modal')
    invariants = await assertGameInvariants(ctx.hostPage, ctx.gameId, invariants)
  })
```

Notes for the implementer:
- Test 5 leaves every browser in `DAY_DISCUSSION` with the result revealed, so the fab is present. Do not vote or change phase in this test.
- `waitForCondition`, `captureSnapshot`, `assertGameInvariants`, `invariants` are already imported/declared in this spec file — reuse them, add no imports unless the linter demands.
- Follow the file's existing test style (testInfo snapshot capture at the end, invariants update).

- [ ] **Step 2: Run the spec locally against the real stack**

Prereq (see `docs/real-backend-testing.md` and memory note: kill any stale dev backend on 8080 first). With backend + frontend dev servers running:

Run: `cd frontend && npx playwright test e2e/real/game-flow.spec.ts --project=chromium-real`
(If the project name differs, use the project that the `e2e/real` specs run under in `playwright.config` — check `grep -n "real" frontend/playwright.config.ts`.)
Expected: PASS including the new `5b` test. Run it twice to shake out ordering flake.

- [ ] **Step 3: Lint the spec**

Run: `cd frontend && npx eslint e2e/real/game-flow.spec.ts`
Expected: exit 0

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/real/game-flow.spec.ts
git commit -m "test(e2e): room info modal shows room code + settings mid-game"
```
