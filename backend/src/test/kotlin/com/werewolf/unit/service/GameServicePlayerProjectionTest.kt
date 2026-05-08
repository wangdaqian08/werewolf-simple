package com.werewolf.unit.service

import com.werewolf.audio.AudioReplayCache
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.GameService
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.*

/**
 * Verifies the avatar URL + per-room displayName override land on every
 * player entry in the getGameState response. The frontend renders the
 * Google profile picture from the `avatar` field and uses `nickname` as
 * the displayed name in night/day/voting grids — both must propagate from
 * the OAuth login → User row → RoomPlayer override.
 */
@ExtendWith(MockitoExtension::class)
@Suppress("UNCHECKED_CAST")
class GameServicePlayerProjectionTest {

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
    @InjectMocks lateinit var gameService: GameService

    private val gameId = 1
    private val roomId = 7
    private val hostId = "google:abc"

    private fun game() = Game(roomId = roomId, hostUserId = hostId).also { g ->
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(g, gameId)
        g.phase = GamePhase.NIGHT
        g.dayNumber = 1
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSheriff = false)

    private fun player(userId: String, seat: Int) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.VILLAGER)

    private fun user(userId: String, nick: String, avatar: String?) =
        User(userId = userId, nickname = nick, avatarUrl = avatar)

    private fun roomPlayer(userId: String, displayName: String?) =
        RoomPlayer(roomId = roomId, userId = userId, displayName = displayName)

    @Test
    fun `getGameState includes avatar URL on every player from User_avatarUrl`() {
        val gp1 = player(hostId, 1)
        val gp2 = player("guest:bot", 2)
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game()))
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room()))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(gp1, gp2))
        whenever(userRepository.findAllById(any<Iterable<String>>())).thenReturn(
            listOf(
                user(hostId, "Daqian Wang", "https://lh3.googleusercontent.com/a/x"),
                user("guest:bot", "Bot", null),
            )
        )
        whenever(roomPlayerRepository.findByRoomId(roomId)).thenReturn(emptyList())
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.empty())

        val state = gameService.getGameState(gameId, hostId)

        val players = state["players"] as List<Map<String, Any?>>
        val host = players.first { it["userId"] == hostId }
        val bot = players.first { it["userId"] == "guest:bot" }

        assertThat(host["avatar"]).isEqualTo("https://lh3.googleusercontent.com/a/x")
        assertThat(bot["avatar"]).isNull()  // null avatarUrl flows through, no fake silhouette
    }

    @Test
    fun `getGameState uses RoomPlayer displayName override when present`() {
        val gp1 = player(hostId, 1)
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game()))
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room()))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(gp1))
        whenever(userRepository.findAllById(any<Iterable<String>>())).thenReturn(
            listOf(user(hostId, "Daqian Wang", "https://x.png"))
        )
        whenever(roomPlayerRepository.findByRoomId(roomId)).thenReturn(
            listOf(roomPlayer(hostId, "DW"))  // user typed DW in lobby
        )
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.empty())

        val state = gameService.getGameState(gameId, hostId)

        val players = state["players"] as List<Map<String, Any?>>
        val host = players.first { it["userId"] == hostId }

        assertThat(host["nickname"]).isEqualTo("DW")
        // Avatar still comes from the User row — overrides only affect the name.
        assertThat(host["avatar"]).isEqualTo("https://x.png")
    }

    @Test
    fun `getGameState falls back to User nickname when no RoomPlayer override`() {
        val gp1 = player(hostId, 1)
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game()))
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room()))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(gp1))
        whenever(userRepository.findAllById(any<Iterable<String>>())).thenReturn(
            listOf(user(hostId, "Daqian Wang", null))
        )
        whenever(roomPlayerRepository.findByRoomId(roomId)).thenReturn(
            listOf(roomPlayer(hostId, null))  // no override
        )
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.empty())

        val state = gameService.getGameState(gameId, hostId)

        val players = state["players"] as List<Map<String, Any?>>
        val host = players.first { it["userId"] == hostId }
        assertThat(host["nickname"]).isEqualTo("Daqian Wang")
    }
}

