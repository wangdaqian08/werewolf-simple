package com.werewolf.unit.phase

import com.werewolf.game.phase.HardModeCounterplay
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.GamePlayer
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinConditionMode
import com.werewolf.model.WinnerSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Branch-complete unit tests for [WinConditionChecker].
 *
 * Matrix: VILLAGER path × WOLF path × {CLASSIC, HARD_MODE} × {POST_VOTE, POST_NIGHT} × counterplay flags.
 * See docs/scenarios/scenario-09-e2e.md for the rule these assertions encode.
 */
class WinConditionCheckerTest {

    private val checker = WinConditionChecker()
    private val noCounterplay = HardModeCounterplay(
        hasGuard = false,
        hasWitchWithPotions = false,
        hasHunterWithBullet = false,
    )

    private fun wolf(seat: Int) = player("w$seat", seat, PlayerRole.WEREWOLF)
    private fun villager(seat: Int) = player("v$seat", seat, PlayerRole.VILLAGER)
    private fun player(userId: String, seat: Int, role: PlayerRole) =
        GamePlayer(gameId = 1, userId = userId, seatIndex = seat, role = role)

    // ── VILLAGER path ─────────────────────────────────────────────────────────

    @Test
    fun `wolves zero returns VILLAGER in CLASSIC POST_VOTE`() {
        val result = checker.check(
            alivePlayers = listOf(villager(1), villager(2)),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.VILLAGER)
    }

    @Test
    fun `wolves zero returns VILLAGER in HARD_MODE POST_NIGHT even when humans empty too`() {
        // Edge: no wolves, no humans (everyone dead) — VILLAGER still wins because they killed all wolves.
        // The check runs VILLAGER first and never reaches the HARD_MODE humans==0 literal branch.
        val result = checker.check(
            alivePlayers = emptyList(),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.VILLAGER)
    }

    @Test
    fun `wolves zero with full counterplay flags still returns VILLAGER`() {
        val allCounterplay = HardModeCounterplay(true, true, true)
        val result = checker.check(
            alivePlayers = listOf(villager(1)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = allCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.VILLAGER)
    }

    // ── CLASSIC wolf path ─────────────────────────────────────────────────────

    @Test
    fun `CLASSIC wolves strictly outnumber humans returns WEREWOLF`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3)),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `CLASSIC wolves equal humans returns WEREWOLF`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), villager(2)),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `CLASSIC wolves outnumbered by humans returns null`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), villager(2), villager(3)),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isNull()
    }

    // ── HARD_MODE literal branch ──────────────────────────────────────────────

    @Test
    fun `HARD_MODE literal win fires POST_VOTE when humans zero`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `HARD_MODE literal win fires POST_NIGHT when humans zero`() {
        // Scenario: wolf kill + witch poison wipe the last two humans at night.
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2), wolf(3)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    // ── HARD_MODE logical branch (POST_VOTE only) ─────────────────────────────

    @Test
    fun `HARD_MODE POST_VOTE logical win fires when wolves equal humans and no counterplay`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3), villager(4)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `HARD_MODE POST_VOTE logical win fires when wolves strictly outnumber and no counterplay`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2), wolf(3), villager(4)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `HARD_MODE POST_VOTE logical win BLOCKED by hasGuard`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(hasGuard = true, hasWitchWithPotions = false, hasHunterWithBullet = false),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `HARD_MODE POST_VOTE logical win BLOCKED by hasWitchWithPotions`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(hasGuard = false, hasWitchWithPotions = true, hasHunterWithBullet = false),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `HARD_MODE POST_VOTE logical win BLOCKED by hasHunterWithBullet`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(hasGuard = false, hasWitchWithPotions = false, hasHunterWithBullet = true),
        )
        assertThat(result).isNull()
    }

    // ── HARD_MODE POST_NIGHT shield (logical branch never fires post-night) ───

    @Test
    fun `HARD_MODE POST_NIGHT with no counterplay still returns null when humans remain`() {
        // Regression guard: FullGameCycleTest.HARD_MODE depends on this.
        // wolves(2) >= humans(1) AND no counterplay — POST_VOTE would win, POST_NIGHT must not.
        val result = checker.check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = noCounterplay,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `HARD_MODE wolves outnumbered by humans POST_VOTE returns null`() {
        val result = checker.check(
            alivePlayers = listOf(wolf(1), villager(2), villager(3), villager(4)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isNull()
    }
}
