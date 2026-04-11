package com.werewolf.game.role

import com.werewolf.audio.RoleRegistry
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

@Order(4)
@Component
class GuardHandler(private val nightPhaseRepository: NightPhaseRepository) : RoleHandler {

    override val role = PlayerRole.GUARD

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.NIGHT && subPhase == NightSubPhase.GUARD_PICK.name)
            setOf(ActionType.GUARD_PROTECT, ActionType.GUARD_SKIP)
        else emptySet()

    override fun nightSubPhases(): List<NightSubPhase> = listOf(NightSubPhase.GUARD_PICK)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.GUARD) return GameActionResult.Rejected("Not the guard")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")
        if (nightPhase.subPhase != NightSubPhase.GUARD_PICK)
            return GameActionResult.Rejected("Not in GUARD_PICK sub-phase")

        when (action.actionType) {
            ActionType.GUARD_PROTECT -> {
                val target = action.targetUserId
                    ?: return GameActionResult.Rejected("Target required")
                if (target == nightPhase.prevGuardTargetUserId)
                    return GameActionResult.Rejected("Cannot protect the same player two nights in a row")
                if (context.alivePlayerById(target) == null)
                    return GameActionResult.Rejected("Target not found or dead")
                nightPhase.guardTargetUserId = target
            }

            ActionType.GUARD_SKIP -> nightPhase.guardTargetUserId = null
            else -> return GameActionResult.Rejected("Unknown guard action: ${action.actionType}")
        }

        nightPhaseRepository.save(nightPhase)
        return GameActionResult.Success()
    }

    /**
     * Get audio configuration for this role
     */
    fun getAudioConfig() = RoleRegistry.getAudioConfig(role)

    /**
     * Get default delay time for dead role simulation
     */
    fun getDefaultDelayMs() = RoleRegistry.getDefaultDelayMs(role) ?: 5000L
}
