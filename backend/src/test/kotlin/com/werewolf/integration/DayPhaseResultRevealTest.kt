package com.werewolf.integration

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.GameService
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.*

/**
 * Integration test for Day Phase result revelation.
 * Verifies that when the host reveals night results, the correct death information is displayed.
 */
@ExtendWith(MockitoExtension::class)
class DayPhaseResultRevealTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var sheriffService: SheriffService

    private lateinit var gameService: GameService

    private val gameId = 1
    private val hostId = "host:001"
    private val wolfId = "wolf:001"
    private val victimId = "victim:001"
    private val guardId = "guard:001"

    @BeforeEach
    fun setUp() {
        gameService = GameService(
            gameRepository = gameRepository,
            roomRepository = roomRepository,
            roomPlayerRepository = roomPlayerRepository,
            gamePlayerRepository = gamePlayerRepository,
            stompPublisher = stompPublisher,
            nightOrchestrator = nightOrchestrator,
            userRepository = userRepository,
            sheriffService = sheriffService,
            nightPhaseRepository = nightPhaseRepository,
            voteRepository = voteRepository,
            eliminationHistoryRepository = eliminationHistoryRepository,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun game(
        phase: GamePhase = GamePhase.DAY_DISCUSSION,
        subPhase: String = DaySubPhase.RESULT_REVEALED.name,
        dayNumber: Int = 2
    ) = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = phase
        it.subPhase = subPhase
        it.dayNumber = dayNumber
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun user(userId: String, nick: String) = User(userId = userId, nickname = nick)

    private fun nightPhase(
        dayNumber: Int = 2,
        wolfTarget: String? = null,
        guardTarget: String? = null,
        antidoteUsed: Boolean = false,
        poisonTarget: String? = null
    ) = NightPhase(gameId = gameId, dayNumber = dayNumber).also {
        it.wolfTargetUserId = wolfTarget
        it.guardTargetUserId = guardTarget
        it.witchAntidoteUsed = antidoteUsed
        it.witchPoisonTargetUserId = poisonTarget
        it.subPhase = NightSubPhase.COMPLETE
    }

    // ── Test Cases ───────────────────────────────────────────────────────────

    @Test
    fun `Day 2 reveal result - Single death from wolf kill should show one killed player`() {
        // Setup: Wolf killed victim, guard did not protect, witch did not save
        val wolf = player(wolfId, 1, PlayerRole.WEREWOLF)
        val victim = player(victimId, 2, PlayerRole.VILLAGER, alive = false) // victim is dead
        val guard = player(guardId, 3, PlayerRole.GUARD)
        val playerList = listOf(wolf, victim, guard)

        val np = nightPhase(
            dayNumber = 2,
            wolfTarget = victimId,
            guardTarget = null, // guard did not protect victim
            antidoteUsed = false
        )

        val g = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name, dayNumber = 2)
        val r = room()

        // Mock repository responses
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(g))
        whenever(roomRepository.findById(g.roomId)).thenReturn(Optional.of(r))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(playerList)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 2)).thenReturn(Optional.of(np))

        val users = listOf(
            user(wolfId, "Wolf"),
            user(victimId, "Victim"),
            user(guardId, "Guard")
        )

        // Mock userRepository.findAllById response (batch lookup)
        whenever(userRepository.findAllById(any())).thenReturn(users)

        // Call getGameState as host
        val result = gameService.getGameState(gameId, hostId)

        // Verify the result
        assertThat(result).isNotNull()
        val dayPhase = result["dayPhase"] as? Map<*, *>
        assertThat(dayPhase).isNotNull()

        val nightResult = dayPhase?.get("nightResult") as? Map<*, *>
        assertThat(nightResult).isNotNull()

        val killedPlayers = nightResult?.get("killedPlayers") as? List<*>
        assertThat(killedPlayers).isNotNull()
        assertThat(killedPlayers).hasSize(1) // Only one player died

        val killedPlayer = killedPlayers!![0] as? Map<*, *>
        assertThat(killedPlayer?.get("killedPlayerId")).isEqualTo(victimId)
        assertThat(killedPlayer?.get("killedNickname")).isEqualTo("Victim")
        assertThat(killedPlayer?.get("killedSeatIndex")).isEqualTo(2)
    }

    @Test
    fun `Day 2 reveal result - Guard protected victim should show no killed players`() {
        // Setup: Wolf killed victim, guard protected victim, victim survives
        val wolf = player(wolfId, 1, PlayerRole.WEREWOLF)
        val victim = player(victimId, 2, PlayerRole.VILLAGER, alive = true) // victim is still alive
        val guard = player(guardId, 3, PlayerRole.GUARD)
        val playerList = listOf(wolf, victim, guard)

        val np = nightPhase(
            dayNumber = 2,
            wolfTarget = victimId,
            guardTarget = victimId, // guard protected victim
            antidoteUsed = false
        )

        val g = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name, dayNumber = 2)
        val r = room()

        // Mock repository responses
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(g))
        whenever(roomRepository.findById(g.roomId)).thenReturn(Optional.of(r))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(playerList)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 2)).thenReturn(Optional.of(np))

        val users = listOf(
            user(wolfId, "Wolf"),
            user(victimId, "Victim"),
            user(guardId, "Guard")
        )

        // Mock userRepository.findAllById response (batch lookup)
        whenever(userRepository.findAllById(any())).thenReturn(users)

        // Call getGameState as host
        val result = gameService.getGameState(gameId, hostId)

        // Verify the result
        assertThat(result).isNotNull()
        val dayPhase = result["dayPhase"] as? Map<*, *>
        assertThat(dayPhase).isNotNull()

        val nightResult = dayPhase?.get("nightResult") as? Map<*, *>
        assertThat(nightResult).isNull() // No one died, nightResult should be null
    }

    @Test
    fun `Day 2 reveal result - Witch poisoned victim should show one killed player`() {
        // Setup: Wolf did not kill (or guard protected), witch poisoned victim
        val wolf = player(wolfId, 1, PlayerRole.WEREWOLF)
        val victim = player(victimId, 2, PlayerRole.VILLAGER, alive = false) // victim is dead
        val guard = player(guardId, 3, PlayerRole.GUARD)
        val playerList = listOf(wolf, victim, guard)

        val np = nightPhase(
            dayNumber = 2,
            wolfTarget = null, // no wolf kill
            guardTarget = null,
            poisonTarget = victimId // witch poisoned victim
        )

        val g = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name, dayNumber = 2)
        val r = room()

        // Mock repository responses
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(g))
        whenever(roomRepository.findById(g.roomId)).thenReturn(Optional.of(r))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(playerList)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 2)).thenReturn(Optional.of(np))

        val users = listOf(
            user(wolfId, "Wolf"),
            user(victimId, "Victim"),
            user(guardId, "Guard")
        )

        // Mock userRepository.findAllById response (batch lookup)
        whenever(userRepository.findAllById(any())).thenReturn(users)

        // Call getGameState as host
        val result = gameService.getGameState(gameId, hostId)

        // Verify the result
        assertThat(result).isNotNull()
        val dayPhase = result["dayPhase"] as? Map<*, *>
        assertThat(dayPhase).isNotNull()

        val nightResult = dayPhase?.get("nightResult") as? Map<*, *>
        assertThat(nightResult).isNotNull()

        val killedPlayers = nightResult?.get("killedPlayers") as? List<*>
        assertThat(killedPlayers).isNotNull()
        assertThat(killedPlayers).hasSize(1) // Only one player died

        val killedPlayer = killedPlayers!![0] as? Map<*, *>
        assertThat(killedPlayer?.get("killedPlayerId")).isEqualTo(victimId)
        assertThat(killedPlayer?.get("killedNickname")).isEqualTo("Victim")
        assertThat(killedPlayer?.get("killedSeatIndex")).isEqualTo(2)
    }
}