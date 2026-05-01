package com.werewolf.game.phase

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class GamePhasePipeline(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val stompPublisher: StompPublisher,
    private val contextLoader: GameContextLoader,
    private val sheriffService: SheriffService,
    private val nightOrchestrator: NightOrchestrator,
) {
    val log: Logger = LoggerFactory.getLogger(GamePhasePipeline::class.java)
    // ── Phase transition actions ───────────────────────────────────────────────

    /** Host: reveal the night kill result (DAY/RESULT_HIDDEN → DAY/RESULT_REVEALED). */
    @Transactional
    fun revealNightResult(request: GameActionRequest, context: GameContext): GameActionResult {
        log.info("[revealNightResult] Received request from ${request.actorUserId}")
        log.info("[revealNightResult] Current game state: phase=${context.game.phase}, subPhase=${context.game.subPhase}, dayNumber=${context.game.dayNumber}")
        
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can reveal night result")
        if (context.game.phase != GamePhase.DAY_DISCUSSION) {
            log.info("[revealNightResult] ERROR: Not in DAY_DISCUSSION phase, actual phase is ${context.game.phase}")
            return GameActionResult.Rejected("Not in DAY phase")
        }
        if (context.game.subPhase != DaySubPhase.RESULT_HIDDEN.name) {
            log.info("[revealNightResult] WARNING: SubPhase is ${context.game.subPhase}, not RESULT_HIDDEN. Result already revealed?")
            return GameActionResult.Rejected("Result already revealed")
        }

        context.game.subPhase = DaySubPhase.RESULT_REVEALED.name
        gameRepository.save(context.game)

        log.info("[revealNightResult] Successfully revealed night result")
        stompPublisher.broadcastGameAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name)
        )

        // Variant B sheriff election: on Day 1, after the N1 result is shown,
        // automatically transition into SHERIFF_ELECTION/SIGNUP. Players have
        // one night of context before the campaign begins.
        // - Skipped if hasSheriff=false (CLASSIC-no-sheriff games go straight
        //   to discussion → vote).
        // - Skipped if a sheriff already exists (defensive — shouldn't happen
        //   on Day 1, but keeps this idempotent).
        // - Skipped on Day 2+ (sheriff election runs at most once per game).
        if (context.room.hasSheriff
            && context.game.dayNumber == 1
            && context.game.sheriffUserId == null
            && sheriffElectionRepository.findByGameId(context.gameId).isEmpty
        ) {
            context.game.phase = GamePhase.SHERIFF_ELECTION
            context.game.subPhase = null
            gameRepository.save(context.game)
            sheriffElectionRepository.save(SheriffElection(gameId = context.gameId))
            stompPublisher.broadcastGameAfterCommit(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SIGNUP.name)
            )
        }
        return GameActionResult.Success()
    }

    /** Host: advance day discussion to voting phase (DAY/RESULT_REVEALED → VOTING/VOTING). */
    @Transactional
    fun dayAdvance(request: GameActionRequest, context: GameContext): GameActionResult {
        log.info("[dayAdvance] Received request from ${request.actorUserId}")
        log.info("[dayAdvance] Current game state: phase=${context.game.phase}, subPhase=${context.game.subPhase}, dayNumber=${context.game.dayNumber}")
        
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can start the vote")
        if (context.game.phase != GamePhase.DAY_DISCUSSION) {
            log.info("[dayAdvance] ERROR: Not in DAY phase, actual phase is ${context.game.phase}")
            return GameActionResult.Rejected("Not in DAY phase")
        }
        if (context.game.subPhase != DaySubPhase.RESULT_REVEALED.name) {
            log.info("[dayAdvance] ERROR: SubPhase is not RESULT_REVEALED, actual is ${context.game.subPhase}")
            return GameActionResult.Rejected("Reveal the night result before starting the vote")
        }

        context.game.phase = GamePhase.DAY_VOTING
        context.game.subPhase = VotingSubPhase.VOTING.name
        gameRepository.save(context.game)

        log.info("[dayAdvance] Successfully advanced to VOTING phase (in-memory; not yet committed)")

        // Defer the STOMP broadcast to afterCommit so the frontend doesn't
        // observe phase=DAY_VOTING and fire a follow-up SUBMIT_VOTE before
        // the dayAdvance UPDATE has actually committed. Without this, on a
        // slow CI runner the host's SUBMIT_VOTE arrives at the backend
        // BEFORE the dayAdvance commit hits the DB; submitVote's freshly
        // loaded GameContext still sees phase=DAY_DISCUSSION and rejects
        // with "Not in voting phase" — the exact failure mode reproduced
        // in CI run 24961013601, shard 1, game-flow test 7.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    log.info("[dayAdvance] afterCommit fired game=${context.gameId} → broadcasting PhaseChanged")
                    stompPublisher.broadcastGame(
                        context.gameId,
                        DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.VOTING.name)
                    )
                }
            })
        } else {
            stompPublisher.broadcastGame(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.VOTING.name)
            )
        }
        return GameActionResult.Success()
    }

    /** Player confirms they have seen their role (ROLE_REVEAL phase). */
    @Transactional
    fun confirmRole(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.ROLE_REVEAL)
            return GameActionResult.Rejected("Not in ROLE_REVEAL phase")

        val player = gamePlayerRepository.findByGameIdAndUserId(context.gameId, request.actorUserId)
            .orElse(null) ?: return GameActionResult.Rejected("Player not found in game")

        if (player.confirmedRole) return GameActionResult.Success() // idempotent

        player.confirmedRole = true
        gamePlayerRepository.save(player)

        stompPublisher.broadcastGameAfterCommit(context.gameId, DomainEvent.RoleConfirmed(context.gameId, request.actorUserId))

        // After everyone has confirmed, the host always drives the next step
        // by calling START_NIGHT. Sheriff election (when enabled) is now run
        // on Day 1 morning after the N1 result is revealed — see
        // [revealNightResult] for the auto-trigger.
        return GameActionResult.Success()
    }

    /** Host: start night after all roles have been confirmed. */
    @Transactional
    fun startNight(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only the host can start the night")
        if (context.game.phase != GamePhase.ROLE_REVEAL)
            return GameActionResult.Rejected("Cannot start night from current phase")
        val allPlayers = gamePlayerRepository.findByGameId(context.gameId)
        if (!allPlayers.all { it.confirmedRole })
            return GameActionResult.Rejected("Not all players have confirmed their role")
        // Cancel any scheduled auto-advance jobs
        sheriffService.cancelScheduledJob(context.gameId)
        nightOrchestrator.initNight(context.gameId, context.game.dayNumber, withWaiting = true)
        return GameActionResult.Success()
    }

    // ── Sheriff election ───────────────────────────────────────────────────────

    @Transactional
    fun handleSheriffElection(request: GameActionRequest, context: GameContext): GameActionResult =
        sheriffService.handle(request, context)
}
