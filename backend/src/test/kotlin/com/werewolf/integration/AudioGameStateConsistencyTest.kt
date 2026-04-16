package com.werewolf.integration

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.AudioService
import com.werewolf.service.GameContextLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
        
        // Verify: Audio files are appropriate for NIGHT phase
        // When transitioning directly to WEREWOLF_PICK, both "goes_dark_close_eyes.mp3" and "wolf_open_eyes.mp3" are played
        assertThat(audioSequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3", "wolf_open_eyes.mp3")
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
        
        // Verify: Audio sequence matches new game state
        assertThat(dayToNightSequence.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(dayToNightSequence.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK.name)
        // When transitioning directly to WEREWOLF_PICK, both "goes_dark_close_eyes.mp3" and "wolf_open_eyes.mp3" are played
        assertThat(dayToNightSequence.audioFiles).containsExactly("goes_dark_close_eyes.mp3","wolf_howl.mp3", "wolf_open_eyes.mp3")

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

    // ── Night Orchestration Audio Consistency Tests ───────────────────────────

    /**
     * Verifies the full dead-role audio chain plays in the correct sequence when all special
     * roles (seer, witch, guard) are dead and only the wolf is alive.
     *
     * Uses the state-machine path (initNight + advance) — NOT the coroutine path — so the
     * test does not block waiting for player actions.
     *
     * Audio timing per dead role (with 5s inter-role gap):
     *   open_eyes(2s) + pause(5s) + close_eyes(2s) + inter-role gap(5s) = 14s
     *   Last role (guard): no inter-role gap → 9s
     * Total: ~37s of real-time delays → @Timeout(90)
     */
    @Test
    @Timeout(90)
    fun `Night orchestration must maintain audio sequence consistency with game state`() {
        // ── Setup: 1 wolf (alive), 1 seer/witch/guard (dead), 2 villagers (alive) ──
        val room = createRoom(hasSeer = true, hasWitch = true, hasGuard = true)
        val game = createGame(roomId = room.roomId!!, phase = GamePhase.DAY_DISCUSSION, dayNumber = 0)

        val wolfPlayer  = createPlayerWithRole(game.gameId!!, "wolf-1",  0, PlayerRole.WEREWOLF, alive = true)
        val seerPlayer  = createPlayerWithRole(game.gameId!!, "seer-1",  1, PlayerRole.SEER,     alive = false)
        val witchPlayer = createPlayerWithRole(game.gameId!!, "witch-1", 2, PlayerRole.WITCH,    alive = false)
        val guardPlayer = createPlayerWithRole(game.gameId!!, "guard-1", 3, PlayerRole.GUARD,    alive = false)
        createPlayerWithRole(game.gameId!!, "vil-1", 4, PlayerRole.VILLAGER, alive = true)
        createPlayerWithRole(game.gameId!!, "vil-2", 5, PlayerRole.VILLAGER, alive = true)

        // ── Init night (state-machine path, does NOT block) ──
        nightOrchestrator.initNight(game.gameId!!, 1)

        // Wolf is alive → night starts in WEREWOLF_PICK
        val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(game.gameId!!, 1).orElseThrow()
        assertThat(nightPhase.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK)

        // Wolf targets the dead seer — no actual kill (seer already dead), game won't end
        nightPhase.wolfTargetUserId = seerPlayer.userId
        nightPhaseRepository.save(nightPhase)

        // ── Advance from WEREWOLF_PICK — triggers the dead-role audio chain ──
        // Seer is dead → subPhase set to SEER_RESULT immediately in this thread;
        // dead-role audio coroutine scheduled in background
        nightOrchestrator.advance(game.gameId!!, NightSubPhase.WEREWOLF_PICK)

        val afterWolf = nightPhaseRepository.findByGameIdAndDayNumber(game.gameId!!, 1).orElseThrow()
        assertThat(afterWolf.subPhase).isEqualTo(NightSubPhase.SEER_RESULT)

        // Wait for seer audio chain: open_eyes(2s) + pause(5s) + close_eyes(2s) + gap(5s) = 14s
        Thread.sleep(16_000)

        val afterSeer = nightPhaseRepository.findByGameIdAndDayNumber(game.gameId!!, 1).orElseThrow()
        assertThat(afterSeer.subPhase).isEqualTo(NightSubPhase.WITCH_ACT)

        // Wait for witch audio chain: same 14s
        Thread.sleep(16_000)

        val afterWitch = nightPhaseRepository.findByGameIdAndDayNumber(game.gameId!!, 1).orElseThrow()
        assertThat(afterWitch.subPhase).isEqualTo(NightSubPhase.GUARD_PICK)

        // Wait for guard audio chain (last role, no inter-role gap): 9s
        Thread.sleep(12_000)

        // Night resolves → COMPLETE
        val finalNight = nightPhaseRepository.findByGameIdAndDayNumber(game.gameId!!, 1).orElseThrow()
        assertThat(finalNight.subPhase).isEqualTo(NightSubPhase.COMPLETE)

        // Wolf targeted dead seer → no real kill → 1W + 2V alive → wolves < others → DAY_DISCUSSION
        val endGame = gameRepository.findById(game.gameId!!).orElseThrow()
        assertThat(endGame.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
    }

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


