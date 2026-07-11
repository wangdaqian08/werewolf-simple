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
        whenever(nightOrchestrator.computePendingKills(any<Int>(), any())).thenReturn(emptyList())
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
