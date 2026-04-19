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
        // Privacy: no kill cause in message
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
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(captor.firstValue.message, Map::class.java) as Map<String, Any>
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
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(event.message, Map::class.java) as Map<String, Any>
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
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(event.message, Map::class.java) as Map<String, Any>
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
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(event.message, Map::class.java) as Map<String, Any>
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
