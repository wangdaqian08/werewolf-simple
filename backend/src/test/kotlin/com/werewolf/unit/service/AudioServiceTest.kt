package com.werewolf.unit.service

import com.werewolf.model.*
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.AudioService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class AudioServiceTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private lateinit var audioService: AudioService

    @BeforeEach
    fun setUp() {
        audioService = AudioService(nightPhaseRepository)
    }

    // ── calculatePhaseTransition Tests ──────────────────────────────────────

    @Test
    fun `calculatePhaseTransition - DAY to NIGHT returns goes_dark_close_eyes mp3`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3", "wolf_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - NIGHT to DAY returns day_time mp3`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.DAY)
        assertThat(sequence.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        assertThat(sequence.audioFiles).containsExactly("day_time.mp3","rooster_crowing.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - non-audio phases returns empty audioFiles`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.ROLE_REVEAL,
            newPhase = GamePhase.SHERIFF_ELECTION,
            oldSubPhase = null,
            newSubPhase = null,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.SHERIFF_ELECTION)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    // ── calculateNightSubPhaseTransition Tests ───────────────────────────────

    @Test
    fun `calculateNightSubPhaseTransition - WEREWOLF_PICK to SEER_PICK returns wolf_close_eyes and seer_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.SEER_PICK.name)
        assertThat(sequence.audioFiles).containsExactly(
            "wolf_close_eyes.mp3",
            "seer_open_eyes.mp3"
        )
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - SEER_PICK to SEER_RESULT returns seer_close_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.SEER_RESULT.name)
        assertThat(sequence.audioFiles).containsExactly("seer_close_eyes.mp3")
    }

    @Test
    fun `calculateNightSubPhaseTransition - SEER_RESULT to WITCH_ACT returns seer_close_eyes and witch_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WITCH_ACT.name)
        assertThat(sequence.audioFiles).containsExactly(
            "seer_close_eyes.mp3",
            "witch_open_eyes.mp3"
        )
    }

    @Test
    fun `calculateNightSubPhaseTransition - WITCH_ACT to GUARD_PICK returns witch_close_eyes and guard_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.GUARD_PICK.name)
        assertThat(sequence.audioFiles).containsExactly(
            "witch_close_eyes.mp3",
            "guard_open_eyes.mp3"
        )
    }

    @Test
    fun `calculateNightSubPhaseTransition - WAITING to WEREWOLF_PICK returns wolf_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.WEREWOLF_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("wolf_open_eyes.mp3")
    }

    @Test
    fun `calculateNightSubPhaseTransition - WAITING to SEER_PICK returns seer_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.SEER_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("seer_open_eyes.mp3")
    }

    @Test
    fun `calculateNightSubPhaseTransition - SEER_RESULT to GUARD_PICK returns seer_close_eyes and guard_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.GUARD_PICK.name)
        assertThat(sequence.audioFiles).containsExactly(
            "seer_close_eyes.mp3",
            "guard_open_eyes.mp3"
        )
    }

    // ── ID Generation Tests ───────────────────────────────────────────────────

    @Test
    fun `calculatePhaseTransition - generates unique IDs for different transitions`() {
        val room = room()
        val sequence1 = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )

        val sequence2 = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        assertThat(sequence1.id).isNotEqualTo(sequence2.id)
    }

    @Test
    fun `calculateNightSubPhaseTransition - generates unique IDs for different transitions`() {
        val sequence1 = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        val sequence2 = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )

        assertThat(sequence1.id).isNotEqualTo(sequence2.id)
    }

    // ── Timestamp Tests ───────────────────────────────────────────────────────

    @Test
    fun `calculatePhaseTransition - includes current timestamp`() {
        val room = room()
        val beforeTime = System.currentTimeMillis()
        
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )
        
        val afterTime = System.currentTimeMillis()

        assertThat(sequence.timestamp).isBetween(beforeTime, afterTime)
    }

    // ── Edge Case Tests ───────────────────────────────────────────────────────

    @Test
    fun `calculatePhaseTransition - GAME_OVER phase returns empty audioFiles`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.VOTING,
            newPhase = GamePhase.GAME_OVER,
            oldSubPhase = null,
            newSubPhase = null,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculatePhaseTransition - NIGHT phase with WAITING subPhase returns only goes_dark_close_eyes`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WAITING.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WAITING.name)
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3",)
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - NIGHT phase without subPhase returns only goes_dark_close_eyes`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = null,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isNull()
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - NIGHT phase with invalid subPhase returns only goes_dark_close_eyes`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = "INVALID_SUBPHASE",
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo("INVALID_SUBPHASE")
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - NIGHT phase with SEER_PICK adds seer_open_eyes`() {
        val room = room(hasSeer = true)
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.SEER_PICK.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.SEER_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3", "wolf_howl.mp3", "seer_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - NIGHT phase with WITCH_ACT adds witch_open_eyes`() {
        val room = room(hasWitch = true)
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WITCH_ACT.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WITCH_ACT.name)
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3", "witch_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - NIGHT phase with GUARD_PICK adds guard_open_eyes`() {
        val room = room(hasGuard = true)
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.GUARD_PICK.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.GUARD_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3", "guard_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - ROLE_REVEAL phase returns empty audioFiles`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = null,
            newPhase = GamePhase.ROLE_REVEAL,
            oldSubPhase = null,
            newSubPhase = null,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.ROLE_REVEAL)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculatePhaseTransition - VOTING phase returns empty audioFiles`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.VOTING,
            oldSubPhase = null,
            newSubPhase = null,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.VOTING)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculatePhaseTransition - null oldPhase is handled correctly`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = null,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3", "wolf_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculateNightSubPhaseTransition - same subPhase returns close and open audio`() {
        // In practice same→same transitions shouldn't occur, but code produces close+open
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.WEREWOLF_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("wolf_close_eyes.mp3", "wolf_open_eyes.mp3")
    }

    @Test
    fun `calculateNightSubPhaseTransition - WAITING to SEER_RESULT has no open eyes audio`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.SEER_RESULT.name)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - WAITING to WITCH_ACT returns witch_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WITCH_ACT.name)
        assertThat(sequence.audioFiles).containsExactly("witch_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - WAITING to GUARD_PICK returns guard_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.GUARD_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("guard_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - WAITING to COMPLETE returns empty audioFiles`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.COMPLETE,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.COMPLETE.name)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - WEREWOLF_PICK to WAITING returns wolf_close_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.WAITING,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WAITING.name)
        assertThat(sequence.audioFiles).containsExactly("wolf_close_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - COMPLETE to WEREWOLF_PICK has no close eyes audio`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.COMPLETE,
            newSubPhase = NightSubPhase.WEREWOLF_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("wolf_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - SEER_PICK to WITCH_ACT skips SEER_RESULT`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WITCH_ACT.name)
        assertThat(sequence.audioFiles).containsExactly("seer_close_eyes.mp3", "witch_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - backwards transition WITCH_ACT to SEER_PICK`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.SEER_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("witch_close_eyes.mp3", "seer_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(5)
    }

    @Test
    fun `calculateNightSubPhaseTransition - WEREWOLF_PICK to GUARD_PICK skips intermediate phases`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.GUARD_PICK.name)
        assertThat(sequence.audioFiles).containsExactly("wolf_close_eyes.mp3", "guard_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(5)
    }

    // ── calculateGameStateAudio Tests ─────────────────────────────────────────

    @Test
    fun `calculateGameStateAudio - NIGHT phase with nightSubPhase returns empty audioFiles`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.NIGHT,
            subPhase = null,
            nightSubPhase = NightSubPhase.WEREWOLF_PICK.name,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculateGameStateAudio - NIGHT phase without nightSubPhase returns goes_dark_close_eyes`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.NIGHT,
            subPhase = null,
            nightSubPhase = null,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isNull()
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3")
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculateGameStateAudio - DAY phase returns empty audioFiles`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.DAY,
            subPhase = DaySubPhase.RESULT_REVEALED.name,
            nightSubPhase = null,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.DAY)
        assertThat(sequence.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculateGameStateAudio - ROLE_REVEAL phase returns empty audioFiles`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.ROLE_REVEAL,
            subPhase = null,
            nightSubPhase = null,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.ROLE_REVEAL)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculateGameStateAudio - SHERIFF_ELECTION phase returns empty audioFiles`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.SHERIFF_ELECTION,
            subPhase = null,
            nightSubPhase = null,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.SHERIFF_ELECTION)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculateGameStateAudio - VOTING phase returns empty audioFiles`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.VOTING,
            subPhase = VotingSubPhase.VOTING.name,
            nightSubPhase = null,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.VOTING)
        assertThat(sequence.subPhase).isEqualTo(VotingSubPhase.VOTING.name)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculateGameStateAudio - GAME_OVER phase returns empty audioFiles`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.GAME_OVER,
            subPhase = null,
            nightSubPhase = null,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculateGameStateAudio - NIGHT phase with invalid nightSubPhase returns empty audioFiles`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.NIGHT,
            subPhase = null,
            nightSubPhase = "INVALID_SUBPHASE",
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo("INVALID_SUBPHASE")
        assertThat(sequence.audioFiles).isEmpty()
        assertThat(sequence.priority).isEqualTo(0)
    }

    @Test
    fun `calculateGameStateAudio - generates unique ID with STATE prefix`() {
        val sequence = audioService.calculateGameStateAudio(
            gameId = 123,
            phase = GamePhase.NIGHT,
            subPhase = null,
            nightSubPhase = NightSubPhase.WEREWOLF_PICK.name,
        )

        assertThat(sequence.id).contains("123")
        assertThat(sequence.id).contains("STATE")
        assertThat(sequence.id).contains("NIGHT")
    }

    @Test
    fun `calculateGameStateAudio - includes timestamp`() {
        val beforeTime = System.currentTimeMillis()
        
        val sequence = audioService.calculateGameStateAudio(
            gameId = 1,
            phase = GamePhase.NIGHT,
            subPhase = null,
            nightSubPhase = null,
        )
        
        val afterTime = System.currentTimeMillis()

        assertThat(sequence.timestamp).isBetween(beforeTime, afterTime)
    }

    // ─── Additional Edge Cases ───────────────────────────────────────────────

    @Test
    fun `calculatePhaseTransition - DAY to DAY returns day_time mp3`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY,
            newPhase = GamePhase.DAY,
            oldSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            newSubPhase = DaySubPhase.RESULT_REVEALED.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.DAY)
        assertThat(sequence.audioFiles).containsExactly("day_time.mp3","rooster_crowing.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - NIGHT to NIGHT with different subPhase returns goes_dark_close_eyes and role audio`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            newSubPhase = NightSubPhase.SEER_PICK.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3", "wolf_howl.mp3", "seer_open_eyes.mp3")
        assertThat(sequence.priority).isEqualTo(10)
    }

    @Test
    fun `calculatePhaseTransition - all non-audio phases return priority 0`() {
        val room = room()
        val nonAudioPhases = listOf(
            GamePhase.ROLE_REVEAL,
            GamePhase.SHERIFF_ELECTION,
            GamePhase.VOTING,
            GamePhase.GAME_OVER
        )

        nonAudioPhases.forEach { phase ->
            val sequence = audioService.calculatePhaseTransition(
                gameId = 1,
                oldPhase = GamePhase.DAY,
                newPhase = phase,
                oldSubPhase = null,
                newSubPhase = null,
                room = room,
            )
            assertThat(sequence.priority).isEqualTo(0)
        }
    }

    @Test
    fun `calculatePhaseTransition - audio phases return priority 10`() {
        val room = room()
        val audioPhases = listOf(
            GamePhase.NIGHT,
            GamePhase.DAY
        )

        audioPhases.forEach { phase ->
            val newSubPhase = if (phase == GamePhase.NIGHT) NightSubPhase.WEREWOLF_PICK.name else DaySubPhase.RESULT_HIDDEN.name
            val sequence = audioService.calculatePhaseTransition(
                gameId = 1,
                oldPhase = GamePhase.DAY,
                newPhase = phase,
                oldSubPhase = null,
                newSubPhase = newSubPhase,
                room = room,
            )
            assertThat(sequence.priority).isEqualTo(10)
        }
    }

    @Test
    fun `calculateNightSubPhaseTransition - all role subPhases return priority 5`() {
        val roleSubPhases = listOf(
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.SEER_PICK,
            NightSubPhase.SEER_RESULT,
            NightSubPhase.WITCH_ACT,
            NightSubPhase.GUARD_PICK
        )

        roleSubPhases.forEach { subPhase ->
            val sequence = audioService.calculateNightSubPhaseTransition(
                gameId = 1,
                oldSubPhase = NightSubPhase.WAITING,
                newSubPhase = subPhase,
            )
            assertThat(sequence.priority).isEqualTo(5)
        }
    }

    @Test
    fun `calculateNightSubPhaseTransition - COMPLETE subPhase returns empty audioFiles from WAITING`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.COMPLETE,
        )

        assertThat(sequence.audioFiles).isEmpty()
    }

    @Test
    fun `calculateNightSubPhaseTransition - COMPLETE subPhase returns empty audioFiles from role subPhase`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.COMPLETE,
        )

        assertThat(sequence.audioFiles).containsExactly("wolf_close_eyes.mp3")
    }

    @Test
    fun `calculateGameStateAudio - state audio always returns priority 0`() {
        val phases = listOf(
            GamePhase.NIGHT,
            GamePhase.DAY,
            GamePhase.ROLE_REVEAL,
            GamePhase.SHERIFF_ELECTION,
            GamePhase.VOTING,
            GamePhase.GAME_OVER
        )

        phases.forEach { phase ->
            val sequence = audioService.calculateGameStateAudio(
                gameId = 1,
                phase = phase,
                subPhase = null,
                nightSubPhase = null,
            )
            assertThat(sequence.priority).isEqualTo(0)
        }
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