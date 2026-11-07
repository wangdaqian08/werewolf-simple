package com.werewolf.unit.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.MediumHandler
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

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediumHandlerTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private lateinit var handler: MediumHandler

    private val gameId = 1
    private val mediumId = "medium:001"
    private val targetId = "target:001"

    @BeforeEach
    fun setUp() {
        handler = MediumHandler(nightPhaseRepository)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
    }

    private fun game() = Game(roomId = 1, hostUserId = "host:001").also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = "host:001", totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun nightPhase(sub: NightSubPhase = NightSubPhase.MEDIUM_PICK) =
        NightPhase(gameId = gameId, dayNumber = 1).also { it.subPhase = sub }

    private fun ctx(np: NightPhase, vararg players: GamePlayer) =
        GameContext(game(), room(), players.toList(), nightPhase = np)

    private fun req(type: ActionType, target: String? = targetId, actor: String = mediumId) =
        GameActionRequest(gameId = gameId, actorUserId = actor, actionType = type, targetUserId = target)

    @Test
    fun `MEDIUM_CHECK stores the exact role`() {
        val np = nightPhase()
        val result = handler.handle(
            req(ActionType.MEDIUM_CHECK),
            ctx(np, player(mediumId, 1, PlayerRole.MEDIUM), player(targetId, 2, PlayerRole.WITCH)),
        )

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(np.mediumCheckedUserId).isEqualTo(targetId)
        assertThat(np.mediumResultRole).isEqualTo(PlayerRole.WITCH)
        assertThat((result as GameActionResult.Success).events)
            .containsExactly(DomainEvent.MediumResult(gameId, targetId, PlayerRole.WITCH))
    }

    @Test
    fun `medium sees KNIGHT as KNIGHT`() {
        val np = nightPhase()
        val result = handler.handle(
            req(ActionType.MEDIUM_CHECK),
            ctx(np, player(mediumId, 1, PlayerRole.MEDIUM), player(targetId, 2, PlayerRole.KNIGHT)),
        )

        assertThat(np.mediumResultRole).isEqualTo(PlayerRole.KNIGHT)
        assertThat((result as GameActionResult.Success).events)
            .containsExactly(DomainEvent.MediumResult(gameId, targetId, PlayerRole.KNIGHT))
    }

    @Test
    fun `cannot check self`() {
        val np = nightPhase()
        val result = handler.handle(
            req(ActionType.MEDIUM_CHECK, target = mediumId),
            ctx(np, player(mediumId, 1, PlayerRole.MEDIUM)),
        )
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(np.mediumCheckedUserId).isNull()
    }

    @Test
    fun `cannot check a dead player`() {
        val np = nightPhase()
        val result = handler.handle(
            req(ActionType.MEDIUM_CHECK),
            ctx(np, player(mediumId, 1, PlayerRole.MEDIUM), player(targetId, 2, PlayerRole.SEER, alive = false)),
        )
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(np.mediumCheckedUserId).isNull()
    }

    @Test
    fun `rejected outside MEDIUM_PICK`() {
        val np = nightPhase(NightSubPhase.MEDIUM_RESULT)
        val result = handler.handle(
            req(ActionType.MEDIUM_CHECK),
            ctx(np, player(mediumId, 1, PlayerRole.MEDIUM), player(targetId, 2, PlayerRole.SEER)),
        )
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(np.mediumCheckedUserId).isNull()
    }

    @Test
    fun `non-medium rejected`() {
        val np = nightPhase()
        val result = handler.handle(
            req(ActionType.MEDIUM_CHECK, actor = "seer:001"),
            ctx(np, player("seer:001", 1, PlayerRole.SEER), player(targetId, 2, PlayerRole.WITCH)),
        )
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(np.mediumCheckedUserId).isNull()
    }

    @Test
    fun `unlearned mechanical wolf shows MECHANICAL_WOLF`() {
        val np = nightPhase()
        handler.handle(
            req(ActionType.MEDIUM_CHECK),
            ctx(np, player(mediumId, 1, PlayerRole.MEDIUM), player(targetId, 2, PlayerRole.MECHANICAL_WOLF)),
        )
        assertThat(np.mediumResultRole).isEqualTo(PlayerRole.MECHANICAL_WOLF)
    }

    @Test
    fun `medium sees the role learned earlier the same night`() {
        val np = nightPhase()
        val mech = player(targetId, 2, PlayerRole.MECHANICAL_WOLF).also { it.learnedRole = PlayerRole.GUARD }
        handler.handle(req(ActionType.MEDIUM_CHECK), ctx(np, player(mediumId, 1, PlayerRole.MEDIUM), mech))
        assertThat(np.mediumResultRole).isEqualTo(PlayerRole.GUARD)
    }

    @Test
    fun `MEDIUM_CONFIRM only in MEDIUM_RESULT`() {
        val medium = player(mediumId, 1, PlayerRole.MEDIUM)
        val inPick = handler.handle(req(ActionType.MEDIUM_CONFIRM, target = null), ctx(nightPhase(), medium))
        val inResult = handler.handle(
            req(ActionType.MEDIUM_CONFIRM, target = null),
            ctx(nightPhase(NightSubPhase.MEDIUM_RESULT), medium),
        )
        assertThat(inPick).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(inResult).isInstanceOf(GameActionResult.Success::class.java)
    }
}
