package com.werewolf.service

import com.werewolf.config.RewardProperties
import com.werewolf.game.DomainEvent
import com.werewolf.model.CreditTxType
import com.werewolf.model.VoteContext
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GameEventRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.GameSettlementRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.repository.VoteRepository
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
 * OrphanedGameRecovery) never get REWARDS — but perk settlement (refund of
 * activations that did not take effect) runs on EVERY call, including
 * cancellations, gated per-activation by PerkSettlementService.
 *
 * The amounts come from [RewardBreakdown], shared with 玩家视角复盘 so the replay
 * explains exactly what was banked: a base for the side you were on and how you
 * fared, plus a bonus per badge held and per day your exile vote found a wolf.
 */
@Service
class RewardSettlementService(
    private val gameSettlementRepository: GameSettlementRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val gameRepository: GameRepository,
    private val walletService: WalletService,
    private val stompPublisher: StompPublisher,
    private val rewards: RewardProperties,
    private val perkSettlementService: PerkSettlementService,
    private val voteRepository: VoteRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val gameEventRepository: GameEventRepository,
) {
    private val log = LoggerFactory.getLogger(RewardSettlementService::class.java)

    @Transactional(propagation = Propagation.REQUIRED)
    fun settle(gameId: Int, winner: WinnerSide?) {
        // Perk settlement runs on EVERY call (including winner == null cancellations)
        // and is per-activation idempotent — independent of the game_settlements guard.
        perkSettlementService.settleForGame(gameId, cancelled = winner == null)

        if (winner == null) return
        if (gameSettlementRepository.tryInsert(gameId) == 0) {
            log.info("[settle] game={} already settled, skipping", gameId)
            return
        }

        val game = gameRepository.findById(gameId).orElse(null) ?: return
        val players = gamePlayerRepository.findByGameId(gameId)
        val facts = facts(gameId, game.dayNumber, winner, players)
        val perUser = players.associate { it.userId to RewardBreakdown.totalFor(it, facts, rewards) }

        perUser.forEach { (userId, amount) ->
            if (amount > 0) {
                walletService.credit(userId, amount, CreditTxType.GAME_REWARD, gameId = gameId)
            }
        }
        log.info("[settle] game={} winner={} rewards={}", gameId, winner, perUser)
        stompPublisher.broadcastGameAfterCommit(gameId, DomainEvent.GameSettled(gameId, perUser))
    }

    /** Everything the per-player amounts depend on, read once per settlement. */
    fun facts(gameId: Int, finalDay: Int, winner: WinnerSide, players: List<com.werewolf.model.GamePlayer>): RewardBreakdown.Facts {
        val roleOf = players.associate { it.userId to it.role }
        val events = gameEventRepository.findByGameIdOrderByCreatedAtAscIdAsc(gameId)
        return RewardBreakdown.Facts(
            winner = winner,
            finalDay = finalDay,
            badgeHolders = RewardBreakdown.badgeHolders(
                sheriffElectionRepository.findByGameId(gameId).orElse(null)?.electedSheriffUserId,
                events,
            ),
            wolfVoteDays = RewardBreakdown.wolfVoteDays(
                voteRepository.findByGameIdAndVoteContext(gameId, VoteContext.ELIMINATION),
                events,
            ) { roleOf[it] },
        )
    }
}
