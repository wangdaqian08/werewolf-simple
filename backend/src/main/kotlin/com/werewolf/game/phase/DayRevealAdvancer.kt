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
 * event (the host's REVEAL_NIGHT_RESULT, a badge handover, or a shot), and who
 * gets the private night-death shot. Centralises the rules so [GamePhasePipeline]
 * and [com.werewolf.game.voting.VotingPipeline] agree.
 *
 * A pending shot never shows in the sub-phase: it lives in
 * [Game.pendingShooterUserId], seen only by the shooter. The public flow is:
 *   1. An unhandled dead sheriff hands over the badge → BADGE_HANDOVER.
 *   2. Otherwise the day simply shows its result → RESULT_REVEALED.
 *
 * Always reads FRESH from the DB: a caller's in-memory GameContext is stale
 * once applyNightKills / badge handover have flipped alive flags.
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

        // After a handover the sheriff points at an alive heir (or null), so this clears.
        val sheriff = game.sheriffUserId
        if (sheriff != null && players.find { it.userId == sheriff }?.alive == false) {
            return DaySubPhase.BADGE_HANDOVER
        }
        return DaySubPhase.RESULT_REVEALED
    }

    /** The wolf-killed shooter who gets a private shot at this morning's reveal, or null. */
    fun nightShooterUserId(gameId: Int): String? {
        val game = gameRepository.findById(gameId).orElse(null) ?: return null
        val players = gamePlayerRepository.findByGameId(gameId)
        val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
        return nightShooterId(game, nightPhase, players)
    }

    /**
     * Pure predicate: the wolf-target userId when that player
     * [PlayerRole.shootsOnDeath], actually died to the wolves this night
     * ([NightOrchestrator.wolfKillLands], so 同守同救 counts), was NOT also
     * poisoned (poison disables the gun), and did not 自爆 or get taken by the
     * White Wolf King during the sheriff campaign. Otherwise null.
     */
    fun nightShooterId(game: Game, nightPhase: NightPhase?, players: List<GamePlayer>): String? {
        val wolfTarget = nightPhase?.wolfTargetUserId ?: return null
        if (!NightOrchestrator.wolfKillLands(nightPhase)) return null
        if (wolfTarget == nightPhase.witchPoisonTargetUserId) return null // poisoned → cannot shoot
        // Self-destructed or taken (the night victim is still alive in the sheriff campaign) → no shot.
        if (wolfTarget == game.selfDestructUserId || wolfTarget == game.selfDestructTakenUserId) return null
        val player = players.find { it.userId == wolfTarget } ?: return null
        if (player.alive) return null // survived (e.g. night-1 immunity perk)
        return if (player.role.shootsOnDeath) wolfTarget else null
    }
}
