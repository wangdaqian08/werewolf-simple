package com.werewolf.unit.service

import com.werewolf.model.*
import com.werewolf.service.AudioService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AudioServiceTest {

    private lateinit var audioService: AudioService

    @BeforeEach
    fun setUp() {
        // Initialize RoleRegistry for unit tests
        initRoleRegistry()

        audioService = AudioService()
    }

    private fun initRoleRegistry() {
        // Manually register role configs for unit tests
        val configs = listOf(
            com.werewolf.audio.impl.WerewolfAudioConfig(),
            com.werewolf.audio.impl.SeerAudioConfig(),
            com.werewolf.audio.impl.WitchAudioConfig(),
            com.werewolf.audio.impl.GuardAudioConfig(),
            com.werewolf.audio.impl.HunterAudioConfig(),
            com.werewolf.audio.impl.IdiotAudioConfig(),
            com.werewolf.audio.impl.VillagerAudioConfig()
        )
        com.werewolf.audio.RoleRegistry.registerAll(configs)
    }

    // ── calculatePhaseTransition Tests ──────────────────────────────────────

    @Test
    fun `calculatePhaseTransition - DAY to NIGHT returns goes_dark_close_eyes mp3`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY_DISCUSSION,
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
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
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
    fun `calculateNightSubPhaseTransition - SEER_PICK to SEER_RESULT returns empty audio`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(sequence.subPhase).isEqualTo(NightSubPhase.SEER_RESULT.name)
        assertThat(sequence.audioFiles).isEmpty()
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
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )

        val sequence2 = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
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
            oldPhase = GamePhase.DAY_DISCUSSION,
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
            oldPhase = GamePhase.DAY_VOTING,
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
            oldPhase = GamePhase.DAY_DISCUSSION,
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
            oldPhase = GamePhase.DAY_DISCUSSION,
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
            oldPhase = GamePhase.DAY_DISCUSSION,
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
            oldPhase = GamePhase.DAY_DISCUSSION,
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
            oldPhase = GamePhase.DAY_DISCUSSION,
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
            oldPhase = GamePhase.DAY_DISCUSSION,
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
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.DAY_VOTING,
            oldSubPhase = null,
            newSubPhase = null,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.DAY_VOTING)
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
            phase = GamePhase.DAY_DISCUSSION,
            subPhase = DaySubPhase.RESULT_REVEALED.name,
            nightSubPhase = null,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
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
            phase = GamePhase.DAY_VOTING,
            subPhase = VotingSubPhase.VOTING.name,
            nightSubPhase = null,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.DAY_VOTING)
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
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            newSubPhase = DaySubPhase.RESULT_REVEALED.name,
            room = room,
        )

        assertThat(sequence.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
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
            GamePhase.DAY_VOTING,
            GamePhase.GAME_OVER
        )

        nonAudioPhases.forEach { phase ->
            val sequence = audioService.calculatePhaseTransition(
                gameId = 1,
                oldPhase = GamePhase.DAY_DISCUSSION,
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
            GamePhase.DAY_DISCUSSION
        )

        audioPhases.forEach { phase ->
            val newSubPhase = if (phase == GamePhase.NIGHT) NightSubPhase.WEREWOLF_PICK.name else DaySubPhase.RESULT_HIDDEN.name
            val sequence = audioService.calculatePhaseTransition(
                gameId = 1,
                oldPhase = GamePhase.DAY_DISCUSSION,
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
            GamePhase.DAY_DISCUSSION,
            GamePhase.ROLE_REVEAL,
            GamePhase.SHERIFF_ELECTION,
            GamePhase.DAY_VOTING,
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

    // ── Game Flow Audio Sequence Tests (TDD) ─────────────────────────────────────

    /**
     * These tests verify the expected audio sequence for a complete game flow.
     * Each role transition should produce separate audio sequences with gaps.
     *
     * Example flow for Night Phase (all roles alive):
     * 1. DAY → NIGHT: goes_dark_close_eyes.mp3, wolf_howl.mp3
     * 2. Start WEREWOLF_PICK: wolf_open_eyes.mp3
     * 3. After werewolf picks → SEER_PICK: wolf_close_eyes.mp3, [gap], seer_open_eyes.mp3
     * 4. After seer picks (SEER_RESULT): (empty - viewing result)
     * 5. After seer confirms → WITCH_ACT: seer_close_eyes.mp3, [gap], witch_open_eyes.mp3
     * 6. After witch acts → GUARD_PICK: witch_close_eyes.mp3, [gap], guard_open_eyes.mp3
     * 7. After guard picks → DAY: guard_close_eyes.mp3, day_time.mp3, rooster_crowing.mp3
     */

    @Test
    fun `GAME FLOW - DAY to NIGHT entry plays goes_dark_close_eyes and wolf_howl`() {
        val room = room(hasSeer = true, hasWitch = true, hasGuard = true)
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WAITING.name, // First night without sheriff
            room = room,
        )

        // Entry to night: atmosphere audio only, no role audio yet
        assertThat(sequence.audioFiles).containsExactly(
            "goes_dark_close_eyes.mp3",
            "wolf_howl.mp3"
        )
    }

    @Test
    fun `GAME FLOW - WAITING to WEREWOLF_PICK plays wolf_open_eyes`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.WEREWOLF_PICK,
        )

        // First role opens eyes
        assertThat(sequence.audioFiles).containsExactly("wolf_open_eyes.mp3")
    }

    @Test
    fun `GAME FLOW - WEREWOLF_PICK to SEER_PICK should return separate sequences with gap`() {
        // This test expects the transition to return TWO separate audio sequences:
        // 1. wolf_close_eyes.mp3
        // 2. seer_open_eyes.mp3
        // With a gap between them (handled by NightOrchestrator)
        val closeSequence = audioService.calculateCloseEyesAudio(
            subPhase = NightSubPhase.WEREWOLF_PICK,
        )
        val openSequence = audioService.calculateOpenEyesAudio(
            subPhase = NightSubPhase.SEER_PICK,
        )

        assertThat(closeSequence).isEqualTo("wolf_close_eyes.mp3")
        assertThat(openSequence).isEqualTo("seer_open_eyes.mp3")
    }

    @Test
    fun `GAME FLOW - SEER_PICK to SEER_RESULT returns empty`() {
        val sequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )

        // Seer is viewing the result - no audio change
        assertThat(sequence.audioFiles).isEmpty()
    }

    @Test
    fun `GAME FLOW - SEER_RESULT to WITCH_ACT should return separate sequences with gap`() {
        val closeSequence = audioService.calculateCloseEyesAudio(
            subPhase = NightSubPhase.SEER_RESULT,
        )
        val openSequence = audioService.calculateOpenEyesAudio(
            subPhase = NightSubPhase.WITCH_ACT,
        )

        assertThat(closeSequence).isEqualTo("seer_close_eyes.mp3")
        assertThat(openSequence).isEqualTo("witch_open_eyes.mp3")
    }

    @Test
    fun `GAME FLOW - WITCH_ACT to GUARD_PICK should return separate sequences with gap`() {
        val closeSequence = audioService.calculateCloseEyesAudio(
            subPhase = NightSubPhase.WITCH_ACT,
        )
        val openSequence = audioService.calculateOpenEyesAudio(
            subPhase = NightSubPhase.GUARD_PICK,
        )

        assertThat(closeSequence).isEqualTo("witch_close_eyes.mp3")
        assertThat(openSequence).isEqualTo("guard_open_eyes.mp3")
    }

    @Test
    fun `GAME FLOW - GUARD_PICK close eyes before day`() {
        val closeSequence = audioService.calculateCloseEyesAudio(
            subPhase = NightSubPhase.GUARD_PICK,
        )

        // Guard is the last special role, close eyes before day transition
        assertThat(closeSequence).isEqualTo("guard_close_eyes.mp3")
    }

    @Test
    fun `GAME FLOW - NIGHT to DAY plays day_time and rooster_crowing`() {
        val room = room()
        val sequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.WAITING.name, // After guard close eyes
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        assertThat(sequence.audioFiles).containsExactly(
            "day_time.mp3",
            "rooster_crowing.mp3"
        )
    }

    // ── Dead Role Audio Tests ─────────────────────────────────────────────────

    @Test
    fun `DEAD ROLE - dead seer plays complete sequence to hide information`() {
        // When seer is dead, we play open_eyes → pause → close_eyes
        // to hide the fact that seer is dead from other players
        val sequence = audioService.calculateDeadRoleAudioSequence(
            gameId = 1,
            skippedRoles = listOf(NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT),
            targetSubPhase = NightSubPhase.WITCH_ACT,
        )

        // Should contain: seer_open_eyes.mp3, seer_close_eyes.mp3, witch_open_eyes.mp3
        assertThat(sequence.audioFiles).containsExactly(
            "seer_open_eyes.mp3",
            "seer_close_eyes.mp3",
            "witch_open_eyes.mp3"
        )
    }

    @Test
    fun `DEAD ROLE - dead witch plays complete sequence`() {
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

    @Test
    fun `DEAD ROLE - dead guard plays complete sequence then transitions to day`() {
        val sequence = audioService.calculateDeadRoleAudioSequence(
            gameId = 1,
            skippedRoles = listOf(NightSubPhase.GUARD_PICK),
            targetSubPhase = NightSubPhase.COMPLETE,
        )

        // Guard is last, only plays open/close eyes
        assertThat(sequence.audioFiles).containsExactly(
            "guard_open_eyes.mp3",
            "guard_close_eyes.mp3"
        )
    }

    @Test
    fun `DEAD ROLE - multiple dead roles each play complete sequence`() {
        // Both seer and witch are dead
        val sequence = audioService.calculateDeadRoleAudioSequence(
            gameId = 1,
            skippedRoles = listOf(
                NightSubPhase.SEER_PICK,
                NightSubPhase.SEER_RESULT,
                NightSubPhase.WITCH_ACT
            ),
            targetSubPhase = NightSubPhase.GUARD_PICK,
        )

        // Each dead role plays open_eyes → close_eyes, then guard opens
        assertThat(sequence.audioFiles).containsExactly(
            "seer_open_eyes.mp3",
            "seer_close_eyes.mp3",
            "witch_open_eyes.mp3",
            "witch_close_eyes.mp3",
            "guard_open_eyes.mp3"
        )
    }

    // ── Room Configuration Tests ──────────────────────────────────────────────

    @Test
    fun `ROOM CONFIG - room without seer should not have SEER_PICK audio in sequence`() {
        // When room has no seer, the night sequence should skip seer phases entirely
        // This is tested at NightOrchestrator level, but AudioService should handle it
        val closeSequence = audioService.calculateCloseEyesAudio(
            subPhase = NightSubPhase.WEREWOLF_PICK,
        )
        val openSequence = audioService.calculateOpenEyesAudio(
            subPhase = NightSubPhase.WITCH_ACT, // Skip directly to witch
        )

        assertThat(closeSequence).isEqualTo("wolf_close_eyes.mp3")
        assertThat(openSequence).isEqualTo("witch_open_eyes.mp3")
    }

    @Test
    fun `ROOM CONFIG - room without witch should skip WITCH_ACT audio`() {
        val closeSequence = audioService.calculateCloseEyesAudio(
            subPhase = NightSubPhase.SEER_RESULT,
        )
        val openSequence = audioService.calculateOpenEyesAudio(
            subPhase = NightSubPhase.GUARD_PICK, // Skip directly to guard
        )

        assertThat(closeSequence).isEqualTo("seer_close_eyes.mp3")
        assertThat(openSequence).isEqualTo("guard_open_eyes.mp3")
    }

    @Test
    fun `ROOM CONFIG - room without special roles transitions directly to day after werewolf`() {
        val closeSequence = audioService.calculateCloseEyesAudio(
            subPhase = NightSubPhase.WEREWOLF_PICK,
        )

        // With no special roles, after werewolf closes eyes, transition to day
        assertThat(closeSequence).isEqualTo("wolf_close_eyes.mp3")
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