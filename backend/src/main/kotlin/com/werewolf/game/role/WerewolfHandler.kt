package com.werewolf.game.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.*
import com.werewolf.repository.NightPhaseRepository
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Order(1)
@Component
class WerewolfHandler(
    private val nightPhaseRepository: NightPhaseRepository,
) : RoleHandler {

    override val role = PlayerRole.WEREWOLF

    /**
     * Tracks which games have already had a WOLF_KILL confirmed this night.
     * First WOLF_KILL wins; subsequent attempts from teammates are rejected
     * so that only one submitAction fires per WEREWOLF_PICK sub-phase.
     * Cleared by [resetKillLock] at the start of each new night.
     */
    private val killConfirmed = ConcurrentHashMap<Int, Boolean>()

    /** Clear the kill lock for [gameId] so the next night allows a fresh WOLF_KILL. */
    fun resetKillLock(gameId: Int) {
        killConfirmed.remove(gameId)
    }

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.NIGHT && subPhase == NightSubPhase.WEREWOLF_PICK.name)
            setOf(ActionType.WOLF_KILL, ActionType.WOLF_SELECT, ActionType.WOLF_SKIP)
        else emptySet()

    override fun nightSubPhases(): List<NightSubPhase> = listOf(NightSubPhase.WEREWOLF_PICK)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        if (action.actionType !in setOf(ActionType.WOLF_KILL, ActionType.WOLF_SELECT, ActionType.WOLF_SKIP))
            return GameActionResult.Rejected("Unknown action: ${action.actionType}")

        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (!actor.role.isWolf) return GameActionResult.Rejected("Not a werewolf")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")
        if (nightPhase.subPhase != NightSubPhase.WEREWOLF_PICK)
            return GameActionResult.Rejected("Not in WEREWOLF_PICK sub-phase")

        // Once any wolf has confirmed via WOLF_KILL, the target is locked for
        // the rest of WEREWOLF_PICK. Reject BOTH further WOLF_KILL and
        // WOLF_SELECT — the latter would silently overwrite wolfTargetUserId
        // and disagree with the first wolf's confirmed pick.
        // Regression: prod game 15 (2026-05-01) — wolf1 confirmed kill on
        // player A, wolf2 then SELECTed player B; B died, contradicting the
        // confirmation.
        if (killConfirmed.containsKey(action.gameId))
            return GameActionResult.Rejected("Kill already confirmed by teammate")

        // 空刀: no kill tonight. Clears any target a wolf had selected.
        if (action.actionType == ActionType.WOLF_SKIP) {
            if (context.room.config?.noKillAllowed != true)
                return GameActionResult.Rejected("空刀 (no kill) is not enabled in this room")
            if (killConfirmed.putIfAbsent(action.gameId, true) != null)
                return GameActionResult.Rejected("Kill already confirmed by teammate")
            nightPhase.wolfTargetUserId = null
            nightPhaseRepository.save(nightPhase)
            return GameActionResult.Success()
        }

        val target = action.targetUserId
            ?: return GameActionResult.Rejected("Target required")
        if (context.alivePlayerById(target) == null)
            return GameActionResult.Rejected("Target not found or dead")

        // Lock only a valid kill: an invalid one must not shut the wolves out.
        if (action.actionType == ActionType.WOLF_KILL && killConfirmed.putIfAbsent(action.gameId, true) != null)
            return GameActionResult.Rejected("Kill already confirmed by teammate")

        nightPhase.wolfTargetUserId = target
        nightPhaseRepository.save(nightPhase)

        return if (action.actionType == ActionType.WOLF_SELECT)
            GameActionResult.Success(events = listOf(DomainEvent.WolfSelectionChanged(context.gameId, target)))
        else
            GameActionResult.Success()
    }
}
