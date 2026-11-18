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

/**
 * 狼美人 night turn, right after the wolves' kill. From night 2 it may charm one living player
 * (not itself, not a pack wolf, not last night's target); on night 1 only SKIP (the 確認 tap) is accepted.
 */
@Order(2)
@Component
class WolfBeautyHandler(private val nightPhaseRepository: NightPhaseRepository) : RoleHandler {

    override val role = PlayerRole.WOLF_BEAUTY

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.NIGHT && subPhase == NightSubPhase.WOLF_BEAUTY_PICK.name)
            setOf(ActionType.WOLF_BEAUTY_CHARM, ActionType.WOLF_BEAUTY_SKIP)
        else emptySet()

    override fun nightSubPhases(): List<NightSubPhase> = listOf(NightSubPhase.WOLF_BEAUTY_PICK)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.WOLF_BEAUTY) return GameActionResult.Rejected("Not the Wolf Beauty")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")
        if (nightPhase.subPhase != NightSubPhase.WOLF_BEAUTY_PICK)
            return GameActionResult.Rejected("Not in WOLF_BEAUTY_PICK sub-phase")

        return when (action.actionType) {
            ActionType.WOLF_BEAUTY_SKIP -> GameActionResult.Success()

            ActionType.WOLF_BEAUTY_CHARM -> {
                if (nightPhase.dayNumber == 1) return GameActionResult.Rejected("No charm on night 1")
                val targetId = action.targetUserId ?: return GameActionResult.Rejected("Target required")
                if (targetId == actor.userId) return GameActionResult.Rejected("Cannot charm yourself")
                val target = context.alivePlayerById(targetId)
                    ?: return GameActionResult.Rejected("Target not found or dead")
                if (target.role.isPackWolf) return GameActionResult.Rejected("Cannot charm a wolf teammate")
                val lastNight = context.allNightPhases.firstOrNull { it.dayNumber == nightPhase.dayNumber - 1 }
                if (lastNight?.wolfBeautyCharmUserId == targetId)
                    return GameActionResult.Rejected("Cannot charm the same player two nights in a row")

                nightPhase.wolfBeautyCharmUserId = targetId
                nightPhaseRepository.save(nightPhase)
                GameActionResult.Success()
            }

            else -> GameActionResult.Rejected("Unknown wolf beauty action: ${action.actionType}")
        }
    }
}
