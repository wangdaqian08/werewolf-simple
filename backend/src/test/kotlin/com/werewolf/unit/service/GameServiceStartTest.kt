package com.werewolf.unit.service

import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.GameService
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class GameServiceStartTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var sheriffService: SheriffService
    @InjectMocks lateinit var gameService: GameService

    private val hostId = "host:001"
    private val roomId = 42

    private fun waitingRoom() = Room(
        roomId = roomId,
        roomCode = "XYZW",
        hostUserId = hostId,
        totalPlayers = 6,
    ).also { it.status = RoomStatus.WAITING }

    private fun hostPlayer() = RoomPlayer(roomId = roomId, userId = hostId, host = true, status = ReadyStatus.NOT_READY)
        .also { it.seatIndex = 0 }

    private fun readyGuest(id: String, seat: Int) = RoomPlayer(roomId = roomId, userId = id, host = false, status = ReadyStatus.READY)
        .also { it.seatIndex = seat }

    private fun notReadyGuest(id: String, seat: Int) = RoomPlayer(roomId = roomId, userId = id, host = false, status = ReadyStatus.NOT_READY)
        .also { it.seatIndex = seat }

    private fun fourReadyGuests() = listOf(
        hostPlayer(),
        readyGuest("guest:1", 1),
        readyGuest("guest:2", 2),
        readyGuest("guest:3", 3),
    )

    // ── startGame ─────────────────────────────────────────────────────────────

    @Test
    fun `startGame rejects when a guest is NOT_READY`() {
        val room = waitingRoom()
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomId(roomId)).thenReturn(
            listOf(hostPlayer(), readyGuest("guest:1", 1), notReadyGuest("guest:2", 2), readyGuest("guest:3", 3))
        )

        val result = gameService.startGame(hostId, roomId)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Not all players are ready")
        verifyNoInteractions(gameRepository)
    }

    @Test
    fun `startGame succeeds when all guests are READY`() {
        val room = waitingRoom()
        val players = fourReadyGuests()
        val assignedGameId = 99

        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomId(roomId)).thenReturn(players)
        // Simulate Hibernate assigning the generated ID to the entity in-place via reflection
        whenever(gameRepository.save(any<Game>())).thenAnswer { invocation ->
            val game = invocation.getArgument<Game>(0)
            val field = Game::class.java.getDeclaredField("gameId")
            field.isAccessible = true
            field.set(game, assignedGameId)
            game
        }
        whenever(gamePlayerRepository.saveAll(any<List<GamePlayer>>())).thenAnswer { it.arguments[0] }

        val result = gameService.startGame(hostId, roomId)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(gamePlayerRepository).saveAll(any<List<GamePlayer>>())

        // broadcastRoom (GAME_STARTED) and broadcastGame (PhaseChanged) both fired
        val roomCaptor = argumentCaptor<Map<*, *>>()
        verify(stompPublisher).broadcastRoom(eq(roomId), roomCaptor.capture())
        assertThat(roomCaptor.firstValue["type"]).isEqualTo("GAME_STARTED")

        verify(stompPublisher).broadcastGame(eq(assignedGameId), any())
    }
}
