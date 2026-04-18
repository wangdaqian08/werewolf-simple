package com.werewolf.integration

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.AudioService
import com.werewolf.service.GameContextLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class AudioGameStateConsistencyTest {

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

    @Autowired
    private lateinit var userRepository: UserRepository

    private val hostId = "host:001"
    private var testUserId = "user:test"

    @BeforeEach
    fun setUp() {
        cleanUpTestData()
        testUserId = "user:${System.currentTimeMillis()}"
        
        // Create test user
        val user = User(userId = testUserId, nickname = "Test User")
        userRepository.save(user)
    }

    @AfterEach
    fun tearDown() {
        cleanUpTestData()
    }

    private fun cleanUpTestData() {
        nightPhaseRepository.deleteAll()
        gamePlayerRepository.deleteAll()
        gameRepository.deleteAll()
        roomPlayerRepository.deleteAll()
        roomRepository.deleteAll()
        userRepository.deleteAll()
    }

    // ── Audio Phase Consistency Tests ─────────────────────────────────────────

    @Test
    fun `Audio sequence phase must match game state phase - DAY to NIGHT`() {
        // Setup: Create game in DAY phase
        val room = createRoom()
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.DAY_DISCUSSION, dayNumber = 1)

        // Execute: Generate audio sequence for DAY to NIGHT transition
        val audioSequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )

        // Verify: Audio sequence phase matches new game state phase
        assertThat(audioSequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(audioSequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        
        // Verify: phase-transition sequence carries ONLY the phase-level ambience.
        // Role-owned audio (wolf_open_eyes.mp3) is broadcast independently by the
        // night role loop — see NightOrchestrator.nightRoleLoop. This decoupling
        // means swapping any role's audio never touches phase-transition logic.
        assertThat(audioSequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3", "wolf_howl.mp3")
    }

    @Test
    fun `Audio sequence phase must match game state phase - NIGHT to DAY`() {
        // Setup: Create game in NIGHT phase
        val room = createRoom()
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.NIGHT, dayNumber = 1)
        createNightPhase(game.gameId!!, 1, NightSubPhase.GUARD_PICK)

        // Execute: Generate audio sequence for NIGHT to DAY transition
        val audioSequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )

        // Verify: Audio sequence phase matches new game state phase
        assertThat(audioSequence.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(audioSequence.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        
        // Verify: Audio files are appropriate for DAY phase
        assertThat(audioSequence.audioFiles).containsExactly("rooster_crowing.mp3", "day_time.mp3")
    }

    @Test
    fun `Audio sequence subPhase must match night phase subPhase - WEREWOLF_PICK to SEER_PICK`() {
        // Setup: Create game in NIGHT phase with WEREWOLF_PICK
        val room = createRoom(hasSeer = true)
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.NIGHT, dayNumber = 1)
        createNightPhase(game.gameId!!, 1, NightSubPhase.WEREWOLF_PICK)

        // Execute: Generate audio sequence for WEREWOLF_PICK to SEER_PICK transition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )

        // Verify: Audio sequence phase and subPhase match new game state
        assertThat(audioSequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(audioSequence.subPhase).isEqualTo(NightSubPhase.SEER_PICK.name)
        
        // Verify: Audio files are appropriate for the transition
        assertThat(audioSequence.audioFiles).containsExactly(
            "wolf_close_eyes.mp3",
            "seer_open_eyes.mp3"
        )
    }

    @Test
    fun `Audio sequence subPhase must match night phase subPhase - SEER_RESULT to WITCH_ACT`() {
        // Setup: Create game in NIGHT phase with SEER_RESULT
        val room = createRoom(hasSeer = true, hasWitch = true)
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.NIGHT, dayNumber = 1)
        createNightPhase(game.gameId!!, 1, NightSubPhase.SEER_RESULT)

        // Execute: Generate audio sequence for SEER_RESULT to WITCH_ACT transition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )

        // Verify: Audio sequence phase and subPhase match new game state
        assertThat(audioSequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(audioSequence.subPhase).isEqualTo(NightSubPhase.WITCH_ACT.name)
        
        // Verify: Audio files are appropriate for the transition
        assertThat(audioSequence.audioFiles).containsExactly(
            "seer_close_eyes.mp3",
            "witch_open_eyes.mp3"
        )
    }

    // ── Complete Game Flow Audio Consistency Tests ──────────────────────────

    @Test
    fun `Complete night cycle - audio sequences must remain consistent with game state`() {
        // Setup: Create a complete game setup
        val room = createRoom(hasSeer = true, hasWitch = true, hasGuard = true)
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.DAY_DISCUSSION, dayNumber = 0)
        createPlayers(game.gameId!!, room.totalPlayers)

        // Step 1: DAY -> NIGHT transition
        val dayToNightSequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )
        
        // Verify: phase-level ambience only; wolf_open_eyes comes separately
        // from the role loop so each role's audio remains independently swappable.
        assertThat(dayToNightSequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(dayToNightSequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        assertThat(dayToNightSequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3", "wolf_howl.mp3")

        // Step 2: WAITING -> WEREWOLF_PICK
        val waitingToWerewolf = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = NightSubPhase.WEREWOLF_PICK,
        )
        
        // Verify: Audio sequence matches new game state
        assertThat(waitingToWerewolf.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(waitingToWerewolf.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        assertThat(waitingToWerewolf.audioFiles).containsExactly("wolf_open_eyes.mp3")

        // Step 3: WEREWOLF_PICK -> SEER_PICK
        val werewolfToSeer = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WEREWOLF_PICK,
            newSubPhase = NightSubPhase.SEER_PICK,
        )
        
        // Verify: Audio sequence matches new game state
        assertThat(werewolfToSeer.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(werewolfToSeer.subPhase).isEqualTo(NightSubPhase.SEER_PICK.name)
        assertThat(werewolfToSeer.audioFiles).containsExactly(
            "wolf_close_eyes.mp3",
            "seer_open_eyes.mp3"
        )

        // Step 4: SEER_PICK -> SEER_RESULT
        val seerToResult = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.SEER_PICK,
            newSubPhase = NightSubPhase.SEER_RESULT,
        )
        
        // Verify: Audio sequence matches new game state
        assertThat(seerToResult.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(seerToResult.subPhase).isEqualTo(NightSubPhase.SEER_RESULT.name)
        assertThat(seerToResult.audioFiles).isEmpty()

        // Step 5: SEER_RESULT -> WITCH_ACT
        val resultToWitch = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.SEER_RESULT,
            newSubPhase = NightSubPhase.WITCH_ACT,
        )
        
        // Verify: Audio sequence matches new game state
        assertThat(resultToWitch.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(resultToWitch.subPhase).isEqualTo(NightSubPhase.WITCH_ACT.name)
        assertThat(resultToWitch.audioFiles).containsExactly(
            "seer_close_eyes.mp3",
            "witch_open_eyes.mp3"
        )

        // Step 6: WITCH_ACT -> GUARD_PICK
        val witchToGuard = audioService.calculateNightSubPhaseTransition(
            gameId = game.gameId!!,
            oldSubPhase = NightSubPhase.WITCH_ACT,
            newSubPhase = NightSubPhase.GUARD_PICK,
        )
        
        // Verify: Audio sequence matches new game state
        assertThat(witchToGuard.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(witchToGuard.subPhase).isEqualTo(NightSubPhase.GUARD_PICK.name)
        assertThat(witchToGuard.audioFiles).containsExactly(
            "witch_close_eyes.mp3",
            "guard_open_eyes.mp3"
        )

        // Step 7: NIGHT -> DAY transition
        val nightToDaySequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.NIGHT,
            newPhase = GamePhase.DAY_DISCUSSION,
            oldSubPhase = NightSubPhase.GUARD_PICK.name,
            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
            room = room,
        )
        
        // Verify: Audio sequence matches new game state
        assertThat(nightToDaySequence.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(nightToDaySequence.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        assertThat(nightToDaySequence.audioFiles).containsExactly("rooster_crowing.mp3", "day_time.mp3")
    }

    // ── Audio Sequence Timing Consistency Tests ────────────────────────────────

    @Test
    fun `Audio sequence timestamp must reflect current time when generated`() {
        // Setup: Create game
        val room = createRoom()
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.DAY_DISCUSSION, dayNumber = 1)

        val beforeTime = System.currentTimeMillis()
        
        // Execute: Generate audio sequence
        val audioSequence = audioService.calculatePhaseTransition(
            gameId = game.gameId!!,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = NightSubPhase.WEREWOLF_PICK.name,
            room = room,
        )
        
        val afterTime = System.currentTimeMillis()

        // Verify: Audio sequence timestamp is within expected range
        assertThat(audioSequence.timestamp).isBetween(beforeTime, afterTime)
    }

    // ── Edge Case Consistency Tests ────────────────────────────────────────────

    // ── Helper Methods ────────────────────────────────────────────────────────

    private fun createRoom(
        hasSeer: Boolean = false,
        hasWitch: Boolean = false,
        hasGuard: Boolean = false,
        roomId: Int? = null
    ): Room {
        val roomCode = roomId?.toString() ?: "TE${System.currentTimeMillis().toString().takeLast(2)}"
        
        val room = Room(
            roomCode = roomCode,
            hostUserId = hostId,
            totalPlayers = 6,
            hasSeer = hasSeer,
            hasWitch = hasWitch,
            hasGuard = hasGuard,
            config = GameConfig.createDefault(),
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

    private fun createNightPhase(
        gameId: Int,
        dayNumber: Int,
        subPhase: NightSubPhase,
    ): NightPhase {
        val nightPhase = NightPhase(
            gameId = gameId,
            dayNumber = dayNumber,
            subPhase = subPhase,
        )
        return nightPhaseRepository.save(nightPhase)
    }

    private fun createPlayers(gameId: Int, count: Int): List<GamePlayer> {
        val players = (1..count).map { seatIndex ->
            GamePlayer(
                gameId = gameId,
                userId = "user:$seatIndex",
                seatIndex = seatIndex,
                role = PlayerRole.VILLAGER,
            )
        }
        return gamePlayerRepository.saveAll(players)
    }
    
    private fun createPlayersWithRoles(gameId: Int, roles: List<PlayerRole>): List<GamePlayer> {
        val players = roles.mapIndexed { seatIndex, role ->
            GamePlayer(
                gameId = gameId,
                userId = "user:$seatIndex",
                seatIndex = seatIndex,
                role = role,
            )
        }
        return gamePlayerRepository.saveAll(players)
    }

    private fun createPlayerWithRole(
        gameId: Int,
        userId: String,
        seatIndex: Int,
        role: PlayerRole,
        alive: Boolean = true,
    ): GamePlayer {
        val player = GamePlayer(gameId = gameId, userId = userId, seatIndex = seatIndex, role = role)
        player.alive = alive
        return gamePlayerRepository.save(player)
    }
}


