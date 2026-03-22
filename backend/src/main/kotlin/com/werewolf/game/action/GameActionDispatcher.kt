package com.werewolf.game.action

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.GamePhasePipeline
import com.werewolf.game.role.RoleHandler
import com.werewolf.game.voting.VotingPipeline
import com.werewolf.model.ActionType
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GameActionDispatcher(
    private val handlers: List<RoleHandler>,
    private val nightOrchestrator: NightOrchestrator,
    private val votingPipeline: VotingPipeline,
    private val gamePhasePipeline: GamePhasePipeline,
    private val contextLoader: GameContextLoader,
    private val stompPublisher: StompPublisher,
) {
    @Transactional
    fun dispatch(request: GameActionRequest): GameActionResult {
        val context = contextLoader.load(request.gameId)

        return when (request.actionType) {
            // ── Role confirmation ──────────────────────────────────────────────
            ActionType.CONFIRM_ROLE -> gamePhasePipeline.confirmRole(request, context)

            // ── Host phase control (DAY) ───────────────────────────────────────
            ActionType.REVEAL_NIGHT_RESULT -> gamePhasePipeline.revealNightResult(request, context)
            ActionType.DAY_ADVANCE -> gamePhasePipeline.dayAdvance(request, context)

            // ── Voting control ────────────────────────────────────────────────
            ActionType.SUBMIT_VOTE -> votingPipeline.submitVote(request, context)
            ActionType.VOTING_REVEAL_TALLY -> votingPipeline.revealTally(request, context)
            ActionType.VOTING_CONTINUE -> votingPipeline.continueToNight(request, context)

            // ── Hunter + badge actions (post-elimination) ─────────────────────
            ActionType.HUNTER_SHOOT, ActionType.HUNTER_SKIP -> votingPipeline.handleHunterShoot(request, context)
            ActionType.BADGE_PASS, ActionType.BADGE_DESTROY -> votingPipeline.handleBadge(request, context)

            // ── Night: werewolf ───────────────────────────────────────────────
            ActionType.WOLF_KILL -> {
                val result = handlers.first { it.role == PlayerRole.WEREWOLF }.handle(request, context)
                if (result is GameActionResult.Success) nightOrchestrator.advance(
                    request.gameId,
                    NightSubPhase.WEREWOLF_PICK
                )
                result
            }

            // ── Night: seer ───────────────────────────────────────────────────
            ActionType.SEER_CHECK -> {
                val result = handlers.first { it.role == PlayerRole.SEER }.handle(request, context)
                if (result is GameActionResult.Success) {
                    // Send seer result privately before advancing
                    result.events.forEach { stompPublisher.sendPrivate(request.actorUserId, it) }
                    nightOrchestrator.advance(request.gameId, NightSubPhase.SEER_PICK)
                }
                result
            }

            ActionType.SEER_CONFIRM -> {
                val result = handlers.first { it.role == PlayerRole.SEER }.handle(request, context)
                if (result is GameActionResult.Success) nightOrchestrator.advance(
                    request.gameId,
                    NightSubPhase.SEER_RESULT
                )
                result
            }

            // ── Night: witch ──────────────────────────────────────────────────
            ActionType.WITCH_ACT -> {
                val result = handlers.first { it.role == PlayerRole.WITCH }.handle(request, context)
                if (result is GameActionResult.Success) nightOrchestrator.advance(
                    request.gameId,
                    NightSubPhase.WITCH_ACT
                )
                result
            }

            // ── Night: guard ──────────────────────────────────────────────────
            ActionType.GUARD_PROTECT, ActionType.GUARD_SKIP -> {
                val result = handlers.first { it.role == PlayerRole.GUARD }.handle(request, context)
                if (result is GameActionResult.Success) nightOrchestrator.advance(
                    request.gameId,
                    NightSubPhase.GUARD_PICK
                )
                result
            }
            // ── Night: idiot ──────────────────────────────────────────────────
            // TODO implement idiot action
            // ── Sheriff election ──────────────────────────────────────────────
            ActionType.SHERIFF_CAMPAIGN, ActionType.SHERIFF_QUIT,
            ActionType.SHERIFF_START_SPEECH, ActionType.SHERIFF_ADVANCE_SPEECH,
            ActionType.SHERIFF_REVEAL_RESULT,
            ActionType.SHERIFF_PASS, ActionType.SHERIFF_QUIT_CAMPAIGN,
            ActionType.SHERIFF_VOTE, ActionType.SHERIFF_CONFIRM_VOTE,
            ActionType.SHERIFF_ABSTAIN -> gamePhasePipeline.handleSheriffElection(request, context)
        }
    }
}
