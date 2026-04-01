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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    // ── Phase transition actions ───────────────────────────────────────────────

    /** Host: reveal the night kill result (DAY/RESULT_HIDDEN → DAY/RESULT_REVEALED). */
    @Transactional
    fun revealNightResult(request: GameActionRequest, context: GameContext): GameActionResult {
        println("[revealNightResult] Received request from ${request.actorUserId}")
        println("[revealNightResult] Current game state: phase=${context.game.phase}, subPhase=${context.game.subPhase}, dayNumber=${context.game.dayNumber}")
        
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can reveal night result")
        if (context.game.phase != GamePhase.DAY) {
            println("[revealNightResult] ERROR: Not in DAY phase, actual phase is ${context.game.phase}")
            return GameActionResult.Rejected("Not in DAY phase")
        }
        if (context.game.subPhase != DaySubPhase.RESULT_HIDDEN.name) {
            println("[revealNightResult] WARNING: SubPhase is ${context.game.subPhase}, not RESULT_HIDDEN. Result already revealed?")
            return GameActionResult.Rejected("Result already revealed")
        }

        context.game.subPhase = DaySubPhase.RESULT_REVEALED.name
        gameRepository.save(context.game)
        
        println("[revealNightResult] Successfully revealed night result")
        stompPublisher.broadcastGame(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY, DaySubPhase.RESULT_REVEALED.name)
        )
        return GameActionResult.Success()
    }

    /** Host: advance day discussion to voting phase (DAY/RESULT_REVEALED → VOTING/VOTING). */
    @Transactional
    fun dayAdvance(request: GameActionRequest, context: GameContext): GameActionResult {
        println("[dayAdvance] Received request from ${request.actorUserId}")
        println("[dayAdvance] Current game state: phase=${context.game.phase}, subPhase=${context.game.subPhase}, dayNumber=${context.game.dayNumber}")
        
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can start the vote")
        if (context.game.phase != GamePhase.DAY) {
            println("[dayAdvance] ERROR: Not in DAY phase, actual phase is ${context.game.phase}")
            return GameActionResult.Rejected("Not in DAY phase")
        }
        if (context.game.subPhase != DaySubPhase.RESULT_REVEALED.name) {
            println("[dayAdvance] ERROR: SubPhase is not RESULT_REVEALED, actual is ${context.game.subPhase}")
            return GameActionResult.Rejected("Reveal the night result before starting the vote")
        }

        context.game.phase = GamePhase.VOTING
        context.game.subPhase = VotingSubPhase.VOTING.name
        gameRepository.save(context.game)
        
        println("[dayAdvance] Successfully advanced to VOTING phase")
        stompPublisher.broadcastGame(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.VOTING.name)
        )
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

        stompPublisher.broadcastGame(context.gameId, DomainEvent.RoleConfirmed(context.gameId, request.actorUserId))

        // Advance once everyone has confirmed
        val allPlayers = gamePlayerRepository.findByGameId(context.gameId)
        if (allPlayers.all { it.confirmedRole }) {
            if (context.room.hasSheriff) {
                context.game.phase = GamePhase.SHERIFF_ELECTION
                gameRepository.save(context.game)
                sheriffElectionRepository.save(SheriffElection(gameId = context.gameId))
                stompPublisher.broadcastGame(
                    context.gameId,
                    DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SIGNUP.name)
                )
            }
            // When hasSheriff=false, stay in ROLE_REVEAL — host will explicitly call START_NIGHT
        }
        return GameActionResult.Success()
    }

    /** Host: start night directly after role reveal when sheriff election is disabled. */
    @Transactional
    fun startNight(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only the host can start the night")
        val fromRoleReveal = context.game.phase == GamePhase.ROLE_REVEAL
        val fromSheriffResult = context.game.phase == GamePhase.SHERIFF_ELECTION &&
            context.election?.subPhase == ElectionSubPhase.RESULT
        if (!fromRoleReveal && !fromSheriffResult)
            return GameActionResult.Rejected("Cannot start night from current phase")
        if (fromRoleReveal) {
            if (context.room.hasSheriff)
                return GameActionResult.Rejected("Sheriff election is enabled for this game")
            val allPlayers = gamePlayerRepository.findByGameId(context.gameId)
            if (!allPlayers.all { it.confirmedRole })
                return GameActionResult.Rejected("Not all players have confirmed their role")
        }
        nightOrchestrator.initNight(context.gameId, context.game.dayNumber, withWaiting = true)
        return GameActionResult.Success()
    }

    // ── Sheriff election ───────────────────────────────────────────────────────

    @Transactional
    fun handleSheriffElection(request: GameActionRequest, context: GameContext): GameActionResult =
        sheriffService.handle(request, context)
}
