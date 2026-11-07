package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.phase.DayRevealAdvancer
import com.werewolf.game.phase.HardModeCounterplay
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

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
    private val dayRevealAdvancer: DayRevealAdvancer,
    private val winConditionChecker: WinConditionChecker,
    private val rewardSettlementService: RewardSettlementService,
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
        // A dead sheriff hands the badge over (never destroyed here); the handover returns the day to RESULT_REVEALED.
        if (loser.userId == game.sheriffUserId) game.subPhase = dayRevealAdvancer.nextSubPhase(context.gameId).name
        gameRepository.save(game)

        fun nickname(userId: String) = userRepository.findById(userId).orElse(null)?.nickname ?: userId
        actionLogService.recordKnightDuel(
            context.gameId, game.dayNumber, actor.userId, nickname(actor.userId), actor.seatIndex,
            target.userId, nickname(target.userId), target.seatIndex, targetIsWolf,
        )

        val events = mutableListOf<DomainEvent>(
            DomainEvent.KnightDuel(context.gameId, actor.userId, actor.seatIndex, target.userId, target.seatIndex, targetIsWolf),
            DomainEvent.PhaseChanged(context.gameId, game.phase, game.subPhase),
        )

        // Last night's deaths are already applied in RESULT_REVEALED, so only the duel's loser changes the count.
        val alive = context.players.filter { it.alive && it.userId != loser.userId }
        val winner = winConditionChecker.check(
            alivePlayers = alive,
            mode = context.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay.of(alive, context.allNightPhases, knightDuelUsed = true),
            roster = context.players,
        )
        if (winner != null) {
            game.winner = winner
            game.phase = GamePhase.GAME_OVER
            game.endedAt = LocalDateTime.now()
            gameRepository.save(game)
            rewardSettlementService.settle(context.gameId, winner)
            events.add(DomainEvent.GameOver(context.gameId, winner))
        }

        broadcastAllAfterCommit(context.gameId, events)

        log.info("[knightDuel] game={} knight={} target={} targetIsWolf={} subPhase={} winner={}",
            context.gameId, actor.userId, target.userId, targetIsWolf, game.subPhase, winner)
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
