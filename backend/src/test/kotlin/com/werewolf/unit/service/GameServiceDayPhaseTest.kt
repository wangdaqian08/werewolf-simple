package com.werewolf.unit.service

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.AudioService
import com.werewolf.service.GameService
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.util.*

@ExtendWith(MockitoExtension::class)
@Suppress("UNCHECKED_CAST")
class GameServiceDayPhaseTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var sheriffService: SheriffService
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @InjectMocks lateinit var gameService: GameService

    private val gameId = 1
    private val hostId = "host:001"
    private val day = 2

    private fun game(subPhase: String = DaySubPhase.RESULT_HIDDEN.name) =
        Game(roomId = 1, hostUserId = hostId).also { g ->
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(g, gameId)
            g.phase = GamePhase.DAY
            g.subPhase = subPhase
            g.dayNumber = day
        }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSheriff = false)
    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role)
    private fun user(userId: String, nick: String) = User(userId = userId, nickname = nick)

    private fun nightPhase(
        wolfTarget: String? = null,
        antidoteUsed: Boolean = false,
        poisonTarget: String? = null,
        guardTarget: String? = null,
    ) = NightPhase(
        gameId = gameId, dayNumber = day,
        wolfTargetUserId = wolfTarget,
        witchAntidoteUsed = antidoteUsed,
        witchPoisonTargetUserId = poisonTarget,
        guardTargetUserId = guardTarget,
    )

    @BeforeEach
    fun setupCommon() {
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room()))
    }

    private fun setupGameAndPlayers(game: Game, players: List<GamePlayer>, users: List<User>) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(userRepository.findAllById(players.map { it.userId })).thenReturn(users)
    }

    private fun dayResult(result: Map<String, Any?>): Map<String, Any?> =
        result["dayPhase"] as Map<String, Any?>

    // ── subPhase ─────────────────────────────────────────────────────────────

    @Test
    fun `getGameState DAY - subPhase defaults to RESULT_HIDDEN`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        setupGameAndPlayers(game(), players, users)

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)

        assertThat(dayPhase["subPhase"]).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    @Test
    fun `getGameState DAY - subPhase is RESULT_REVEALED after host reveals`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_REVEALED.name), players, users)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase())) // peaceful night

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)

        assertThat(dayPhase["subPhase"]).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }

    // ── nightResult — wolf kill ──────────────────────────────────────────────

    @Test
    fun `getGameState DAY - nightResult is null when subPhase is RESULT_HIDDEN`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_HIDDEN.name), players, users)

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)

        assertThat(dayPhase["nightResult"]).isNull()
    }

    @Test
    fun `getGameState DAY - wolf kill shown when result revealed and wolf target not saved`() {
        val victim = player("u2", 1)
        val players = listOf(player(hostId, 0), victim)
        val users = listOf(user(hostId, "Host"), user("u2", "Victim"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_REVEALED.name), players, users)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(wolfTarget = "u2")))

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)
        val nightResult = dayPhase["nightResult"] as Map<String, Any?>
        val killedPlayers = nightResult["killedPlayers"] as List<Map<String, Any?>>

        assertThat(killedPlayers).hasSize(1)
        assertThat(killedPlayers[0]["killedPlayerId"]).isEqualTo("u2")
        assertThat(killedPlayers[0]["killedNickname"]).isEqualTo("Victim")
        assertThat(killedPlayers[0]["killedSeatIndex"]).isEqualTo(1)
    }

    @Test
    fun `getGameState DAY - wolf kill negated by guard protection, nightResult is null`() {
        val players = listOf(player(hostId, 0), player("u2", 1))
        val users = listOf(user(hostId, "Host"), user("u2", "Protected"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_REVEALED.name), players, users)
        // Guard protected the wolf target
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(wolfTarget = "u2", guardTarget = "u2")))

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)

        assertThat(dayPhase["nightResult"]).isNull() // nobody died
    }

    @Test
    fun `getGameState DAY - wolf kill negated by witch antidote, nightResult is null`() {
        val players = listOf(player(hostId, 0), player("u2", 1))
        val users = listOf(user(hostId, "Host"), user("u2", "Saved"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_REVEALED.name), players, users)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(wolfTarget = "u2", antidoteUsed = true)))

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)

        assertThat(dayPhase["nightResult"]).isNull()
    }

    // ── nightResult — multiple kills (wolf + poison) ─────────────────────────

    @Test
    fun `getGameState DAY - wolf kill + witch poison shows two killed players`() {
        val wolfVictim = player("u2", 1)
        val poisonVictim = player("u3", 2)
        val players = listOf(player(hostId, 0), wolfVictim, poisonVictim)
        val users = listOf(user(hostId, "Host"), user("u2", "WolfVictim"), user("u3", "PoisonVictim"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_REVEALED.name), players, users)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(wolfTarget = "u2", poisonTarget = "u3")))

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)
        val nightResult = dayPhase["nightResult"] as Map<String, Any?>
        val killedPlayers = nightResult["killedPlayers"] as List<Map<String, Any?>>

        assertThat(killedPlayers).hasSize(2)
        val killedIds = killedPlayers.map { it["killedPlayerId"] }
        assertThat(killedIds).containsExactlyInAnyOrder("u2", "u3")
    }

    @Test
    fun `getGameState DAY - only witch poison when wolf target saved by antidote`() {
        val players = listOf(player(hostId, 0), player("u2", 1), player("u3", 2))
        val users = listOf(user(hostId, "Host"), user("u2", "Saved"), user("u3", "Poisoned"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_REVEALED.name), players, users)
        // Wolf attacked u2 but witch used antidote; witch also poisoned u3
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(wolfTarget = "u2", antidoteUsed = true, poisonTarget = "u3")))

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)
        val nightResult = dayPhase["nightResult"] as Map<String, Any?>
        val killedPlayers = nightResult["killedPlayers"] as List<Map<String, Any?>>

        assertThat(killedPlayers).hasSize(1)
        assertThat(killedPlayers[0]["killedPlayerId"]).isEqualTo("u3")
    }

    // ── canVote ──────────────────────────────────────────────────────────────

    @Test
    fun `getGameState DAY - canVote is true for alive player with vote rights`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        setupGameAndPlayers(game(), players, users)

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)

        assertThat(dayPhase["canVote"]).isEqualTo(true)
    }

    @Test
    fun `getGameState DAY - canVote is false for dead player`() {
        val dead = player(hostId, 0).also { it.alive = false }
        val players = listOf(dead)
        val users = listOf(user(hostId, "Host"))
        setupGameAndPlayers(game(), players, users)

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)

        assertThat(dayPhase["canVote"]).isEqualTo(false)
    }

    @Test
    fun `getGameState DAY - peaceful night shows null nightResult`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_REVEALED.name), players, users)
        // No wolf target at all
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase()))

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)

        assertThat(dayPhase["nightResult"]).isNull()
    }

    @Test
    fun `getGameState DAY - wolf and witch kill same player shows only once (no duplicate)`() {
        // Bug fix: when wolf and witch kill the same player, they should only appear once
        val victim = player("u2", 1)
        val players = listOf(player(hostId, 0), victim)
        val users = listOf(user(hostId, "Host"), user("u2", "Victim"))
        setupGameAndPlayers(game(DaySubPhase.RESULT_REVEALED.name), players, users)
        // Wolf attacks u2, witch also poisons u2 - same player
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(wolfTarget = "u2", poisonTarget = "u2")))

        val result = gameService.getGameState(gameId, hostId)
        val dayPhase = dayResult(result)
        val nightResult = dayPhase["nightResult"] as Map<String, Any?>
        val killedPlayers = nightResult["killedPlayers"] as List<Map<String, Any?>>

        // Should only show the player once, not twice
        assertThat(killedPlayers).hasSize(1)
        assertThat(killedPlayers[0]["killedPlayerId"]).isEqualTo("u2")
    }
}
