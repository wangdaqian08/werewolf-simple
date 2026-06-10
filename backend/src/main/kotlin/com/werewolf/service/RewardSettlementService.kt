package com.werewolf.service

import com.werewolf.config.RewardProperties
import com.werewolf.game.DomainEvent
import com.werewolf.model.CreditTxType
import com.werewolf.model.GamePlayer
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.GameSettlementRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Credits game rewards to every player's wallet exactly once per game.
 * Called from each game-over path (VotingPipeline.endGame,
 * NightOrchestrator.resolveNightKills, SelfDestructService.selfDestruct)
 * inside the game-over transaction; the game_settlements insert-first guard
 * makes duplicate calls a no-op. Cancelled games (winner == null, e.g.
 * OrphanedGameRecovery) are never settled.
 *
 * Reward formula (amounts from werewolf.rewards config):
 *   winning side          → winBonus
 *   losing villager side  → participation
 *   losing wolf           → wolfPerDaySurvived × days survived
 */
@Service
class RewardSettlementService(
    private val gameSettlementRepository: GameSettlementRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val gameRepository: GameRepository,
    private val walletService: WalletService,
    private val stompPublisher: StompPublisher,
    private val rewards: RewardProperties,
) {
    private val log = LoggerFactory.getLogger(RewardSettlementService::class.java)

    @Transactional(propagation = Propagation.REQUIRED)
    fun settle(gameId: Int, winner: WinnerSide?) {
        if (winner == null) return
        if (gameSettlementRepository.tryInsert(gameId) == 0) {
            log.info("[settle] game={} already settled, skipping", gameId)
            return
        }

        val game = gameRepository.findById(gameId).orElse(null) ?: return
        val players = gamePlayerRepository.findByGameId(gameId)
        val perUser = players.associate { it.userId to rewardFor(it, winner, game.dayNumber) }

        perUser.forEach { (userId, amount) ->
            if (amount > 0) {
                walletService.credit(userId, amount, CreditTxType.GAME_REWARD, gameId = gameId)
            }
        }
        log.info("[settle] game={} winner={} rewards={}", gameId, winner, perUser)
        stompPublisher.broadcastGameAfterCommit(gameId, DomainEvent.GameSettled(gameId, perUser))
    }

    private fun rewardFor(player: GamePlayer, winner: WinnerSide, finalDay: Int): Int {
        val isWolf = player.role == PlayerRole.WEREWOLF
        val won = if (winner == WinnerSide.WEREWOLF) isWolf else !isWolf
        return when {
            won -> rewards.winBonus
            isWolf -> rewards.wolfPerDaySurvived * (player.diedDay ?: finalDay).coerceAtLeast(1)
            else -> rewards.participation
        }
    }
}
