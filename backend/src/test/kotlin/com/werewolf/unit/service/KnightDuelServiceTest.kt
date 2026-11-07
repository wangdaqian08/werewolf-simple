package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.phase.DayRevealAdvancer
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.ActionLogService
import com.werewolf.service.KnightDuelService
import com.werewolf.service.RewardSettlementService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnightDuelServiceTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var actionLogService: ActionLogService
    @Mock lateinit var dayRevealAdvancer: DayRevealAdvancer
    @Mock lateinit var rewardSettlementService: RewardSettlementService

    private lateinit var service: KnightDuelService

    @BeforeEach
    fun setUp() {
        service = KnightDuelService(
            gameRepository, gamePlayerRepository, userRepository, stompPublisher, actionLogService,
            dayRevealAdvancer, WinConditionChecker(), rewardSettlementService,
        )
        // Mirror the DB: the service re-reads the rows it mutates.
        whenever(gamePlayerRepository.findByGameIdAndUserId(eq(gameId), any())).thenAnswer { inv ->
            Optional.ofNullable(players.find { it.userId == inv.getArgument<String>(1) })
        }
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        // Mirror the real advancer: a dead sitting sheriff owes the handover.
        whenever(dayRevealAdvancer.nextSubPhase(gameId)).thenAnswer {
            val sheriff = currentGame.sheriffUserId
            if (sheriff != null && players.find { it.userId == sheriff }?.alive == false) DaySubPhase.BADGE_HANDOVER
            else DaySubPhase.RESULT_REVEALED
        }
    }

    private val gameId = 10
    private val hostId = "host:001"
    private val knightId = "knight:001"
    private val wolfId = "wolf:001"
    private val wolf2Id = "wolf:002"
    private val villagerId = "village:001"
    private val day = 2

    private lateinit var players: List<GamePlayer>
    private lateinit var currentGame: Game

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role, alive = alive)

    private fun game(
        phase: GamePhase = GamePhase.DAY_DISCUSSION,
        subPhase: String? = DaySubPhase.RESULT_REVEALED.name,
    ) = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = phase
        it.subPhase = subPhase
        it.dayNumber = day
    }

    /** Two pack wolves stay alive by default, so a wolf duel does not end the game. */
    private fun context(
        game: Game = game(),
        knightAlive: Boolean = true,
        wolf2Alive: Boolean = true,
        extra: List<GamePlayer> = emptyList(),
        nightPhases: List<NightPhase> = emptyList(),
        winCondition: WinConditionMode = WinConditionMode.CLASSIC,
    ): GameContext {
        currentGame = game
        players = listOf(
            player(knightId, 1, PlayerRole.KNIGHT, knightAlive),
            player(wolfId, 2, PlayerRole.WEREWOLF),
            player(wolf2Id, 3, PlayerRole.WEREWOLF, wolf2Alive),
            player(villagerId, 4, PlayerRole.VILLAGER),
        ) + extra
        return GameContext(
            game, Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 8, winCondition = winCondition), players,
            allNightPhases = nightPhases,
        )
    }

    /** [ctx] with [sheriffId] holding the badge (game field and player flag, as in production). */
    private fun withSheriff(ctx: GameContext, sheriffId: String) = ctx.also {
        it.game.sheriffUserId = sheriffId
        players.first { p -> p.userId == sheriffId }.sheriff = true
    }

    private fun broadcastEvents(): List<DomainEvent> {
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        return captor.allValues
    }

    private fun req(actor: String = knightId, target: String? = wolfId) =
        GameActionRequest(gameId, actor, ActionType.KNIGHT_DUEL, targetUserId = target)

    private fun assertNothingSaved() {
        verify(gamePlayerRepository, never()).save(any<GamePlayer>())
        verify(gameRepository, never()).save(any<Game>())
        verifyNoInteractions(actionLogService)
        verifyNoInteractions(stompPublisher)
    }

    private fun assertRejected(result: GameActionResult) {
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertNothingSaved()
    }

    // ── Window ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(ElectionSubPhase::class)
    fun `rejected in every SHERIFF_ELECTION sub-phase`(sub: ElectionSubPhase) {
        val ctx = context(game(GamePhase.SHERIFF_ELECTION, sub.name))
        assertRejected(service.duel(req(), ctx))
    }

    @Test
    fun `rejected in RESULT_HIDDEN, BADGE_HANDOVER, every DAY_VOTING sub-phase and NIGHT`() {
        val cases = listOf(
            GamePhase.DAY_DISCUSSION to DaySubPhase.RESULT_HIDDEN.name,
            GamePhase.DAY_DISCUSSION to DaySubPhase.BADGE_HANDOVER.name,
            GamePhase.NIGHT to NightSubPhase.WEREWOLF_PICK.name,
        ) + VotingSubPhase.values().map { GamePhase.DAY_VOTING to it.name }
        cases.forEach { (phase, sub) ->
            assertRejected(service.duel(req(), context(game(phase, sub))))
        }
    }

    @Test
    fun `rejected after a self-destruct (daySkipVoting)`() {
        val ctx = context(game().also { it.daySkipVoting = true })
        assertRejected(service.duel(req(), ctx))
    }

    @Test
    fun `night-killed knight in SPEECH gets the window rejection`() {
        val night = NightPhase(gameId = gameId, dayNumber = day, wolfTargetUserId = knightId)
        val killed = context(game(GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SPEECH.name), nightPhases = listOf(night))
        val clean = context(game(GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SPEECH.name))

        val a = service.duel(req(), killed) as GameActionResult.Rejected
        val b = service.duel(req(), clean) as GameActionResult.Rejected

        assertThat(a.reason).isEqualTo(b.reason)
        assertNothingSaved()
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    fun `non-knight rejected`() = assertRejected(service.duel(req(actor = villagerId), context()))

    @Test
    fun `unknown actor rejected`() = assertRejected(service.duel(req(actor = "nobody"), context()))

    @Test
    fun `dead knight rejected`() = assertRejected(service.duel(req(), context(knightAlive = false)))

    @Test
    fun `second duel rejected`() {
        val ctx = context(game().also { it.knightDuelTargetUserId = wolfId; it.knightDuelDay = day - 1 })
        assertRejected(service.duel(req(), ctx))
    }

    @Test
    fun `target self rejected`() = assertRejected(service.duel(req(target = knightId), context()))

    @Test
    fun `dead target rejected`() =
        assertRejected(service.duel(req(target = "dead:001"), context(extra = listOf(player("dead:001", 5, PlayerRole.WEREWOLF, alive = false)))))

    @Test
    fun `unknown target rejected`() = assertRejected(service.duel(req(target = "nobody"), context()))

    @Test
    fun `missing target rejected`() = assertRejected(service.duel(req(target = null), context()))

    // ── Outcomes ─────────────────────────────────────────────────────────────

    @Test
    fun `duel on WEREWOLF kills it and skips the vote`() {
        val ctx = context()

        val result = service.duel(req(target = wolfId), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        val wolf = players.first { it.userId == wolfId }
        assertThat(wolf.alive).isFalse()
        assertThat(wolf.diedDay).isEqualTo(day)
        assertThat(players.first { it.userId == knightId }.alive).isTrue()
        assertThat(ctx.game.daySkipVoting).isTrue()
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(ctx.game.knightDuelTargetUserId).isEqualTo(wolfId)
        assertThat(ctx.game.knightDuelDay).isEqualTo(day)
    }

    @ParameterizedTest
    @ValueSource(strings = ["WHITE_WOLF_KING", "BLACK_WOLF_KING", "MECHANICAL_WOLF"])
    fun `duel on any wolf role kills it and skips the vote`(role: String) {
        val ctx = context(extra = listOf(player("special:001", 5, PlayerRole.valueOf(role))))

        val result = service.duel(req(target = "special:001"), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(players.first { it.userId == "special:001" }.alive).isFalse()
        assertThat(ctx.game.daySkipVoting).isTrue()
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(ctx.game.knightDuelDay).isEqualTo(day)
    }

    @Test
    fun `duel on a good player kills the knight, the day goes on`() {
        val ctx = context()

        val result = service.duel(req(target = villagerId), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        val knight = players.first { it.userId == knightId }
        assertThat(knight.alive).isFalse()
        assertThat(knight.diedDay).isEqualTo(day)
        assertThat(players.first { it.userId == villagerId }.alive).isTrue()
        assertThat(ctx.game.daySkipVoting).isFalse()
        assertThat(ctx.game.knightDuelTargetUserId).isEqualTo(villagerId)
        assertThat(ctx.game.knightDuelDay).isEqualTo(day)
    }

    @Test
    fun `duel does not touch self-destruct fields`() {
        val wolfCtx = context()
        service.duel(req(target = wolfId), wolfCtx)
        assertThat(wolfCtx.game.selfDestructUserId).isNull()
        assertThat(wolfCtx.game.selfDestructTakenUserId).isNull()

        val goodCtx = context()
        service.duel(req(target = villagerId), goodCtx)
        assertThat(goodCtx.game.selfDestructUserId).isNull()
        assertThat(goodCtx.game.selfDestructTakenUserId).isNull()
    }

    @Test
    fun `records KNIGHT_DUEL and broadcasts KnightDuel + PhaseChanged`() {
        val ctx = context()

        service.duel(req(target = wolfId), ctx)

        verify(actionLogService).recordKnightDuel(
            eq(gameId), eq(day), eq(knightId), any(), eq(1), eq(wolfId), any(), eq(2), eq(true),
        )
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, times(2)).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.allValues[0]).isEqualTo(DomainEvent.KnightDuel(gameId, knightId, 1, wolfId, 2, true))
        assertThat(captor.allValues[1])
            .isEqualTo(DomainEvent.PhaseChanged(gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name))
    }

    // ── Game end (K6) ────────────────────────────────────────────────────────

    @Test
    fun `duel on the last wolf ends the game, villagers win`() {
        val ctx = context(wolf2Alive = false)

        val result = service.duel(req(target = wolfId), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(ctx.game.winner).isEqualTo(WinnerSide.VILLAGER)
        assertThat(ctx.game.endedAt).isNotNull()
        verify(rewardSettlementService).settle(gameId, WinnerSide.VILLAGER)
        assertThat(broadcastEvents().last()).isEqualTo(DomainEvent.GameOver(gameId, WinnerSide.VILLAGER))
    }

    @Test
    fun `HARD_MODE - failed duel kills the last god, wolves win`() {
        // The knight is the only god; once it dies 2 wolves face 1 villager with no counterplay.
        val ctx = context(winCondition = WinConditionMode.HARD_MODE)

        service.duel(req(target = villagerId), ctx)

        assertThat(players.first { it.userId == knightId }.alive).isFalse()
        assertThat(ctx.game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(ctx.game.winner).isEqualTo(WinnerSide.WEREWOLF)
        verify(rewardSettlementService).settle(gameId, WinnerSide.WEREWOLF)
        assertThat(broadcastEvents().last()).isEqualTo(DomainEvent.GameOver(gameId, WinnerSide.WEREWOLF))
    }

    @Test
    fun `HARD_MODE - a duelled wolf leaves parity and the spent duel is no counterplay, wolves win`() {
        // 3 wolves + knight + villager: the duel kills one wolf -> 2 wolves vs 2 humans, and the
        // living knight's duel is now used, so the wolves hold parity with no counterplay.
        val ctx = context(
            winCondition = WinConditionMode.HARD_MODE,
            extra = listOf(player("wolf:003", 5, PlayerRole.WEREWOLF)),
        )

        service.duel(req(target = wolfId), ctx)

        assertThat(players.first { it.userId == knightId }.alive).isTrue()
        assertThat(ctx.game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(ctx.game.winner).isEqualTo(WinnerSide.WEREWOLF)
        verify(rewardSettlementService).settle(gameId, WinnerSide.WEREWOLF)
    }

    @Test
    fun `a duel that ends nothing does not settle the game`() {
        val ctx = context()

        service.duel(req(target = wolfId), ctx)

        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.winner).isNull()
        verifyNoInteractions(rewardSettlementService)
    }

    // ── Sheriff badge (K5) ───────────────────────────────────────────────────

    @Test
    fun `knight sheriff dies → BADGE_HANDOVER, sheriff kept for handover`() {
        val ctx = withSheriff(context(), knightId)

        service.duel(req(target = villagerId), ctx)

        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        assertThat(ctx.game.sheriffUserId).isEqualTo(knightId)
        assertThat(players.first { it.userId == knightId }.sheriff).isTrue()
        assertThat(broadcastEvents())
            .contains(DomainEvent.PhaseChanged(gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.BADGE_HANDOVER.name))
    }

    @Test
    fun `duelled wolf sheriff hands the badge over, not destroyed`() {
        val ctx = withSheriff(context(), wolfId)

        service.duel(req(target = wolfId), ctx)

        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        assertThat(ctx.game.sheriffUserId).isEqualTo(wolfId)
        assertThat(players.first { it.userId == wolfId }.sheriff).isTrue()
        assertThat(ctx.game.daySkipVoting).isTrue()
        assertThat(broadcastEvents()).noneMatch { it is DomainEvent.BadgeHandover }
    }

    @Test
    fun `a non-sheriff death leaves the day on RESULT_REVEALED`() {
        val ctx = withSheriff(context(), villagerId)

        service.duel(req(target = wolfId), ctx)

        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(ctx.game.sheriffUserId).isEqualTo(villagerId)
    }
}
