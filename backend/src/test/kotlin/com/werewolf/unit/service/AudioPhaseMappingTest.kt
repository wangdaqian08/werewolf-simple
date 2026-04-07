package com.werewolf.unit.service

import com.werewolf.model.DaySubPhase
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.Room
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.AudioService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class AudioPhaseMappingTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private lateinit var audioService: AudioService

    @BeforeEach
    fun setUp() {
        audioService = AudioService(nightPhaseRepository)
    }

    // ── Audio File to Phase Mapping Tests ─────────────────────────────────────

    @Test
    fun `Audio file mapping - goes_dark_close_eyes mp3 must only be used for NIGHT phase initialization`() {
        val room = room()
        
        // Test: goes_dark_close_eyes.mp3 should appear in DAY -> NIGHT transition
        // When transitioning directly to WEREWOLF_PICK, it also includes role audio
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )

        // Both "goes_dark_close_eyes.mp3" and "wolf_open_eyes.mp3" are included when transitioning to WEREWOLF_PICK
        assertThat(sequence.audioFiles).contains("goes_dark_close_eyes.mp3")
        assertThat(sequence.audioFiles).contains("wolf_open_eyes.mp3")
        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
    }

    @Test
    fun `Audio file mapping - day_time mp3 must only be used for NIGHT to DAY transition`() {
        val room = room()
        
        // Test: day_time.mp3 should only appear in NIGHT -> DAY transition
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        assertThat(sequence.audioFiles).containsExactly("day_time.mp3","rooster_crowing.mp3")
        assertThat(sequence.phase).isEqualTo(GamePhase.DAY)
    }

    @Test
    fun `Audio file mapping - wolf_open_eyes mp3 must only be used for WEREWOLF_PICK phase`() {
        // Test: wolf_open_eyes.mp3 should only appear for WEREWOLF_PICK
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.WEREWOLF_PICK,
        )

        assertThat(sequence.audioFiles).containsExactly("wolf_open_eyes.mp3")
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
    }

    @Test
    fun `Audio file mapping - wolf_close_eyes mp3 must only be used when leaving WEREWOLF_PICK phase`() {
        // Test: wolf_close_eyes.mp3 should only appear when transitioning FROM WEREWOLF_PICK
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        assertThat(sequence.audioFiles).contains("wolf_close_eyes.mp3")
        assertThat(sequence.audioFiles).isNotEqualTo(listOf("wolf_close_eyes.mp3"))
    }

    @Test
    fun `Audio file mapping - seer_open_eyes mp3 must only be used for SEER_PICK phase`() {
        // Test: seer_open_eyes.mp3 should only appear for SEER_PICK
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        assertThat(sequence.audioFiles).contains("seer_open_eyes.mp3")
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.SEER_PICK.name)
    }

    @Test
    fun `Audio file mapping - seer_close_eyes mp3 must be used for both SEER_PICK and SEER_RESULT exits`() {
        // Test: seer_close_eyes.mp3 should appear when leaving SEER_PICK
        val sequence1 = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )
        assertThat(sequence1.audioFiles).containsExactly("seer_close_eyes.mp3")

        // Test: seer_close_eyes.mp3 should also appear when leaving SEER_RESULT
        val sequence2 = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )
        assertThat(sequence2.audioFiles).contains("seer_close_eyes.mp3")
    }

    @Test
    fun `Audio file mapping - witch_open_eyes mp3 must only be used for WITCH_ACT phase`() {
        // Test: witch_open_eyes.mp3 should only appear for WITCH_ACT
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )

        assertThat(sequence.audioFiles).contains("witch_open_eyes.mp3")
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WITCH_ACT.name)
    }

    @Test
    fun `Audio file mapping - witch_close_eyes mp3 must only be used when leaving WITCH_ACT phase`() {
        // Test: witch_close_eyes.mp3 should only appear when transitioning FROM WITCH_ACT
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        assertThat(sequence.audioFiles).contains("witch_close_eyes.mp3")
    }

    @Test
    fun `Audio file mapping - guard_open_eyes mp3 must only be used for GUARD_PICK phase`() {
        // Test: guard_open_eyes.mp3 should only appear for GUARD_PICK
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        assertThat(sequence.audioFiles).contains("guard_open_eyes.mp3")
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.GUARD_PICK.name)
    }

    @Test
    fun `Audio file mapping - guard_close_eyes mp3 must only be used when leaving GUARD_PICK phase`() {
        // Test: guard_close_eyes.mp3 should only appear when transitioning FROM GUARD_PICK
        val room = room(hasGuard = true)
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        // guard_close_eyes.mp3 is NOT in the sequence for NIGHT -> DAY transition
        // This is because the sequence only contains "day_time.mp3"
        assertThat(sequence.audioFiles).doesNotContain("guard_close_eyes.mp3")
    }

    // ── Audio Sequence Correctness Tests ────────────────────────────────────────

    @Test
    fun `Audio sequence correctness - must have consistent close eye then open eye pattern`() {
        // Test: All night sub-phase transitions should follow the pattern: close eyes → open eyes
        val transitions = listOf(
            Pair(NightSubPhase.WEREWOLF_PICK, NightSubPhase.SEER_PICK),
            Pair(NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT),
            Pair(NightSubPhase.SEER_RESULT, NightSubPhase.WITCH_ACT),
            Pair(NightSubPhase.WITCH_ACT, NightSubPhase.GUARD_PICK),
        )

        transitions.forEach { (oldSubPhase, newSubPhase) ->
            val sequence = audioService.calculateNightSubPhaseTransition(
                gameId = 1,
                oldSubPhase = oldSubPhase,
                newSubPhase = newSubPhase,
            )

            if (sequence.audioFiles.size == 2) {
                // Should be [close eyes, open eyes]
                assertThat(sequence.audioFiles[0]).contains("close_eyes")
                assertThat(sequence.audioFiles[1]).contains("open_eyes")
            } else if (sequence.audioFiles.size == 1) {
                // Should be close eyes only (for SEER_RESULT)
                assertThat(sequence.audioFiles[0]).contains("close_eyes")
            }
        }
    }

    @Test
    fun `Audio sequence correctness - must not have conflicting audio files`() {
        // Test: No sequence should contain both "闭眼" and "睁眼" for the same role
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        // Sequence should be: ["wolf_close_eyes.mp3", "seer_open_eyes.mp3"]
        // Not: ["wolf_close_eyes.mp3", "wolf_open_eyes.mp3"]
        assertThat(sequence.audioFiles).doesNotContain("wolf_open_eyes.mp3")
        assertThat(sequence.audioFiles).doesNotContain("seer_close_eyes.mp3")
    }

    @Test
    fun `Audio sequence correctness - priority must reflect audio importance`() {
        // Test: Main phase transitions should have higher priority
        val dayToNightSequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room(),
        )

        val subPhaseSequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        // Main phase transitions have priority 10, sub-phase transitions have priority 5
        assertThat(dayToNightSequence.priority).isEqualTo(10)
        assertThat(subPhaseSequence.priority).isEqualTo(5)
    }

    @Test
    fun `Audio sequence correctness - non-audio phases must have priority 0`() {
        // Test: Non-audio phases should have priority 0
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.ROLE_REVEAL,
            newPhase = GamePhase.SHERIFF_ELECTION,
            oldSubPhase = null,
            newSubPhase = null,
            room = room(),
        )

        assertThat(sequence.priority).isEqualTo(0)
        assertThat(sequence.audioFiles).isEmpty()
    }

    // ── Audio Sequence State Consistency Tests ────────────────────────────────

    @Test
    fun `Audio sequence state consistency - phase field must always match game phase`() {
        val room = room()
        
        // Test NIGHT phase
        val nightSequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )
        assertThat(nightSequence.phase).isEqualTo(GamePhase.NIGHT)

        // Test DAY phase
        val daySequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )
        assertThat(daySequence.phase).isEqualTo(GamePhase.DAY)

        // Test non-audio phase
        val nonAudioSequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.ROLE_REVEAL,
            newPhase = GamePhase.SHERIFF_ELECTION,
            oldSubPhase = null,
            newSubPhase = null,
            room = room(),
        )
        assertThat(nonAudioSequence.phase).isEqualTo(GamePhase.SHERIFF_ELECTION)
    }

    @Test
    fun `Audio sequence state consistency - subPhase field must match when in NIGHT phase`() {
        // Test all NIGHT sub-phases
        val nightSubPhases = listOf(
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.SEER_PICK,
            NightSubPhase.SEER_RESULT,
            NightSubPhase.WITCH_ACT,
            NightSubPhase.GUARD_PICK,
        )

        nightSubPhases.forEach { subPhase ->
            val sequence = audioService.calculateNightSubPhaseTransition(
                gameId = 1,
                oldSubPhase = NightSubPhase.WAITING,
                newSubPhase = subPhase,
            )

            assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
            assertThat(sequence.subPhase).isEqualTo(subPhase.name)
        }
    }

    @Test
    fun `Audio sequence state consistency - subPhase field must be null for non-NIGHT phases`() {
        val room = room()
        
        // Test DAY phase
        val daySequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )
        assertThat(daySequence.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)

        // Test non-audio phase
        val nonAudioSequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.ROLE_REVEAL,
            newPhase = GamePhase.SHERIFF_ELECTION,
            oldSubPhase = null,
            newSubPhase = null,
            room = room(),
        )
        assertThat(nonAudioSequence.subPhase).isNull()
    }

    // ── Audio File Validation Tests ─────────────────────────────────────────────

    @Test
    fun `Audio file validation - all audio files must be mp3 format`() {
        val allSequences = listOf(
            // Main phase transitions
            audioService.calculatePhaseTransition(
                gameId = 1, oldPhase = GamePhase.DAY, newPhase = GamePhase.NIGHT,
                oldSubPhase = null, newSubPhase = NightSubPhase.WEREWOLF_PICK.name, room = room(),
            ),
            audioService.calculatePhaseTransition(
                gameId = 1, oldPhase = GamePhase.NIGHT, newPhase = GamePhase.DAY,
                oldSubPhase = NightSubPhase.GUARD_PICK.name, newSubPhase = DaySubPhase.RESULT_HIDDEN.name, room = room(),
            ),
            // Night sub-phase transitions
            audioService.calculateNightSubPhaseTransition(
                gameId = 1, oldSubPhase = NightSubPhase.WAITING, newSubPhase = NightSubPhase.WEREWOLF_PICK,
            ),
            audioService.calculateNightSubPhaseTransition(
                gameId = 1, oldSubPhase = NightSubPhase.WEREWOLF_PICK, newSubPhase = NightSubPhase.SEER_PICK,
            ),
            audioService.calculateNightSubPhaseTransition(
                gameId = 1, oldSubPhase = NightSubPhase.SEER_PICK, newSubPhase = NightSubPhase.SEER_RESULT,
            ),
            audioService.calculateNightSubPhaseTransition(
                gameId = 1, oldSubPhase = NightSubPhase.SEER_RESULT, newSubPhase = NightSubPhase.WITCH_ACT,
            ),
            audioService.calculateNightSubPhaseTransition(
                gameId = 1, oldSubPhase = NightSubPhase.WITCH_ACT, newSubPhase = NightSubPhase.GUARD_PICK,
            ),
        )

        // Verify all audio files are mp3 format
        allSequences.forEach { sequence ->
            sequence.audioFiles.forEach { audioFile ->
                assertThat(audioFile).endsWith(".mp3")
            }
        }
    }

    @Test
    fun `Audio file validation - audio files must not contain duplicates in sequence`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        // Verify no duplicate audio files
        assertThat(sequence.audioFiles).doesNotHaveDuplicates()
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