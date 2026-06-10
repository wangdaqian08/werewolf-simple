package com.werewolf.unit.service

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
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.util.*

/**
 * getGameState GAME_OVER "settlement" block: rewards are rebuilt from the
 * credit_transactions ledger (durable across a Result-screen refresh), with
 * the requesting player's own earnings and current balance surfaced. Off
 * GAME_OVER the block is absent.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Suppress("UNCHECKED_CAST")
class GameServiceSettlementTest {

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
    @Mock lateinit var dayRevealAdvancer: DayRevealAdvancer
    @Mock lateinit var creditTransactionRepository: CreditTransactionRepository
    @Mock lateinit var walletService: WalletService
    @Mock lateinit var perkService: com.werewolf.service.PerkService
    @InjectMocks lateinit var gameService: GameService

    private val gameId = 1
    private val hostId = "host:001"
    private val wolfId = "wolf:001"

    private fun game(phase: GamePhase) = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = phase
        it.dayNumber = 2
        it.winner = if (phase == GamePhase.GAME_OVER) WinnerSide.VILLAGER else null
    }

    private fun player(userId: String, seat: Int, role: PlayerRole) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role)

    private fun rewardTx(userId: String, amount: Int) = CreditTransaction(
        userId = userId, type = CreditTxType.GAME_REWARD, amount = amount, balanceAfter = amount, gameId = gameId,
    )

    @BeforeEach
    fun common() {
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(Room(roomCode = "ABC", hostUserId = hostId, totalPlayers = 6)))
        whenever(hostTimerService.snapshot(any())).thenReturn(TimerSnapshot(0L, 0L, false))
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(
            listOf(User(userId = hostId, nickname = "Host"), User(userId = wolfId, nickname = "Wolf")),
        )
    }

    @Test
    fun `GAME_OVER - settlement block exposes rewards, myEarned and myBalance`() {
        val players = listOf(player(hostId, 0, PlayerRole.VILLAGER), player(wolfId, 1, PlayerRole.WEREWOLF))
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game(GamePhase.GAME_OVER)))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(creditTransactionRepository.findByGameIdAndType(gameId, CreditTxType.GAME_REWARD))
            .thenReturn(listOf(rewardTx(hostId, 20), rewardTx(wolfId, 8)))
        whenever(walletService.balance(hostId)).thenReturn(120)

        val result = gameService.getGameState(gameId, hostId)

        val settlement = result["settlement"] as Map<String, Any?>
        assertThat(settlement).isNotNull()
        val rewards = settlement["rewards"] as List<Map<String, Any?>>
        assertThat(rewards).hasSize(2)
        val mine = rewards.first { it["userId"] == hostId }
        assertThat(mine["amount"]).isEqualTo(20)
        assertThat(mine["nickname"]).isEqualTo("Host")
        assertThat(mine["seatIndex"]).isEqualTo(0)
        assertThat(settlement["myEarned"]).isEqualTo(20)
        assertThat(settlement["myBalance"]).isEqualTo(120)
    }

    @Test
    fun `GAME_OVER - myEarned is null for a player with no reward row`() {
        val players = listOf(player(hostId, 0, PlayerRole.VILLAGER), player(wolfId, 1, PlayerRole.WEREWOLF))
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game(GamePhase.GAME_OVER)))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        // Only the host has a reward row; query as the wolf.
        whenever(creditTransactionRepository.findByGameIdAndType(gameId, CreditTxType.GAME_REWARD))
            .thenReturn(listOf(rewardTx(hostId, 20)))
        whenever(walletService.balance(wolfId)).thenReturn(0)

        val result = gameService.getGameState(gameId, wolfId)

        val settlement = result["settlement"] as Map<String, Any?>
        assertThat(settlement["myEarned"]).isNull()
        assertThat(settlement["myBalance"]).isEqualTo(0)
    }

    @Test
    fun `non-GAME_OVER phase has no settlement block`() {
        val players = listOf(player(hostId, 0, PlayerRole.VILLAGER))
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game(GamePhase.NIGHT)))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)

        val result = gameService.getGameState(gameId, hostId)

        assertThat(result["settlement"]).isNull()
        // The ledger is never consulted off GAME_OVER.
        org.mockito.kotlin.verify(creditTransactionRepository, org.mockito.kotlin.never())
            .findByGameIdAndType(any(), any())
    }
}
