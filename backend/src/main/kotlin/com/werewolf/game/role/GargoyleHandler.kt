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
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Gargoyle (石像鬼) night turn, the first of the night (先验后刀). It always walks both
 * GARGOYLE_PICK and GARGOYLE_RESULT, whether it checks or skips, so the sequence is no tell.
 * A check tells it the target's exact role; a player checked on any earlier night cannot be
 * checked again. Its kill (once it is the last wolf) happens in the wolves' turn.
 */
@Order(0)
@Component
class GargoyleHandler(private val nightPhaseRepository: NightPhaseRepository) : RoleHandler {

    override val role = PlayerRole.GARGOYLE

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> = when {
        phase == GamePhase.NIGHT && subPhase == NightSubPhase.GARGOYLE_PICK.name -> setOf(ActionType.GARGOYLE_CHECK, ActionType.GARGOYLE_SKIP)
        phase == GamePhase.NIGHT && subPhase == NightSubPhase.GARGOYLE_RESULT.name -> setOf(ActionType.GARGOYLE_CONFIRM)
        else -> emptySet()
    }

    override fun nightSubPhases(): List<NightSubPhase> =
        listOf(NightSubPhase.GARGOYLE_PICK, NightSubPhase.GARGOYLE_RESULT)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.GARGOYLE) return GameActionResult.Rejected("Not the gargoyle")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")

        return when (action.actionType) {
            ActionType.GARGOYLE_CONFIRM ->
                if (nightPhase.subPhase != NightSubPhase.GARGOYLE_RESULT) GameActionResult.Rejected("Not in GARGOYLE_RESULT sub-phase")
                else GameActionResult.Success()

            ActionType.GARGOYLE_SKIP ->
                if (nightPhase.subPhase != NightSubPhase.GARGOYLE_PICK) GameActionResult.Rejected("Not in GARGOYLE_PICK sub-phase")
                else GameActionResult.Success()

            ActionType.GARGOYLE_CHECK -> {
                if (nightPhase.subPhase != NightSubPhase.GARGOYLE_PICK)
                    return GameActionResult.Rejected("Not in GARGOYLE_PICK sub-phase")
                val targetId = action.targetUserId ?: return GameActionResult.Rejected("Target required")
                if (targetId == actor.userId) return GameActionResult.Rejected("Cannot check yourself")
                val target = context.alivePlayerById(targetId)
                    ?: return GameActionResult.Rejected("Target not found or dead")
                // No repeat for the whole game, not only the previous night.
                if (context.allNightPhases.any { it.dayNumber < nightPhase.dayNumber && it.gargoyleCheckedUserId == targetId })
                    return GameActionResult.Rejected("That player was already checked")

                nightPhase.gargoyleCheckedUserId = targetId
                nightPhase.gargoyleResultRole = target.role
                nightPhaseRepository.save(nightPhase)
                GameActionResult.Success(listOf(DomainEvent.GargoyleResult(context.gameId, targetId, target.role)))
            }

            else -> GameActionResult.Rejected("Unknown gargoyle action: ${action.actionType}")
        }
    }
}
