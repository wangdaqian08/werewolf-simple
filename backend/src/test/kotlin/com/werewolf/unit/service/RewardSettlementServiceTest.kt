package com.werewolf.unit.service

import com.werewolf.config.RewardProperties
import com.werewolf.model.CreditTxType
import com.werewolf.model.Game
import com.werewolf.model.GamePlayer
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.GameSettlementRepository
import com.werewolf.service.RewardSettlementService
import com.werewolf.service.StompPublisher
import com.werewolf.service.WalletService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import java.util.*

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RewardSettlementServiceTest {

    @Mock lateinit var gameSettlementRepository: GameSettlementRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var walletService: WalletService
    @Mock lateinit var stompPublisher: StompPublisher

    private lateinit var service: RewardSettlementService

    private val gameId = 7
    private val rewards = RewardProperties(winBonus = 20, participation = 5, wolfPerDaySurvived = 4)

    @BeforeEach
    fun setUp() {
        service = RewardSettlementService(
            gameSettlementRepository, gamePlayerRepository, gameRepository,
            walletService, stompPublisher, rewards,
        )
        whenever(gameSettlementRepository.tryInsert(gameId)).thenReturn(1)
        whenever(walletService.credit(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(100)
    }

    private fun game(dayNumber: Int = 3) = Game(roomId = 1, hostUserId = "host:001").also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.dayNumber = dayNumber
    }

    private fun player(userId: String, role: PlayerRole, diedDay: Int? = null) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = 0, role = role).also { it.diedDay = diedDay }

    private fun stub(players: List<GamePlayer>, finalDay: Int = 3) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game(finalDay)))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
    }

    @Test
    fun `villager win - villagers get winBonus, dead wolf gets per-day-survived`() {
        stub(
            listOf(
                player("v1", PlayerRole.VILLAGER),
                player("v2", PlayerRole.SEER, diedDay = 2),
                player("w1", PlayerRole.WEREWOLF, diedDay = 2),
            ),
        )
        service.settle(gameId, WinnerSide.VILLAGER)

        verify(walletService).credit(eq("v1"), eq(20), eq(CreditTxType.GAME_REWARD), eq(gameId), anyOrNull(), anyOrNull(), anyOrNull())
        // dead villager-side player still won — full win bonus
        verify(walletService).credit(eq("v2"), eq(20), eq(CreditTxType.GAME_REWARD), eq(gameId), anyOrNull(), anyOrNull(), anyOrNull())
        // losing wolf died day 2 → 4 × 2 = 8
        verify(walletService).credit(eq("w1"), eq(8), eq(CreditTxType.GAME_REWARD), eq(gameId), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `wolf win - wolves get winBonus, losing villagers get participation`() {
        stub(
            listOf(
                player("w1", PlayerRole.WEREWOLF),
                player("v1", PlayerRole.VILLAGER, diedDay = 1),
                player("v2", PlayerRole.WITCH),
            ),
        )
        service.settle(gameId, WinnerSide.WEREWOLF)

        verify(walletService).credit(eq("w1"), eq(20), eq(CreditTxType.GAME_REWARD), eq(gameId), anyOrNull(), anyOrNull(), anyOrNull())
        verify(walletService).credit(eq("v1"), eq(5), eq(CreditTxType.GAME_REWARD), eq(gameId), anyOrNull(), anyOrNull(), anyOrNull())
        verify(walletService).credit(eq("v2"), eq(5), eq(CreditTxType.GAME_REWARD), eq(gameId), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `losing wolf that survived to the end is scaled by the final day`() {
        stub(listOf(player("w1", PlayerRole.WEREWOLF)), finalDay = 4)
        service.settle(gameId, WinnerSide.VILLAGER)
        // alive wolf on a day-4 villager win → 4 × 4 = 16
        verify(walletService).credit(eq("w1"), eq(16), eq(CreditTxType.GAME_REWARD), eq(gameId), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `second settle is a no-op (insert-first guard)`() {
        whenever(gameSettlementRepository.tryInsert(gameId)).thenReturn(0)
        service.settle(gameId, WinnerSide.VILLAGER)
        verify(walletService, never()).credit(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `cancelled game (winner null) is never settled`() {
        service.settle(gameId, null)
        verify(gameSettlementRepository, never()).tryInsert(any())
        verify(walletService, never()).credit(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }
}
