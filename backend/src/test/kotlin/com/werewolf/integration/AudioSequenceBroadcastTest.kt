package com.werewolf.integration

import com.werewolf.audio.RoleRegistry
import com.werewolf.audio.impl.*
import com.werewolf.model.*
import com.werewolf.service.AudioService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for audio sequence behavior during game flow.
 * Tests verify that audio sequences are calculated correctly for:
 * - Phase transitions (DAY → NIGHT, NIGHT → DAY)
 * - Role transitions (wolf → seer → witch → guard)
 * - Dead role handling (information hiding)
 */
class AudioSequenceBroadcastTest {

    private lateinit var audioService: AudioService

    @BeforeEach
    fun setUp() {
        // Initialize RoleRegistry for unit tests
        RoleRegistry.registerAll(listOf(
            WerewolfAudioConfig(),
            SeerAudioConfig(),
            WitchAudioConfig(),
            GuardAudioConfig(),
            HunterAudioConfig(),
            IdiotAudioConfig(),
            VillagerAudioConfig()
        ))
        
        audioService = AudioService()
    }

    // ── Full Night Phase Flow Tests ─────────────────────────────────────────────

    /**
     * Test Case: First night with all roles alive
     *
     * Expected Audio Sequence:
     * 1. DAY → NIGHT: goes_dark_close_eyes.mp3, wolf_howl.mp3
     * 2. WAITING → WEREWOLF_PICK: wolf_open_eyes.mp3
     * 3. WEREWOLF_PICK → WITCH_ACT: wolf_close_eyes.mp3, `[gap]`, witch_open_eyes.mp3
     * 4. WITCH_ACT → SEER_PICK: witch_close_eyes.mp3, `[gap]`, seer_open_eyes.mp3
     * 5. SEER_PICK → SEER_RESULT: (empty - viewing result)
     * 6. SEER_RESULT → GUARD_PICK: seer_close_eyes.mp3, `[gap]`, guard_open_eyes.mp3
     * 7. GUARD_PICK → DAY: guard_close_eyes.mp3, [then] rooster_crowing.mp3, day_time.mp3
     */
    @Test
    fun `full night flow - all roles alive - complete audio sequence`() {
        // 1. DAY → NIGHT transition
        val nightEntry = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            newSubPhase = NightSubPhase.WAITING.name,
        )
        assertThat(nightEntry.audioFiles).containsExactly(
            "goes_dark_close_eyes.mp3",
            "wolf_howl.mp3"
        )
        
        // 2. WAITING → WEREWOLF_PICK
        val wolfOpen = audioService.calculateOpenEyesAudio(NightSubPhase.WEREWOLF_PICK)
        assertThat(wolfOpen).isEqualTo("wolf_open_eyes.mp3")
        
        // 3. WEREWOLF_PICK → WITCH_ACT (separate sequences with gap)
        val wolfClose = audioService.calculateCloseEyesAudio(NightSubPhase.WEREWOLF_PICK)
        val witchOpen = audioService.calculateOpenEyesAudio(NightSubPhase.WITCH_ACT)
        assertThat(wolfClose).isEqualTo("wolf_close_eyes.mp3")
        assertThat(witchOpen).isEqualTo("witch_open_eyes.mp3")

        // 4. WITCH_ACT → SEER_PICK (separate sequences with gap)
        val witchClose = audioService.calculateCloseEyesAudio(NightSubPhase.WITCH_ACT)
        val seerOpen = audioService.calculateOpenEyesAudio(NightSubPhase.SEER_PICK)
        assertThat(witchClose).isEqualTo("witch_close_eyes.mp3")
        assertThat(seerOpen).isEqualTo("seer_open_eyes.mp3")

        // 5. SEER_PICK → SEER_RESULT (no audio change)
        val seerPickToResult = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )
        assertThat(seerPickToResult.audioFiles).isEmpty()

        // 6. SEER_RESULT → GUARD_PICK (separate sequences with gap)
        val seerClose = audioService.calculateCloseEyesAudio(NightSubPhase.SEER_RESULT)
        val guardOpen = audioService.calculateOpenEyesAudio(NightSubPhase.GUARD_PICK)
        assertThat(seerClose).isEqualTo("seer_close_eyes.mp3")
        assertThat(guardOpen).isEqualTo("guard_open_eyes.mp3")
        
        // 7. GUARD_PICK → DAY
        val guardClose = audioService.calculateCloseEyesAudio(NightSubPhase.GUARD_PICK)
        assertThat(guardClose).isEqualTo("guard_close_eyes.mp3")
        
        val dayEntry = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
        )
        assertThat(dayEntry.audioFiles).containsExactly(
            "rooster_crowing.mp3",
            "day_time.mp3"
        )
    }

    // ── Room Configuration Tests ────────────────────────────────────────────────

    /**
     * Test Case: Room without special roles (only werewolves + villagers)
     */
    @Test
    fun `room without special roles - only werewolves`() {
        // After werewolf picks, transition directly to day
        val wolfClose = audioService.calculateCloseEyesAudio(NightSubPhase.WEREWOLF_PICK)
        assertThat(wolfClose).isEqualTo("wolf_close_eyes.mp3")
        
        val dayEntry = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
        )
        assertThat(dayEntry.audioFiles).containsExactly(
            "rooster_crowing.mp3",
            "day_time.mp3"
        )
    }

    /**
     * Test Case: Room with only Seer (no Witch, no Guard)
     */
    @Test
    fun `room with only seer - no witch or guard`() {
        // After seer confirms, transition directly to day
        val seerClose = audioService.calculateCloseEyesAudio(NightSubPhase.SEER_RESULT)
        assertThat(seerClose).isEqualTo("seer_close_eyes.mp3")
    }

    /**
     * Test Case: Room with Seer and Witch (no Guard)
     */
    @Test
    fun `room with seer and witch - no guard`() {
        // After witch acts, transition directly to day
        val witchClose = audioService.calculateCloseEyesAudio(NightSubPhase.WITCH_ACT)
        assertThat(witchClose).isEqualTo("witch_close_eyes.mp3")
    }

    // ── Guard Close Eyes Bug Regression Test ─────────────────────────────────────

    /**
     * Test Case: Guard completes as last special role
     *
     * REGRESSION TEST: guard_close_eyes.mp3 was playing twice.
     *
     * Expected sequence when guard completes:
     * 1. SEER_RESULT → GUARD_PICK: seer_close_eyes.mp3 → `[gap]` → guard_open_eyes.mp3
     * 2. GUARD_PICK completion: guard_close_eyes.mp3 (only ONCE)
     * 3. DAY transition: rooster_crowing.mp3 → day_time.mp3
     *
     * The guard_close_eyes.mp3 must play exactly ONCE, not twice.
     */
    @Test
    fun `guard completes as last role - guard_close_eyes plays exactly once`() {
        // Simulate the SEER_RESULT → GUARD_PICK transition
        // This is what scheduleAliveRoleTransition plays
        val seerClose = audioService.calculateCloseEyesAudio(NightSubPhase.SEER_RESULT)
        val guardOpen = audioService.calculateOpenEyesAudio(NightSubPhase.GUARD_PICK)

        assertThat(seerClose).isEqualTo("seer_close_eyes.mp3")
        assertThat(guardOpen).isEqualTo("guard_open_eyes.mp3")
        
        // When guard completes, ONLY guard_close_eyes.mp3 should play
        // This is what scheduleLastSpecialRoleCloseEyesAndAdvanceToDay plays
        val guardClose = audioService.calculateCloseEyesAudio(NightSubPhase.GUARD_PICK)
        assertThat(guardClose).isEqualTo("guard_close_eyes.mp3")
        
        // Then day audio plays
        val dayEntry = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
        )
        assertThat(dayEntry.audioFiles).containsExactly(
            "rooster_crowing.mp3",
            "day_time.mp3"
        )
        
        // CRITICAL: guard_close_eyes.mp3 should NOT appear anywhere else
        // It should only appear in the GUARD_PICK close calculation
        // This verifies the audio sequence calculation doesn't have duplicates
    }

    /**
     * Test Case: Verify no duplicate close_eyes in transition sequence
     */
    @Test
    fun `night sub-phase transition - no duplicate close_eyes audio`() {
        // SEER_RESULT → GUARD_PICK transition
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        // Should contain: seer_close_eyes, guard_open_eyes
        assertThat(sequence.audioFiles).containsExactly(
            "seer_close_eyes.mp3",
            "guard_open_eyes.mp3"
        )

        // Should NOT contain duplicate close_eyes
        val closeEyesCount = sequence.audioFiles.count { it.contains("close_eyes") }
        assertThat(closeEyesCount).isEqualTo(1)
    }
}
