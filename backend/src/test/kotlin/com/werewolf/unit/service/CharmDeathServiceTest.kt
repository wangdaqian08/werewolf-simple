package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.service.ActionLogService
import com.werewolf.service.CharmDeathService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CharmDeathServiceTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var actionLogService: ActionLogService
    @Mock lateinit var stompPublisher: StompPublisher

    private lateinit var service: CharmDeathService

    private val gameId = 10
    private val day = 2
    private val beauty = "beauty:001"
    private val wolf = "wolf:001"
    private val seer = "seer:001"
    private val villager = "village:001"
    private val villager2 = "village:002"

    private lateinit var players: List<GamePlayer>

    @BeforeEach
    fun setUp() {
        service = CharmDeathService(gameRepository, gamePlayerRepository, actionLogService, stompPublisher, WinConditionChecker())
        whenever(gamePlayerRepository.findByGameId(gameId)).thenAnswer { players }
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
    }

    private fun p(id: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = id, seatIndex = seat, role = role, alive = alive)

    private fun ctx(
        roster: List<GamePlayer>,
        charm: String? = villager,
        mode: WinConditionMode = WinConditionMode.CLASSIC,
    ): GameContext {
        players = roster
        val game = Game(roomId = 1, hostUserId = "host").also {
            Game::class.java.getDeclaredField("gameId").apply { isAccessible = true }.set(it, gameId)
            it.phase = GamePhase.DAY_DISCUSSION
            it.dayNumber = day
        }
        val night = NightPhase(gameId = gameId, dayNumber = day, wolfBeautyCharmUserId = charm)
        return GameContext(
            game, Room(roomCode = "ABCD", hostUserId = "host", totalPlayers = 8, winCondition = mode), roster,
            allNightPhases = listOf(night),
        )
    }

    private fun board() = listOf(
        p(beauty, 1, PlayerRole.WOLF_BEAUTY), p(wolf, 2, PlayerRole.WEREWOLF),
        p(seer, 3, PlayerRole.SEER), p(villager, 4, PlayerRole.VILLAGER), p(villager2, 5, PlayerRole.VILLAGER),
    )

    @Test
    fun `not the wolf beauty or no charm - null and nothing saved`() {
        val c = ctx(board())
        assertThat(service.onDayExit(c, wolf)).isNull()
        assertThat(service.onDayExit(ctx(board(), charm = null), beauty)).isNull()
        verify(gamePlayerRepository, never()).save(any<GamePlayer>())
        verify(gameRepository, never()).save(any<Game>())
        verifyNoInteractions(actionLogService)
        verifyNoInteractions(stompPublisher)
    }

    @Test
    fun `charm victim dies, is logged and broadcast after commit`() {
        val c = ctx(board())
        val out = service.onDayExit(c, beauty)

        assertThat(out).isEqualTo(com.werewolf.service.CharmOutcome(villager, null))
        val v = players.first { it.userId == villager }
        assertThat(v.alive).isFalse()
        assertThat(v.diedDay).isEqualTo(day)
        assertThat(c.game.charmDeathUserId).isEqualTo(villager)
        verify(gameRepository).save(c.game)
        verify(actionLogService).recordCharmDeath(gameId, day, beauty, villager)
        verify(stompPublisher).broadcastGameAfterCommit(gameId, DomainEvent.PlayerCharmDeath(gameId, beauty, villager))
    }

    @Test
    fun `last god taken while the beauty is the last wolf - werewolf wins, beauty already saved dead or not`() {
        for (beautyAlreadyDead in listOf(false, true)) {
            val roster = listOf(
                p(beauty, 1, PlayerRole.WOLF_BEAUTY, alive = !beautyAlreadyDead),
                p(seer, 2, PlayerRole.SEER), p(villager, 3, PlayerRole.VILLAGER),
            )
            val out = service.onDayExit(ctx(roster, charm = seer), beauty)
            assertThat(out?.winner).isEqualTo(WinnerSide.WEREWOLF)
        }
    }

    @Test
    fun `hard mode one against one after the charm is not a win`() {
        val roster = listOf(
            p(beauty, 1, PlayerRole.WOLF_BEAUTY, alive = false), p(seer, 2, PlayerRole.SEER), p(villager, 3, PlayerRole.VILLAGER),
        )
        val out = service.onDayExit(ctx(roster, charm = seer, mode = WinConditionMode.HARD_MODE), beauty)
        assertThat(out).isNotNull
        assertThat(out?.winner).isNull()
    }
}
