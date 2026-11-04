package com.werewolf.unit.night

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.NightPhase
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
    ) = NightPhase(gameId = 1, dayNumber = dayNumber).also {
        it.mechGuardTargetUserId = mechGuard
        it.mechPoisonTargetUserId = mechPoison
        it.wolfTargetUserId = wolfTarget
        it.guardTargetUserId = guardTarget
        it.witchAntidoteUsed = antidoteUsed
        it.witchPoisonTargetUserId = poisonTarget
    }

    @Test
    fun `guard AND antidote on the same wolf target - target DIES (同守同救 奶穿)`() {
        val kills = NightOrchestrator.computeKills(
            night(wolfTarget = "u1", guardTarget = "u1", antidoteUsed = true),
        )
        assertThat(kills).containsExactly("u1")
    }

    @Test
    fun `guard and antidote on different players - antidote still saves the wolf target`() {
        val kills = NightOrchestrator.computeKills(
            night(wolfTarget = "u1", guardTarget = "u2", antidoteUsed = true),
        )
        assertThat(kills).isEmpty()
    }

    @Test
    fun `poison pierces guard protection - the guarded poison target still dies`() {
        val kills = NightOrchestrator.computeKills(
            night(wolfTarget = "u1", guardTarget = "u2", poisonTarget = "u2"),
        )
        assertThat(kills).containsExactlyInAnyOrder("u1", "u2")
    }

    @Test
    fun `guard-saved wolf target who is also poisoned dies by the poison`() {
        val kills = NightOrchestrator.computeKills(
            night(wolfTarget = "u1", guardTarget = "u1", poisonTarget = "u1"),
        )
        assertThat(kills).containsExactly("u1")
    }

    @Test
    fun `poison on the unprotected wolf target produces a single death (distinct)`() {
        val kills = NightOrchestrator.computeKills(
            night(wolfTarget = "u1", poisonTarget = "u1"),
        )
        assertThat(kills).containsExactly("u1")
    }

    @Test
    fun `mech guard blocks the wolf kill`() {
        assertThat(NightOrchestrator.computeKills(night(wolfTarget = "u1", mechGuard = "u1"))).isEmpty()
    }

    @Test
    fun `mech guard blocks the witch poison`() {
        assertThat(NightOrchestrator.computeKills(night(poisonTarget = "u1", mechGuard = "u1"))).isEmpty()
    }

    @Test
    fun `mech guard plus antidote on the wolf target - target lives`() {
        val kills = NightOrchestrator.computeKills(
            night(wolfTarget = "u1", antidoteUsed = true, mechGuard = "u1"),
        )
        assertThat(kills).isEmpty()
    }

    @Test
    fun `mech guard shields the target of the mech poison too`() {
        assertThat(NightOrchestrator.computeKills(night(mechPoison = "u1", mechGuard = "u1"))).isEmpty()
    }

    @Test
    fun `mech poison kills`() {
        assertThat(NightOrchestrator.computeKills(night(mechPoison = "u2"))).containsExactly("u2")
    }

    @Test
    fun `mech poison pierces the real guard - the guarded target still dies`() {
        assertThat(NightOrchestrator.computeKills(night(guardTarget = "u2", mechPoison = "u2"))).containsExactly("u2")
    }

    @Test
    fun `antidote does not undo the mech poison on the wolf target`() {
        val kills = NightOrchestrator.computeKills(night(wolfTarget = "u1", antidoteUsed = true, mechPoison = "u1"))
        assertThat(kills).containsExactly("u1")
    }

    @Test
    fun `wolf kill, witch poison and mech poison on three players`() {
        val kills = NightOrchestrator.computeKills(night(wolfTarget = "u1", poisonTarget = "u2", mechPoison = "u3"))
        assertThat(kills).containsExactlyInAnyOrder("u1", "u2", "u3")
    }

    @Test
    fun `kill list order does not reveal the source`() {
        val kills = NightOrchestrator.computeKills(night(wolfTarget = "u9", poisonTarget = "u1"))
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
}
