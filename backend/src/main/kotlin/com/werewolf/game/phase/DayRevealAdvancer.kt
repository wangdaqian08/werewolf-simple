package com.werewolf.game.phase

import com.werewolf.model.DaySubPhase
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import org.springframework.stereotype.Component

/**
 * Decides the next [DaySubPhase] during the day-reveal flow after any death
 * event (the host's REVEAL_NIGHT_RESULT, a badge handover, or a night-death
 * hunter's shot). Centralises the priority so [GamePhasePipeline] and
 * [com.werewolf.game.voting.VotingPipeline] agree.
 *
 * Priority (confirmed product order — badge first, then hunter):
 *   1. An unhandled dead sheriff hands over the badge → BADGE_HANDOVER.
 *   2. A wolf-killed hunter (not poisoned, not yet resolved) shoots →
 *      HUNTER_SHOOT_NIGHT_DEATH.
 *   3. Otherwise the day simply shows its result → RESULT_REVEALED.
 *
 * Always reads FRESH from the DB: a caller's in-memory GameContext is stale
 * once applyNightKills / badge handover have flipped alive flags. Assumes the
 * night kills have already been applied (true: revealNightResult applies them
 * before consulting this advancer).
 */
@Component
class DayRevealAdvancer(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val nightPhaseRepository: NightPhaseRepository,
) {
    /** The next day sub-phase given the current persisted state of [gameId]. */
    fun nextSubPhase(gameId: Int): DaySubPhase {
        val game = gameRepository.findById(gameId).orElse(null) ?: return DaySubPhase.RESULT_REVEALED
        val players = gamePlayerRepository.findByGameId(gameId)

        // 1. Unhandled dead sheriff → badge handover first. After a handover the
        //    sheriff points at an alive heir (or null), so this clears.
        val sheriff = game.sheriffUserId
        if (sheriff != null && players.find { it.userId == sheriff }?.alive == false) {
            return DaySubPhase.BADGE_HANDOVER
        }

        // 2. Wolf-killed hunter who hasn't shot/passed yet → hunter shoot.
        val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
        if (pendingHunterId(nightPhase, players) != null) {
            return DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH
        }

        // 3. Nothing pending — the host can start the vote from here.
        return DaySubPhase.RESULT_REVEALED
    }

    /**
     * Mark the night-death hunter shoot as resolved (the hunter shot OR passed),
     * so [nextSubPhase] never re-enters HUNTER_SHOOT_NIGHT_DEATH for this night.
     */
    fun markHunterShootResolved(gameId: Int) {
        val game = gameRepository.findById(gameId).orElse(null) ?: return
        nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).ifPresent {
            it.hunterNightShootResolved = true
            nightPhaseRepository.save(it)
        }
    }

    /** The wolf-killed hunter eligible to shoot at day reveal for [gameId], or null. */
    fun pendingHunterUserId(gameId: Int): String? {
        val game = gameRepository.findById(gameId).orElse(null) ?: return null
        val players = gamePlayerRepository.findByGameId(gameId)
        val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
        return pendingHunterId(nightPhase, players)
    }

    /**
     * Pure predicate: returns the wolf-target userId when that player is a
     * HUNTER who actually died to the wolves this night (not antidote-saved, not
     * guard-protected), was NOT also poisoned (poison disables the gun), and has
     * not yet shot or passed. Otherwise null.
     */
    fun pendingHunterId(nightPhase: NightPhase?, players: List<GamePlayer>): String? {
        if (nightPhase == null || nightPhase.hunterNightShootResolved) return null
        val wolfTarget = nightPhase.wolfTargetUserId ?: return null
        val wolfKilled = !nightPhase.witchAntidoteUsed && nightPhase.guardTargetUserId != wolfTarget
        if (!wolfKilled) return null
        if (wolfTarget == nightPhase.witchPoisonTargetUserId) return null // poisoned → cannot shoot
        val player = players.find { it.userId == wolfTarget } ?: return null
        return if (player.role == PlayerRole.HUNTER) wolfTarget else null
    }
}
