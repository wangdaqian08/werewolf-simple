package com.werewolf.unit.service

import com.werewolf.audio.AudioReplayCache
import com.werewolf.game.night.NightOrchestrator
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
import org.mockito.kotlin.whenever
import java.util.*

@ExtendWith(MockitoExtension::class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GameServiceStateTimerTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var sheriffService: SheriffService
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @Mock lateinit var audioReplayCache: AudioReplayCache
    @Mock lateinit var hostTimerService: HostTimerService

    private lateinit var gameService: GameService

    private val gameId = 1
    private val hostId = "host:001"
    private val day = 1

    @BeforeEach
    fun setUp() {
        gameService = GameService(
            gameRepository, roomRepository, roomPlayerRepository, gamePlayerRepository,
            stompPublisher, nightOrchestrator, userRepository, sheriffService,
            nightPhaseRepository, voteRepository, eliminationHistoryRepository,
            audioReplayCache, hostTimerService,
        )
    }

    private fun game() = Game(roomId = 1, hostUserId = hostId).also { g ->
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(g, gameId)
        g.phase = GamePhase.DAY_DISCUSSION
        g.subPhase = DaySubPhase.RESULT_HIDDEN.name
        g.dayNumber = day
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 4, hasSheriff = false)
    private fun player(userId: String, seat: Int) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.VILLAGER)
    private fun user(userId: String, nick: String) = User(userId = userId, nickname = nick)

    @Test
    fun `getGameState includes timer field with remainingMs, durationMs, running`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game()))
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room()))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(userRepository.findAllById(players.map { it.userId })).thenReturn(users)
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(audioReplayCache.getLatest(gameId)).thenReturn(null)
        whenever(audioReplayCache.snapshot(gameId)).thenReturn(emptyList())
        whenever(hostTimerService.snapshot(gameId)).thenReturn(TimerSnapshot(0L, 0L, false))

        val result = gameService.getGameState(gameId, hostId)

        @Suppress("UNCHECKED_CAST")
        val timer = result["timer"] as Map<String, Any?>
        assertThat(timer).containsKey("remainingMs")
        assertThat(timer).containsKey("durationMs")
        assertThat(timer).containsKey("running")
        assertThat(timer["running"]).isEqualTo(false)
    }

    @Test
    fun `getGameState returns freshly-decremented remainingMs from snapshot`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game()))
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room()))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(userRepository.findAllById(players.map { it.userId })).thenReturn(users)
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(audioReplayCache.getLatest(gameId)).thenReturn(null)
        whenever(audioReplayCache.snapshot(gameId)).thenReturn(emptyList())
        // Simulate 5 seconds elapsed on a 60s timer
        whenever(hostTimerService.snapshot(gameId)).thenReturn(TimerSnapshot(55_000L, 60_000L, true))

        val result = gameService.getGameState(gameId, hostId)

        @Suppress("UNCHECKED_CAST")
        val timer = result["timer"] as Map<String, Any?>
        assertThat(timer["remainingMs"]).isEqualTo(55_000L)
        assertThat(timer["durationMs"]).isEqualTo(60_000L)
        assertThat(timer["running"]).isEqualTo(true)
    }
}
