package com.werewolf.game.phase

import com.werewolf.model.GamePlayer
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinConditionMode
import com.werewolf.model.WinnerSide
import org.springframework.stereotype.Component

/**
 * When the win check is invoked.
 *
 * POST_VOTE fires both the literal (humans==0) and logical (wolves≥humans with no counterplay)
 * HARD_MODE branches. POST_NIGHT fires only the literal branch — the logical branch is deferred
 * until the next day's vote because the good side has not yet had its discussion + vote window
 * to use counterplay (guard save, witch antidote, hunter shot).
 *
 * See docs/scenarios/scenario-09-e2e.md for the full rule.
 */
enum class WinCheckTrigger { POST_VOTE, POST_NIGHT }

/**
 * Active counterplay tokens at a HARD_MODE win check. All three must be false for the
 * wolves to claim a logical (numeric-parity) win.
 *
 *   hasGuard — a living GUARD (can still protect tomorrow night).
 *   hasWitchWithPotions — a living WITCH holding antidote or poison.
 *   hasHunterWithBullet — a living HUNTER who has not yet fired.
 */
data class HardModeCounterplay(
    val hasGuard: Boolean,
    val hasWitchWithPotions: Boolean,
    val hasHunterWithBullet: Boolean,
) {
    val any: Boolean get() = hasGuard || hasWitchWithPotions || hasHunterWithBullet
}

@Component
class WinConditionChecker {
    /**
     * Returns the winning side if the game has ended, or null if play must continue.
     *
     * The two winning sides are determined independently and explicitly:
     *   • VILLAGER wins iff [checkVillagerWin] returns non-null (wolves fully eliminated).
     *   • WEREWOLF wins iff [checkWolfWin] returns non-null (mode-dependent: literal
     *     elimination in either mode, plus HARD_MODE's post-vote "wolves ≥ humans with
     *     no counterplay" logical branch).
     *
     * VILLAGER is evaluated first because it overrides any wolf-win claim.
     */
    fun check(
        alivePlayers: List<GamePlayer>,
        mode: WinConditionMode,
        trigger: WinCheckTrigger,
        counterplay: HardModeCounterplay,
    ): WinnerSide? {
        val wolves = alivePlayers.count { it.role == PlayerRole.WEREWOLF }
        val humans = alivePlayers.count { it.role != PlayerRole.WEREWOLF }

        checkVillagerWin(wolves)?.let { return it }
        checkWolfWin(wolves, humans, mode, trigger, counterplay)?.let { return it }
        return null
    }

    /** Villager path: the only way villagers win is by killing every wolf. */
    private fun checkVillagerWin(wolves: Int): WinnerSide? =
        if (wolves == 0) WinnerSide.VILLAGER else null

    /** Wolf path: dispatch by win-condition mode; each mode owns its full rule. */
    private fun checkWolfWin(
        wolves: Int,
        humans: Int,
        mode: WinConditionMode,
        trigger: WinCheckTrigger,
        counterplay: HardModeCounterplay,
    ): WinnerSide? = when (mode) {
        WinConditionMode.CLASSIC -> checkClassicWolfWin(wolves, humans)
        WinConditionMode.HARD_MODE -> checkHardModeWolfWin(humans, wolves, trigger, counterplay)
    }

    /** CLASSIC (屠边): wolves win at numeric parity — wolves ≥ humans. */
    private fun checkClassicWolfWin(wolves: Int, humans: Int): WinnerSide? =
        if (wolves >= humans) WinnerSide.WEREWOLF else null

    /**
     * HARD_MODE (屠城):
     *   • Literal: humans == 0 — fires at any trigger (day or night).
     *   • Logical: wolves ≥ humans AND no remaining counterplay — fires POST_VOTE only.
     *     Deferred post-night because the good side has not yet had its discussion + vote
     *     window to exercise counterplay (guard save, witch antidote, hunter shot).
     */
    private fun checkHardModeWolfWin(
        humans: Int,
        wolves: Int,
        trigger: WinCheckTrigger,
        counterplay: HardModeCounterplay,
    ): WinnerSide? {
        if (humans == 0) return WinnerSide.WEREWOLF
        if (trigger == WinCheckTrigger.POST_NIGHT) return null
        if (wolves >= humans && !counterplay.any) return WinnerSide.WEREWOLF
        return null
    }
}
