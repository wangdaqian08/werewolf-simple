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
import org.springframework.stereotype.Component

@Component
class SeerHandler(private val nightPhaseRepository: NightPhaseRepository) : RoleHandler {

    override val role = PlayerRole.SEER

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> = when {
        phase == GamePhase.NIGHT && subPhase == NightSubPhase.SEER_PICK.name -> setOf(ActionType.SEER_CHECK)
        phase == GamePhase.NIGHT && subPhase == NightSubPhase.SEER_RESULT.name -> setOf(ActionType.SEER_CONFIRM)
        else -> emptySet()
    }

    override fun nightSubPhases(): List<NightSubPhase> =
        listOf(NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.SEER) return GameActionResult.Rejected("Not the seer")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")

        return when (action.actionType) {
            ActionType.SEER_CHECK -> {
                if (nightPhase.subPhase != NightSubPhase.SEER_PICK)
                    return GameActionResult.Rejected("Not in SEER_PICK sub-phase")

                val target = action.targetUserId
                    ?: return GameActionResult.Rejected("Target required")
                val targetPlayer = context.alivePlayerById(target)
                    ?: return GameActionResult.Rejected("Target not found or dead")

                val isWerewolf = targetPlayer.role == PlayerRole.WEREWOLF
                nightPhase.seerCheckedUserId = target
                nightPhase.seerResultIsWerewolf = isWerewolf
                nightPhaseRepository.save(nightPhase)

                // Result is returned as an event — caller (NightOrchestrator) sends it privately
                GameActionResult.Success(
                    listOf(
                        DomainEvent.SeerResult(context.gameId, target, isWerewolf)
                    )
                )
            }

            ActionType.SEER_CONFIRM -> {
                if (nightPhase.subPhase != NightSubPhase.SEER_RESULT)
                    return GameActionResult.Rejected("Not in SEER_RESULT sub-phase")
                GameActionResult.Success()
            }

            else -> GameActionResult.Rejected("Unknown seer action: ${action.actionType}")
        }
    }
}
