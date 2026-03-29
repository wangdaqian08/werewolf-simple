package com.werewolf.game.role

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

@Order(3)
@Component
class WitchHandler(private val nightPhaseRepository: NightPhaseRepository) : RoleHandler {

    override val role = PlayerRole.WITCH

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.NIGHT && subPhase == NightSubPhase.WITCH_ACT.name) setOf(ActionType.WITCH_ACT)
        else emptySet()

    override fun nightSubPhases(): List<NightSubPhase> = listOf(NightSubPhase.WITCH_ACT)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        if (action.actionType != ActionType.WITCH_ACT) return GameActionResult.Rejected("Unknown action: ${action.actionType}")

        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.WITCH) return GameActionResult.Rejected("Not the witch")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")
        if (nightPhase.subPhase != NightSubPhase.WITCH_ACT)
            return GameActionResult.Rejected("Not in WITCH_ACT sub-phase")

        val useAntidote = action.payload["useAntidote"] as? Boolean ?: false
        val poisonTarget = action.payload["poisonTargetUserId"] as? String

        // Antidote can only be used once per game
        val antidoteEverUsed = context.allNightPhases.any { it.witchAntidoteUsed && it.id != nightPhase.id }
        if (useAntidote && antidoteEverUsed)
            return GameActionResult.Rejected("Antidote already used in a previous round")

        // Cannot use both antidote and poison the same night
        if (useAntidote && poisonTarget != null)
            return GameActionResult.Rejected("Cannot use antidote and poison on the same night")

        if (poisonTarget != null && context.alivePlayerById(poisonTarget) == null)
            return GameActionResult.Rejected("Poison target not found or dead")

        if (useAntidote) nightPhase.witchAntidoteUsed = true
        if (poisonTarget != null) nightPhase.witchPoisonTargetUserId = poisonTarget

        nightPhaseRepository.save(nightPhase)
        return GameActionResult.Success()
    }
}
