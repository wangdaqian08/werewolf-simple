package com.werewolf.unit.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.MechanicalWolfHandler
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.NightPhaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MechanicalWolfHandlerTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository

    private lateinit var handler: MechanicalWolfHandler

    private val gameId = 1
    private val mechId = "mech:001"
    private val targetId = "target:001"

    @BeforeEach
    fun setUp() {
        handler = MechanicalWolfHandler(nightPhaseRepository, gamePlayerRepository)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
    }

    private fun game() = Game(roomId = 1, hostUserId = "host:001").also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = "host:001", totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true, learned: PlayerRole? = null) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also {
            it.alive = alive
            it.learnedRole = learned
        }

    private fun night(day: Int = 1, sub: NightSubPhase = NightSubPhase.MECH_WOLF_PICK) =
        NightPhase(gameId = gameId, dayNumber = day).also { it.subPhase = sub }

    /** A learned mech (learned [role] on night 1) at [tonight]; returns the tonight night phase. */
    private fun ctx(np: NightPhase, mech: GamePlayer, vararg others: GamePlayer, earlier: List<NightPhase> = emptyList()) =
        GameContext(game(), room(), listOf(mech) + others, nightPhase = np, allNightPhases = earlier + np)

    private fun learnNight() = night(1, NightSubPhase.COMPLETE).also { it.mechLearnTargetUserId = "someone" }

    private fun req(type: ActionType, target: String? = targetId, actor: String = mechId) =
        GameActionRequest(gameId = gameId, actorUserId = actor, actionType = type, targetUserId = target)

    private fun rejected(r: GameActionResult) = assertThat(r).isInstanceOf(GameActionResult.Rejected::class.java)
    private fun success(r: GameActionResult) = assertThat(r).isInstanceOf(GameActionResult.Success::class.java)

    // ── Learning ──────────────────────────────────────────────────────────────

    @Test
    fun `MECH_LEARN sets learnedRole to the target's role and mechLearnTargetUserId`() {
        val np = night()
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF)
        val result = handler.handle(req(ActionType.MECH_LEARN), ctx(np, mech, player(targetId, 2, PlayerRole.WITCH)))

        success(result)
        assertThat(mech.learnedRole).isEqualTo(PlayerRole.WITCH)
        assertThat(np.mechLearnTargetUserId).isEqualTo(targetId)
        assertThat((result as GameActionResult.Success).events).containsExactly(DomainEvent.MechWolfResult(gameId))
        verify(gamePlayerRepository).save(mech)
        verify(nightPhaseRepository).save(np)
    }

    @Test
    fun `learn keeps the role after the target dies`() {
        val np = night()
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF)
        val target = player(targetId, 2, PlayerRole.WITCH)
        handler.handle(req(ActionType.MECH_LEARN), ctx(np, mech, target))

        target.alive = false

        assertThat(mech.learnedRole).isEqualTo(PlayerRole.WITCH)
    }

    @Test
    fun `cannot learn twice in a game`() {
        val np = night(2)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH)
        val result = handler.handle(
            req(ActionType.MECH_LEARN),
            ctx(np, mech, player(targetId, 2, PlayerRole.SEER), earlier = listOf(learnNight())),
        )

        rejected(result)
        assertThat(mech.learnedRole).isEqualTo(PlayerRole.WITCH)
    }

    @Test
    fun `cannot learn self or a dead player`() {
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF)
        rejected(handler.handle(req(ActionType.MECH_LEARN, target = mechId), ctx(night(), mech)))
        rejected(handler.handle(req(ActionType.MECH_LEARN), ctx(night(), mech, player(targetId, 2, PlayerRole.WITCH, alive = false))))
        rejected(handler.handle(req(ActionType.MECH_LEARN, target = null), ctx(night(), mech)))
        assertThat(mech.learnedRole).isNull()
        verify(gamePlayerRepository, never()).save(any<GamePlayer>())
    }

    // ── Learned skills ────────────────────────────────────────────────────────

    @Test
    fun `MECH_CHECK with learned MEDIUM stores mediumSees(target)`() {
        val np = night(2)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.MEDIUM)
        // The other mech-less target is a plain role; a target that itself learned shows the learned role.
        val target = player(targetId, 2, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.GUARD)
        val result = handler.handle(req(ActionType.MECH_CHECK), ctx(np, mech, target, earlier = listOf(learnNight())))

        success(result)
        assertThat(np.mechCheckTargetUserId).isEqualTo(targetId)
        assertThat(np.mechCheckResultRole).isEqualTo(PlayerRole.GUARD)
        assertThat((result as GameActionResult.Success).events).containsExactly(DomainEvent.MechWolfResult(gameId))
        verify(gamePlayerRepository, never()).save(any<GamePlayer>())
    }

    @Test
    fun `MECH_CHECK with learned SEER stores the target role`() {
        val np = night(2)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.SEER)
        val result = handler.handle(
            req(ActionType.MECH_CHECK),
            ctx(np, mech, player(targetId, 2, PlayerRole.WEREWOLF), earlier = listOf(learnNight())),
        )

        success(result)
        assertThat(np.mechCheckResultRole).isEqualTo(PlayerRole.WEREWOLF)
    }

    @Test
    fun `MECH_CHECK rejected on the learning night`() {
        val np = night(1)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.SEER)
        np.mechLearnTargetUserId = "someone"
        rejected(handler.handle(req(ActionType.MECH_CHECK), ctx(np, mech, player(targetId, 2, PlayerRole.WEREWOLF))))
    }

    @Test
    fun `MECH_CHECK rejects self, dead and missing targets`() {
        val np = night(2)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.SEER)
        val earlier = listOf(learnNight())
        rejected(handler.handle(req(ActionType.MECH_CHECK, target = mechId), ctx(np, mech, earlier = earlier)))
        rejected(handler.handle(req(ActionType.MECH_CHECK), ctx(np, mech, player(targetId, 2, PlayerRole.SEER, alive = false), earlier = earlier)))
        rejected(handler.handle(req(ActionType.MECH_CHECK, target = null), ctx(np, mech, earlier = earlier)))
        assertThat(np.mechCheckTargetUserId).isNull()
    }

    @Test
    fun `MECH_POISON allowed the night after learning WITCH, then never again`() {
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH)
        val target = player(targetId, 2, PlayerRole.VILLAGER)
        val n1 = learnNight()
        val n2 = night(2)

        success(handler.handle(req(ActionType.MECH_POISON), ctx(n2, mech, target, earlier = listOf(n1))))
        assertThat(n2.mechPoisonTargetUserId).isEqualTo(targetId)

        val n3 = night(3)
        rejected(handler.handle(req(ActionType.MECH_POISON), ctx(n3, mech, target, earlier = listOf(n1, n2))))
        assertThat(n3.mechPoisonTargetUserId).isNull()
    }

    @Test
    fun `MECH_POISON rejected on the learning night`() {
        val np = night(1)
        np.mechLearnTargetUserId = "someone"
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH)
        rejected(handler.handle(req(ActionType.MECH_POISON), ctx(np, mech, player(targetId, 2, PlayerRole.VILLAGER))))
    }

    @Test
    fun `MECH_POISON on self rejected`() {
        val np = night(2)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH)
        rejected(handler.handle(req(ActionType.MECH_POISON, target = mechId), ctx(np, mech, earlier = listOf(learnNight()))))
        assertThat(np.mechPoisonTargetUserId).isNull()
    }

    @Test
    fun `MECH_PROTECT allowed once, self allowed`() {
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.GUARD)
        val target = player(targetId, 2, PlayerRole.VILLAGER)
        val n1 = learnNight()
        val n2 = night(2)

        success(handler.handle(req(ActionType.MECH_PROTECT, target = mechId), ctx(n2, mech, target, earlier = listOf(n1))))
        assertThat(n2.mechGuardTargetUserId).isEqualTo(mechId)

        rejected(handler.handle(req(ActionType.MECH_PROTECT), ctx(night(3), mech, target, earlier = listOf(n1, n2))))
    }

    @Test
    fun `MECH_PROTECT rejects a dead target`() {
        val np = night(2)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.GUARD)
        rejected(handler.handle(
            req(ActionType.MECH_PROTECT),
            ctx(np, mech, player(targetId, 2, PlayerRole.VILLAGER, alive = false), earlier = listOf(learnNight())),
        ))
    }

    @Test
    fun `MECH_PROTECT rejected when learned WITCH`() {
        val np = night(2)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH)
        rejected(handler.handle(
            req(ActionType.MECH_PROTECT),
            ctx(np, mech, player(targetId, 2, PlayerRole.VILLAGER), earlier = listOf(learnNight())),
        ))
        rejected(handler.handle(
            req(ActionType.MECH_CHECK),
            ctx(np, mech, player(targetId, 2, PlayerRole.VILLAGER), earlier = listOf(learnNight())),
        ))
    }

    @Test
    fun `MECH_LEARN rejected after learning even on a later night`() {
        val np = night(2)
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.HUNTER)
        rejected(handler.handle(
            req(ActionType.MECH_LEARN),
            ctx(np, mech, player(targetId, 2, PlayerRole.VILLAGER), earlier = listOf(learnNight())),
        ))
    }

    // ── Skip, confirm and sub-phase rules ─────────────────────────────────────

    @Test
    fun `MECH_SKIP always allowed in MECH_WOLF_PICK`() {
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF)
        success(handler.handle(req(ActionType.MECH_SKIP, target = null), ctx(night(), mech)))
        val learned = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.HUNTER)
        success(handler.handle(req(ActionType.MECH_SKIP, target = null), ctx(night(2), learned, earlier = listOf(learnNight()))))
        rejected(handler.handle(req(ActionType.MECH_SKIP, target = null), ctx(night(sub = NightSubPhase.MECH_WOLF_RESULT), mech)))
    }

    @Test
    fun `MECH_CONFIRM only in MECH_WOLF_RESULT`() {
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF)
        success(handler.handle(req(ActionType.MECH_CONFIRM, target = null), ctx(night(sub = NightSubPhase.MECH_WOLF_RESULT), mech)))
        rejected(handler.handle(req(ActionType.MECH_CONFIRM, target = null), ctx(night(), mech)))
    }

    @Test
    fun `pick actions rejected in MECH_WOLF_RESULT`() {
        val mech = player(mechId, 1, PlayerRole.MECHANICAL_WOLF)
        val np = night(sub = NightSubPhase.MECH_WOLF_RESULT)
        rejected(handler.handle(req(ActionType.MECH_LEARN), ctx(np, mech, player(targetId, 2, PlayerRole.WITCH))))
        assertThat(mech.learnedRole).isNull()
    }

    @Test
    fun `every action rejected for a non-mech or a dead mech`() {
        val actions = listOf(
            ActionType.MECH_LEARN, ActionType.MECH_CHECK, ActionType.MECH_POISON,
            ActionType.MECH_PROTECT, ActionType.MECH_SKIP, ActionType.MECH_CONFIRM,
        )
        val target = player(targetId, 9, PlayerRole.WITCH)
        actions.forEach { type ->
            val sub = if (type == ActionType.MECH_CONFIRM) NightSubPhase.MECH_WOLF_RESULT else NightSubPhase.MECH_WOLF_PICK
            val notMech = player(mechId, 1, PlayerRole.WEREWOLF)
            rejected(handler.handle(req(type), ctx(night(sub = sub), notMech, target)))
            val dead = player(mechId, 1, PlayerRole.MECHANICAL_WOLF, alive = false)
            rejected(handler.handle(req(type), ctx(night(sub = sub), dead, target)))
        }
        assertThat(target.learnedRole).isNull()
    }

    @Test
    fun `walks both sub-phases whatever it does`() {
        assertThat(handler.nightSubPhases())
            .containsExactly(NightSubPhase.MECH_WOLF_PICK, NightSubPhase.MECH_WOLF_RESULT)
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.MECH_WOLF_RESULT.name))
            .containsExactly(ActionType.MECH_CONFIRM)
    }
}
