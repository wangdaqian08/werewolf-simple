package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionDispatcher
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.GamePhasePipeline
import com.werewolf.game.role.RoleHandler
import com.werewolf.game.voting.VotingPipeline
import com.werewolf.model.*
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class GameActionDispatcherTest {

    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var votingPipeline: VotingPipeline
    @Mock lateinit var gamePhasePipeline: GamePhasePipeline
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var stompPublisher: StompPublisher

    private val gameId = 1
    private val hostId = "host:001"
    private val wolfId = "wolf:001"
    private val wolf2Id = "wolf:002"
    private val deadWolfId = "wolf:003"
    private val seerActorId = "seer:001"

    private fun game(phase: GamePhase = GamePhase.NIGHT, subPhase: String = NightSubPhase.WEREWOLF_PICK.name) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = phase
            it.subPhase = subPhase
        }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun req(actorId: String, actionType: ActionType, target: String? = null) =
        GameActionRequest(gameId = gameId, actorUserId = actorId, actionType = actionType, targetUserId = target)

    /** Build a stub RoleHandler that returns the given result for any handle() call. */
    private fun stubHandler(role: PlayerRole, result: GameActionResult): RoleHandler = object : RoleHandler {
        override val role = role
        override fun acceptedActions(phase: GamePhase, subPhase: String?) = emptySet<ActionType>()
        override fun nightSubPhases() = emptyList<NightSubPhase>()
        override fun handle(action: GameActionRequest, context: GameContext) = result
    }

    private fun makeDispatcher(handlers: List<RoleHandler>) = GameActionDispatcher(
        handlers = handlers,
        nightOrchestrator = nightOrchestrator,
        votingPipeline = votingPipeline,
        gamePhasePipeline = gamePhasePipeline,
        contextLoader = contextLoader,
        stompPublisher = stompPublisher,
    )

    // ── WOLF_KILL routing ─────────────────────────────────────────────────────

    @Nested
    inner class WolfKillRoutingTests {

        @Test
        fun `WOLF_KILL success - advances WEREWOLF_PICK sub-phase`() {
            val wolfHandler = stubHandler(PlayerRole.WEREWOLF, GameActionResult.Success())
            val ctx = GameContext(game(), room(), emptyList())
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(wolfHandler)).dispatch(req(wolfId, ActionType.WOLF_KILL, "u2"))

            verify(nightOrchestrator).submitAction(gameId)
        }

        @Test
        fun `WOLF_KILL rejected - does NOT advance sub-phase`() {
            val wolfHandler = stubHandler(PlayerRole.WEREWOLF, GameActionResult.Rejected("actor is dead"))
            val ctx = GameContext(game(), room(), emptyList())
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(wolfHandler)).dispatch(req(wolfId, ActionType.WOLF_KILL, "u2"))

            verify(nightOrchestrator, never()).submitAction(any())
        }
    }

    // ── WOLF_SELECT routing ───────────────────────────────────────────────────

    @Nested
    inner class WolfSelectRoutingTests {

        private val selectionEvent = DomainEvent.WolfSelectionChanged(gameId, "u2")

        @Test
        fun `WOLF_SELECT success - sends WolfSelectionChanged to all alive wolves`() {
            val wolf1 = player(wolfId, 1, PlayerRole.WEREWOLF)
            val wolf2 = player(wolf2Id, 2, PlayerRole.WEREWOLF)
            val villager = player("v1", 3, PlayerRole.VILLAGER)
            val wolfHandler = stubHandler(PlayerRole.WEREWOLF, GameActionResult.Success(events = listOf(selectionEvent)))
            val ctx = GameContext(game(), room(), listOf(wolf1, wolf2, villager))
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(wolfHandler)).dispatch(req(wolfId, ActionType.WOLF_SELECT, "u2"))

            // Only the 2 alive wolves receive the private broadcast
            verify(stompPublisher).sendPrivate(wolfId, selectionEvent)
            verify(stompPublisher).sendPrivate(wolf2Id, selectionEvent)
            verify(stompPublisher, never()).sendPrivate(eq("v1"), any())
        }

        @Test
        fun `WOLF_SELECT success - dead wolves are excluded from broadcast`() {
            val aliveWolf = player(wolfId, 1, PlayerRole.WEREWOLF, alive = true)
            val deadWolf  = player(deadWolfId, 3, PlayerRole.WEREWOLF, alive = false)
            val wolfHandler = stubHandler(PlayerRole.WEREWOLF, GameActionResult.Success(events = listOf(selectionEvent)))
            val ctx = GameContext(game(), room(), listOf(aliveWolf, deadWolf))
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(wolfHandler)).dispatch(req(wolfId, ActionType.WOLF_SELECT, "u2"))

            verify(stompPublisher).sendPrivate(wolfId, selectionEvent)
            verify(stompPublisher, never()).sendPrivate(eq(deadWolfId), any())
        }

        @Test
        fun `WOLF_SELECT success - does NOT advance sub-phase`() {
            val wolf = player(wolfId, 1, PlayerRole.WEREWOLF)
            val wolfHandler = stubHandler(PlayerRole.WEREWOLF, GameActionResult.Success(events = listOf(selectionEvent)))
            val ctx = GameContext(game(), room(), listOf(wolf))
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(wolfHandler)).dispatch(req(wolfId, ActionType.WOLF_SELECT, "u2"))

            verify(nightOrchestrator, never()).submitAction(any())
        }

        @Test
        fun `WOLF_SELECT rejected - does NOT call sendPrivate`() {
            val wolfHandler = stubHandler(PlayerRole.WEREWOLF, GameActionResult.Rejected("actor is dead"))
            val ctx = GameContext(game(), room(), emptyList())
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(wolfHandler)).dispatch(req(wolfId, ActionType.WOLF_SELECT, "u2"))

            verify(stompPublisher, never()).sendPrivate(any(), any())
        }

        @Test
        fun `WOLF_SELECT with single alive wolf - only that wolf gets the broadcast`() {
            val aliveWolf = player(wolfId, 1, PlayerRole.WEREWOLF, alive = true)
            val wolfHandler = stubHandler(PlayerRole.WEREWOLF, GameActionResult.Success(events = listOf(selectionEvent)))
            val ctx = GameContext(game(), room(), listOf(aliveWolf))
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(wolfHandler)).dispatch(req(wolfId, ActionType.WOLF_SELECT, "u2"))

            verify(stompPublisher, times(1)).sendPrivate(any(), any())
            verify(stompPublisher).sendPrivate(wolfId, selectionEvent)
        }
    }

    // ── SEER_CHECK routing ────────────────────────────────────────────────────

    @Nested
    inner class SeerCheckRoutingTests {

        private val seerResultEvent = DomainEvent.SeerResult(gameId, "u2", isWerewolf = false)

        @Test
        fun `SEER_CHECK success - sends result privately to seer and advances SEER_PICK`() {
            val seerHandler = stubHandler(PlayerRole.SEER, GameActionResult.Success(events = listOf(seerResultEvent)))
            val ctx = GameContext(game(), room(), emptyList())
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(seerHandler)).dispatch(req(seerActorId, ActionType.SEER_CHECK, "u2"))

            verify(stompPublisher).sendPrivate(seerActorId, seerResultEvent)
            verify(nightOrchestrator).submitAction(gameId)
        }

        @Test
        fun `SEER_CHECK rejected - does NOT advance sub-phase`() {
            val seerHandler = stubHandler(PlayerRole.SEER, GameActionResult.Rejected("not the seer"))
            val ctx = GameContext(game(), room(), emptyList())
            whenever(contextLoader.load(gameId)).thenReturn(ctx)

            makeDispatcher(listOf(seerHandler)).dispatch(req(seerActorId, ActionType.SEER_CHECK, "u2"))

            verify(nightOrchestrator, never()).submitAction(any())
            verify(stompPublisher, never()).sendPrivate(any(), any())
        }
    }

    // ── SEER_CONFIRM routing ──────────────────────────────────────────────────

    @Test
    fun `SEER_CONFIRM success - advances SEER_RESULT sub-phase`() {
        val seerHandler = stubHandler(PlayerRole.SEER, GameActionResult.Success())
        val ctx = GameContext(game(subPhase = NightSubPhase.SEER_RESULT.name), room(), emptyList())
        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(seerHandler)).dispatch(req(seerActorId, ActionType.SEER_CONFIRM))

        verify(nightOrchestrator).submitAction(gameId)
    }

    // ── WITCH_ACT routing ─────────────────────────────────────────────────────

    @Test
    fun `WITCH_ACT rejected - does NOT advance sub-phase`() {
        val witchHandler = stubHandler(PlayerRole.WITCH, GameActionResult.Rejected("antidote already used"))
        val ctx = GameContext(game(subPhase = NightSubPhase.WITCH_ACT.name), room(), emptyList())
        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(witchHandler)).dispatch(req("witch:001", ActionType.WITCH_ACT))

        verify(nightOrchestrator, never()).submitAction(any())
    }

    // ── GUARD routing ─────────────────────────────────────────────────────────

    @Test
    fun `GUARD_PROTECT success - advances GUARD_PICK sub-phase`() {
        val guardHandler = stubHandler(PlayerRole.GUARD, GameActionResult.Success())
        val ctx = GameContext(game(subPhase = NightSubPhase.GUARD_PICK.name), room(), emptyList())
        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(guardHandler)).dispatch(req("guard:001", ActionType.GUARD_PROTECT, "u2"))

        verify(nightOrchestrator).submitAction(gameId)
    }

    @Test
    fun `GUARD_SKIP success - advances GUARD_PICK sub-phase`() {
        val guardHandler = stubHandler(PlayerRole.GUARD, GameActionResult.Success())
        val ctx = GameContext(game(subPhase = NightSubPhase.GUARD_PICK.name), room(), emptyList())
        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(guardHandler)).dispatch(req("guard:001", ActionType.GUARD_SKIP))

        verify(nightOrchestrator).submitAction(gameId)
    }

    @Test
    fun `GUARD_PROTECT rejected - does NOT advance sub-phase`() {
        val guardHandler = stubHandler(PlayerRole.GUARD, GameActionResult.Rejected("same player two nights"))
        val ctx = GameContext(game(subPhase = NightSubPhase.GUARD_PICK.name), room(), emptyList())
        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(guardHandler)).dispatch(req("guard:001", ActionType.GUARD_PROTECT, "u2"))

        verify(nightOrchestrator, never()).submitAction(any())
    }

    // ── WITCH_ACT auto-advance logic ─────────────────────────────────────────

    @Test
    fun `WITCH_ACT - both potions used in current night, advances to next phase`() {
        // 女巫在当前夜晚使用了两种药水，自动推进
        val witchHandler = stubHandler(PlayerRole.WITCH, GameActionResult.Success())
        val witch = player("witch:001", 1, PlayerRole.WITCH)
        val target = player("target:001", 2)

        // 当前夜晚阶段（第一夜）
        val currentNightPhase = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WITCH_ACT
            it.witchAntidoteUsed = true  // 女巫使用了解药
            it.witchPoisonTargetUserId = "target:001"  // 女巫使用了毒药
        }

        val ctx = GameContext(
            game(subPhase = NightSubPhase.WITCH_ACT.name),
            Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasWitch = true),
            listOf(witch, target),
            nightPhase = currentNightPhase,
            allNightPhases = emptyList()
        )

        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(witchHandler)).dispatch(req("witch:001", ActionType.WITCH_ACT))

        // 应该自动推进
        verify(nightOrchestrator).submitAction(gameId)
    }

    @Test
    fun `WITCH_ACT - only antidote used in current night, advances to next phase`() {
        // 女巫在当前夜晚只使用了解药，应该推进
        val witchHandler = stubHandler(PlayerRole.WITCH, GameActionResult.Success())
        val witch = player("witch:001", 1, PlayerRole.WITCH)

        // 当前夜晚阶段（第一夜）
        val currentNightPhase = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WITCH_ACT
            it.witchAntidoteUsed = true  // 女巫使用了解药
            it.witchPoisonTargetUserId = null  // 没有使用毒药
        }

        val ctx = GameContext(
            game(subPhase = NightSubPhase.WITCH_ACT.name),
            Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasWitch = true),
            listOf(witch),
            nightPhase = currentNightPhase,
            allNightPhases = emptyList()
        )

        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(witchHandler)).dispatch(req("witch:001", ActionType.WITCH_ACT))

        // 应该推进
        verify(nightOrchestrator).submitAction(gameId)
    }

    @Test
    fun `WITCH_ACT - only poison used in current night, advances to next phase`() {
        // 女巫在当前夜晚只使用了毒药，应该推进
        val witchHandler = stubHandler(PlayerRole.WITCH, GameActionResult.Success())
        val witch = player("witch:001", 1, PlayerRole.WITCH)
        val target = player("target:001", 2)

        // 当前夜晚阶段（第一夜）
        val currentNightPhase = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WITCH_ACT
            it.witchAntidoteUsed = false  // 没有使用解药
            it.witchPoisonTargetUserId = "target:001"  // 女巫使用了毒药
        }

        val ctx = GameContext(
            game(subPhase = NightSubPhase.WITCH_ACT.name),
            Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasWitch = true),
            listOf(witch, target),
            nightPhase = currentNightPhase,
            allNightPhases = emptyList()
        )

        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(witchHandler)).dispatch(req("witch:001", ActionType.WITCH_ACT))

        // 应该推进
        verify(nightOrchestrator).submitAction(gameId)
    }

    @Test
    fun `WITCH_ACT - both potions available but not used, advances to next phase`() {
        // 女巫有两种药水但都没有使用（放弃），应该推进
        val witchHandler = stubHandler(PlayerRole.WITCH, GameActionResult.Success())
        val witch = player("witch:001", 1, PlayerRole.WITCH)

        // 当前夜晚阶段（第一夜）
        val currentNightPhase = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WITCH_ACT
            it.witchAntidoteUsed = false  // 没有使用解药
            it.witchPoisonTargetUserId = null  // 没有使用毒药
        }

        val ctx = GameContext(
            game(subPhase = NightSubPhase.WITCH_ACT.name),
            Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasWitch = true),
            listOf(witch),
            nightPhase = currentNightPhase,
            allNightPhases = emptyList()
        )

        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        makeDispatcher(listOf(witchHandler)).dispatch(req("witch:001", ActionType.WITCH_ACT))

        // 应该推进
        verify(nightOrchestrator).submitAction(gameId)
    }
}
