package com.werewolf.unit.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.WolfBeautyHandler
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
class WolfBeautyHandlerTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private lateinit var handler: WolfBeautyHandler

    private val gameId = 1
    private val beautyId = "beauty:001"
    private val targetId = "target:001"

    @BeforeEach
    fun setUp() {
        handler = WolfBeautyHandler(nightPhaseRepository)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
    }

    private fun game() = Game(roomId = 1, hostUserId = "host:001").also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = "host:001", totalPlayers = 12, hasWolfBeauty = true)

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun nightPhase(sub: NightSubPhase = NightSubPhase.WOLF_BEAUTY_PICK, day: Int = 2) =
        NightPhase(gameId = gameId, dayNumber = day).also { it.subPhase = sub }

    private fun ctx(np: NightPhase, vararg players: GamePlayer, earlier: List<NightPhase> = emptyList()) =
        GameContext(game(), room(), players.toList(), nightPhase = np, allNightPhases = earlier + np)

    private fun req(type: ActionType, target: String? = targetId, actor: String = beautyId) =
        GameActionRequest(gameId = gameId, actorUserId = actor, actionType = type, targetUserId = target)

    private val beauty get() = player(beautyId, 1, PlayerRole.WOLF_BEAUTY)

    private fun rejected(result: GameActionResult, message: String) {
        assertThat(result).isEqualTo(GameActionResult.Rejected(message))
    }

    @Test
    fun `the handler is ordered right after the werewolf handler and has one step`() {
        assertThat(WolfBeautyHandler::class.java.getAnnotation(Order::class.java).value).isEqualTo(2)
        assertThat(handler.nightSubPhases()).containsExactly(NightSubPhase.WOLF_BEAUTY_PICK)
    }

    @Test
    fun `acceptedActions only in WOLF_BEAUTY_PICK`() {
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.WOLF_BEAUTY_PICK.name))
            .containsExactlyInAnyOrder(ActionType.WOLF_BEAUTY_CHARM, ActionType.WOLF_BEAUTY_SKIP)
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.WEREWOLF_PICK.name)).isEmpty()
        assertThat(handler.acceptedActions(GamePhase.DAY_DISCUSSION, NightSubPhase.WOLF_BEAUTY_PICK.name)).isEmpty()
    }

    @Test
    fun `W3 - CHARM saves the target`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, beauty, player(targetId, 2, PlayerRole.SEER)))
        assertThat(result).isEqualTo(GameActionResult.Success())
        assertThat(np.wolfBeautyCharmUserId).isEqualTo(targetId)
    }

    @Test
    fun `SKIP saves nothing`() {
        val np = nightPhase()
        val result = handler.handle(req(ActionType.WOLF_BEAUTY_SKIP, target = null), ctx(np, beauty))
        assertThat(result).isEqualTo(GameActionResult.Success())
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `Q3 - SKIP is accepted on night 1`() {
        val np = nightPhase(day = 1)
        assertThat(handler.handle(req(ActionType.WOLF_BEAUTY_SKIP, target = null), ctx(np, beauty)))
            .isEqualTo(GameActionResult.Success())
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `W2 - no charm on night 1`() {
        val np = nightPhase(day = 1)
        rejected(handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, beauty, player(targetId, 2, PlayerRole.SEER))), "No charm on night 1")
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `a non-wolf-beauty is rejected`() {
        val np = nightPhase()
        rejected(
            handler.handle(req(ActionType.WOLF_BEAUTY_CHARM, actor = "w"), ctx(np, beauty, player("w", 3, PlayerRole.WEREWOLF), player(targetId, 2, PlayerRole.SEER))),
            "Not the Wolf Beauty",
        )
    }

    @Test
    fun `a dead wolf beauty is rejected`() {
        val np = nightPhase()
        rejected(
            handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, player(beautyId, 1, PlayerRole.WOLF_BEAUTY, alive = false), player(targetId, 2, PlayerRole.SEER))),
            "Actor is dead",
        )
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `charm and skip are rejected outside WOLF_BEAUTY_PICK`() {
        val np = nightPhase(sub = NightSubPhase.WEREWOLF_PICK)
        val c = ctx(np, beauty, player(targetId, 2, PlayerRole.SEER))
        rejected(handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), c), "Not in WOLF_BEAUTY_PICK sub-phase")
        rejected(handler.handle(req(ActionType.WOLF_BEAUTY_SKIP, target = null), c), "Not in WOLF_BEAUTY_PICK sub-phase")
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `CHARM requires a target`() {
        rejected(handler.handle(req(ActionType.WOLF_BEAUTY_CHARM, target = null), ctx(nightPhase(), beauty)), "Target required")
    }

    @Test
    fun `W3 - cannot charm itself`() {
        val np = nightPhase()
        rejected(handler.handle(req(ActionType.WOLF_BEAUTY_CHARM, target = beautyId), ctx(np, beauty)), "Cannot charm yourself")
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `W3 - cannot charm a dead player`() {
        val np = nightPhase()
        rejected(
            handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, beauty, player(targetId, 2, PlayerRole.SEER, alive = false))),
            "Target not found or dead",
        )
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `W3 - cannot charm a pack wolf`() {
        val np = nightPhase()
        rejected(
            handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, beauty, player(targetId, 2, PlayerRole.WHITE_WOLF_KING))),
            "Cannot charm a wolf teammate",
        )
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `W3 - cannot charm last night's target`() {
        val night2 = nightPhase(NightSubPhase.COMPLETE, day = 2).also { it.wolfBeautyCharmUserId = targetId }
        val np = nightPhase(day = 3)
        rejected(
            handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, beauty, player(targetId, 2, PlayerRole.SEER), earlier = listOf(night2))),
            "Cannot charm the same player two nights in a row",
        )
        assertThat(np.wolfBeautyCharmUserId).isNull()
    }

    @Test
    fun `a target charmed two nights ago can be charmed again`() {
        val night2 = nightPhase(NightSubPhase.COMPLETE, day = 2).also { it.wolfBeautyCharmUserId = targetId }
        val night3 = nightPhase(NightSubPhase.COMPLETE, day = 3)
        val np = nightPhase(day = 4)
        val result = handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, beauty, player(targetId, 2, PlayerRole.SEER), earlier = listOf(night2, night3)))
        assertThat(result).isEqualTo(GameActionResult.Success())
        assertThat(np.wolfBeautyCharmUserId).isEqualTo(targetId)
    }

    @Test
    fun `D - a gargoyle or a mechanical wolf can be charmed`() {
        listOf(PlayerRole.GARGOYLE, PlayerRole.MECHANICAL_WOLF).forEach { role ->
            val np = nightPhase()
            val result = handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, beauty, player(targetId, 2, role)))
            assertThat(result).isEqualTo(GameActionResult.Success())
            assertThat(np.wolfBeautyCharmUserId).isEqualTo(targetId)
        }
    }

    @Test
    fun `Q5 - tonight's knife target can be charmed`() {
        val np = nightPhase().also { it.wolfTargetUserId = targetId }
        val result = handler.handle(req(ActionType.WOLF_BEAUTY_CHARM), ctx(np, beauty, player(targetId, 2, PlayerRole.SEER)))
        assertThat(result).isEqualTo(GameActionResult.Success())
        assertThat(np.wolfBeautyCharmUserId).isEqualTo(targetId)
    }
}
