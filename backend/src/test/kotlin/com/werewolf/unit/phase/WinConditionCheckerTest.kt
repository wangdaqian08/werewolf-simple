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
    private fun seer(seat: Int) = player("s$seat", seat, PlayerRole.SEER)
    private fun player(userId: String, seat: Int, role: PlayerRole) =
        GamePlayer(gameId = 1, userId = userId, seatIndex = seat, role = role)

    private fun check(
        alivePlayers: List<GamePlayer>,
        mode: WinConditionMode,
        trigger: WinCheckTrigger,
        counterplay: HardModeCounterplay,
        roster: List<GamePlayer> = alivePlayers,
    ) = checker.check(alivePlayers, mode, trigger, counterplay, roster)

    // ── VILLAGER path ─────────────────────────────────────────────────────────

    @Test
    fun `wolves zero returns VILLAGER in CLASSIC POST_VOTE`() {
        val result = check(
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
        val result = check(
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
        val result = check(
            alivePlayers = listOf(villager(1)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = allCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.VILLAGER)
    }

    // ── CLASSIC wolf path (屠边) ───────────────────────────────────────────────

    @Test
    fun `CLASSIC counts WHITE_WOLF_KING as a wolf`() {
        val wk = player("wk", 1, PlayerRole.WHITE_WOLF_KING)
        val result = check(
            alivePlayers = listOf(wk, villager(2)),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
            roster = listOf(wk, villager(2), seer(3)),
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `CLASSIC all villagers dead returns WEREWOLF even when wolves are outnumbered`() {
        val guard = player("g3", 3, PlayerRole.GUARD)
        val result = check(
            alivePlayers = listOf(wolf(1), seer(2), guard),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = noCounterplay,
            roster = listOf(wolf(1), seer(2), guard, villager(4), villager(5)),
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `CLASSIC all gods dead returns WEREWOLF even when wolves are outnumbered`() {
        val result = check(
            alivePlayers = listOf(wolf(1), villager(3), villager(4)),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = noCounterplay,
            roster = listOf(wolf(1), seer(2), villager(3), villager(4)),
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `CLASSIC parity with a god and a villager alive returns null`() {
        val alive = listOf(wolf(1), wolf(2), seer(3), villager(4))
        val result = check(
            alivePlayers = alive,
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
            roster = alive + villager(5),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `CLASSIC a revealed idiot still counts as a living god`() {
        val idiot = player("i2", 2, PlayerRole.IDIOT).also { it.idiotRevealed = true; it.canVote = false }
        val roster = listOf(wolf(1), idiot, villager(3))
        assertThat(
            check(roster, WinConditionMode.CLASSIC, WinCheckTrigger.POST_VOTE, noCounterplay, roster),
        ).isNull()
        assertThat(
            check(listOf(wolf(1), idiot), WinConditionMode.CLASSIC, WinCheckTrigger.POST_VOTE, noCounterplay, roster),
        ).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `CLASSIC a side the board never dealt cannot be wiped`() {
        val roster = listOf(wolf(1), villager(2), villager(3))
        assertThat(
            check(listOf(wolf(1), villager(2)), WinConditionMode.CLASSIC, WinCheckTrigger.POST_VOTE, noCounterplay, roster),
        ).isNull()
        assertThat(
            check(listOf(wolf(1)), WinConditionMode.CLASSIC, WinCheckTrigger.POST_VOTE, noCounterplay, roster),
        ).isEqualTo(WinnerSide.WEREWOLF)
    }

    // ── HARD_MODE literal branch ──────────────────────────────────────────────

    @Test
    fun `HARD_MODE literal win fires POST_VOTE when humans zero`() {
        val result = check(
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
        val result = check(
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
        val result = check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3), villager(4)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `HARD_MODE POST_VOTE logical win fires when wolves strictly outnumber and no counterplay`() {
        val result = check(
            alivePlayers = listOf(wolf(1), wolf(2), wolf(3), villager(4)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `HARD_MODE POST_VOTE logical win BLOCKED by hasGuard`() {
        val result = check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(hasGuard = true, hasWitchWithPotions = false, hasHunterWithBullet = false),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `HARD_MODE POST_VOTE logical win BLOCKED by hasWitchWithPotions`() {
        val result = check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(hasGuard = false, hasWitchWithPotions = true, hasHunterWithBullet = false),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `HARD_MODE POST_VOTE logical win BLOCKED by hasHunterWithBullet`() {
        val result = check(
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
        val result = check(
            alivePlayers = listOf(wolf(1), wolf(2), villager(3)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = noCounterplay,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `HARD_MODE wolves outnumbered by humans POST_VOTE returns null`() {
        val result = check(
            alivePlayers = listOf(wolf(1), villager(2), villager(3), villager(4)),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = noCounterplay,
        )
        assertThat(result).isNull()
    }

    // A hunter only shoots once dead, so a living hunter always holds the bullet —
    // whatever other shots (e.g. a BLACK_WOLF_KING's) the game has seen.
    @Test
    fun `HardModeCounterplay of - a living hunter always holds the bullet`() {
        val counterplay = HardModeCounterplay.of(listOf(player("h", 1, PlayerRole.HUNTER), wolf(2)), emptyList())
        assertThat(counterplay.hasHunterWithBullet).isTrue()
    }

    @Test
    fun `HardModeCounterplay of - a living BLACK_WOLF_KING is no good-side counterplay`() {
        val counterplay = HardModeCounterplay.of(listOf(player("k", 1, PlayerRole.BLACK_WOLF_KING), villager(2)), emptyList())
        assertThat(counterplay.any).isFalse()
    }
}
