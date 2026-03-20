package com.werewolf.game.phase

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GamePhasePipeline(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val sheriffCandidateRepository: SheriffCandidateRepository,
    private val voteRepository: VoteRepository,
    private val stompPublisher: StompPublisher,
    private val contextLoader: GameContextLoader,
    private val nightOrchestrator: NightOrchestrator,
) {
    // ── Phase transition actions ───────────────────────────────────────────────

    /** Host: reveal the night kill result (DAY/RESULT_HIDDEN → DAY/RESULT_REVEALED). */
    @Transactional
    fun revealNightResult(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can reveal night result")
        if (context.game.phase != GamePhase.DAY)
            return GameActionResult.Rejected("Not in DAY phase")
        if (context.game.subPhase != DaySubPhase.RESULT_HIDDEN.name)
            return GameActionResult.Rejected("Result already revealed")

        context.game.subPhase = DaySubPhase.RESULT_REVEALED.name
        gameRepository.save(context.game)
        stompPublisher.broadcastGame(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY, DaySubPhase.RESULT_REVEALED.name)
        )
        return GameActionResult.Success()
    }

    /** Host: advance day discussion to voting phase (DAY/RESULT_REVEALED → VOTING/VOTING). */
    @Transactional
    fun dayAdvance(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can start the vote")
        if (context.game.phase != GamePhase.DAY)
            return GameActionResult.Rejected("Not in DAY phase")
        if (context.game.subPhase != DaySubPhase.RESULT_REVEALED.name)
            return GameActionResult.Rejected("Reveal the night result before starting the vote")

        context.game.phase = GamePhase.VOTING
        context.game.subPhase = VotingSubPhase.VOTING.name
        gameRepository.save(context.game)
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

        // Advance to NIGHT once everyone has confirmed
        val allPlayers = gamePlayerRepository.findByGameId(context.gameId)
        if (allPlayers.all { it.confirmedRole }) {
            nightOrchestrator.initNight(context.gameId, context.game.dayNumber)
        }
        return GameActionResult.Success()
    }

    // ── Sheriff election actions ───────────────────────────────────────────────

    /** Route all sheriff election actions. */
    @Transactional
    fun handleSheriffElection(request: GameActionRequest, context: GameContext): GameActionResult {
        return when (request.actionType) {
            ActionType.SHERIFF_CAMPAIGN -> sheriffSignUp(request, context)
            ActionType.SHERIFF_QUIT -> sheriffQuit(request, context)
            ActionType.SHERIFF_START_SPEECH -> sheriffStartSpeech(request, context)
            ActionType.SHERIFF_ADVANCE_SPEECH -> sheriffAdvanceSpeech(request, context)
            ActionType.SHERIFF_REVEAL_RESULT -> sheriffRevealResult(request, context)
            else -> GameActionResult.Rejected("Unknown sheriff action: ${request.actionType}")
        }
    }

    private fun sheriffSignUp(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election
            ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Sign-up period is over")

        val player = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Player not found")
        if (!player.alive) return GameActionResult.Rejected("Dead players cannot run for sheriff")

        val existing = sheriffCandidateRepository.findByElectionId(election.id!!)
            .firstOrNull { it.userId == request.actorUserId }
        if (existing != null) {
            existing.status = CandidateStatus.RUNNING
            sheriffCandidateRepository.save(existing)
        } else {
            sheriffCandidateRepository.save(
                SheriffCandidate(
                    electionId = election.id!!,
                    userId = request.actorUserId,
                )
            )
        }
        return GameActionResult.Success()
    }

    private fun sheriffQuit(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election
            ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Sign-up period is over")

        val candidate = sheriffCandidateRepository.findByElectionId(election.id!!)
            .firstOrNull { it.userId == request.actorUserId }
            ?: return GameActionResult.Rejected("Not a candidate")

        candidate.status = CandidateStatus.QUIT
        sheriffCandidateRepository.save(candidate)
        return GameActionResult.Success()
    }

    private fun sheriffStartSpeech(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can start speeches")
        val election = context.election
            ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Not in SIGNUP sub-phase")

        val candidates = sheriffCandidateRepository.findByElectionId(election.id!!)
            .filter { it.status == CandidateStatus.RUNNING }

        if (candidates.isEmpty()) {
            // No candidates — skip election, proceed to NIGHT
            nightOrchestrator.initNight(context.gameId, context.game.dayNumber)
            return GameActionResult.Success()
        }

        election.subPhase = ElectionSubPhase.SPEECH
        election.speakingOrder = candidates.map { it.userId }.shuffled().joinToString(",")
        election.currentSpeakerIdx = 0
        sheriffElectionRepository.save(election)

        stompPublisher.broadcastGame(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SPEECH.name)
        )
        return GameActionResult.Success()
    }

    private fun sheriffAdvanceSpeech(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can advance speeches")
        val election = context.election
            ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SPEECH)
            return GameActionResult.Rejected("Not in SPEECH sub-phase")

        val order = election.speakingOrder?.split(",") ?: emptyList()
        val nextIdx = election.currentSpeakerIdx + 1

        if (nextIdx >= order.size) {
            // All speeches done — move to voting
            election.subPhase = ElectionSubPhase.VOTING
            sheriffElectionRepository.save(election)
            stompPublisher.broadcastGame(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.VOTING.name)
            )
        } else {
            election.currentSpeakerIdx = nextIdx
            sheriffElectionRepository.save(election)
        }
        return GameActionResult.Success()
    }

    private fun sheriffRevealResult(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can reveal result")
        val election = context.election
            ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.VOTING)
            return GameActionResult.Rejected("Not in VOTING sub-phase")

        val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.SHERIFF_ELECTION, context.game.dayNumber
        )
        val tally = votes.filter { it.targetUserId != null }
            .groupingBy { it.targetUserId!! }.eachCount()
        val maxVotes = tally.values.maxOrNull() ?: 0
        val topCandidates = tally.filterValues { it == maxVotes }.keys.toList()
        val winnerUserId = if (topCandidates.size == 1) topCandidates.first() else null

        election.subPhase = ElectionSubPhase.RESULT
        election.electedSheriffUserId = winnerUserId
        sheriffElectionRepository.save(election)

        if (winnerUserId != null) {
            context.game.sheriffUserId = winnerUserId
            gameRepository.save(context.game)
            gamePlayerRepository.findByGameIdAndUserId(context.gameId, winnerUserId).ifPresent {
                it.sheriff = true; gamePlayerRepository.save(it)
            }
        }

        stompPublisher.broadcastGame(context.gameId, DomainEvent.SheriffElected(context.gameId, winnerUserId))

        // Election done — proceed to night
        nightOrchestrator.initNight(context.gameId, context.game.dayNumber)
        return GameActionResult.Success()
    }
}
