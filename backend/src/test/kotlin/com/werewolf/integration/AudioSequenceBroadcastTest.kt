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
     * 3. WEREWOLF_PICK → SEER_PICK: wolf_close_eyes.mp3, [gap], seer_open_eyes.mp3
     * 4. SEER_PICK → SEER_RESULT: (empty - viewing result)
     * 5. SEER_RESULT → WITCH_ACT: seer_close_eyes.mp3, [gap], witch_open_eyes.mp3
     * 6. WITCH_ACT → GUARD_PICK: witch_close_eyes.mp3, [gap], guard_open_eyes.mp3
     * 7. GUARD_PICK → DAY: guard_close_eyes.mp3, [then] rooster_crowing.mp3, day_time.mp3
     */
    @Test
    fun `full night flow - all roles alive - complete audio sequence`() {
        val room = room(hasSeer = true, hasWitch = true, hasGuard = true)
        
        // 1. DAY → NIGHT transition
        val nightEntry = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WAITING.name,
            room = room,
        )
        assertThat(nightEntry.audioFiles).containsExactly(
            "goes_dark_close_eyes.mp3",
            "wolf_howl.mp3"
        )
        
        // 2. WAITING → WEREWOLF_PICK
        val wolfOpen = audioService.calculateOpenEyesAudio(NightSubPhase.WEREWOLF_PICK)
        assertThat(wolfOpen).isEqualTo("wolf_open_eyes.mp3")
        
        // 3. WEREWOLF_PICK → SEER_PICK (separate sequences with gap)
        val wolfClose = audioService.calculateCloseEyesAudio(NightSubPhase.WEREWOLF_PICK)
        val seerOpen = audioService.calculateOpenEyesAudio(NightSubPhase.SEER_PICK)
        assertThat(wolfClose).isEqualTo("wolf_close_eyes.mp3")
        assertThat(seerOpen).isEqualTo("seer_open_eyes.mp3")
        
        // 4. SEER_PICK → SEER_RESULT (no audio change)
        val seerPickToResult = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )
        assertThat(seerPickToResult.audioFiles).isEmpty()
        
        // 5. SEER_RESULT → WITCH_ACT (separate sequences with gap)
        val seerClose = audioService.calculateCloseEyesAudio(NightSubPhase.SEER_RESULT)
        val witchOpen = audioService.calculateOpenEyesAudio(NightSubPhase.WITCH_ACT)
        assertThat(seerClose).isEqualTo("seer_close_eyes.mp3")
        assertThat(witchOpen).isEqualTo("witch_open_eyes.mp3")
        
        // 6. WITCH_ACT → GUARD_PICK (separate sequences with gap)
        val witchClose = audioService.calculateCloseEyesAudio(NightSubPhase.WITCH_ACT)
        val guardOpen = audioService.calculateOpenEyesAudio(NightSubPhase.GUARD_PICK)
        assertThat(witchClose).isEqualTo("witch_close_eyes.mp3")
        assertThat(guardOpen).isEqualTo("guard_open_eyes.mp3")
        
        // 7. GUARD_PICK → DAY
        val guardClose = audioService.calculateCloseEyesAudio(NightSubPhase.GUARD_PICK)
        assertThat(guardClose).isEqualTo("guard_close_eyes.mp3")
        
        val dayEntry = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.WAITING.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )
        assertThat(dayEntry.audioFiles).containsExactly(
            "rooster_crowing.mp3",
            "day_time.mp3"
        )
    }

    // ── Dead Role Tests (Information Hiding) ─────────────────────────────────────

    /**
     * Test Case: Seer is dead
     * 
     * When seer is dead, we must play the complete audio sequence:
     * - seer_open_eyes.mp3
     * - [pause to simulate operation time]
     * - seer_close_eyes.mp3
     * 
     * This hides the information that seer is dead from other players.
     */
    @Test
    fun `dead seer - plays complete sequence to hide information`() {
        val sequence = audioService.calculateDeadRoleAudioSequence(
            gameId = 1,
            skippedRoles = listOf(NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT),
            targetSubPhase = NightSubPhase.WITCH_ACT,
        )
        
        // Should play: seer_open_eyes → seer_close_eyes → witch_open_eyes
        assertThat(sequence.audioFiles).containsExactly(
            "seer_open_eyes.mp3",
            "seer_close_eyes.mp3",
            "witch_open_eyes.mp3"
        )
    }

    /**
     * Test Case: Witch is dead
     */
    @Test
    fun `dead witch - plays complete sequence then advances to guard`() {
        val sequence = audioService.calculateDeadRoleAudioSequence(
            gameId = 1,
            skippedRoles = listOf(NightSubPhase.WITCH_ACT),
            targetSubPhase = NightSubPhase.GUARD_PICK,
        )
        
        assertThat(sequence.audioFiles).containsExactly(
            "witch_open_eyes.mp3",
            "witch_close_eyes.mp3",
            "guard_open_eyes.mp3"
        )
    }

    /**
     * Test Case: Guard is dead (last special role)
     */
    @Test
    fun `dead guard - plays complete sequence then transitions to day`() {
        val sequence = audioService.calculateDeadRoleAudioSequence(
            gameId = 1,
            skippedRoles = listOf(NightSubPhase.GUARD_PICK),
            targetSubPhase = NightSubPhase.COMPLETE,
        )
        
        assertThat(sequence.audioFiles).containsExactly(
            "guard_open_eyes.mp3",
            "guard_close_eyes.mp3"
        )
    }

    /**
     * Test Case: Multiple dead roles
     * Each dead role must play complete sequence to hide information.
     */
    @Test
    fun `multiple dead roles - each plays complete sequence`() {
        // Seer and Witch are both dead
        val sequence = audioService.calculateDeadRoleAudioSequence(
            gameId = 1,
            skippedRoles = listOf(
                NightSubPhase.SEER_PICK,
                NightSubPhase.SEER_RESULT,
                NightSubPhase.WITCH_ACT
            ),
            targetSubPhase = NightSubPhase.GUARD_PICK,
        )
        
        assertThat(sequence.audioFiles).containsExactly(
            "seer_open_eyes.mp3",
            "seer_close_eyes.mp3",
            "witch_open_eyes.mp3",
            "witch_close_eyes.mp3",
            "guard_open_eyes.mp3"
        )
    }

    // ── Room Configuration Tests ────────────────────────────────────────────────

    /**
     * Test Case: Room without special roles (only werewolves + villagers)
     */
    @Test
    fun `room without special roles - only werewolves`() {
        val room = room(hasSeer = false, hasWitch = false, hasGuard = false)
        
        // After werewolf picks, transition directly to day
        val wolfClose = audioService.calculateCloseEyesAudio(NightSubPhase.WEREWOLF_PICK)
        assertThat(wolfClose).isEqualTo("wolf_close_eyes.mp3")
        
        val dayEntry = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.WAITING.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
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
        val room = room(hasSeer = true, hasWitch = false, hasGuard = false)
        
        // After seer confirms, transition directly to day
        val seerClose = audioService.calculateCloseEyesAudio(NightSubPhase.SEER_RESULT)
        assertThat(seerClose).isEqualTo("seer_close_eyes.mp3")
    }

    /**
     * Test Case: Room with Seer and Witch (no Guard)
     */
    @Test
    fun `room with seer and witch - no guard`() {
        val room = room(hasSeer = true, hasWitch = true, hasGuard = false)
        
        // After witch acts, transition directly to day
        val witchClose = audioService.calculateCloseEyesAudio(NightSubPhase.WITCH_ACT)
        assertThat(witchClose).isEqualTo("witch_close_eyes.mp3")
    }

    // ── Helper Functions ──────────────────────────────────────────────────────

    private fun room(
        hasSeer: Boolean = false,
        hasWitch: Boolean = false,
        hasGuard: Boolean = false,
    ) = Room(
        roomCode = "ABCD",
        hostUserId = "host:001",
        totalPlayers = 6,
        hasSeer = hasSeer,
        hasWitch = hasWitch,
        hasGuard = hasGuard,
    )
}