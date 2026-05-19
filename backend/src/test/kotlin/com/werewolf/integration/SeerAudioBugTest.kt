package com.werewolf.integration

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.service.AudioService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Test to reproduce the seer audio bug reported by the user:
 * - SEER_PICK -> SEER_RESULT plays no audio (seer stays awake)
 * - SEER_RESULT -> GUARD_PICK should play "seer_close_eyes.mp3" and "guard_open_eyes.mp3",
 *   but the historical bug only emitted "seer_close_eyes.mp3"
 */
@SpringBootTest
@ActiveProfiles("test")
class SeerAudioBugTest {

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var gamePlayerRepository: GamePlayerRepository

    @Autowired
    private lateinit var roomRepository: RoomRepository

    @Autowired
    private lateinit var nightPhaseRepository: NightPhaseRepository

    @Autowired
    private lateinit var audioService: AudioService

    @Autowired
    private lateinit var nightOrchestrator: NightOrchestrator

    private val hostId = "host:001"

    @BeforeEach
    fun setUp() {
        cleanUpTestData()
    }

    @AfterEach
    fun tearDown() {
        cleanUpTestData()
    }

    private fun cleanUpTestData() {
        nightPhaseRepository.deleteAll()
        gamePlayerRepository.deleteAll()
        gameRepository.deleteAll()
        roomRepository.deleteAll()
    }

    private fun createPlayersWithRoles(gameId: Int, room: Room): List<GamePlayer> {
        val players = mutableListOf<GamePlayer>()
        val roles = mutableListOf(
            PlayerRole.WEREWOLF,
            PlayerRole.WEREWOLF,
            PlayerRole.SEER,
            PlayerRole.WITCH,
            PlayerRole.GUARD,
            PlayerRole.VILLAGER
        )

        roles.forEachIndexed { index, role ->
            val player = GamePlayer(
                gameId = gameId,
                userId = "user:$index",
                seatIndex = index + 1,
                role = role,
                alive = true,
            )
            players.add(player)
        }

        return gamePlayerRepository.saveAll(players)
    }

    @Test
    fun `SEER_PICK to SEER_RESULT transition - generates correct audio sequence`() {
        // Given: AudioService.calculateNightSubPhaseTransition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )

        // Then: Should be empty (seer_close_eyes.mp3 is only played when leaving SEER_RESULT)
        assertThat(audioSequence.audioFiles).isEmpty()
        println("SEER_PICK -> SEER_RESULT: ${audioSequence.audioFiles}")
    }

    @Test
    fun `SEER_RESULT to GUARD_PICK transition - generates correct audio sequence`() {
        // Given: AudioService.calculateNightSubPhaseTransition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        // Then: Should contain both "seer_close_eyes.mp3" and "guard_open_eyes.mp3"
        assertThat(audioSequence.audioFiles).containsExactly("seer_close_eyes.mp3", "guard_open_eyes.mp3")
        println("SEER_RESULT -> GUARD_PICK: ${audioSequence.audioFiles}")
    }

    // TODO unable to pass
    @Test
    fun `Complete seer workflow - verifies audio sequences for all transitions`() {
        println("\n=== Complete Seer Workflow Test ===")

        // Setup: Create room and game with all roles
        val room = Room(
            roomCode = "AB${System.currentTimeMillis().toString().takeLast(2)}",
            hostUserId = hostId,
            totalPlayers = 6,
            hasSeer = true,
            hasWitch = true,
            hasGuard = true,
            config = GameConfig.createDefault(),
        )
        val savedRoom = roomRepository.save(room)
        val game = Game(
            roomId = savedRoom.roomId!!,
            hostUserId = hostId,
        )
        game.phase = GamePhase.DAY_DISCUSSION
        game.dayNumber = 1
        val savedGame = gameRepository.save(game)
        val gameId = requireNotNull(savedGame.gameId) { "Game ID should not be null after save" }

        // Create players with roles
        val players = createPlayersWithRoles(gameId, savedRoom)
        println("  Created ${players.size} players with roles")

        // Step 1: Initialize night phase (DAY -> NIGHT)
        println("\nStep 1: DAY -> NIGHT")
        nightOrchestrator.initNight(gameId, newDayNumber = 1, withWaiting = false)
        val nightPhase1 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElse(null)
        println("  Initial sub-phase: ${nightPhase1?.subPhase}")

        // Step 2: Simulate WEREWOLF_PICK -> WITCH_ACT
        println("\nStep 2: WEREWOLF_PICK -> WITCH_ACT")
        nightOrchestrator.advanceToSubPhase(gameId, NightSubPhase.WITCH_ACT)
        val nightPhase2 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElse(null)
        println("  Sub-phase after WEREWOLF_PICK: ${nightPhase2?.subPhase}")
        assertThat(nightPhase2).isNotNull()

        // Calculate audio for this transition
        val audio1 = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )
        println("  Audio: ${audio1.audioFiles}")
        assertThat(audio1.audioFiles).containsExactly("wolf_close_eyes.mp3", "witch_open_eyes.mp3")

        // Step 3: Simulate WITCH_ACT -> SEER_PICK (witch acts and seer wakes)
        println("\nStep 3: WITCH_ACT -> SEER_PICK (witch acts and seer wakes)")
        nightOrchestrator.advanceToSubPhase(gameId, NightSubPhase.SEER_PICK)
        val nightPhase3 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElse(null)
        println("  Sub-phase after WITCH_ACT: ${nightPhase3?.subPhase}")
        assertThat(nightPhase3).isNotNull()

        // Calculate audio for this transition
        val audio2 = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.SEER_PICK,
        )
        println("  Audio: ${audio2.audioFiles}")
        assertThat(audio2.audioFiles).containsExactly("witch_close_eyes.mp3", "seer_open_eyes.mp3")

        // Step 4: Simulate SEER_PICK -> SEER_RESULT (seer checks a player; stays awake)
        println("\nStep 4: SEER_PICK -> SEER_RESULT (seer checks a player)")
        nightOrchestrator.advanceToSubPhase(gameId, NightSubPhase.SEER_RESULT)
        val nightPhase4 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElse(null)
        println("  Sub-phase after SEER_PICK: ${nightPhase4?.subPhase}")
        assertThat(nightPhase4).isNotNull()

        val audio3 = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )
        println("  Audio: ${audio3.audioFiles}")
        assertThat(audio3.audioFiles).isEmpty()

        // Step 5: Simulate SEER_RESULT -> GUARD_PICK (seer confirms and advances)
        println("\nStep 5: SEER_RESULT -> GUARD_PICK (seer confirms and advances)")
        nightOrchestrator.advanceToSubPhase(gameId, NightSubPhase.GUARD_PICK)
        val nightPhase5 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElse(null)
        println("  Sub-phase after SEER_RESULT: ${nightPhase5?.subPhase}")
        assertThat(nightPhase5).isNotNull()

        // Calculate audio for this transition - THIS IS WHERE THE BUG WAS REPORTED
        val audio4 = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )
        println("  Audio: ${audio4.audioFiles}")

        // This should contain both "seer_close_eyes.mp3" and "guard_open_eyes.mp3"
        // If the bug exists, it will only contain "seer_close_eyes.mp3"
        assertThat(audio4.audioFiles)
            .describedAs("SEER_RESULT -> GUARD_PICK should play both seer close and guard open")
            .containsExactly("seer_close_eyes.mp3", "guard_open_eyes.mp3")

        println("\n=== Test Completed Successfully ===")
    }

    @Test
    fun `WITCH_ACT to SEER_PICK transition - generates correct audio sequence`() {
        // Given: AudioService.calculateNightSubPhaseTransition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        // Then: Should contain both "witch_close_eyes.mp3" and "seer_open_eyes.mp3"
        assertThat(audioSequence.audioFiles).containsExactly("witch_close_eyes.mp3", "seer_open_eyes.mp3")
        println("WITCH_ACT -> SEER_PICK: ${audioSequence.audioFiles}")
    }

    @Test
    fun `GUARD_PICK to NIGHT COMPLETE - DAY transition generates correct audio`() {
        // Setup: Create room
        val room = Room(
            roomCode = "AB${System.currentTimeMillis().toString().takeLast(2)}",
            hostUserId = hostId,
            totalPlayers = 6,
            hasSeer = false,
            hasWitch = false,
            hasGuard = true,
        )
        val savedRoom = roomRepository.save(room)

        // Given: AudioService.calculatePhaseTransition for NIGHT -> DAY
        val audioSequence = audioService.calculatePhaseTransition(
            gameId = 1,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = savedRoom,
        )

        // Then: Should contain "day_time.mp3"
        assertThat(audioSequence.audioFiles).containsExactly("rooster_crowing.mp3", "day_time.mp3")
        println("NIGHT -> DAY: ${audioSequence.audioFiles}")
    }
}
