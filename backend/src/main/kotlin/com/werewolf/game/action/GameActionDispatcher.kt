package com.werewolf.game.action

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.GameContext
import com.werewolf.game.night.NightTurnLedger
import com.werewolf.game.phase.GamePhasePipeline
import com.werewolf.game.role.RoleHandler
import com.werewolf.game.voting.VotingPipeline
import com.werewolf.model.ActionType
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.service.GameContextLoader
import com.werewolf.service.GameNotFoundException
import com.werewolf.service.SelfDestructService
import com.werewolf.service.StompPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class GameActionDispatcher(
    private val handlers: List<RoleHandler>,
    private val nightOrchestrator: NightOrchestrator,
    private val nightTurnLedger: NightTurnLedger,
    private val votingPipeline: VotingPipeline,
    private val gamePhasePipeline: GamePhasePipeline,
    private val contextLoader: GameContextLoader,
    private val stompPublisher: StompPublisher,
    private val selfDestructService: SelfDestructService,
) {
    @Transactional
    fun dispatch(request: GameActionRequest): GameActionResult {
        val context = try {
            contextLoader.load(request.gameId)
        } catch (e: GameNotFoundException) {
            return GameActionResult.Rejected(e.message!!)
        }

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
            ActionType.WOLF_KILL, ActionType.WOLF_SKIP -> nightTurn(request, context, PlayerRole.WEREWOLF)

            ActionType.WOLF_SELECT -> {
                val result = handlers.first { it.role == PlayerRole.WEREWOLF }.handle(request, context)
                if (result is GameActionResult.Success) {
                    // Broadcast selection to all alive wolves (no sub-phase advance).
                    // afterCommit ordering keeps the event consistent with what
                    // /api/game/{id}/state will return on a follow-up read.
                    val aliveWolves = context.alivePlayers.filter { it.role.isWolf }
                    result.events.forEach { event ->
                        aliveWolves.forEach { wolf -> stompPublisher.sendPrivateAfterCommit(wolf.userId, event) }
                    }
                }
                result
            }

            // ── Night: seer ───────────────────────────────────────────────────
            ActionType.SEER_CHECK -> nightTurn(request, context, PlayerRole.SEER) { result ->
                // Send seer result privately AFTER commit. The frontend's
                // SeerResult handler refetches state to pick up
                // nightPhase.seerCheckedUserId / seerResultIsWerewolf — a
                // pre-commit send would let that refetch see null fields.
                result.events.forEach { stompPublisher.sendPrivateAfterCommit(request.actorUserId, it) }
            }

            ActionType.SEER_CONFIRM -> nightTurn(request, context, PlayerRole.SEER)

            // Night: witch ──────────────────────────────────────────────────
            ActionType.WITCH_ACT -> nightTurn(request, context, PlayerRole.WITCH)

            // ── Night: guard ──────────────────────────────────────────────────
            ActionType.GUARD_PROTECT, ActionType.GUARD_SKIP -> nightTurn(request, context, PlayerRole.GUARD)
            // ── Idiot reveal (day) ────────────────────────────────────────────
            // The idiot reveals only by being voted out (VotingPipeline does it); no client sends this.
            ActionType.IDIOT_REVEAL -> GameActionResult.Rejected("The idiot is revealed only when voted out")

            // ── Sheriff election ──────────────────────────────────────────────
            ActionType.SHERIFF_CAMPAIGN, ActionType.SHERIFF_QUIT,
            ActionType.SHERIFF_START_SPEECH, ActionType.SHERIFF_ADVANCE_SPEECH,
            ActionType.SHERIFF_REVEAL_RESULT, ActionType.SHERIFF_APPOINT,
            ActionType.SHERIFF_PASS, ActionType.SHERIFF_QUIT_CAMPAIGN,
            ActionType.SHERIFF_VOTE, ActionType.SHERIFF_CONFIRM_VOTE,
            ActionType.SHERIFF_ABSTAIN, ActionType.SHERIFF_END_RESULT,
            ActionType.SHERIFF_SET_SPEECH_ORDER
                -> gamePhasePipeline.handleSheriffElection(request, context)

            // ── Wolf self-destruction (day action) ─────────────────────────────
            ActionType.WOLF_SELF_DESTRUCT -> selfDestructService.selfDestruct(request, context)
        }
    }

    private companion object {
        /** The night sub-phase each turn-completing action belongs to. */
        val TURN_SUB_PHASE = mapOf(
            ActionType.WOLF_KILL to NightSubPhase.WEREWOLF_PICK,
            ActionType.WOLF_SKIP to NightSubPhase.WEREWOLF_PICK,
            ActionType.SEER_CHECK to NightSubPhase.SEER_PICK,
            ActionType.SEER_CONFIRM to NightSubPhase.SEER_RESULT,
            ActionType.WITCH_ACT to NightSubPhase.WITCH_ACT,
            ActionType.GUARD_PROTECT to NightSubPhase.GUARD_PICK,
            ActionType.GUARD_SKIP to NightSubPhase.GUARD_PICK,
        )
    }

    /**
     * Runs an action that completes a night turn, at most once per turn ([NightTurnLedger]):
     * a repeat (double tap, retry, second tab) is rejected instead of signalling the night
     * loop twice. The claim is dropped if the action is rejected or its transaction rolls
     * back, so the player can retry. Only a living holder of [role] claims the turn.
     */
    private fun nightTurn(
        request: GameActionRequest,
        context: GameContext,
        role: PlayerRole,
        onSuccess: (GameActionResult.Success) -> Unit = {},
    ): GameActionResult {
        val actor = context.alivePlayerById(request.actorUserId)
        val holdsRole = actor != null && (if (role == PlayerRole.WEREWOLF) actor.role.isWolf else actor.role == role)
        // Claim only the action's own turn while it is live: an out-of-turn request
        // must never hold another role's turn (the handler rejects it unclaimed).
        val turn = context.nightPhase
            ?.takeIf { holdsRole && it.subPhase == TURN_SUB_PHASE[request.actionType] }
            ?.let { it.dayNumber to it.subPhase }
        if (turn != null && !nightTurnLedger.claim(request.gameId, turn.first, turn.second))
            return GameActionResult.Rejected("This turn was already confirmed")

        val result = handlers.first { it.role == role }.handle(request, context)
        if (result !is GameActionResult.Success) {
            turn?.let { nightTurnLedger.release(request.gameId, it.first, it.second) }
            return result
        }
        if (turn != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED)
                        nightTurnLedger.release(request.gameId, turn.first, turn.second)
                }
            })
        }
        onSuccess(result)
        nightOrchestrator.submitAction(request.gameId)
        return result
    }
}
