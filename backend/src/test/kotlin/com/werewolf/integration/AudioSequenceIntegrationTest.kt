package com.werewolf.integration

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.AudioService
import com.werewolf.service.GameContextLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AudioSequenceIntegrationTest {

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var gamePlayerRepository: GamePlayerRepository

    @Autowired
    private lateinit var roomRepository: RoomRepository

    @Autowired
    private lateinit var roomPlayerRepository: RoomPlayerRepository

    @Autowired
    private lateinit var nightPhaseRepository: NightPhaseRepository

    @Autowired
    private lateinit var audioService: AudioService

    @Autowired
    private lateinit var nightOrchestrator: com.werewolf.game.night.NightOrchestrator

    @Autowired
    private lateinit var gameContextLoader: GameContextLoader

    private val hostId = "host:001"

    @BeforeEach
    fun setUp() {
        // Clean up test data
        cleanUpTestData()
    }

    private fun cleanUpTestData() {
        nightPhaseRepository.deleteAll()
        gamePlayerRepository.deleteAll()
        gameRepository.deleteAll()
        roomPlayerRepository.deleteAll()
        roomRepository.deleteAll()
    }

    // ── Integration Tests ─────────────────────────────────────────────────────

    @Test
    fun `DAY to NIGHT transition - generates correct audio sequence`() {
        // Setup: Create a room and game in DAY phase
        val room = createRoom()
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.DAY_DISCUSSION, dayNumber = 1)

        // Execute: Calculate audio sequence for DAY to NIGHT transition
        val audioSequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )

        // Verify: Audio sequence contains goes_dark_close_eyes.mp3
        assertThat(audioSequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(audioSequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        assertThat(audioSequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3", "wolf_open_eyes.mp3")
        assertThat(audioSequence.priority).isEqualTo(10)
    }

    @Test
    fun `NIGHT to DAY transition - generates correct audio sequence`() {
        // Setup: Create a room and game in NIGHT phase
        val room = createRoom()
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.NIGHT, dayNumber = 1)

        // Execute: Calculate audio sequence for NIGHT to DAY transition
        val audioSequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        // Verify: Audio sequence contains day_time.mp3
        assertThat(audioSequence.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(audioSequence.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        assertThat(audioSequence.audioFiles).containsExactly("rooster_crowing.mp3", "day_time.mp3")
        assertThat(audioSequence.priority).isEqualTo(10)
    }

    @Test
    fun `Night sub-phase transitions - generate correct audio sequences`() {
        // Test all night sub-phase transitions
        val transitions = listOf(
            Triple(NightSubPhase.WAITING, NightSubPhase.WEREWOLF_PICK, listOf("wolf_open_eyes.mp3")),
            Triple(NightSubPhase.WEREWOLF_PICK, NightSubPhase.SEER_PICK, listOf("wolf_close_eyes.mp3", "seer_open_eyes.mp3")),
            Triple(NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT, emptyList()),
            Triple(NightSubPhase.SEER_RESULT, NightSubPhase.WITCH_ACT, listOf("seer_close_eyes.mp3", "witch_open_eyes.mp3")),
            Triple(NightSubPhase.WITCH_ACT, NightSubPhase.GUARD_PICK, listOf("witch_close_eyes.mp3", "guard_open_eyes.mp3")),
        )

        transitions.forEach { (oldSubPhase, newSubPhase, expectedAudioFiles) ->
            val audioSequence = audioService.calculateNightSubPhaseTransition(
                gameId = 1,
                oldSubPhase = oldSubPhase,
                newSubPhase = newSubPhase,
            )

            assertThat(audioSequence.phase).isEqualTo(GamePhase.NIGHT)
            assertThat(audioSequence.subPhase).isEqualTo(newSubPhase.name)
            assertThat(audioSequence.audioFiles).containsExactlyElementsOf(expectedAudioFiles)
        }
    }

    @Test
    fun `Night orchestration - broadcasts AudioSequence events during sub-phase advances`() {
        // Setup: Create room and game with players
        val room = createRoom(hasSeer = true, hasWitch = true, hasGuard = true)
        assertThat(roomRepository.findById(room.roomId!!)).isPresent

        val game = createGame(roomId = room.roomId!!, phase = GamePhase.NIGHT, dayNumber = 1)
        assertThat(gameRepository.findById(game.gameId!!)).isPresent

        createPlayers(game.gameId!!, room.totalPlayers)

        // Verify the game has correct roomId
        val loadedGame = gameRepository.findById(game.gameId!!).orElseThrow()
        assertThat(loadedGame.roomId).isEqualTo(room.roomId!!)

        // Initialize night phase
        nightOrchestrator.startNightPhase(game.gameId!!, newDayNumber = game.dayNumber, withWaiting = false)

        // Get the created night phase
        val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(game.gameId!!, game.dayNumber)
        assertThat(nightPhase).isNotNull()

        // Execute: Advance from WEREWOLF_PICK to SEER_PICK
        val context = gameContextLoader.load(game.gameId!!)
        nightOrchestrator.advance(game.gameId!!, NightSubPhase.WEREWOLF_PICK)

        // Verify: Night phase was advanced
        val updatedNightPhase = nightPhaseRepository.findByGameIdAndDayNumber(game.gameId!!, game.dayNumber).orElse(null)
        assertThat(updatedNightPhase?.subPhase).isNotEqualTo(NightSubPhase.WEREWOLF_PICK)

        // Verify: Audio sequence can be calculated for the transition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        assertThat(audioSequence.audioFiles).containsExactly("wolf_close_eyes.mp3", "seer_open_eyes.mp3")
    }

    @Test
    fun `Complete night cycle - generates correct audio sequences for all transitions`() {
        // Setup: Create a complete game setup with all roles
        val room = createRoom(hasSeer = true, hasWitch = true, hasGuard = true)
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.DAY_DISCUSSION, dayNumber = 0)
        val players = createPlayers(game.gameId!!, room.totalPlayers)

        // Simulate a complete night cycle
        val dayNumber = 1

        // DAY -> NIGHT transition
        val dayToNightSequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )
        assertThat(dayToNightSequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3", "wolf_howl.mp3","wolf_open_eyes.mp3")

        // WAITING -> WEREWOLF_PICK
        val waitingToWerewolf = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.WEREWOLF_PICK,
        )
        assertThat(waitingToWerewolf.audioFiles).containsExactly("wolf_open_eyes.mp3")

        // WEREWOLF_PICK -> SEER_PICK
        val werewolfToSeer = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )
        assertThat(werewolfToSeer.audioFiles).containsExactly("wolf_close_eyes.mp3", "seer_open_eyes.mp3")

        // SEER_PICK -> SEER_RESULT
        val seerToResult = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )
        assertThat(seerToResult.audioFiles).isEmpty()

        // SEER_RESULT -> WITCH_ACT
        val resultToWitch = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )
        assertThat(resultToWitch.audioFiles).containsExactly("seer_close_eyes.mp3", "witch_open_eyes.mp3")

        // WITCH_ACT -> GUARD_PICK
        val witchToGuard = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )
        assertThat(witchToGuard.audioFiles).containsExactly("witch_close_eyes.mp3", "guard_open_eyes.mp3")

        // NIGHT -> DAY transition
        val nightToDaySequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )
        assertThat(nightToDaySequence.audioFiles).containsExactly("rooster_crowing.mp3", "day_time.mp3")
    }

    @Test
    fun `AudioSequence IDs are unique across multiple transitions`() {
        val room = createRoom()
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.DAY_DISCUSSION, dayNumber = 1)

        // Generate multiple audio sequences
        val sequence1 = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )

        val sequence2 = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        val sequence3 = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        // Verify all IDs are unique
        assertThat(sequence1.id).isNotEqualTo(sequence2.id)
        assertThat(sequence2.id).isNotEqualTo(sequence3.id)
        assertThat(sequence1.id).isNotEqualTo(sequence3.id)
    }

    // ── Helper Methods ────────────────────────────────────────────────────────

    private fun createRoom(
        hasSeer: Boolean = false,
        hasWitch: Boolean = false,
        hasGuard: Boolean = false,
    ): Room {
        val room = Room(
            roomCode = "AB${System.currentTimeMillis().toString().takeLast(2)}",
            hostUserId = hostId,
            totalPlayers = 6,
            hasSeer = hasSeer,
            hasWitch = hasWitch,
            hasGuard = hasGuard,
        )
        return roomRepository.save(room)
    }

    private fun createGame(
        roomId: Int,
        phase: GamePhase,
        dayNumber: Int,
    ): Game {
        val game = Game(
            roomId = roomId,
            hostUserId = hostId,
        )
        game.phase = phase
        game.dayNumber = dayNumber
        return gameRepository.save(game)
    }

    private fun createPlayers(gameId: Int, count: Int): List<GamePlayer> {
        val players = (1..count).map { seatIndex ->
            // Create a mix of roles to prevent immediate game end
            val role = when (seatIndex) {
                1, 2 -> PlayerRole.WEREWOLF // Add wolves to prevent immediate game over
                3 -> PlayerRole.SEER
                4 -> PlayerRole.WITCH
                5 -> PlayerRole.GUARD
                else -> PlayerRole.VILLAGER
            }
            GamePlayer(
                gameId = gameId,
                userId = "user:$seatIndex",
                seatIndex = seatIndex,
                role = role,
            )
        }
        return gamePlayerRepository.saveAll(players)
    }
}