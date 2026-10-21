package com.werewolf.game.phase

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.DaySubPhase
import com.werewolf.model.Game
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
 * Priority (confirmed product order, 网易 flow — shot first, then badge):
 *   1. A wolf-killed shooter (HUNTER / BLACK_WOLF_KING; not poisoned, not yet
 *      resolved) or a chained shooter (killed by that shot) fires →
 *      HUNTER_SHOOT_NIGHT_DEATH.
 *   2. An unhandled dead sheriff hands over the badge → BADGE_HANDOVER.
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

        // 1. A shooter who hasn't shot/passed yet → shoot first: a skill resolves
        //    at death, the badge is handed over afterwards (网易 order).
        val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
        if (pendingShooterId(game, nightPhase, players) != null) {
            return DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH
        }

        // 2. Unhandled dead sheriff → badge handover. After a handover the
        //    sheriff points at an alive heir (or null), so this clears.
        val sheriff = game.sheriffUserId
        if (sheriff != null && players.find { it.userId == sheriff }?.alive == false) {
            return DaySubPhase.BADGE_HANDOVER
        }

        // 3. Nothing pending — the host can start the vote from here.
        return DaySubPhase.RESULT_REVEALED
    }

    /**
     * Mark the night-death shoot as resolved (the wolf-killed shooter shot OR
     * passed), so [nextSubPhase] never re-enters HUNTER_SHOOT_NIGHT_DEATH for
     * this night's victim. A chained shot is tracked by Game.chainShooterUserId.
     */
    fun markHunterShootResolved(gameId: Int) {
        val game = gameRepository.findById(gameId).orElse(null) ?: return
        nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).ifPresent {
            it.hunterNightShootResolved = true
            nightPhaseRepository.save(it)
        }
    }

    /** The player who may fire during the day-reveal shot for [gameId], or null. */
    fun pendingShooterUserId(gameId: Int): String? {
        val game = gameRepository.findById(gameId).orElse(null) ?: return null
        val players = gamePlayerRepository.findByGameId(gameId)
        val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
        return pendingShooterId(game, nightPhase, players)
    }

    /**
     * Pure predicate: the chained shooter if one is pending; otherwise the
     * wolf-target userId when that player [PlayerRole.shootsOnDeath], actually
     * died to the wolves this night ([NightOrchestrator.wolfKillLands], so 同守同救
     * counts), was NOT also poisoned (poison disables the gun), did not 自爆 or get
     * taken by the White Wolf King during the sheriff campaign, and has not yet
     * shot or passed. Otherwise null.
     */
    fun pendingShooterId(game: Game, nightPhase: NightPhase?, players: List<GamePlayer>): String? {
        game.chainShooterUserId?.let { return it }
        if (nightPhase == null || nightPhase.hunterNightShootResolved) return null
        val wolfTarget = nightPhase.wolfTargetUserId ?: return null
        if (!NightOrchestrator.wolfKillLands(nightPhase)) return null
        if (wolfTarget == nightPhase.witchPoisonTargetUserId) return null // poisoned → cannot shoot
        // Self-destructed or taken (the night victim is still alive in the sheriff campaign) → no shot.
        if (wolfTarget == game.selfDestructUserId || wolfTarget == game.selfDestructTakenUserId) return null
        val player = players.find { it.userId == wolfTarget } ?: return null
        if (player.alive) return null // survived (e.g. night-1 immunity perk)
        return if (player.role.shootsOnDeath) wolfTarget else null
    }
}
