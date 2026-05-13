package com.werewolf.game.action

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.GamePhasePipeline
import com.werewolf.game.role.RoleHandler
import com.werewolf.game.voting.VotingPipeline
import com.werewolf.model.ActionType
import com.werewolf.model.PlayerRole
import com.werewolf.service.GameContextLoader
import com.werewolf.service.SelfDestructService
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
    private val selfDestructService: SelfDestructService,
) {
    @Transactional
    fun dispatch(request: GameActionRequest): GameActionResult {
        val context = contextLoader.load(request.gameId)

        return when (request.actionType) {
            // ── Role confirmation ──────────────────────────────────────────────
            ActionType.CONFIRM_ROLE -> gamePhasePipeline.confirmRole(request, context)
            ActionType.START_NIGHT -> gamePhasePipeline.startNight(request, context)

            // ── Host phase control (DAY) ───────────────────────────────────────
            ActionType.REVEAL_NIGHT_RESULT -> gamePhasePipeline.revealNightResult(request, context)
            ActionType.DAY_ADVANCE -> gamePhasePipeline.dayAdvance(request, context)

            // ── Voting control ────────────────────────────────────────────────
            ActionType.SUBMIT_VOTE -> votingPipeline.submitVote(request, context)
            ActionType.VOTING_UNVOTE -> votingPipeline.unvote(request, context)
            ActionType.VOTING_REVEAL_TALLY -> votingPipeline.revealTally(request, context)
            ActionType.VOTING_CONTINUE -> votingPipeline.continueToNight(request, context)

            // ── Hunter + badge actions (post-elimination) ─────────────────────
            ActionType.HUNTER_SHOOT, ActionType.HUNTER_PASS -> votingPipeline.handleHunterShoot(request, context)
            ActionType.BADGE_PASS, ActionType.BADGE_DESTROY -> votingPipeline.handleBadge(request, context)

            // ── Night: werewolf ───────────────────────────────────────────────
            ActionType.WOLF_KILL -> {
                val result = handlers.first { it.role == PlayerRole.WEREWOLF }.handle(request, context)
                if (result is GameActionResult.Success) nightOrchestrator.submitAction(request.gameId)
                result
            }

            ActionType.WOLF_SELECT -> {
                val result = handlers.first { it.role == PlayerRole.WEREWOLF }.handle(request, context)
                if (result is GameActionResult.Success) {
                    // Broadcast selection to all alive wolves (no sub-phase advance).
                    // afterCommit ordering keeps the event consistent with what
                    // /api/game/{id}/state will return on a follow-up read.
                    val aliveWolves = context.alivePlayers.filter { it.role == PlayerRole.WEREWOLF }
                    result.events.forEach { event ->
                        aliveWolves.forEach { wolf -> stompPublisher.sendPrivateAfterCommit(wolf.userId, event) }
                    }
                }
                result
            }

            // ── Night: seer ───────────────────────────────────────────────────
            ActionType.SEER_CHECK -> {
                val result = handlers.first { it.role == PlayerRole.SEER }.handle(request, context)
                if (result is GameActionResult.Success) {
                    // Send seer result privately AFTER commit. The frontend's
                    // SeerResult handler refetches state to pick up
                    // nightPhase.seerCheckedUserId / seerResultIsWerewolf — a
                    // pre-commit send would let that refetch see null fields.
                    result.events.forEach { stompPublisher.sendPrivateAfterCommit(request.actorUserId, it) }
                    nightOrchestrator.submitAction(request.gameId)
                }
                result
            }

            ActionType.SEER_CONFIRM -> {
                val result = handlers.first { it.role == PlayerRole.SEER }.handle(request, context)
                if (result is GameActionResult.Success) nightOrchestrator.submitAction(request.gameId)
                result
            }

            // Night: witch ──────────────────────────────────────────────────
            ActionType.WITCH_ACT -> {
                val result = handlers.first { it.role == PlayerRole.WITCH }.handle(request, context)
                if (result is GameActionResult.Success) nightOrchestrator.submitAction(request.gameId)
                result
            }

            // ── Night: guard ──────────────────────────────────────────────────
            ActionType.GUARD_PROTECT, ActionType.GUARD_SKIP -> {
                val result = handlers.first { it.role == PlayerRole.GUARD }.handle(request, context)
                if (result is GameActionResult.Success) nightOrchestrator.submitAction(request.gameId)
                result
            }
            // ── Idiot reveal (day) ────────────────────────────────────────────
            ActionType.IDIOT_REVEAL -> handlers.first { it.role == PlayerRole.IDIOT }.handle(request, context)

            // ── Sheriff election ──────────────────────────────────────────────
            ActionType.SHERIFF_CAMPAIGN, ActionType.SHERIFF_QUIT,
            ActionType.SHERIFF_START_SPEECH, ActionType.SHERIFF_ADVANCE_SPEECH,
            ActionType.SHERIFF_REVEAL_RESULT, ActionType.SHERIFF_APPOINT,
            ActionType.SHERIFF_PASS, ActionType.SHERIFF_QUIT_CAMPAIGN,
            ActionType.SHERIFF_VOTE, ActionType.SHERIFF_CONFIRM_VOTE,
            ActionType.SHERIFF_ABSTAIN, ActionType.SHERIFF_END_RESULT
                -> gamePhasePipeline.handleSheriffElection(request, context)

            // ── Wolf self-destruction (day action) ─────────────────────────────
            ActionType.WOLF_SELF_DESTRUCT -> selfDestructService.selfDestruct(request, context)
        }
    }
}
