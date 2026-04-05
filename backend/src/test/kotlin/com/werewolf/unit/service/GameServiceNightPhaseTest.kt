package com.werewolf.unit.service

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
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.util.*

@ExtendWith(MockitoExtension::class)
@Suppress("UNCHECKED_CAST")
class GameServiceNightPhaseTest {

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
    private val day = 2

    private fun game(phase: GamePhase = GamePhase.NIGHT) = Game(roomId = 1, hostUserId = "u1").also { g ->
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(g, gameId)
        g.phase = phase
        g.dayNumber = day
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = "u1", totalPlayers = 6, hasSheriff = false)

    private fun player(userId: String, seat: Int, role: PlayerRole) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role)

    private fun user(userId: String, nick: String) = User(userId = userId, nickname = nick)

    private fun nightPhase(
        subPhase: NightSubPhase = NightSubPhase.WEREWOLF_PICK,
        wolfTarget: String? = null,
        seerChecked: String? = null,
        seerIsWolf: Boolean? = null,
        antidoteUsed: Boolean = false,
        poisonTarget: String? = null,
        prevGuardTarget: String? = null,
    ) = NightPhase(
        gameId = gameId,
        dayNumber = day,
        subPhase = subPhase,
        wolfTargetUserId = wolfTarget,
        seerCheckedUserId = seerChecked,
        seerResultIsWerewolf = seerIsWolf,
        witchAntidoteUsed = antidoteUsed,
        witchPoisonTargetUserId = poisonTarget,
        prevGuardTargetUserId = prevGuardTarget,
    )

    @BeforeEach
    fun setupCommon() {
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room()))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun setupGameAndPlayers(game: Game, players: List<GamePlayer>, users: List<User>) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(userRepository.findAllById(players.map { it.userId })).thenReturn(users)
    }

    @Suppress("UNCHECKED_CAST")
    private fun nightPhaseResult(result: Map<String, Any?>): Map<String, Any?> =
        result["nightPhase"] as Map<String, Any?>

    // ─────────────────────────────────────────────────────────────────────────
    // Bug 1 + 2 — WEREWOLF: player names + teammate list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - players map always includes nickname`() {
        val players = listOf(
            player("u1", 1, PlayerRole.VILLAGER),
            player("u2", 2, PlayerRole.WEREWOLF),
        )
        val users = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val game = game(phase = GamePhase.DAY).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val playerList = result["players"] as List<Map<String, Any?>>
        val alice = playerList.first { it["userId"] == "u1" }
        val bob   = playerList.first { it["userId"] == "u2" }
        assertThat(alice["nickname"]).isEqualTo("Alice")
        assertThat(bob["nickname"]).isEqualTo("Bob")
    }

    @Test
    fun `getGameState - WEREWOLF gets teammate list formatted as seat-dot-nick`() {
        val wolf1 = player("u1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("u2", 2, PlayerRole.WEREWOLF)
        val villager = player("u3", 3, PlayerRole.VILLAGER)
        val players = listOf(wolf1, wolf2, villager)
        val users = listOf(user("u1", "Wolf1"), user("u2", "Wolf2"), user("u3", "Dave"))
        val game = game()
        setupGameAndPlayers(game, players, users)

        val np = nightPhase(subPhase = NightSubPhase.WEREWOLF_PICK, wolfTarget = "u3")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        @Suppress("UNCHECKED_CAST")
        val teammates = night["teammates"] as List<String>
        assertThat(teammates).containsExactly("2·Wolf2")
        assertThat(night["selectedTargetId"]).isEqualTo("u3")
    }

    @Test
    fun `getGameState - non-wolf player does NOT get teammates`() {
        val players = listOf(
            player("u1", 1, PlayerRole.VILLAGER),
            player("u2", 2, PlayerRole.WEREWOLF),
        )
        val users = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val game = game()
        setupGameAndPlayers(game, players, users)
        val np = nightPhase(subPhase = NightSubPhase.WAITING)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night.containsKey("teammates")).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bug 3 — SEER: result card + history
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - SEER gets seerResult when check is complete`() {
        val seer = player("u1", 1, PlayerRole.SEER)
        val target = player("u3", 3, PlayerRole.WEREWOLF)
        val players = listOf(seer, target)
        val users = listOf(user("u1", "Seer"), user("u3", "Wolf"))
        val game = game()
        setupGameAndPlayers(game, players, users)

        // current night: seer already checked u3
        val np = nightPhase(
            subPhase = NightSubPhase.SEER_RESULT,
            seerChecked = "u3",
            seerIsWolf = true,
        )
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        // history includes previous night (day 1) and current (day 2)
        val prevNight = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.seerCheckedUserId = "u3"
            it.seerResultIsWerewolf = true
        }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(prevNight, np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        @Suppress("UNCHECKED_CAST")
        val seerResult = night["seerResult"] as Map<String, Any?>
        assertThat(seerResult["checkedPlayerId"]).isEqualTo("u3")
        assertThat(seerResult["checkedNickname"]).isEqualTo("Wolf")
        assertThat(seerResult["checkedSeatIndex"]).isEqualTo(3)
        assertThat(seerResult["isWerewolf"]).isEqualTo(true)

        @Suppress("UNCHECKED_CAST")
        val history = seerResult["history"] as List<Map<String, Any?>>
        assertThat(history).hasSize(2)
        assertThat(history[0]["round"]).isEqualTo(1)
        assertThat(history[0]["isWerewolf"]).isEqualTo(true)
    }

    @Test
    fun `getGameState - SEER does NOT get seerResult before check is done`() {
        val seer = player("u1", 1, PlayerRole.SEER)
        val players = listOf(seer)
        val users = listOf(user("u1", "Seer"))
        val game = game()
        setupGameAndPlayers(game, players, users)
        val np = nightPhase(subPhase = NightSubPhase.SEER_PICK)  // no seerCheckedUserId
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night.containsKey("seerResult")).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bug 4 — WITCH: antidote / poison availability + attack info
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - WITCH gets hasAntidote=true and hasPoison=true when unused`() {
        val witch = player("u1", 1, PlayerRole.WITCH)
        val attacked = player("u4", 4, PlayerRole.VILLAGER)
        val players = listOf(witch, attacked)
        val users = listOf(user("u1", "Witch"), user("u4", "Dave"))
        val game = game()
        setupGameAndPlayers(game, players, users)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT, wolfTarget = "u4")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        // no prior night, so no antidote/poison used ever
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["hasAntidote"]).isEqualTo(true)
        assertThat(night["hasPoison"]).isEqualTo(true)
        assertThat(night["attackedPlayerId"]).isEqualTo("u4")
        assertThat(night["attackedNickname"]).isEqualTo("Dave")
        assertThat(night["attackedSeatIndex"]).isEqualTo(4)
    }

    @Test
    fun `getGameState - WITCH gets hasAntidote=false when used in a previous night`() {
        val witch = player("u1", 1, PlayerRole.WITCH)
        val attacked = player("u4", 4, PlayerRole.VILLAGER)
        val players = listOf(witch, attacked)
        val users = listOf(user("u1", "Witch"), user("u4", "Dave"))
        val game = game()
        setupGameAndPlayers(game, players, users)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT, wolfTarget = "u4")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        // night 1: antidote was used
        val night1 = NightPhase(gameId = gameId, dayNumber = 1).also { it.witchAntidoteUsed = true }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(night1, np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["hasAntidote"]).isEqualTo(false)
        assertThat(night["hasPoison"]).isEqualTo(true)  // poison not yet used
    }

    @Test
    fun `getGameState - WITCH gets hasPoison=false when poison used in a previous night`() {
        val witch = player("u1", 1, PlayerRole.WITCH)
        val players = listOf(witch)
        val users = listOf(user("u1", "Witch"))
        val game = game()
        setupGameAndPlayers(game, players, users)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        val night1 = NightPhase(gameId = gameId, dayNumber = 1).also { it.witchPoisonTargetUserId = "u5" }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(night1, np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["hasPoison"]).isEqualTo(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bug 5 — GUARD: previous target indicator
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - GUARD gets previousGuardTargetId`() {
        val guard = player("u1", 1, PlayerRole.GUARD)
        val players = listOf(guard)
        val users = listOf(user("u1", "Guard"))
        val game = game()
        setupGameAndPlayers(game, players, users)

        val np = nightPhase(subPhase = NightSubPhase.GUARD_PICK, prevGuardTarget = "u4")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["previousGuardTargetId"]).isEqualTo("u4")
    }

    @Test
    fun `getGameState - GUARD gets null previousGuardTargetId on first night`() {
        val guard = player("u1", 1, PlayerRole.GUARD)
        val players = listOf(guard)
        val users = listOf(user("u1", "Guard"))
        val game = game()
        setupGameAndPlayers(game, players, users)

        val np = nightPhase(subPhase = NightSubPhase.GUARD_PICK)  // no prevGuardTarget
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["previousGuardTargetId"]).isNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE_REVEAL phase
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - ROLE_REVEAL returns confirmedCount and totalCount`() {
        val p1 = player("u1", 1, PlayerRole.VILLAGER).also { it.confirmedRole = true }
        val p2 = player("u2", 2, PlayerRole.WEREWOLF)
        val p3 = player("u3", 3, PlayerRole.SEER)
        val players = listOf(p1, p2, p3)
        val users = listOf(user("u1", "Alice"), user("u2", "Bob"), user("u3", "Carol"))
        val game = game(phase = GamePhase.ROLE_REVEAL).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val roleReveal = result["roleReveal"] as Map<String, Any?>
        assertThat(roleReveal["confirmedCount"]).isEqualTo(1)
        assertThat(roleReveal["totalCount"]).isEqualTo(3)
    }

    @Test
    fun `getGameState - ROLE_REVEAL werewolf sees teammate list`() {
        val wolf1 = player("u1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("u2", 2, PlayerRole.WEREWOLF)
        val villager = player("u3", 3, PlayerRole.VILLAGER)
        val players = listOf(wolf1, wolf2, villager)
        val users = listOf(user("u1", "Wolf1"), user("u2", "Wolf2"), user("u3", "Dave"))
        val game = game(phase = GamePhase.ROLE_REVEAL).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val roleReveal = result["roleReveal"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val teammates = roleReveal["teammates"] as List<String>
        assertThat(teammates).containsExactly("Wolf2")
    }

    @Test
    fun `getGameState - ROLE_REVEAL non-werewolf gets empty teammates`() {
        val villager = player("u1", 1, PlayerRole.VILLAGER)
        val wolf = player("u2", 2, PlayerRole.WEREWOLF)
        val players = listOf(villager, wolf)
        val users = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val game = game(phase = GamePhase.ROLE_REVEAL).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val roleReveal = result["roleReveal"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val teammates = roleReveal["teammates"] as List<String>
        assertThat(teammates).isEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GAME_OVER phase — roles exposed for all players
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - GAME_OVER exposes roles for all players`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val seer = player("u2", 2, PlayerRole.SEER)
        val villager = player("u3", 3, PlayerRole.VILLAGER)
        val players = listOf(wolf, seer, villager)
        val users = listOf(user("u1", "Wolf"), user("u2", "Seer"), user("u3", "Villager"))
        val game = game(phase = GamePhase.GAME_OVER).also {
            it.dayNumber = 1
            it.winner = WinnerSide.WEREWOLF
        }
        setupGameAndPlayers(game, players, users)

        // Requesting as u3 (villager) — should still see everyone's roles
        val result = gameService.getGameState(gameId, "u3")

        @Suppress("UNCHECKED_CAST")
        val playerList = result["players"] as List<Map<String, Any?>>
        val wolfEntry = playerList.first { it["userId"] == "u1" }
        val seerEntry = playerList.first { it["userId"] == "u2" }
        assertThat(wolfEntry["role"]).isEqualTo("WEREWOLF")
        assertThat(seerEntry["role"]).isEqualTo("SEER")
    }

    @Test
    fun `getGameState - during NIGHT phase other player roles are hidden`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val seer = player("u2", 2, PlayerRole.SEER)
        val players = listOf(wolf, seer)
        val users = listOf(user("u1", "Wolf"), user("u2", "Seer"))
        val game = game(phase = GamePhase.NIGHT)
        setupGameAndPlayers(game, players, users)

        val np = nightPhase(subPhase = NightSubPhase.WAITING)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        // Requesting as u2 (seer) — should only see own role
        val result = gameService.getGameState(gameId, "u2")

        @Suppress("UNCHECKED_CAST")
        val playerList = result["players"] as List<Map<String, Any?>>
        val wolfEntry = playerList.first { it["userId"] == "u1" }
        val seerEntry = playerList.first { it["userId"] == "u2" }
        assertThat(wolfEntry["role"]).isNull() // hidden
        assertThat(seerEntry["role"]).isEqualTo("SEER") // own role visible
    }
}
