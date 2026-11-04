package com.werewolf.game.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.NightPhaseRepository
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Mechanical wolf night turn. It always walks both MECH_WOLF_PICK and MECH_WOLF_RESULT,
 * whatever it does (learn, check, poison, protect or skip), so the sequence is no tell.
 * What it may do tonight is [RoleRules.mechNightSkill]; the effects of poison and protect
 * are resolved by the night orchestrator.
 */
@Order(2)
@Component
class MechanicalWolfHandler(
    private val nightPhaseRepository: NightPhaseRepository,
    private val gamePlayerRepository: GamePlayerRepository,
) : RoleHandler {

    override val role = PlayerRole.MECHANICAL_WOLF

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> = when {
        phase == GamePhase.NIGHT && subPhase == NightSubPhase.MECH_WOLF_PICK.name ->
            setOf(ActionType.MECH_LEARN, ActionType.MECH_CHECK, ActionType.MECH_POISON, ActionType.MECH_PROTECT, ActionType.MECH_SKIP)
        phase == GamePhase.NIGHT && subPhase == NightSubPhase.MECH_WOLF_RESULT.name -> setOf(ActionType.MECH_CONFIRM)
        else -> emptySet()
    }

    override fun nightSubPhases(): List<NightSubPhase> =
        listOf(NightSubPhase.MECH_WOLF_PICK, NightSubPhase.MECH_WOLF_RESULT)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.MECHANICAL_WOLF) return GameActionResult.Rejected("Not the mechanical wolf")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")

        if (action.actionType == ActionType.MECH_CONFIRM) {
            if (nightPhase.subPhase != NightSubPhase.MECH_WOLF_RESULT)
                return GameActionResult.Rejected("Not in MECH_WOLF_RESULT sub-phase")
            return GameActionResult.Success()
        }

        if (nightPhase.subPhase != NightSubPhase.MECH_WOLF_PICK)
            return GameActionResult.Rejected("Not in MECH_WOLF_PICK sub-phase")
        if (action.actionType == ActionType.MECH_SKIP) return GameActionResult.Success()

        val skill = RoleRules.mechNightSkill(actor, nightPhase.dayNumber, context.allNightPhases)
        val allowed = when (action.actionType) {
            ActionType.MECH_LEARN -> skill == MechNightSkill.LEARN
            ActionType.MECH_CHECK -> skill == MechNightSkill.CHECK_ROLE || skill == MechNightSkill.CHECK_ALIGNMENT
            ActionType.MECH_POISON -> skill == MechNightSkill.POISON
            ActionType.MECH_PROTECT -> skill == MechNightSkill.PROTECT
            else -> return GameActionResult.Rejected("Unknown mechanical wolf action: ${action.actionType}")
        }
        if (!allowed) return GameActionResult.Rejected("${action.actionType} not available tonight")

        val targetId = action.targetUserId ?: return GameActionResult.Rejected("Target required")
        val target: GamePlayer = context.alivePlayerById(targetId)
            ?: return GameActionResult.Rejected("Target not found or dead")
        // Only the guard's protection may target oneself.
        if (targetId == actor.userId && action.actionType != ActionType.MECH_PROTECT)
            return GameActionResult.Rejected("Cannot target yourself")

        when (action.actionType) {
            ActionType.MECH_LEARN -> {
                // Both fields in one action: mechNightSkill finds the learning night from the NightPhase.
                actor.learnedRole = target.role
                nightPhase.mechLearnTargetUserId = targetId
                gamePlayerRepository.save(actor)
            }
            ActionType.MECH_CHECK -> {
                nightPhase.mechCheckTargetUserId = targetId
                nightPhase.mechCheckResultRole = if (skill == MechNightSkill.CHECK_ROLE) RoleRules.mediumSees(target) else target.role
            }
            ActionType.MECH_POISON -> nightPhase.mechPoisonTargetUserId = targetId
            else -> nightPhase.mechGuardTargetUserId = targetId
        }
        nightPhaseRepository.save(nightPhase)
        return GameActionResult.Success(listOf(DomainEvent.MechWolfResult(context.gameId)))
    }
}
