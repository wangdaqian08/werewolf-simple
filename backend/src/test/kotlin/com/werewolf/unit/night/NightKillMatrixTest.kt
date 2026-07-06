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
    ) = NightPhase(gameId = 1, dayNumber = dayNumber).also {
        it.wolfTargetUserId = wolfTarget
        it.guardTargetUserId = guardTarget
        it.witchAntidoteUsed = antidoteUsed
        it.witchPoisonTargetUserId = poisonTarget
    }

    @Test
    fun `guard AND antidote on the same wolf target - target LIVES (同守同救 house rule)`() {
        // Traditional 狼人杀 kills a same-night guarded-and-saved target (奶穿);
        // this codebase treats any save as a save. Pinned so a future rule
        // change is a deliberate decision, not an accident.
        val kills = NightOrchestrator.computeKills(
            night(wolfTarget = "u1", guardTarget = "u1", antidoteUsed = true),
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
}
