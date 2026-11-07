package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Handles `KNIGHT_DUEL`: once per game, by day, the knight challenges one player.
 * A wolf target dies and the day ends with no vote; a good target means the knight dies.
 *
 * Per memory no-bang-bang-in-production: NEVER use `!!` in this file. Use `?: error("...")`.
 */
@Service
class KnightDuelService(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val userRepository: UserRepository,
    private val stompPublisher: StompPublisher,
    private val actionLogService: ActionLogService,
) {
    private val log = LoggerFactory.getLogger(KnightDuelService::class.java)

    @Transactional
    fun duel(request: GameActionRequest, context: GameContext): GameActionResult {
        val game = context.game
        // The window check comes first: during the sheriff campaign last night's victims are still
        // alive in the DB, so a dead knight must get the same rejection as a living one.
        if (game.phase != GamePhase.DAY_DISCUSSION || game.subPhase != DaySubPhase.RESULT_REVEALED.name || game.daySkipVoting)
            return GameActionResult.Rejected("The knight can only duel after the night result is revealed and before voting starts")
        val actor = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Player not found")
        if (actor.role != PlayerRole.KNIGHT) return GameActionResult.Rejected("Only the knight can duel")
        if (!actor.alive) return GameActionResult.Rejected("Dead players cannot act")
        if (game.knightDuelTargetUserId != null) return GameActionResult.Rejected("The knight has already dueled")
        val targetUserId = request.targetUserId ?: return GameActionResult.Rejected("Target required")
        if (targetUserId == actor.userId) return GameActionResult.Rejected("The knight cannot duel itself")
        val target = context.alivePlayerById(targetUserId)
            ?: return GameActionResult.Rejected("Target not found or dead")

        val actorRow = gamePlayerRepository.findByGameIdAndUserId(context.gameId, actor.userId).orElse(null)
            ?: return GameActionResult.Rejected("Player not found in DB")
        val targetRow = gamePlayerRepository.findByGameIdAndUserId(context.gameId, target.userId).orElse(null)
            ?: return GameActionResult.Rejected("Target not found in DB")

        val targetIsWolf = target.role.isWolf
        val loser = if (targetIsWolf) targetRow else actorRow
        loser.alive = false
        loser.diedDay = game.dayNumber
        gamePlayerRepository.save(loser)

        if (targetIsWolf) game.daySkipVoting = true
        game.knightDuelTargetUserId = target.userId
        game.knightDuelDay = game.dayNumber
        gameRepository.save(game)

        fun nickname(userId: String) = userRepository.findById(userId).orElse(null)?.nickname ?: userId
        actionLogService.recordKnightDuel(
            context.gameId, game.dayNumber, actor.userId, nickname(actor.userId), actor.seatIndex,
            target.userId, nickname(target.userId), target.seatIndex, targetIsWolf,
        )

        broadcastAllAfterCommit(
            context.gameId,
            listOf(
                DomainEvent.KnightDuel(context.gameId, actor.userId, actor.seatIndex, target.userId, target.seatIndex, targetIsWolf),
                DomainEvent.PhaseChanged(context.gameId, game.phase, game.subPhase),
            ),
        )

        log.info("[knightDuel] game={} knight={} target={} targetIsWolf={}", context.gameId, actor.userId, target.userId, targetIsWolf)
        return GameActionResult.Success()
    }

    private fun broadcastAllAfterCommit(gameId: Int, events: List<DomainEvent>) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    events.forEach { stompPublisher.broadcastGame(gameId, it) }
                }
            })
        } else {
            events.forEach { stompPublisher.broadcastGame(gameId, it) }
        }
    }
}
