package com.werewolf.integration

import com.werewolf.game.action.GameActionDispatcher
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.service.AudioService
import com.werewolf.service.GameContextLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Test to verify that AudioSequence events are broadcast correctly
 * and contain the correct audio files for each transition.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AudioSequenceBroadcastTest {

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

    @Autowired
    private lateinit var gameActionDispatcher:GameActionDispatcher

    @Autowired
    private lateinit var gameContextLoader: GameContextLoader

    private val hostId = "host:001"
    private val seerUserId = "seer:001"

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

    private fun createRoomWithSeer(): Room {
        val room = Room(
            roomCode = "AB${System.currentTimeMillis().toString().takeLast(2)}",
            hostUserId = hostId,
            totalPlayers = 6,
            hasSeer = true,
            hasWitch = true,
            hasGuard = true,
        )
        return roomRepository.save(room)
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
            val userId = if (role == PlayerRole.SEER) seerUserId else "user:$index"
            val player = GamePlayer(
                gameId = gameId,
                userId = userId,
                seatIndex = index + 1,
                role = role,
                alive = true,
            )
            players.add(player)
        }

        return gamePlayerRepository.saveAll(players)
    }

    @Test
    fun `SEER_CHECK action - broadcasts correct AudioSequence event`() {
        println("\n=== SEER_CHECK Action Test ===")

        // Setup: Create room and game with players
        val room = createRoomWithSeer()
        val game = Game(
            roomId = room.roomId!!,
            hostUserId = hostId,
        )
        game.phase = GamePhase.NIGHT
        game.dayNumber = 1
        val savedGame = gameRepository.save(game)
        val gameId = requireNotNull(savedGame.gameId) { "Game ID should not be null after save" }

        val players = createPlayersWithRoles(gameId, room)
        println("Created ${players.size} players with roles")

        // Initialize night phase
        nightOrchestrator.initNight(gameId, newDayNumber = 1, withWaiting = false)

        // Advance to SEER_PICK
        nightOrchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)
        val nightPhase1 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElse(null)
        println("Sub-phase after WEREWOLF_PICK: ${nightPhase1?.subPhase}")
        assertThat(nightPhase1?.subPhase).isEqualTo(NightSubPhase.SEER_PICK)

        // Calculate what audio sequence should be broadcast when SEER_CHECK is called
        val expectedAudioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )
        println("Expected audio sequence for SEER_CHECK: ${expectedAudioSequence.audioFiles}")
        assertThat(expectedAudioSequence.audioFiles).isEmpty()

        println("\n=== Test Completed Successfully ===")
    }

    @Test
    fun `SEER_CONFIRM action - broadcasts correct AudioSequence event`() {
        println("\n=== SEER_CONFIRM Action Test ===")

        // Setup: Create room and game with players
        val room = createRoomWithSeer()
        val game = Game(
            roomId = room.roomId!!,
            hostUserId = hostId,
        )
        game.phase = GamePhase.NIGHT
        game.dayNumber = 1
        val savedGame = gameRepository.save(game)
        val gameId = requireNotNull(savedGame.gameId) { "Game ID should not be null after save" }

        val players = createPlayersWithRoles(gameId, room)
        println("Created ${players.size} players with roles")

        // Initialize night phase
        nightOrchestrator.initNight(gameId, newDayNumber = 1, withWaiting = false)

        // Advance to SEER_PICK
        nightOrchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)
        val nightPhase1 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElse(null)
        println("Sub-phase after WEREWOLF_PICK: ${nightPhase1?.subPhase}")
        assertThat(nightPhase1?.subPhase).isEqualTo(NightSubPhase.SEER_PICK)

        // Advance to SEER_RESULT (simulating SEER_CHECK)
        nightOrchestrator.advance(gameId, NightSubPhase.SEER_PICK)
        val nightPhase2 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElse(null)
        println("Sub-phase after SEER_PICK: ${nightPhase2?.subPhase}")
        assertThat(nightPhase2?.subPhase).isEqualTo(NightSubPhase.SEER_RESULT)

        // Calculate what audio sequence should be broadcast when SEER_CONFIRM is called
        val expectedAudioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )
        println("Expected audio sequence for SEER_CONFIRM: ${expectedAudioSequence.audioFiles}")

        // This is the bug: the expected audio sequence should contain both files
        assertThat(expectedAudioSequence.audioFiles)
            .describedAs("SEER_CONFIRM should play both seer close and witch open")
            .containsExactly("seer_close_eyes.mp3", "witch_open_eyes.mp3")

        println("\n=== Test Completed Successfully ===")
    }
}
