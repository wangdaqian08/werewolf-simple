package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.*
import com.werewolf.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * Handles `WOLF_SELF_DESTRUCT` action: a werewolf publicly kills themselves during
 * SHERIFF_ELECTION, DAY_DISCUSSION, or DAY_VOTING to end the day without a vote.
 *
 * Per memory no-bang-bang-in-production: NEVER use `!!` in this file. Use `?: error("...")`.
 */
@Service
class SelfDestructService(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val sheriffCandidateRepository: SheriffCandidateRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val userRepository: UserRepository,
    private val stompPublisher: StompPublisher,
    private val actionLogService: ActionLogService,
    private val nightOrchestrator: NightOrchestrator,
    private val winConditionChecker: WinConditionChecker,
) {
    private val log = LoggerFactory.getLogger(SelfDestructService::class.java)

    private val allowedPhases = setOf(
        GamePhase.SHERIFF_ELECTION,
        GamePhase.DAY_DISCUSSION,
        GamePhase.DAY_VOTING,
    )

    @Transactional
    fun selfDestruct(request: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Player not found")
        if (!actor.alive) return GameActionResult.Rejected("Dead players cannot act")
        if (actor.role != PlayerRole.WEREWOLF)
            return GameActionResult.Rejected("Only werewolves can self-destruct")
        if (context.game.phase !in allowedPhases)
            return GameActionResult.Rejected("Self-destruct not allowed in phase ${context.game.phase}")

        val user = userRepository.findById(request.actorUserId).orElse(null)
        val nickname = user?.nickname ?: request.actorUserId

        // Mark wolf dead. If they were the sheriff, also burn the badge so the
        // ⭐ disappears from every player slot (game.sheriffUserId alone is not
        // enough — PlayerSlot reads GamePlayer.sheriff).
        val wolfPlayer = gamePlayerRepository.findByGameIdAndUserId(context.gameId, request.actorUserId)
            .orElse(null) ?: return GameActionResult.Rejected("Player not found in DB")
        wolfPlayer.alive = false
        val wasSheriff = context.game.sheriffUserId == request.actorUserId
        if (wasSheriff) {
            wolfPlayer.sheriff = false
            context.game.sheriffUserId = null
        }
        gamePlayerRepository.save(wolfPlayer)

        // Set daySkipVoting flag
        context.game.daySkipVoting = true

        // Phase transition
        when (context.game.phase) {
            GamePhase.SHERIFF_ELECTION -> {
                // Abort election → DAY_DISCUSSION/RESULT_HIDDEN
                context.game.phase = GamePhase.DAY_DISCUSSION
                context.game.subPhase = DaySubPhase.RESULT_HIDDEN.name
            }
            GamePhase.DAY_DISCUSSION -> {
                // Stay in current sub-phase — flag change enables host's "进入夜晚" button
            }
            GamePhase.DAY_VOTING -> {
                // Discard tallies → VOTE_RESULT
                context.game.subPhase = VotingSubPhase.VOTE_RESULT.name
            }
            else -> {
                // allowedPhases guard above prevents other phases reaching here
            }
        }

        gameRepository.save(context.game)

        // Record event
        actionLogService.recordSelfDestruct(
            context.gameId, context.game.dayNumber, request.actorUserId, nickname, actor.seatIndex,
        )

        // Broadcast after commit
        val eventsToSend = mutableListOf<DomainEvent>()
        eventsToSend.add(DomainEvent.WolfSelfDestructed(context.gameId, request.actorUserId, actor.seatIndex, nickname))
        if (wasSheriff) {
            eventsToSend.add(DomainEvent.BadgeHandover(context.gameId, request.actorUserId, null))
        }
        eventsToSend.add(DomainEvent.PhaseChanged(context.gameId, context.game.phase, context.game.subPhase))

        // Win-condition check (last wolf dying → villager win)
        val freshAlivePlayers = context.players.filter { it.userId != request.actorUserId && it.alive }
        val winner = winConditionChecker.check(
            alivePlayers = freshAlivePlayers,
            mode = context.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = com.werewolf.game.phase.HardModeCounterplay(
                hasGuard = freshAlivePlayers.any { it.role == PlayerRole.GUARD },
                hasWitchWithPotions = freshAlivePlayers.any { it.role == PlayerRole.WITCH },
                hasHunterWithBullet = freshAlivePlayers.any { it.role == PlayerRole.HUNTER },
            ),
        )
        if (winner != null) {
            context.game.winner = winner
            context.game.phase = GamePhase.GAME_OVER
            context.game.endedAt = LocalDateTime.now()
            gameRepository.save(context.game)
            eventsToSend.add(DomainEvent.GameOver(context.gameId, winner))
        }

        broadcastAllAfterCommit(context.gameId, eventsToSend)

        log.info("[selfDestruct] game={} actor={} phase={} subPhase={} wasSheriff={} winner={}",
            context.gameId, request.actorUserId, context.game.phase, context.game.subPhase, wasSheriff, winner)

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
