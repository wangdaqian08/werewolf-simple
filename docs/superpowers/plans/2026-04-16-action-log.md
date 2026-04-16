# Action Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow players to view a per-round history of public game events (night deaths, vote tallies, hunter shots, idiot reveals) via a floating button → drawer during the day phase.

**Architecture:** Backend writes `GameEvent` rows to the existing `game_events` table at four transition points (night end, vote reveal, hunter shot, idiot reveal); a new `GET /api/game/{gameId}/events` endpoint returns them ordered by time; the frontend `ActionLogDrawer.vue` fetches and renders them grouped by round.

**Tech Stack:** Kotlin + Spring Boot 3 / JPA (backend), Vue 3 + TypeScript + Tailwind (frontend), Jackson for JSON payload serialisation, Mockito for unit tests.

---

## File Map

### Backend — Create
- `backend/src/main/kotlin/com/werewolf/service/ActionLogService.kt` — writes and reads `GameEvent` rows; owns all JSON serialisation
- `backend/src/test/kotlin/com/werewolf/unit/service/ActionLogServiceTest.kt` — unit tests for `ActionLogService`

### Backend — Modify
- `backend/src/main/kotlin/com/werewolf/dto/GameDtos.kt` — add `ActionLogEntryDto`
- `backend/src/main/kotlin/com/werewolf/game/night/NightOrchestrator.kt` — inject `ActionLogService`; call `recordNightDeaths()` in `resolveNightKills()`
- `backend/src/main/kotlin/com/werewolf/game/voting/VotingPipeline.kt` — inject `ActionLogService`; call `recordVoteResult()`, `recordHunterShot()`, `recordIdiotReveal()`
- `backend/src/main/kotlin/com/werewolf/controller/GameController.kt` — add `GET /{gameId}/events` endpoint
- `backend/src/test/kotlin/com/werewolf/unit/service/NightOrchestratorTest.kt` — add `actionLogService` mock; add NIGHT_DEATH test
- `backend/src/test/kotlin/com/werewolf/unit/service/VotingPipelineTest.kt` — add `actionLogService` mock; add VOTE_RESULT / HUNTER_SHOT / IDIOT_REVEAL tests

### Frontend — Create
- `frontend/src/components/ActionLogDrawer.vue` — slide-up drawer, groups events by round

### Frontend — Modify
- `frontend/src/types/index.ts` — add `ActionLogEntry` and payload types
- `frontend/src/services/gameService.ts` — add `getActionLog(gameId)`
- `frontend/src/components/DayPhase.vue` — add `gameId` prop, floating button, wire drawer
- `frontend/src/views/GameView.vue` — pass `:game-id` to `DayPhase`

---

## Task 1: Add `ActionLogEntryDto` and create `ActionLogService`

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/dto/GameDtos.kt`
- Create: `backend/src/main/kotlin/com/werewolf/service/ActionLogService.kt`
- Create: `backend/src/test/kotlin/com/werewolf/unit/service/ActionLogServiceTest.kt`

- [ ] **Step 1: Add `ActionLogEntryDto` to `GameDtos.kt`**

Open `backend/src/main/kotlin/com/werewolf/dto/GameDtos.kt` and append:

```kotlin
data class ActionLogEntryDto(
    val id: Int,
    val eventType: String,
    val message: String,
    val targetUserId: String?,
    val createdAt: String?,
)
```

- [ ] **Step 2: Write the failing tests for `ActionLogService`**

Create `backend/src/test/kotlin/com/werewolf/unit/service/ActionLogServiceTest.kt`:

```kotlin
package com.werewolf.unit.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.werewolf.model.*
import com.werewolf.repository.GameEventRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.ActionLogService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class ActionLogServiceTest {

    @Mock lateinit var gameEventRepository: GameEventRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository

    @InjectMocks lateinit var service: ActionLogService

    private val gameId = 1
    private val mapper = jacksonObjectMapper()

    private fun user(id: String, name: String) = User(userId = id, nickname = name)
    private fun player(userId: String, seat: Int) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.VILLAGER)

    // ── recordNightDeaths ───────────────────────────────────────────────────

    @Test
    fun `recordNightDeaths - saves one NIGHT_DEATH row per killed player`() {
        whenever(userRepository.findAllById(any())).thenReturn(
            listOf(user("u1", "Alice"), user("u2", "Bob"))
        )
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(
            listOf(player("u1", 3), player("u2", 5))
        )

        service.recordNightDeaths(gameId, dayNumber = 1, killedIds = listOf("u1", "u2"))

        val captor = argumentCaptor<GameEvent>()
        verify(gameEventRepository, times(2)).save(captor.capture())
        val saved = captor.allValues
        assertThat(saved).allMatch { it.eventType == "NIGHT_DEATH" && it.gameId == gameId }
        assertThat(saved.map { it.targetUserId }).containsExactlyInAnyOrder("u1", "u2")
        // Privacy: no cause in message
        saved.forEach { e ->
            assertThat(e.message).doesNotContain("cause", "wolf", "poison", "witch")
        }
    }

    @Test
    fun `recordNightDeaths - skips save when list is empty`() {
        service.recordNightDeaths(gameId, dayNumber = 1, killedIds = emptyList())
        verify(gameEventRepository, never()).save(any())
    }

    @Test
    fun `recordNightDeaths - message contains dayNumber, nickname, seatIndex`() {
        whenever(userRepository.findAllById(any())).thenReturn(listOf(user("u1", "Alice")))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(player("u1", 3)))

        service.recordNightDeaths(gameId, dayNumber = 2, killedIds = listOf("u1"))

        val captor = argumentCaptor<GameEvent>()
        verify(gameEventRepository).save(captor.capture())
        val payload = mapper.readValue(captor.firstValue.message, Map::class.java)
        assertThat(payload["dayNumber"]).isEqualTo(2)
        assertThat(payload["nickname"]).isEqualTo("Alice")
        assertThat(payload["seatIndex"]).isEqualTo(3)
    }

    // ── recordVoteResult ────────────────────────────────────────────────────

    @Test
    fun `recordVoteResult - saves VOTE_RESULT row with tally and eliminated player`() {
        val votes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.ELIMINATION, dayNumber = 1,
                voterUserId = "u2", targetUserId = "u1"),
        )
        whenever(userRepository.findAllById(any())).thenReturn(
            listOf(user("u1", "Alice"), user("u2", "Bob"))
        )
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(
            listOf(player("u1", 1), player("u2", 2))
        )

        service.recordVoteResult(
            gameId = gameId, dayNumber = 1,
            votes = votes, tally = mapOf("u1" to 1.0),
            sheriffUserId = null, eliminatedUserId = "u1", eliminatedRole = PlayerRole.VILLAGER,
        )

        val captor = argumentCaptor<GameEvent>()
        verify(gameEventRepository).save(captor.capture())
        val event = captor.firstValue
        assertThat(event.eventType).isEqualTo("VOTE_RESULT")
        assertThat(event.targetUserId).isEqualTo("u1")
        val payload = mapper.readValue(event.message, Map::class.java)
        assertThat(payload["eliminatedUserId"]).isEqualTo("u1")
        assertThat(payload["eliminatedRole"]).isEqualTo("VILLAGER")
    }

    @Test
    fun `recordVoteResult - saves row with null eliminatedUserId for tie`() {
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(emptyList())

        service.recordVoteResult(
            gameId = gameId, dayNumber = 1,
            votes = emptyList(), tally = emptyMap(),
            sheriffUserId = null, eliminatedUserId = null, eliminatedRole = null,
        )

        val captor = argumentCaptor<GameEvent>()
        verify(gameEventRepository).save(captor.capture())
        assertThat(captor.firstValue.eventType).isEqualTo("VOTE_RESULT")
        assertThat(captor.firstValue.targetUserId).isNull()
    }

    // ── recordHunterShot ────────────────────────────────────────────────────

    @Test
    fun `recordHunterShot - saves HUNTER_SHOT row with hunter and target details`() {
        whenever(userRepository.findAllById(any())).thenReturn(
            listOf(user("h1", "Hunter"), user("t1", "Target"))
        )
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(
            listOf(player("h1", 4), player("t1", 7))
        )

        service.recordHunterShot(gameId, dayNumber = 1, hunterUserId = "h1", targetUserId = "t1")

        val captor = argumentCaptor<GameEvent>()
        verify(gameEventRepository).save(captor.capture())
        val event = captor.firstValue
        assertThat(event.eventType).isEqualTo("HUNTER_SHOT")
        assertThat(event.targetUserId).isEqualTo("t1")
        val payload = mapper.readValue(event.message, Map::class.java)
        assertThat(payload["hunterUserId"]).isEqualTo("h1")
        assertThat(payload["targetUserId"]).isEqualTo("t1")
        assertThat(payload["hunterSeatIndex"]).isEqualTo(4)
        assertThat(payload["targetSeatIndex"]).isEqualTo(7)
    }

    // ── recordIdiotReveal ───────────────────────────────────────────────────

    @Test
    fun `recordIdiotReveal - saves IDIOT_REVEAL row`() {
        whenever(userRepository.findById("idiot1")).thenReturn(Optional.of(user("idiot1", "Idiot")))
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "idiot1"))
            .thenReturn(Optional.of(player("idiot1", 6)))

        service.recordIdiotReveal(gameId, dayNumber = 1, userId = "idiot1")

        val captor = argumentCaptor<GameEvent>()
        verify(gameEventRepository).save(captor.capture())
        val event = captor.firstValue
        assertThat(event.eventType).isEqualTo("IDIOT_REVEAL")
        assertThat(event.targetUserId).isEqualTo("idiot1")
        val payload = mapper.readValue(event.message, Map::class.java)
        assertThat(payload["nickname"]).isEqualTo("Idiot")
        assertThat(payload["seatIndex"]).isEqualTo(6)
    }

    // ── getLog ──────────────────────────────────────────────────────────────

    @Test
    fun `getLog - returns events in order mapped to dto`() {
        val events = listOf(
            GameEvent(gameId = gameId, eventType = "NIGHT_DEATH", message = "{}", targetUserId = "u1"),
            GameEvent(gameId = gameId, eventType = "VOTE_RESULT", message = "{}", targetUserId = null),
        )
        whenever(gameEventRepository.findByGameIdOrderByCreatedAtAsc(gameId)).thenReturn(events)

        val result = service.getLog(gameId)

        assertThat(result).hasSize(2)
        assertThat(result[0].eventType).isEqualTo("NIGHT_DEATH")
        assertThat(result[1].eventType).isEqualTo("VOTE_RESULT")
    }
}
```

- [ ] **Step 3: Run tests — expect failure (ActionLogService not yet created)**

```bash
cd backend && ./gradlew test --tests "com.werewolf.unit.service.ActionLogServiceTest" 2>&1 | tail -20
```

Expected: compilation error — `ActionLogService` not found.

- [ ] **Step 4: Create `ActionLogService`**

Create `backend/src/main/kotlin/com/werewolf/service/ActionLogService.kt`:

```kotlin
package com.werewolf.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.werewolf.dto.ActionLogEntryDto
import com.werewolf.model.*
import com.werewolf.repository.GameEventRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class ActionLogService(
    private val gameEventRepository: GameEventRepository,
    private val userRepository: UserRepository,
    private val gamePlayerRepository: GamePlayerRepository,
) {
    private val mapper = jacksonObjectMapper()

    fun recordNightDeaths(gameId: Int, dayNumber: Int, killedIds: List<String>) {
        if (killedIds.isEmpty()) return
        val users = userRepository.findAllById(killedIds).associateBy { it.userId }
        val players = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }
        killedIds.forEach { userId ->
            val payload = mapOf(
                "dayNumber"  to dayNumber,
                "userId"     to userId,
                "nickname"   to (users[userId]?.nickname ?: userId),
                "seatIndex"  to (players[userId]?.seatIndex ?: 0),
            )
            gameEventRepository.save(
                GameEvent(
                    gameId      = gameId,
                    eventType   = "NIGHT_DEATH",
                    message     = mapper.writeValueAsString(payload),
                    targetUserId = userId,
                )
            )
        }
    }

    fun recordVoteResult(
        gameId: Int,
        dayNumber: Int,
        votes: List<Vote>,
        tally: Map<String, Double>,
        sheriffUserId: String?,
        eliminatedUserId: String?,
        eliminatedRole: PlayerRole?,
    ) {
        val allUserIds = (votes.map { it.voterUserId } +
            votes.mapNotNull { it.targetUserId } +
            listOfNotNull(eliminatedUserId)).distinct()
        val users = userRepository.findAllById(allUserIds).associateBy { it.userId }
        val players = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }

        val tallyList = tally.entries
            .sortedByDescending { it.value }
            .map { (targetId, count) ->
                val voters = votes
                    .filter { it.targetUserId == targetId }
                    .map { v ->
                        mapOf(
                            "userId"    to v.voterUserId,
                            "nickname"  to (users[v.voterUserId]?.nickname ?: v.voterUserId),
                            "seatIndex" to (players[v.voterUserId]?.seatIndex ?: 0),
                        )
                    }
                mapOf(
                    "userId"    to targetId,
                    "nickname"  to (users[targetId]?.nickname ?: targetId),
                    "seatIndex" to (players[targetId]?.seatIndex ?: 0),
                    "votes"     to count,
                    "voters"    to voters,
                )
            }

        val payload = mapOf(
            "dayNumber"           to dayNumber,
            "tally"               to tallyList,
            "eliminatedUserId"    to eliminatedUserId,
            "eliminatedNickname"  to eliminatedUserId?.let { users[it]?.nickname ?: it },
            "eliminatedSeatIndex" to eliminatedUserId?.let { players[it]?.seatIndex },
            "eliminatedRole"      to eliminatedRole?.name,
        )
        gameEventRepository.save(
            GameEvent(
                gameId       = gameId,
                eventType    = "VOTE_RESULT",
                message      = mapper.writeValueAsString(payload),
                targetUserId = eliminatedUserId,
            )
        )
    }

    fun recordHunterShot(gameId: Int, dayNumber: Int, hunterUserId: String, targetUserId: String) {
        val users = userRepository.findAllById(listOf(hunterUserId, targetUserId)).associateBy { it.userId }
        val players = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }
        val payload = mapOf(
            "dayNumber"        to dayNumber,
            "hunterUserId"     to hunterUserId,
            "hunterNickname"   to (users[hunterUserId]?.nickname ?: hunterUserId),
            "hunterSeatIndex"  to (players[hunterUserId]?.seatIndex ?: 0),
            "targetUserId"     to targetUserId,
            "targetNickname"   to (users[targetUserId]?.nickname ?: targetUserId),
            "targetSeatIndex"  to (players[targetUserId]?.seatIndex ?: 0),
        )
        gameEventRepository.save(
            GameEvent(
                gameId       = gameId,
                eventType    = "HUNTER_SHOT",
                message      = mapper.writeValueAsString(payload),
                targetUserId = targetUserId,
            )
        )
    }

    fun recordIdiotReveal(gameId: Int, dayNumber: Int, userId: String) {
        val user = userRepository.findById(userId).orElse(null)
        val player = gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElse(null)
        val payload = mapOf(
            "dayNumber" to dayNumber,
            "userId"    to userId,
            "nickname"  to (user?.nickname ?: userId),
            "seatIndex" to (player?.seatIndex ?: 0),
        )
        gameEventRepository.save(
            GameEvent(
                gameId       = gameId,
                eventType    = "IDIOT_REVEAL",
                message      = mapper.writeValueAsString(payload),
                targetUserId = userId,
            )
        )
    }

    fun getLog(gameId: Int): List<ActionLogEntryDto> =
        gameEventRepository.findByGameIdOrderByCreatedAtAsc(gameId).map { e ->
            ActionLogEntryDto(
                id           = e.id ?: 0,
                eventType    = e.eventType,
                message      = e.message,
                targetUserId = e.targetUserId,
                createdAt    = e.createdAt?.toString(),
            )
        }
}
```

- [ ] **Step 5: Run tests — expect pass**

```bash
cd backend && ./gradlew test --tests "com.werewolf.unit.service.ActionLogServiceTest" 2>&1 | tail -20
```

Expected: `ActionLogServiceTest > all tests PASSED`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/com/werewolf/dto/GameDtos.kt \
        backend/src/main/kotlin/com/werewolf/service/ActionLogService.kt \
        backend/src/test/kotlin/com/werewolf/unit/service/ActionLogServiceTest.kt
git commit -m "feat: add ActionLogService with NIGHT_DEATH/VOTE_RESULT/HUNTER_SHOT/IDIOT_REVEAL recording"
```

---

## Task 2: Write NIGHT_DEATH events in `NightOrchestrator`

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/game/night/NightOrchestrator.kt`
- Modify: `backend/src/test/kotlin/com/werewolf/unit/service/NightOrchestratorTest.kt`

- [ ] **Step 1: Write the failing test for NIGHT_DEATH recording**

Open `backend/src/test/kotlin/com/werewolf/unit/service/NightOrchestratorTest.kt`.

Add `@Mock` field at the top of the class (after the existing mocks):
```kotlin
@Mock lateinit var actionLogService: com.werewolf.service.ActionLogService
```

Update `makeOrchestrator()` to include the new dependency:
```kotlin
private fun makeOrchestrator(handlers: List<RoleHandler>) = NightOrchestrator(
    handlers = handlers,
    gameRepository = gameRepository,
    gamePlayerRepository = gamePlayerRepository,
    nightPhaseRepository = nightPhaseRepository,
    winConditionChecker = winConditionChecker,
    stompPublisher = stompPublisher,
    contextLoader = contextLoader,
    nightWaitingScheduler = nightWaitingScheduler,
    audioService = audioService,
    coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
    actionLogService = actionLogService,
)
```

Add this new test (find a good location near the `resolveNightKills` tests):
```kotlin
@Test
fun `resolveNightKills - records NIGHT_DEATH events for each kill`() {
    val wolf = GamePlayer(gameId = gameId, userId = "wolf1", seatIndex = 1, role = PlayerRole.WEREWOLF)
    val villager = GamePlayer(gameId = gameId, userId = "vil1", seatIndex = 2, role = PlayerRole.VILLAGER)
    val game = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
        it.dayNumber = 1
    }
    val room = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)
    val nightPhase = NightPhase(gameId = gameId, dayNumber = 1, subPhase = NightSubPhase.COMPLETE).also {
        it.wolfTargetUserId = "vil1"
    }
    val ctx = GameContext(game, room, listOf(wolf, villager), nightPhase)
    val updatedCtx = GameContext(game, room, listOf(wolf, villager.also { it.alive = false }), nightPhase)

    whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "vil1"))
        .thenReturn(Optional.of(villager))
    whenever(contextLoader.load(gameId)).thenReturn(updatedCtx)
    whenever(winConditionChecker.check(any(), any())).thenReturn(null)
    whenever(gameRepository.save(any())).thenAnswer { it.arguments[0] }
    whenever(nightPhaseRepository.save(any())).thenAnswer { it.arguments[0] }
    mockAudioServiceForDayTransition(room)

    nightOrchestrator.resolveNightKills(ctx, nightPhase)

    verify(actionLogService).recordNightDeaths(gameId, 1, listOf("vil1"))
}
```

- [ ] **Step 2: Run test — expect failure (actionLogService not injected yet)**

```bash
cd backend && ./gradlew test --tests "com.werewolf.unit.service.NightOrchestratorTest.resolveNightKills*" 2>&1 | tail -20
```

Expected: compilation error — `actionLogService` not a parameter of `NightOrchestrator`.

- [ ] **Step 3: Inject `ActionLogService` into `NightOrchestrator` and call `recordNightDeaths`**

In `backend/src/main/kotlin/com/werewolf/game/night/NightOrchestrator.kt`:

1. Add to constructor (after `coroutineScope`):
```kotlin
private val actionLogService: com.werewolf.service.ActionLogService,
```

2. In `resolveNightKills()`, after the `for (killId in kills.distinct())` loop and before checking win condition, add:
```kotlin
// Record public action log events (no kill cause, only who died)
actionLogService.recordNightDeaths(gameId, nightPhase.dayNumber, kills.distinct())
```

The exact insertion point (after the kill loop, before `nightPhase.subPhase = NightSubPhase.COMPLETE`):
```kotlin
// Apply kills
for (killId in kills.distinct()) {
    gamePlayerRepository.findByGameIdAndUserId(gameId, killId).ifPresent { player ->
        player.alive = false
        gamePlayerRepository.save(player)
    }
}

// Record public action log (cause intentionally omitted)
actionLogService.recordNightDeaths(gameId, nightPhase.dayNumber, kills.distinct())

nightPhase.subPhase = NightSubPhase.COMPLETE
nightPhaseRepository.save(nightPhase)
```

- [ ] **Step 4: Run the new test — expect pass**

```bash
cd backend && ./gradlew test --tests "com.werewolf.unit.service.NightOrchestratorTest.resolveNightKills*" 2>&1 | tail -20
```

Expected: PASSED

- [ ] **Step 5: Run all NightOrchestratorTest tests to ensure no regressions**

```bash
cd backend && ./gradlew test --tests "com.werewolf.unit.service.NightOrchestratorTest" 2>&1 | tail -30
```

Expected: all existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/com/werewolf/game/night/NightOrchestrator.kt \
        backend/src/test/kotlin/com/werewolf/unit/service/NightOrchestratorTest.kt
git commit -m "feat: write NIGHT_DEATH action log events on night resolution"
```

---

## Task 3: Write VOTE_RESULT, HUNTER_SHOT, IDIOT_REVEAL events in `VotingPipeline`

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/game/voting/VotingPipeline.kt`
- Modify: `backend/src/test/kotlin/com/werewolf/unit/service/VotingPipelineTest.kt`

- [ ] **Step 1: Write failing tests**

Open `backend/src/test/kotlin/com/werewolf/unit/service/VotingPipelineTest.kt`.

Add mock field:
```kotlin
@Mock lateinit var actionLogService: com.werewolf.service.ActionLogService
```

Update `makeVotingPipeline()`:
```kotlin
private fun makeVotingPipeline(handlers: List<RoleHandler>) = VotingPipeline(
    handlers = handlers,
    voteRepository = voteRepository,
    gameRepository = gameRepository,
    gamePlayerRepository = gamePlayerRepository,
    eliminationHistoryRepository = eliminationHistoryRepository,
    winConditionChecker = winConditionChecker,
    stompPublisher = stompPublisher,
    contextLoader = contextLoader,
    nightOrchestrator = nightOrchestrator,
    actionLogService = actionLogService,
)
```

Add these new tests at the end of `VotingPipelineTest`:

```kotlin
// ── Action log recording ─────────────────────────────────────────────────

@Test
fun `revealTally - records VOTE_RESULT event with tally and eliminated player`() {
    val hostPlayer = player(hostId, 1)
    val target = player("vil1", 2, PlayerRole.VILLAGER)
    val voter = player("vil2", 3)
    val game = game(subPhase = VotingSubPhase.VOTING.name)
    val voteList = listOf(vote("vil2", "vil1"))

    whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(any(), any(), any()))
        .thenReturn(voteList)
    whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "vil1"))
        .thenReturn(Optional.of(target))
    whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, 1))
        .thenReturn(Optional.of(
            EliminationHistory(gameId = gameId, dayNumber = 1,
                eliminatedUserId = "vil1", eliminatedRole = PlayerRole.VILLAGER)
        ))
    val updatedCtx = ctx(game, hostPlayer, target.also { it.alive = false }, voter)
    whenever(contextLoader.load(gameId)).thenReturn(updatedCtx)
    whenever(winConditionChecker.check(any(), any())).thenReturn(null)
    whenever(gameRepository.save(any())).thenAnswer { it.arguments[0] }

    val ctx = ctx(game, hostPlayer, target, voter)
    votingPipeline.revealTally(
        GameActionRequest(gameId = gameId, actorUserId = hostId, actionType = ActionType.REVEAL_TALLY),
        ctx,
    )

    verify(actionLogService).recordVoteResult(
        gameId = gameId, dayNumber = 1,
        votes = voteList,
        tally = any(),
        sheriffUserId = null,
        eliminatedUserId = "vil1",
        eliminatedRole = PlayerRole.VILLAGER,
    )
}

@Test
fun `handleHunterShoot - records HUNTER_SHOT event`() {
    val hunter = player(hostId, 1, PlayerRole.HUNTER).also { it.alive = false }
    val target = player("vil1", 2)
    val game = game(subPhase = VotingSubPhase.HUNTER_SHOOT.name)
    val ctx = ctx(game, hunter, target)

    whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "vil1"))
        .thenReturn(Optional.of(target))
    whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(any(), any()))
        .thenReturn(Optional.empty())
    val updatedCtx = ctx(game, hunter, target.also { it.alive = false })
    whenever(contextLoader.load(gameId)).thenReturn(updatedCtx)
    whenever(winConditionChecker.check(any(), any())).thenReturn(null)
    whenever(gameRepository.save(any())).thenAnswer { it.arguments[0] }

    votingPipeline.handleHunterShoot(
        GameActionRequest(gameId = gameId, actorUserId = hostId, actionType = ActionType.HUNTER_SHOOT,
            targetUserId = "vil1"),
        ctx,
    )

    verify(actionLogService).recordHunterShot(gameId, dayNumber = 1,
        hunterUserId = hostId, targetUserId = "vil1")
}

@Test
fun `revealTally - records IDIOT_REVEAL when idiot is top-voted`() {
    val hostPlayer = player(hostId, 1)
    val idiot = player("idiot1", 2, PlayerRole.IDIOT)
    val voter = player("vil1", 3)
    val game = game(subPhase = VotingSubPhase.VOTING.name)
    val voteList = listOf(vote("vil1", "idiot1"))

    whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(any(), any(), any()))
        .thenReturn(voteList)
    whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "idiot1"))
        .thenReturn(Optional.of(idiot))
    whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, 1))
        .thenReturn(Optional.empty())  // no elimination — idiot survived
    val updatedCtx = ctx(game, hostPlayer, idiot, voter)
    whenever(contextLoader.load(gameId)).thenReturn(updatedCtx)
    whenever(winConditionChecker.check(any(), any())).thenReturn(null)
    whenever(gameRepository.save(any())).thenAnswer { it.arguments[0] }

    val ctx = ctx(game, hostPlayer, idiot, voter)
    votingPipeline.revealTally(
        GameActionRequest(gameId = gameId, actorUserId = hostId, actionType = ActionType.REVEAL_TALLY),
        ctx,
    )

    verify(actionLogService).recordIdiotReveal(gameId, dayNumber = 1, userId = "idiot1")
}
```

- [ ] **Step 2: Run tests — expect failure**

```bash
cd backend && ./gradlew test --tests "com.werewolf.unit.service.VotingPipelineTest.revealTally*records*" \
  --tests "com.werewolf.unit.service.VotingPipelineTest.handleHunterShoot*records*" \
  --tests "com.werewolf.unit.service.VotingPipelineTest.revealTally*IDIOT*" 2>&1 | tail -20
```

Expected: compilation error — `actionLogService` not in `VotingPipeline`.

- [ ] **Step 3: Inject `ActionLogService` into `VotingPipeline`**

In `backend/src/main/kotlin/com/werewolf/game/voting/VotingPipeline.kt`:

1. Add to constructor (after `nightOrchestrator`):
```kotlin
private val actionLogService: com.werewolf.service.ActionLogService,
```

2. In `revealTally()`, after the `if (eliminated != null) { ... } else if (!wasRevote) { ... } else { ... }` block, add:

```kotlin
// Write action log: vote tally + outcome (votes captured before possible deletion in re-vote path)
val eliminatedRole = eliminated?.let { context.playerById(it)?.role }
actionLogService.recordVoteResult(
    gameId         = context.gameId,
    dayNumber      = context.game.dayNumber,
    votes          = votes,
    tally          = tally,
    sheriffUserId  = context.game.sheriffUserId,
    eliminatedUserId = eliminated,
    eliminatedRole = eliminatedRole,
)
```

3. In `handleHunterShoot()`, inside the `ActionType.HUNTER_SHOOT ->` branch, after `targetPlayer.alive = false` and `gamePlayerRepository.save(targetPlayer)`, add:

```kotlin
// Action log: hunter shot is a public event
actionLogService.recordHunterShot(
    gameId       = context.gameId,
    dayNumber    = context.game.dayNumber,
    hunterUserId = actor.userId,
    targetUserId = target,
)
```

4. In `collectEliminationEvents()`, in the idiot branch (inside `if (player.role == PlayerRole.IDIOT && !player.idiotRevealed)`), after `events.add(DomainEvent.IdiotRevealed(...))`, add:

```kotlin
// Action log: idiot reveal is a public event
actionLogService.recordIdiotReveal(
    gameId    = context.gameId,
    dayNumber = context.game.dayNumber,
    userId    = targetId,
)
```

- [ ] **Step 4: Run new tests — expect pass**

```bash
cd backend && ./gradlew test --tests "com.werewolf.unit.service.VotingPipelineTest.revealTally*records*" \
  --tests "com.werewolf.unit.service.VotingPipelineTest.handleHunterShoot*records*" \
  --tests "com.werewolf.unit.service.VotingPipelineTest.revealTally*IDIOT*" 2>&1 | tail -20
```

Expected: PASSED

- [ ] **Step 5: Run full VotingPipelineTest — no regressions**

```bash
cd backend && ./gradlew test --tests "com.werewolf.unit.service.VotingPipelineTest" 2>&1 | tail -30
```

Expected: all existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/com/werewolf/game/voting/VotingPipeline.kt \
        backend/src/test/kotlin/com/werewolf/unit/service/VotingPipelineTest.kt
git commit -m "feat: write VOTE_RESULT/HUNTER_SHOT/IDIOT_REVEAL action log events in VotingPipeline"
```

---

## Task 4: Add `GET /api/game/{gameId}/events` endpoint

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/controller/GameController.kt`

- [ ] **Step 1: Inject `ActionLogService` and add the endpoint**

In `backend/src/main/kotlin/com/werewolf/controller/GameController.kt`:

1. Add `actionLogService: ActionLogService` to the constructor:
```kotlin
@RestController
@RequestMapping("/api/game")
class GameController(
    private val gameService: GameService,
    private val gameActionDispatcher: GameActionDispatcher,
    private val nightOrchestrator: NightOrchestrator,
    private val actionLogService: com.werewolf.service.ActionLogService,
) {
```

2. Add the new endpoint method after `getGameState`:
```kotlin
@GetMapping("/{gameId}/events")
fun getGameLog(
    @PathVariable gameId: Int,
    authentication: Authentication,
): ResponseEntity<List<com.werewolf.dto.ActionLogEntryDto>> {
    return ResponseEntity.ok(actionLogService.getLog(gameId))
}
```

- [ ] **Step 2: Run the full backend test suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -40
```

Expected: all tests pass. If any integration tests that build `GameController` fail due to missing `ActionLogService` bean, check that `@Service` on `ActionLogService` is present (it is from Task 1).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/com/werewolf/controller/GameController.kt
git commit -m "feat: add GET /api/game/{gameId}/events endpoint"
```

---

## Task 5: Frontend — types and service method

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/gameService.ts`

- [ ] **Step 1: Add types to `frontend/src/types/index.ts`**

Append after the existing `GameEvent` interface (around line 136):

```typescript
// ── Action Log ────────────────────────────────────────────────────────────────

export interface ActionLogEntry {
  id: number
  eventType: 'NIGHT_DEATH' | 'VOTE_RESULT' | 'HUNTER_SHOT' | 'IDIOT_REVEAL'
  message: string
  targetUserId?: string
  createdAt?: string
}

export interface NightDeathPayload {
  dayNumber: number
  userId: string
  nickname: string
  seatIndex: number
}

export interface VoteTallyVoter {
  userId: string
  nickname: string
  seatIndex: number
}

export interface VoteTallyRow {
  userId: string
  nickname: string
  seatIndex: number
  votes: number
  voters: VoteTallyVoter[]
}

export interface VoteResultPayload {
  dayNumber: number
  tally: VoteTallyRow[]
  eliminatedUserId?: string
  eliminatedNickname?: string
  eliminatedSeatIndex?: number
  eliminatedRole?: string
}

export interface HunterShotPayload {
  dayNumber: number
  hunterUserId: string
  hunterNickname: string
  hunterSeatIndex: number
  targetUserId: string
  targetNickname: string
  targetSeatIndex: number
}

export interface IdiotRevealPayload {
  dayNumber: number
  userId: string
  nickname: string
  seatIndex: number
}
```

- [ ] **Step 2: Add `getActionLog` to `frontend/src/services/gameService.ts`**

Replace the full file content with:

```typescript
import http from './http'
import type { ActionLogEntry, GameActionRequest, GameActionResponse, GameState } from '@/types'

export const gameService = {
  async getState(gameId: string): Promise<GameState> {
    const { data } = await http.get<GameState>(`/game/${gameId}/state`)
    return data
  },

  async submitAction(req: GameActionRequest): Promise<GameActionResponse> {
    const { data } = await http.post<GameActionResponse>('/game/action', req)
    return data
  },

  async startGame(roomId: number): Promise<void> {
    await http.post('/game/start', { roomId })
  },

  async getActionLog(gameId: string): Promise<ActionLogEntry[]> {
    const { data } = await http.get<ActionLogEntry[]>(`/game/${gameId}/events`)
    return data
  },
}
```

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/services/gameService.ts
git commit -m "feat: add ActionLogEntry types and getActionLog service method"
```

---

## Task 6: Create `ActionLogDrawer.vue`

**Files:**
- Create: `frontend/src/components/ActionLogDrawer.vue`

- [ ] **Step 1: Create the component**

Create `frontend/src/components/ActionLogDrawer.vue`:

```vue
<template>
  <Teleport to="body">
    <div class="log-backdrop" @click.self="$emit('close')">
      <div class="log-panel">
        <header class="log-header">
          <span class="log-title">历史记录 · Log</span>
          <button class="log-close" @click="$emit('close')">✕</button>
        </header>

        <div v-if="loading" class="log-loading">加载中…</div>

        <div v-else-if="rounds.length === 0" class="log-empty">暂无记录 · No records yet</div>

        <div v-else class="log-body">
          <template v-for="round in rounds" :key="round.dayNumber">
            <!-- Night deaths -->
            <div v-if="round.nightDeaths.length > 0" class="log-section">
              <div class="log-section-title">第{{ round.dayNumber }}夜</div>
              <div
                v-for="d in round.nightDeaths"
                :key="d.userId"
                class="log-row log-row-death"
              >
                <span class="log-seat">{{ d.seatIndex }}号</span>
                <span class="log-name">{{ d.nickname }}</span>
                <span class="log-badge log-badge-dead">出局</span>
              </div>
            </div>

            <!-- Day voting -->
            <div v-if="round.voteResult" class="log-section">
              <div class="log-section-title">第{{ round.dayNumber }}天 · 投票</div>

              <div
                v-for="t in round.voteResult.tally"
                :key="t.userId"
                class="log-tally-row"
              >
                <span class="log-tally-name">{{ t.seatIndex }}号·{{ t.nickname }}</span>
                <span class="log-tally-votes">{{ t.votes }}票</span>
                <span class="log-tally-voters">
                  [{{ t.voters.map((v) => `${v.seatIndex}号`).join(', ') || '弃权' }}]
                </span>
              </div>

              <!-- Normal elimination -->
              <div
                v-if="
                  round.voteResult.eliminatedUserId &&
                  round.voteResult.eliminatedUserId !== round.idiotReveal?.userId
                "
                class="log-outcome log-outcome-elim"
              >
                ▶ 淘汰: {{ round.voteResult.eliminatedSeatIndex }}号·{{
                  round.voteResult.eliminatedNickname
                }}
                <span v-if="round.voteResult.eliminatedRole" class="log-role">
                  ({{ round.voteResult.eliminatedRole }})
                </span>
              </div>

              <!-- Idiot survived -->
              <div v-if="round.idiotReveal" class="log-outcome log-outcome-idiot">
                ▶ {{ round.idiotReveal.seatIndex }}号·{{ round.idiotReveal.nickname }}
                揭示身份：白痴，免于出局
              </div>

              <!-- Hunter shot -->
              <div v-if="round.hunterShot" class="log-outcome log-outcome-hunter">
                ▶ {{ round.hunterShot.hunterSeatIndex }}号·{{
                  round.hunterShot.hunterNickname
                }}
                开枪击中 {{ round.hunterShot.targetSeatIndex }}号·{{
                  round.hunterShot.targetNickname
                }}
              </div>

              <!-- No elimination (tie) -->
              <div
                v-if="!round.voteResult.eliminatedUserId && !round.idiotReveal"
                class="log-outcome log-outcome-tie"
              >
                ▶ 平票，无人出局
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { gameService } from '@/services/gameService'
import type {
  ActionLogEntry,
  HunterShotPayload,
  IdiotRevealPayload,
  NightDeathPayload,
  VoteResultPayload,
} from '@/types'

const props = defineProps<{ gameId: string }>()
defineEmits<{ close: [] }>()

interface LogRound {
  dayNumber: number
  nightDeaths: NightDeathPayload[]
  voteResult?: VoteResultPayload
  hunterShot?: HunterShotPayload
  idiotReveal?: IdiotRevealPayload
}

const loading = ref(true)
const entries = ref<ActionLogEntry[]>([])

onMounted(async () => {
  try {
    entries.value = await gameService.getActionLog(props.gameId)
  } finally {
    loading.value = false
  }
})

const rounds = computed<LogRound[]>(() => {
  const roundMap = new Map<number, LogRound>()

  for (const entry of entries.value) {
    let payload: Record<string, unknown>
    try {
      payload = JSON.parse(entry.message)
    } catch {
      continue
    }
    const day = payload['dayNumber'] as number
    if (!roundMap.has(day)) {
      roundMap.set(day, { dayNumber: day, nightDeaths: [] })
    }
    const round = roundMap.get(day)!

    if (entry.eventType === 'NIGHT_DEATH') {
      round.nightDeaths.push(payload as unknown as NightDeathPayload)
    } else if (entry.eventType === 'VOTE_RESULT') {
      round.voteResult = payload as unknown as VoteResultPayload
    } else if (entry.eventType === 'HUNTER_SHOT') {
      round.hunterShot = payload as unknown as HunterShotPayload
    } else if (entry.eventType === 'IDIOT_REVEAL') {
      round.idiotReveal = payload as unknown as IdiotRevealPayload
    }
  }

  return Array.from(roundMap.values()).sort((a, b) => a.dayNumber - b.dayNumber)
})
</script>

<style scoped>
.log-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(26, 20, 12, 0.55);
  z-index: 50;
  display: flex;
  align-items: flex-end;
}

.log-panel {
  width: 100%;
  max-height: 80dvh;
  background: var(--paper);
  border-radius: 1rem 1rem 0 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.log-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.875rem 1rem 0.75rem;
  border-bottom: 1px solid var(--border-l);
  flex-shrink: 0;
}

.log-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--text);
}

.log-close {
  background: none;
  border: none;
  font-size: 1rem;
  color: var(--muted);
  cursor: pointer;
  padding: 0.25rem;
}

.log-loading,
.log-empty {
  padding: 2rem 1rem;
  text-align: center;
  color: var(--muted);
  font-size: 0.875rem;
}

.log-body {
  overflow-y: auto;
  padding: 0.75rem 1rem 2rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.log-section {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.log-section-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding-bottom: 0.25rem;
  border-bottom: 1px solid var(--border-l);
  margin-bottom: 0.25rem;
}

.log-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8125rem;
}

.log-seat {
  color: var(--muted);
  min-width: 2rem;
}

.log-name {
  color: var(--text);
  font-weight: 500;
}

.log-badge {
  font-size: 0.6875rem;
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  font-weight: 600;
}

.log-badge-dead {
  background: rgba(181, 37, 26, 0.1);
  color: var(--red);
}

.log-tally-row {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  font-size: 0.8125rem;
  flex-wrap: wrap;
}

.log-tally-name {
  color: var(--text);
  font-weight: 500;
  min-width: 5rem;
}

.log-tally-votes {
  color: var(--red);
  font-weight: 700;
  min-width: 2rem;
}

.log-tally-voters {
  color: var(--muted);
  font-size: 0.75rem;
  flex: 1;
}

.log-outcome {
  font-size: 0.8125rem;
  padding: 0.3125rem 0.5rem;
  border-radius: 0.375rem;
  margin-top: 0.25rem;
}

.log-outcome-elim {
  background: rgba(181, 37, 26, 0.08);
  color: var(--red);
  font-weight: 500;
}

.log-role {
  color: var(--muted);
  font-weight: 400;
}

.log-outcome-idiot {
  background: rgba(160, 120, 48, 0.1);
  color: var(--gold);
  font-weight: 500;
}

.log-outcome-hunter {
  background: rgba(45, 106, 63, 0.1);
  color: var(--green);
  font-weight: 500;
}

.log-outcome-tie {
  background: rgba(138, 122, 101, 0.08);
  color: var(--muted);
}
</style>
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/ActionLogDrawer.vue
git commit -m "feat: add ActionLogDrawer component with round-grouped history view"
```

---

## Task 7: Wire floating button in `DayPhase` and `GameView`

**Files:**
- Modify: `frontend/src/components/DayPhase.vue`
- Modify: `frontend/src/views/GameView.vue`

- [ ] **Step 1: Add `gameId` prop and floating button to `DayPhase.vue`**

1. In the `<script lang="ts" setup>` block, import `ActionLogDrawer` and add `ref`:

```typescript
// Add at the top of imports:
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import ActionLogDrawer from '@/components/ActionLogDrawer.vue'

// Add after existing props/emits:
const showLog = ref(false)
```

2. Update `defineProps` to include `gameId`:

```typescript
const props = defineProps<{
  dayPhase: DayPhaseState
  players: GamePlayer[]
  myUserId: string
  isHost: boolean
  actionPending?: boolean
  gameId: string
}>()
```

3. Add the floating button and drawer at the end of the template (just before the closing `</div>` of `day-wrap`):

```html
    <!-- Action log floating button -->
    <button class="log-fab" aria-label="查看记录" @click="showLog = true">📋</button>
    <ActionLogDrawer v-if="showLog" :game-id="gameId" @close="showLog = false" />
  </div>
```

4. Add the CSS (inside the `<style scoped>` block):

```css
.log-fab {
  position: fixed;
  bottom: 5.5rem;
  right: 1rem;
  width: 2.75rem;
  height: 2.75rem;
  border-radius: 50%;
  background: var(--paper);
  border: 1px solid var(--border);
  font-size: 1.1rem;
  cursor: pointer;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}
```

- [ ] **Step 2: Pass `:game-id` in `GameView.vue`**

Find the `<DayPhase` block in `GameView.vue` (around the line that says `v-else-if="gameStore.state?.phase === 'DAY_DISCUSSION'"`).

Add `:game-id="String(gameStore.state.gameId)"` to the props:

```html
<DayPhase
  :key="`${gameStore.state.dayPhase.subPhase}-${gameStore.state.dayPhase.dayNumber}`"
  :day-phase="gameStore.state.dayPhase"
  :players="gameStore.state.players"
  :my-user-id="userStore.userId ?? ''"
  :is-host="isHost"
  :action-pending="actionPending"
  :game-id="String(gameStore.state.gameId)"
  @reveal-result="handleRevealResult"
  @start-vote="handleStartVote"
  @vote="handleDayVote"
  @skip="handleDaySkip"
  @select-player="handleDaySelectPlayer"
/>
```

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: no errors.

- [ ] **Step 4: Run full backend test suite one final time**

```bash
cd backend && ./gradlew test 2>&1 | tail -40
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/DayPhase.vue frontend/src/views/GameView.vue
git commit -m "feat: wire action log floating button in DayPhase during day discussion"
```

---

## Resuming After Rate Limit

If the session is interrupted, check the git log to see which tasks are complete:

```bash
git log --oneline -10
```

Then read the plan and continue from the first unchecked `- [ ]` step in the next pending task. All code is in this document — no context reconstruction needed.
