package com.werewolf.game.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.repository.NightPhaseRepository
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

@Order(5)
@Component
class MediumHandler(private val nightPhaseRepository: NightPhaseRepository) : RoleHandler {

    private val log = LoggerFactory.getLogger(MediumHandler::class.java)

    override val role = PlayerRole.MEDIUM

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> = when {
        phase == GamePhase.NIGHT && subPhase == NightSubPhase.MEDIUM_PICK.name -> setOf(ActionType.MEDIUM_CHECK)
        phase == GamePhase.NIGHT && subPhase == NightSubPhase.MEDIUM_RESULT.name -> setOf(ActionType.MEDIUM_CONFIRM)
        else -> emptySet()
    }

    override fun nightSubPhases(): List<NightSubPhase> =
        listOf(NightSubPhase.MEDIUM_PICK, NightSubPhase.MEDIUM_RESULT)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.MEDIUM) return GameActionResult.Rejected("Not the medium")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")

        return when (action.actionType) {
            ActionType.MEDIUM_CHECK -> {
                if (nightPhase.subPhase != NightSubPhase.MEDIUM_PICK)
                    return GameActionResult.Rejected("Not in MEDIUM_PICK sub-phase")

                val target = action.targetUserId
                    ?: return GameActionResult.Rejected("Target required")
                if (target == action.actorUserId)
                    return GameActionResult.Rejected("Cannot check yourself")
                val targetPlayer = context.alivePlayerById(target)
                    ?: return GameActionResult.Rejected("Target not found or dead")

                val seenRole = RoleRules.mediumSees(targetPlayer)
                nightPhase.mediumCheckedUserId = target
                nightPhase.mediumResultRole = seenRole
                nightPhaseRepository.save(nightPhase)
                // [race] The HTTP write timestamp: this log line is the anchor for the
                // NightOrchestrator's [race] read/save logs. If a coroutine's
                // `read-before-save` for day N sub-phase MEDIUM_RESULT logs mediumChecked=null
                // AFTER this line logged mediumChecked=<target>, the afterCommit barrier
                // failed to hold (either not installed, or @Transactional missing upstream).
                log.info(
                    "[race] MEDIUM_CHECK wrote game={} day={} thread={} txActive={} mediumChecked={} mediumRole={} (tx has NOT committed yet — afterCommit is what the coroutine must wait for)",
                    context.gameId,
                    nightPhase.dayNumber,
                    Thread.currentThread().name,
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    target,
                    seenRole,
                )

                // Result is returned as an event — caller (NightOrchestrator) sends it privately
                GameActionResult.Success(
                    listOf(
                        DomainEvent.MediumResult(context.gameId, target, seenRole)
                    )
                )
            }

            ActionType.MEDIUM_CONFIRM -> {
                if (nightPhase.subPhase != NightSubPhase.MEDIUM_RESULT)
                    return GameActionResult.Rejected("Not in MEDIUM_RESULT sub-phase")
                GameActionResult.Success()
            }

            else -> GameActionResult.Rejected("Unknown medium action: ${action.actionType}")
        }
    }
}
