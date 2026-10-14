package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.*
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.ActionLogService
import com.werewolf.service.SelfDestructService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SelfDestructServiceTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var actionLogService: ActionLogService
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var winConditionChecker: WinConditionChecker
    @Mock lateinit var rewardSettlementService: com.werewolf.service.RewardSettlementService
    @Mock lateinit var dayRevealAdvancer: com.werewolf.game.phase.DayRevealAdvancer
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository

    private lateinit var selfDestructService: SelfDestructService

    @BeforeEach
    fun setUp() {
        selfDestructService = SelfDestructService(
            gameRepository,
            gamePlayerRepository,
            userRepository,
            stompPublisher,
            actionLogService,
            nightOrchestrator,
            winConditionChecker,
            rewardSettlementService,
            dayRevealAdvancer,
            eliminationHistoryRepository,
        )
    }

    private val gameId = 10
    private val electionId = 20
    private val hostId = "host:001"
    private val wolfId = "wolf:001"
    private val villageId = "village:001"

    private fun game(
        phase: GamePhase = GamePhase.DAY_DISCUSSION,
        subPhase: String = DaySubPhase.RESULT_REVEALED.name,
        sheriffUserId: String? = null,
    ) = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = phase
        it.subPhase = subPhase
        it.sheriffUserId = sheriffUserId
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 4, hasSheriff = true)

    private fun wolfPlayer(userId: String = wolfId, seat: Int = 1, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.WEREWOLF, alive = alive)

    private fun villagePlayer(userId: String = villageId, seat: Int = 2) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.VILLAGER)

    private fun election(
        subPhase: ElectionSubPhase = ElectionSubPhase.SIGNUP,
    ) = SheriffElection(gameId = gameId, subPhase = subPhase).also {
        val f = SheriffElection::class.java.getDeclaredField("id"); f.isAccessible = true; f.set(it, electionId)
    }

    private fun context(
        game: Game = game(),
        players: List<GamePlayer> = listOf(wolfPlayer(), villagePlayer()),
        election: SheriffElection? = null,
    ) = GameContext(game, room(), players, election = election)

    private fun req(actorUserId: String = wolfId, targetUserId: String? = null) =
        GameActionRequest(gameId, actorUserId, ActionType.WOLF_SELF_DESTRUCT, targetUserId = targetUserId)

    // ── Case 1: Wolf during SHERIFF_ELECTION/SIGNUP → election aborts ──────────

    @Test
    fun `wolf during SHERIFF_ELECTION SIGNUP aborts election and transitions to DAY_DISCUSSION RESULT_HIDDEN`() {
        val elec = election(ElectionSubPhase.SIGNUP)
        val ctx = context(
            game = game(phase = GamePhase.SHERIFF_ELECTION, subPhase = null.toString()),
            players = listOf(wolfPlayer(), villagePlayer()),
            election = elec,
        )
        ctx.game.phase = GamePhase.SHERIFF_ELECTION
        ctx.game.subPhase = null

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        assertThat(ctx.game.daySkipVoting).isTrue()
    }

    // ── Case 2: Wolf during SHERIFF_ELECTION/VOTING aborts election ─────────────

    @Test
    fun `wolf during SHERIFF_ELECTION VOTING aborts election and clears candidates`() {
        val elec = election(ElectionSubPhase.VOTING)
        val ctx = context(
            game = game(phase = GamePhase.SHERIFF_ELECTION, subPhase = null.toString()),
            players = listOf(wolfPlayer(), villagePlayer()),
            election = elec,
        )
        ctx.game.phase = GamePhase.SHERIFF_ELECTION
        ctx.game.subPhase = null

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        assertThat(ctx.game.daySkipVoting).isTrue()
    }

    // ── Case 3: Wolf-sheriff self-destructs → badge destroyed ───────────────────

    @Test
    fun `wolf-sheriff in DAY_DISCUSSION RESULT_REVEALED destroys badge and broadcasts BadgeHandover`() {
        val sheriffWolf = wolfPlayer().also { it.sheriff = true }
        val ctx = context(
            game = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name, sheriffUserId = wolfId),
            players = listOf(sheriffWolf, villagePlayer()),
        )

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(sheriffWolf))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.sheriffUserId).isNull()
        // Badge burned on the wolf's GamePlayer row too — otherwise the ⭐ stays on
        // every player slot because PlayerSlot reads GamePlayer.sheriff, not game.sheriffUserId.
        assertThat(sheriffWolf.sheriff).isFalse()

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        val handover = captor.allValues.filterIsInstance<DomainEvent.BadgeHandover>()
        assertThat(handover).isNotEmpty()
        assertThat(handover.first().fromUserId).isEqualTo(wolfId)
        assertThat(handover.first().toUserId).isNull()
    }

    // ── Case 4: Wolf during DAY_VOTING → day ends to the "enter night" path ─────
    // Standard rule: 自爆 ends the day with NO vote. We must NOT leave the game on
    // a DAY_VOTING vote-result screen (reported as "still goes to voting"); instead
    // route to DAY_DISCUSSION/RESULT_REVEALED so the host's single 进入夜晚 button
    // advances straight to night, identical to a self-destruct during discussion.

    @ParameterizedTest
    @ValueSource(strings = ["VOTING", "RE_VOTING", "VOTE_RESULT"])
    fun `self-destruct is rejected once day voting has started`(subPhase: String) {
        val ctx = context(game = game(phase = GamePhase.DAY_VOTING, subPhase = subPhase))
        stubCanSucceed(ctx)
        assertThat(selfDestructService.selfDestruct(req(), ctx)).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(ctx.game.daySkipVoting).isFalse()
    }

    // ── Case 5: Non-wolf attempts → Rejected ───────────────────────────────────

    @Test
    fun `non-wolf gets rejected with message`() {
        val ctx = context(
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        val result = selfDestructService.selfDestruct(req(villageId), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Only werewolves")
        verify(gameRepository, never()).save(any<Game>())
    }

    @Test
    fun `WHITE_WOLF_KING can self-destruct like any wolf`() {
        val king = GamePlayer(gameId = gameId, userId = wolfId, seatIndex = 1, role = PlayerRole.WHITE_WOLF_KING)
        val ctx = context(players = listOf(king, villagePlayer()))
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId)).thenReturn(Optional.of(king))
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(king.alive).isFalse()
    }

    // ── White Wolf King (白狼王): optional take ─────────────────────────────────

    private val kingId = "king:001"

    private fun kingPlayer() =
        GamePlayer(gameId = gameId, userId = kingId, seatIndex = 3, role = PlayerRole.WHITE_WOLF_KING)

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role, alive = alive)

    // Take scenarios include a second alive wolf: a last-wolf king takes no one (house rule).

    /** Stubs the repositories so both the king and [taken] are loaded fresh and saved. */
    private fun stubKingTakes(ctx: GameContext, king: GamePlayer, taken: GamePlayer) {
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, king.userId)).thenReturn(Optional.of(king))
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, taken.userId)).thenReturn(Optional.of(taken))
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(null)
    }

    @Test
    fun `WHITE_WOLF_KING takes the chosen player with it`() {
        val king = kingPlayer()
        val taken = villagePlayer()
        val ctx = context(players = listOf(king, wolfPlayer(), taken, player("v2", 4, PlayerRole.VILLAGER)))
        ctx.game.dayNumber = 2
        stubKingTakes(ctx, king, taken)

        val result = selfDestructService.selfDestruct(req(kingId, villageId), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(king.alive).isFalse()
        assertThat(taken.alive).isFalse()
        assertThat(taken.diedDay).isEqualTo(2)
        assertThat(ctx.game.selfDestructTakenUserId).isEqualTo(villageId)
        assertThat(ctx.game.daySkipVoting).isTrue()
        verify(actionLogService).recordSelfDestruct(
            eq(gameId), eq(2), eq(kingId), any(), eq(3), eq(villageId), any(), eq(2),
        )
    }

    @Test
    fun `win check sees both the king and the taken player as dead`() {
        val king = kingPlayer()
        val taken = villagePlayer()
        val survivor = player("v2", 4, PlayerRole.VILLAGER)
        val ctx = context(players = listOf(king, wolfPlayer(), taken, survivor))
        stubKingTakes(ctx, king, taken)

        selfDestructService.selfDestruct(req(kingId, villageId), ctx)

        val captor = argumentCaptor<List<GamePlayer>>()
        verify(winConditionChecker).check(captor.capture(), any(), any(), any(), any())
        assertThat(captor.firstValue.map { it.userId }).containsExactlyInAnyOrder(wolfId, "v2")
    }

    @Test
    fun `during the sheriff campaign the win check counts last night's pending kills`() {
        val poisonedWolf = wolfPlayer("wolf:002", seat = 3)
        val np = NightPhase(gameId = gameId, dayNumber = 1).also { it.witchPoisonTargetUserId = poisonedWolf.userId }
        // Like GameContextLoader outside NIGHT: no nightPhase, last night only in allNightPhases.
        val ctx = GameContext(
            game(phase = GamePhase.SHERIFF_ELECTION, subPhase = ElectionSubPhase.SIGNUP.name).also { it.dayNumber = 1 }, room(),
            listOf(wolfPlayer(), poisonedWolf, villagePlayer()), allNightPhases = listOf(np),
        )
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId)).thenReturn(Optional.of(wolfPlayer()))
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(nightOrchestrator.computePendingKills(gameId, np)).thenReturn(listOf(poisonedWolf.userId))
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(WinnerSide.VILLAGER)

        selfDestructService.selfDestruct(req(), ctx)

        val captor = argumentCaptor<List<GamePlayer>>()
        verify(winConditionChecker).check(captor.capture(), any(), any(), any(), any())
        assertThat(captor.firstValue.map { it.userId }).containsExactly(villageId)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.GAME_OVER)
        verify(nightOrchestrator).applyNightKills(gameId, listOf(poisonedWolf.userId))
    }

    /** Stubs that let a self-destruct go through, so a rejection test cannot pass by accident. */
    private fun stubCanSucceed(ctx: GameContext) {
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId)).thenReturn(Optional.of(wolfPlayer()))
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
    }

    @Test
    fun `a second self-destruct on the same day is rejected`() {
        val ctx = context(players = listOf(wolfPlayer(), wolfPlayer("wolf:002", seat = 3), villagePlayer()))
        ctx.game.daySkipVoting = true
        stubCanSucceed(ctx)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `self-destruct waits while a voted-out hunter is about to shoot`() {
        val ctx = context(game = game(phase = GamePhase.DAY_VOTING, subPhase = VotingSubPhase.HUNTER_SHOOT.name))
        stubCanSucceed(ctx)
        assertThat(selfDestructService.selfDestruct(req(), ctx)).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `self-destruct waits while the night-killed sheriff hands over the badge`() {
        val ctx = context(game = game(subPhase = DaySubPhase.BADGE_HANDOVER.name))
        stubCanSucceed(ctx)
        assertThat(selfDestructService.selfDestruct(req(), ctx)).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `HARD_MODE counterplay after a self-destruct ignores a witch with no potions left`() {
        val witch = GamePlayer(gameId = gameId, userId = "witch:001", seatIndex = 3, role = PlayerRole.WITCH)
        val used = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.witchAntidoteUsed = true
            it.witchPoisonTargetUserId = villageId
        }
        val ctx = GameContext(
            game(), room(), listOf(wolfPlayer(), wolfPlayer("wolf:002", seat = 4), witch),
            allNightPhases = listOf(used),
        )
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId)).thenReturn(Optional.of(wolfPlayer()))
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)

        selfDestructService.selfDestruct(req(), ctx)

        val captor = argumentCaptor<com.werewolf.game.phase.HardModeCounterplay>()
        verify(winConditionChecker).check(any(), any(), any(), captor.capture(), any())
        assertThat(captor.firstValue.hasWitchWithPotions).isFalse()
    }

    @Test
    fun `a plain WEREWOLF cannot take a player`() {
        val ctx = context(players = listOf(wolfPlayer(), villagePlayer()))

        val result = selfDestructService.selfDestruct(req(wolfId, villageId), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        verify(gamePlayerRepository, never()).save(any<GamePlayer>())
        verify(gameRepository, never()).save(any<Game>())
    }

    @Test
    fun `WHITE_WOLF_KING cannot take itself`() {
        val ctx = context(players = listOf(kingPlayer(), villagePlayer()))

        val result = selfDestructService.selfDestruct(req(kingId, kingId), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        verify(gameRepository, never()).save(any<Game>())
    }

    @Test
    fun `WHITE_WOLF_KING cannot take a dead or unknown player`() {
        val ctx = context(players = listOf(kingPlayer(), player("dead", 4, PlayerRole.VILLAGER, alive = false)))

        assertThat(selfDestructService.selfDestruct(req(kingId, "dead"), ctx))
            .isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(selfDestructService.selfDestruct(req(kingId, "nobody"), ctx))
            .isInstanceOf(GameActionResult.Rejected::class.java)
        verify(gameRepository, never()).save(any<Game>())
    }

    @Test
    fun `a taken hunter dies without a shot`() {
        val king = kingPlayer()
        val hunter = player("hunter", 4, PlayerRole.HUNTER)
        val ctx = context(players = listOf(king, wolfPlayer(), hunter, villagePlayer()))
        stubKingTakes(ctx, king, hunter)

        selfDestructService.selfDestruct(req(kingId, "hunter"), ctx)

        assertThat(hunter.alive).isFalse()
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }

    @Test
    fun `a taken sheriff hands the badge over next (day result already shown)`() {
        val king = kingPlayer()
        val sheriff = villagePlayer().also { it.sheriff = true }
        val ctx = context(
            game = game(subPhase = DaySubPhase.RESULT_REVEALED.name, sheriffUserId = villageId),
            players = listOf(king, wolfPlayer(), sheriff, player("v2", 4, PlayerRole.VILLAGER)),
        )
        stubKingTakes(ctx, king, sheriff)
        whenever(dayRevealAdvancer.nextSubPhase(gameId)).thenReturn(DaySubPhase.BADGE_HANDOVER)

        selfDestructService.selfDestruct(req(kingId, villageId), ctx)

        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        // The dead sheriff keeps the badge until they pass or destroy it.
        assertThat(ctx.game.sheriffUserId).isEqualTo(villageId)
    }

    @Test
    fun `self-destruct is rejected before the host reveals the night result`() {
        val ctx = context(game = game(subPhase = DaySubPhase.RESULT_HIDDEN.name))
        stubCanSucceed(ctx)
        assertThat(selfDestructService.selfDestruct(req(), ctx)).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(ctx.game.daySkipVoting).isFalse()
    }

    @Test
    fun `self-destruct is rejected while the night-killed hunter is shooting`() {
        val ctx = context(game = game(subPhase = DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name))
        stubCanSucceed(ctx)
        assertThat(selfDestructService.selfDestruct(req(), ctx)).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    // ── Case 6: Dead wolf attempts → Rejected ──────────────────────────────────

    @Test
    fun `dead wolf gets rejected`() {
        val ctx = context(
            players = listOf(wolfPlayer(alive = false), villagePlayer()),
        )

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Dead players")
        verify(gameRepository, never()).save(any<Game>())
    }

    // ── Case 7: NIGHT phase rejects ─────────────────────────────────────────────

    @Test
    fun `NIGHT phase gets rejected with phase-not-allowed message`() {
        val ctx = context(
            game = game(phase = GamePhase.NIGHT, subPhase = NightSubPhase.WEREWOLF_PICK.name),
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("only allowed")
        verify(gameRepository, never()).save(any<Game>())
    }

    // ── Case 8: Last wolf self-destructs → GameOver villager win ───────────────

    @Test
    fun `last wolf self-destruct triggers villager win`() {
        val ctx = context(
            game = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name),
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(WinnerSide.VILLAGER)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(ctx.game.winner).isEqualTo(WinnerSide.VILLAGER)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        val gameOver = captor.allValues.filterIsInstance<DomainEvent.GameOver>()
        assertThat(gameOver).isNotEmpty()
        assertThat(gameOver.first().winner).isEqualTo(WinnerSide.VILLAGER)
    }

    // ── Case 8b: last wolf self-destruct settles rewards and records diedDay ────

    @Test
    fun `last wolf self-destruct settles the game and stamps diedDay on the wolf`() {
        val ctx = context(
            game = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name),
            players = listOf(wolfPlayer(), villagePlayer()),
        )
        // game.dayNumber defaults to 1.

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(WinnerSide.VILLAGER)

        selfDestructService.selfDestruct(req(), ctx)

        // Settlement fires on the self-destruct game-over path.
        verify(rewardSettlementService).settle(gameId, WinnerSide.VILLAGER)
        // The self-destructing wolf is stamped with the day it died (drives reward scaling).
        val captor = argumentCaptor<GamePlayer>()
        verify(gamePlayerRepository).save(captor.capture())
        assertThat(captor.firstValue.diedDay).isEqualTo(1)
    }

    // ── Case 10: records the self-destructed player on the game (for the banner) ─

    @Test
    fun `self-destruct records the self-destructed userId on the game`() {
        val ctx = context(
            game = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name),
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any(), any())).thenReturn(null)

        selfDestructService.selfDestruct(req(), ctx)

        assertThat(ctx.game.selfDestructUserId).isEqualTo(wolfId)
    }
}
