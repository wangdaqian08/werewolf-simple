package com.werewolf.unit.night

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the uncovered rows of the night-kill interaction matrix on the static
 * [NightOrchestrator.computeKills] — the single source of truth for kill
 * resolution. The basic rows (plain wolf kill, guard save, antidote save,
 * poison double-kill, win checks) live in NightOrchestratorTest; these are
 * the interaction corners game-flow scenarios rely on.
 */
class NightKillMatrixTest {

    private fun night(
        wolfTarget: String? = null,
        guardTarget: String? = null,
        antidoteUsed: Boolean = false,
        poisonTarget: String? = null,
        dayNumber: Int = 2,
        mechGuard: String? = null,
        mechPoison: String? = null,
        charm: String? = null,
    ) = NightPhase(gameId = 1, dayNumber = dayNumber).also {
        it.wolfBeautyCharmUserId = charm
        it.mechGuardTargetUserId = mechGuard
        it.mechPoisonTargetUserId = mechPoison
        it.wolfTargetUserId = wolfTarget
        it.guardTargetUserId = guardTarget
        it.witchAntidoteUsed = antidoteUsed
        it.witchPoisonTargetUserId = poisonTarget
    }

    private fun computeKills(np: NightPhase, players: List<GamePlayer> = emptyList()) =
        NightOrchestrator.computeKills(np, emptySet(), players)

    @Test
    fun `guard AND antidote on the same wolf target - target DIES (同守同救 奶穿)`() {
        val kills = computeKills(
            night(wolfTarget = "u1", guardTarget = "u1", antidoteUsed = true),
        )
        assertThat(kills).containsExactly("u1")
    }

    @Test
    fun `guard and antidote on different players - antidote still saves the wolf target`() {
        val kills = computeKills(
            night(wolfTarget = "u1", guardTarget = "u2", antidoteUsed = true),
        )
        assertThat(kills).isEmpty()
    }

    @Test
    fun `poison pierces guard protection - the guarded poison target still dies`() {
        val kills = computeKills(
            night(wolfTarget = "u1", guardTarget = "u2", poisonTarget = "u2"),
        )
        assertThat(kills).containsExactlyInAnyOrder("u1", "u2")
    }

    @Test
    fun `guard-saved wolf target who is also poisoned dies by the poison`() {
        val kills = computeKills(
            night(wolfTarget = "u1", guardTarget = "u1", poisonTarget = "u1"),
        )
        assertThat(kills).containsExactly("u1")
    }

    @Test
    fun `poison on the unprotected wolf target produces a single death (distinct)`() {
        val kills = computeKills(
            night(wolfTarget = "u1", poisonTarget = "u1"),
        )
        assertThat(kills).containsExactly("u1")
    }

    @Test
    fun `mech guard blocks the wolf kill`() {
        assertThat(computeKills(night(wolfTarget = "u1", mechGuard = "u1"))).isEmpty()
    }

    @Test
    fun `mech guard blocks the witch poison`() {
        assertThat(computeKills(night(poisonTarget = "u1", mechGuard = "u1"))).isEmpty()
    }

    @Test
    fun `mech guard plus antidote on the wolf target - target lives`() {
        val kills = computeKills(
            night(wolfTarget = "u1", antidoteUsed = true, mechGuard = "u1"),
        )
        assertThat(kills).isEmpty()
    }

    @Test
    fun `mech guard shields the target of the mech poison too`() {
        assertThat(computeKills(night(mechPoison = "u1", mechGuard = "u1"))).isEmpty()
    }

    @Test
    fun `mech poison kills`() {
        assertThat(computeKills(night(mechPoison = "u2"))).containsExactly("u2")
    }

    @Test
    fun `mech poison pierces the real guard - the guarded target still dies`() {
        assertThat(computeKills(night(guardTarget = "u2", mechPoison = "u2"))).containsExactly("u2")
    }

    @Test
    fun `antidote does not undo the mech poison on the wolf target`() {
        val kills = computeKills(night(wolfTarget = "u1", antidoteUsed = true, mechPoison = "u1"))
        assertThat(kills).containsExactly("u1")
    }

    @Test
    fun `wolf kill, witch poison and mech poison on three players`() {
        val kills = computeKills(night(wolfTarget = "u1", poisonTarget = "u2", mechPoison = "u3"))
        assertThat(kills).containsExactlyInAnyOrder("u1", "u2", "u3")
    }

    @Test
    fun `kill list order does not reveal the source`() {
        val kills = computeKills(night(wolfTarget = "u9", poisonTarget = "u1"))
        assertThat(kills).containsExactly("u1", "u9")
    }

    @Test
    fun `night-1 perk is not a decisive save when the mech guard shielded the wolf target`() {
        val saves = NightOrchestrator.night1PerkDecisiveSaves(
            night(wolfTarget = "u1", dayNumber = 1, mechGuard = "u1"),
            setOf("u1"),
        )
        assertThat(saves).isEmpty()
    }

    // ── 殉情 (W9, M) ─────────────────────────────────────────────────────────

    /** u1 = 狼美人, u2 = the charmed player, u3 = a villager. */
    private val board = listOf(
        GamePlayer(gameId = 1, userId = "u1", seatIndex = 1, role = PlayerRole.WOLF_BEAUTY),
        GamePlayer(gameId = 1, userId = "u2", seatIndex = 2, role = PlayerRole.SEER),
        GamePlayer(gameId = 1, userId = "u3", seatIndex = 3, role = PlayerRole.VILLAGER),
    )

    @Test
    fun `witch-poisoned 狼美人 takes the charmed player`() {
        assertThat(computeKills(night(poisonTarget = "u1", charm = "u2"), board)).containsExactly("u1", "u2")
    }

    @Test
    fun `the guard, the antidote and the mech shield do not stop the 殉情`() {
        assertThat(computeKills(night(poisonTarget = "u1", charm = "u2", guardTarget = "u2"), board)).contains("u2")
        assertThat(computeKills(night(wolfTarget = "u2", antidoteUsed = true, poisonTarget = "u1", charm = "u2"), board))
            .containsExactly("u1", "u2")
        assertThat(computeKills(night(mechPoison = "u1", charm = "u2", mechGuard = "u2"), board)).containsExactly("u1", "u2")
    }

    @Test
    fun `no 殉情 without a charm, or while the 狼美人 lives`() {
        assertThat(computeKills(night(poisonTarget = "u1"), board)).containsExactly("u1")
        assertThat(computeKills(night(wolfTarget = "u3", charm = "u2"), board)).containsExactly("u3")
    }

    @Test
    fun `the 殉情 stays on the dawn list when recomputed after the kills are applied`() {
        val applied = board.map { p ->
            GamePlayer(gameId = 1, userId = p.userId, seatIndex = p.seatIndex, role = p.role).also {
                if (p.userId != "u3") { it.alive = false; it.diedDay = 2 }
            }
        }
        assertThat(computeKills(night(poisonTarget = "u1", charm = "u2"), applied)).containsExactly("u1", "u2")
    }

    @Test
    fun `the 殉情 does not reveal its source in the list order`() {
        val players = listOf(
            GamePlayer(gameId = 1, userId = "u9", seatIndex = 1, role = PlayerRole.WOLF_BEAUTY),
            GamePlayer(gameId = 1, userId = "u1", seatIndex = 2, role = PlayerRole.SEER),
        )
        assertThat(computeKills(night(poisonTarget = "u9", charm = "u1"), players)).containsExactly("u1", "u9")
    }
}
