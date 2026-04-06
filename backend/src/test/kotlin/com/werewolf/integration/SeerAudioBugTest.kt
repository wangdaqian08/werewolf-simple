package com.werewolf.integration

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.AudioService
import com.werewolf.game.night.NightOrchestrator
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
 * - SEER_PICK -> SEER_RESULT plays "预言家请闭眼.mp3"
 * - SEER_RESULT -> WITCH_ACT should play "预言家请闭眼.mp3" and "女巫请睁眼.mp3",
 *   but user reports only hearing "预言家请闭眼.mp3"
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
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

        // Then: Should only contain "预言家请闭眼.mp3"
        assertThat(audioSequence.audioFiles).containsExactly("预言家请闭眼.mp3")
        println("SEER_PICK -> SEER_RESULT: ${audioSequence.audioFiles}")
    }

    @Test
    fun `SEER_RESULT to WITCH_ACT transition - generates correct audio sequence`() {
        // Given: AudioService.calculateNightSubPhaseTransition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )

        // Then: Should contain both "预言家请闭眼.mp3" and "女巫请睁眼.mp3"
        assertThat(audioSequence.audioFiles).containsExactly("预言家请闭眼.mp3", "女巫请睁眼.mp3")
        println("SEER_RESULT -> WITCH_ACT: ${audioSequence.audioFiles}")
    }

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
        )
        val savedRoom = roomRepository.save(room)
        val game = Game(
            roomId = savedRoom.roomId!!,
            hostUserId = hostId,
        )
        game.phase = GamePhase.DAY
        game.dayNumber = 1
        val savedGame = gameRepository.save(game)

        // Create players with roles
        val players = createPlayersWithRoles(savedGame.gameId!!, savedRoom)
        println("  Created ${players.size} players with roles")

        // Step 1: Initialize night phase (DAY -> NIGHT)
        println("\nStep 1: DAY -> NIGHT")
        nightOrchestrator.initNight(savedGame.gameId!!, newDayNumber = 1, withWaiting = false)
        val nightPhase1 = nightPhaseRepository.findByGameIdAndDayNumber(savedGame.gameId!!, 1).orElse(null)
        println("  Initial sub-phase: ${nightPhase1?.subPhase}")

        // Step 2: Simulate WEREWOLF_PICK -> SEER_PICK
        println("\nStep 2: WEREWOLF_PICK -> SEER_PICK")
        nightOrchestrator.advance(savedGame.gameId!!, NightSubPhase.WEREWOLF_PICK)
        val nightPhase2 = nightPhaseRepository.findByGameIdAndDayNumber(savedGame.gameId!!, 1).orElse(null)
        println("  Sub-phase after WEREWOLF_PICK: ${nightPhase2?.subPhase}")
        assertThat(nightPhase2).isNotNull()

        // Calculate audio for this transition
        val audio1 = audioService.calculateNightSubPhaseTransition(
            gameId = savedGame.gameId!!,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )
        println("  Audio: ${audio1.audioFiles}")
        assertThat(audio1.audioFiles).containsExactly("狼人请闭眼.mp3", "预言家请睁眼.mp3")

        // Step 3: Simulate SEER_PICK -> SEER_RESULT (seer checks a player)
        println("\nStep 3: SEER_PICK -> SEER_RESULT (seer checks a player)")
        nightOrchestrator.advance(savedGame.gameId!!, NightSubPhase.SEER_PICK)
        val nightPhase3 = nightPhaseRepository.findByGameIdAndDayNumber(savedGame.gameId!!, 1).orElse(null)
        println("  Sub-phase after SEER_PICK: ${nightPhase3?.subPhase}")
        assertThat(nightPhase3).isNotNull()

        // Calculate audio for this transition
        val audio2 = audioService.calculateNightSubPhaseTransition(
            gameId = savedGame.gameId!!,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )
        println("  Audio: ${audio2.audioFiles}")
        assertThat(audio2.audioFiles).containsExactly("预言家请闭眼.mp3")

        // Step 4: Simulate SEER_RESULT -> WITCH_ACT (seer confirms and advances)
        println("\nStep 4: SEER_RESULT -> WITCH_ACT (seer confirms and advances)")
        nightOrchestrator.advance(savedGame.gameId!!, NightSubPhase.SEER_RESULT)
        val nightPhase4 = nightPhaseRepository.findByGameIdAndDayNumber(savedGame.gameId!!, 1).orElse(null)
        println("  Sub-phase after SEER_RESULT: ${nightPhase4?.subPhase}")
        assertThat(nightPhase4).isNotNull()

        // Calculate audio for this transition - THIS IS WHERE THE BUG IS REPORTED
        val audio3 = audioService.calculateNightSubPhaseTransition(
            gameId = savedGame.gameId!!,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )
        println("  Audio: ${audio3.audioFiles}")

        // This should contain both "预言家请闭眼.mp3" and "女巫请睁眼.mp3"
        // If the bug exists, it will only contain "预言家请闭眼.mp3"
        assertThat(audio3.audioFiles)
            .describedAs("SEER_RESULT -> WITCH_ACT should play both seer close and witch open")
            .containsExactly("预言家请闭眼.mp3", "女巫请睁眼.mp3")

        println("\n=== Test Completed Successfully ===")
    }

    @Test
    fun `WITCH_ACT to GUARD_PICK transition - generates correct audio sequence`() {
        // Given: AudioService.calculateNightSubPhaseTransition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = 1,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )

        // Then: Should contain both "女巫请闭眼.mp3" and "守卫请睁眼.mp3"
        assertThat(audioSequence.audioFiles).containsExactly("女巫请闭眼.mp3", "守卫请睁眼.mp3")
        println("WITCH_ACT -> GUARD_PICK: ${audioSequence.audioFiles}")
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
            newPhase = GamePhase.DAY,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = savedRoom,
        )

        // Then: Should contain "天亮了.mp3"
        assertThat(audioSequence.audioFiles).containsExactly("天亮了.mp3")
        println("NIGHT -> DAY: ${audioSequence.audioFiles}")
    }
}
