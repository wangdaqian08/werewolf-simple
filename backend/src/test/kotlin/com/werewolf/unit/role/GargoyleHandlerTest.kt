package com.werewolf.unit.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.GargoyleHandler
import com.werewolf.model.*
import com.werewolf.repository.NightPhaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.core.annotation.Order

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GargoyleHandlerTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private lateinit var handler: GargoyleHandler

    private val gameId = 1
    private val gargoyleId = "gargoyle:001"
    private val targetId = "target:001"

    @BeforeEach
    fun setUp() {
        handler = GargoyleHandler(nightPhaseRepository)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
    }

    private fun game() = Game(roomId = 1, hostUserId = "host:001").also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = "host:001", totalPlayers = 12, hasGargoyle = true)

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun nightPhase(sub: NightSubPhase = NightSubPhase.GARGOYLE_PICK, day: Int = 1) =
        NightPhase(gameId = gameId, dayNumber = day).also { it.subPhase = sub }

    private fun ctx(np: NightPhase, vararg players: GamePlayer, earlier: List<NightPhase> = emptyList()) =
        GameContext(game(), room(), players.toList(), nightPhase = np, allNightPhases = earlier + np)

    private fun req(type: ActionType, target: String? = targetId, actor: String = gargoyleId) =
        GameActionRequest(gameId = gameId, actorUserId = actor, actionType = type, targetUserId = target)

    private val gargoyle get() = player(gargoyleId, 1, PlayerRole.GARGOYLE)

    @Test
    fun `G9 - the gargoyle's handler is ordered before the werewolf handler`() {
        assertThat(GargoyleHandler::class.java.getAnnotation(Order::class.java).value).isEqualTo(0)
        assertThat(handler.nightSubPhases()).containsExactly(NightSubPhase.GARGOYLE_PICK, NightSubPhase.GARGOYLE_RESULT)
    }

    @Test
    fun `G3 - GARGOYLE_CHECK stores and returns the exact role`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.GARGOYLE_CHECK), ctx(np, gargoyle, player(targetId, 2, PlayerRole.WITCH)))

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(np.gargoyleCheckedUserId).isEqualTo(targetId)
        assertThat(np.gargoyleResultRole).isEqualTo(PlayerRole.WITCH)
        assertThat((result as GameActionResult.Success).events)
            .containsExactly(DomainEvent.GargoyleResult(gameId, targetId, PlayerRole.WITCH))
    }

    @Test
    fun `G13 - checking a pack wolf shows its exact role`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.GARGOYLE_CHECK), ctx(np, gargoyle, player(targetId, 2, PlayerRole.WHITE_WOLF_KING)))

        assertThat(np.gargoyleResultRole).isEqualTo(PlayerRole.WHITE_WOLF_KING)
        assertThat((result as GameActionResult.Success).events)
            .containsExactly(DomainEvent.GargoyleResult(gameId, targetId, PlayerRole.WHITE_WOLF_KING))
    }

    @Test
    fun `G3 - cannot check itself`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.GARGOYLE_CHECK, target = gargoyleId), ctx(np, gargoyle))
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(np.gargoyleCheckedUserId).isNull()
    }

    @Test
    fun `G3 - cannot check a dead player`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.GARGOYLE_CHECK), ctx(np, gargoyle, player(targetId, 2, PlayerRole.SEER, alive = false)))
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(np.gargoyleCheckedUserId).isNull()
    }

    @Test
    fun `G3 - cannot check an unknown player`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.GARGOYLE_CHECK, target = "nobody"), ctx(np, gargoyle))
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `GARGOYLE_CHECK requires a target`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.GARGOYLE_CHECK, target = null), ctx(np, gargoyle))
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `G4 - a player checked on an earlier night cannot be checked again`() {
        // Night 3 vs night 1: not just the previous night, the whole game.
        val night1 = nightPhase(NightSubPhase.COMPLETE, day = 1).also {
            it.gargoyleCheckedUserId = targetId; it.gargoyleResultRole = PlayerRole.SEER
        }
        val night2 = nightPhase(NightSubPhase.COMPLETE, day = 2)
        val np = nightPhase(day = 3)
        val result = handler.handle(
            req(ActionType.GARGOYLE_CHECK),
            ctx(np, gargoyle, player(targetId, 2, PlayerRole.SEER), earlier = listOf(night1, night2)),
        )

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(np.gargoyleCheckedUserId).isNull()
    }

    @Test
    fun `G4 - a player not checked before can be checked on a later night`() {
        val night1 = nightPhase(NightSubPhase.COMPLETE, day = 1).also { it.gargoyleCheckedUserId = "other" }
        val np = nightPhase(day = 2)
        val result = handler.handle(
            req(ActionType.GARGOYLE_CHECK),
            ctx(np, gargoyle, player(targetId, 2, PlayerRole.SEER), player("other", 3, PlayerRole.VILLAGER), earlier = listOf(night1)),
        )
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
    }

    @Test
    fun `G5 - GARGOYLE_SKIP succeeds and stores nothing`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.GARGOYLE_SKIP, target = null), ctx(np, gargoyle))
        assertThat(result).isEqualTo(GameActionResult.Success())
        assertThat(np.gargoyleCheckedUserId).isNull()
        assertThat(np.gargoyleResultRole).isNull()
    }

    @Test
    fun `GARGOYLE_CONFIRM succeeds only in GARGOYLE_RESULT`() {
        assertThat(handler.handle(req(ActionType.GARGOYLE_CONFIRM, null), ctx(nightPhase(NightSubPhase.GARGOYLE_RESULT), gargoyle)))
            .isInstanceOf(GameActionResult.Success::class.java)
        assertThat(handler.handle(req(ActionType.GARGOYLE_CONFIRM, null), ctx(nightPhase(NightSubPhase.GARGOYLE_PICK), gargoyle)))
            .isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `check and skip are rejected outside GARGOYLE_PICK`() {
        val np = nightPhase(NightSubPhase.GARGOYLE_RESULT)
        val c = ctx(np, gargoyle, player(targetId, 2, PlayerRole.SEER))
        assertThat(handler.handle(req(ActionType.GARGOYLE_CHECK), c)).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(handler.handle(req(ActionType.GARGOYLE_SKIP, null), c)).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `a dead gargoyle is rejected`() {
        val np = nightPhase()
        val result = handler.handle(
            req(ActionType.GARGOYLE_CHECK),
            ctx(np, player(gargoyleId, 1, PlayerRole.GARGOYLE, alive = false), player(targetId, 2, PlayerRole.SEER)),
        )
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(np.gargoyleCheckedUserId).isNull()
    }

    @Test
    fun `a non-gargoyle is rejected`() {
        val np = nightPhase()
        val result = handler.handle(
            req(ActionType.GARGOYLE_CHECK, actor = "w"),
            ctx(np, gargoyle, player("w", 3, PlayerRole.WEREWOLF), player(targetId, 2, PlayerRole.SEER)),
        )
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `acceptedActions per sub-phase`() {
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.GARGOYLE_PICK.name))
            .containsExactlyInAnyOrder(ActionType.GARGOYLE_CHECK, ActionType.GARGOYLE_SKIP)
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.GARGOYLE_RESULT.name))
            .containsExactly(ActionType.GARGOYLE_CONFIRM)
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.WEREWOLF_PICK.name)).isEmpty()
    }
}
