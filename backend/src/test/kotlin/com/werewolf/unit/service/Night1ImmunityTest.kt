package com.werewolf.unit.service

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.NightPhase
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness

/**
 * Matrix test for the first-night immunity perk in
 * [NightOrchestrator.computePendingKills]: immunity cancels the wolf kill on
 * night 1 only, never affects witch poison, and composes with guard/witch
 * saves without observable difference.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Night1ImmunityTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var winConditionChecker: WinConditionChecker
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader

    private val orchestrator: NightOrchestrator by lazy {
        NightOrchestrator(
            handlers = emptyList(),
            gameRepository = gameRepository,
            gamePlayerRepository = gamePlayerRepository,
            nightPhaseRepository = nightPhaseRepository,
            sheriffElectionRepository = mock(),
            eliminationHistoryRepository = mock(),
            winConditionChecker = winConditionChecker,
            stompPublisher = stompPublisher,
            contextLoader = contextLoader,
            audioService = mock(),
            coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            actionLogService = mock(),
            timing = com.werewolf.config.GameTimingProperties(),
            rewardSettlementService = mock(),
            perkService = mock(),
        )
    }

    private fun night(
        dayNumber: Int = 1,
        wolfTarget: String? = null,
        guardTarget: String? = null,
        antidoteUsed: Boolean = false,
        poisonTarget: String? = null,
    ) = NightPhase(gameId = 1, dayNumber = dayNumber).also {
        it.wolfTargetUserId = wolfTarget
        it.guardTargetUserId = guardTarget
        it.witchAntidoteUsed = antidoteUsed
        it.witchPoisonTargetUserId = poisonTarget
    }

    @Test
    fun `night 1 - immune wolf target survives`() {
        val np = night(dayNumber = 1, wolfTarget = "victim")
        assertThat(orchestrator.computePendingKills(np, setOf("victim"))).isEmpty()
    }

    @Test
    fun `night 1 - non-immune wolf target still dies`() {
        val np = night(dayNumber = 1, wolfTarget = "victim")
        assertThat(orchestrator.computePendingKills(np, setOf("someone-else"))).containsExactly("victim")
    }

    @Test
    fun `night 2 - immunity has expired, holder dies`() {
        val np = night(dayNumber = 2, wolfTarget = "victim")
        assertThat(orchestrator.computePendingKills(np, setOf("victim"))).containsExactly("victim")
    }

    @Test
    fun `witch poison is NOT blocked by immunity (not a wolf kill)`() {
        val np = night(dayNumber = 1, poisonTarget = "victim")
        assertThat(orchestrator.computePendingKills(np, setOf("victim"))).containsExactly("victim")
    }

    @Test
    fun `wolf kill blocked by immunity but poison on same night still lands on other player`() {
        val np = night(dayNumber = 1, wolfTarget = "immune", poisonTarget = "other")
        assertThat(orchestrator.computePendingKills(np, setOf("immune"))).containsExactly("other")
    }

    @Test
    fun `guard save and immunity on the same target are indistinguishable (no kill, no error)`() {
        val np = night(dayNumber = 1, wolfTarget = "victim", guardTarget = "victim")
        assertThat(orchestrator.computePendingKills(np, setOf("victim"))).isEmpty()
    }

    @Test
    fun `empty immune set preserves legacy behavior`() {
        val np = night(dayNumber = 1, wolfTarget = "victim")
        assertThat(orchestrator.computePendingKills(np)).containsExactly("victim")
    }

    // ── night1PerkDecisiveSaves — the settlement trigger predicate ──────────
    // Must be the precise complement of computeKills' other saves for the same
    // target: the perk "took effect" only when it alone blocked the wolf kill.

    @Test
    fun `decisive save - attacked immune target with no other save`() {
        val np = night(dayNumber = 1, wolfTarget = "victim")
        assertThat(NightOrchestrator.night1PerkDecisiveSaves(np, setOf("victim")))
            .containsExactly("victim")
    }

    @Test
    fun `no decisive save - holder was not attacked`() {
        val np = night(dayNumber = 1, wolfTarget = "other")
        assertThat(NightOrchestrator.night1PerkDecisiveSaves(np, setOf("victim"))).isEmpty()
    }

    @Test
    fun `no decisive save - night 2`() {
        val np = night(dayNumber = 2, wolfTarget = "victim")
        assertThat(NightOrchestrator.night1PerkDecisiveSaves(np, setOf("victim"))).isEmpty()
    }

    @Test
    fun `no decisive save - witch antidote also saved the target`() {
        val np = night(dayNumber = 1, wolfTarget = "victim", antidoteUsed = true)
        assertThat(NightOrchestrator.night1PerkDecisiveSaves(np, setOf("victim"))).isEmpty()
    }

    @Test
    fun `no decisive save - guard also protected the target`() {
        val np = night(dayNumber = 1, wolfTarget = "victim", guardTarget = "victim")
        assertThat(NightOrchestrator.night1PerkDecisiveSaves(np, setOf("victim"))).isEmpty()
    }

    @Test
    fun `no decisive save - no wolf target at all`() {
        val np = night(dayNumber = 1, wolfTarget = null)
        assertThat(NightOrchestrator.night1PerkDecisiveSaves(np, setOf("victim"))).isEmpty()
    }
}
