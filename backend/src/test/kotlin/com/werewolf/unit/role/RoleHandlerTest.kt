package com.werewolf.unit.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.GuardHandler
import com.werewolf.game.role.SeerHandler
import com.werewolf.game.role.WerewolfHandler
import com.werewolf.game.role.WitchHandler
import com.werewolf.model.*
import com.werewolf.repository.NightPhaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class RoleHandlerTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private val gameId = 1
    private val hostId = "host:001"

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun game() = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun nightPhase(
        subPhase: NightSubPhase,
        prevGuardTarget: String? = null,
    ) = NightPhase(gameId = gameId, dayNumber = 1).also {
        it.subPhase = subPhase
        it.prevGuardTargetUserId = prevGuardTarget
    }

    private fun req(actorId: String, actionType: ActionType, target: String? = null, payload: Map<String, Any?> = emptyMap()) =
        GameActionRequest(gameId = gameId, actorUserId = actorId, actionType = actionType, targetUserId = target, payload = payload)

    // ── WerewolfHandler ───────────────────────────────────────────────────────

    @Nested
    inner class WerewolfHandlerTests {

        private lateinit var handler: WerewolfHandler

        @BeforeEach
        fun setUp() {
            handler = WerewolfHandler(nightPhaseRepository)
        }

        private fun wolfCtx(wolfAlive: Boolean = true, targetAlive: Boolean = true): GameContext {
            val np = nightPhase(NightSubPhase.WEREWOLF_PICK)
            val wolf = player("wolf", 1, PlayerRole.WEREWOLF, wolfAlive)
            val victim = player("u2", 2, PlayerRole.VILLAGER, targetAlive)
            return GameContext(game(), room(), listOf(wolf, victim), nightPhase = np)
        }

        @Test
        fun `wolf kill - rejected when actor is not a werewolf`() {
            val np = nightPhase(NightSubPhase.WEREWOLF_PICK)
            val villager = player("v1", 1, PlayerRole.VILLAGER)
            val ctx = GameContext(game(), room(), listOf(villager), nightPhase = np)
            val result = handler.handle(req("v1", ActionType.WOLF_KILL, "u2"), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("werewolf")
        }

        @Test
        fun `wolf kill - rejected when actor is dead`() {
            val np = nightPhase(NightSubPhase.WEREWOLF_PICK)
            val deadWolf = player("wolf", 1, PlayerRole.WEREWOLF, alive = false)
            val ctx = GameContext(game(), room(), listOf(deadWolf), nightPhase = np)
            val result = handler.handle(req("wolf", ActionType.WOLF_KILL, "u2"), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("dead")
        }

        @Test
        fun `wolf kill - rejected when target not found`() {
            val ctx = wolfCtx()
            val result = handler.handle(req("wolf", ActionType.WOLF_KILL, "nonexistent"), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("not found")
        }

        @Test
        fun `wolf kill - sets wolfTargetUserId and saves on valid action`() {
            val ctx = wolfCtx()
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(req("wolf", ActionType.WOLF_KILL, "u2"), ctx)

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val captor = argumentCaptor<NightPhase>()
            verify(nightPhaseRepository).save(captor.capture())
            assertThat(captor.firstValue.wolfTargetUserId).isEqualTo("u2")
        }

        @Test
        fun `wolf select - sets wolfTargetUserId and returns WolfSelectionChanged event`() {
            val ctx = wolfCtx()
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(req("wolf", ActionType.WOLF_SELECT, "u2"), ctx)

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val success = result as GameActionResult.Success
            assertThat(success.events).hasSize(1)
            assertThat(success.events[0]).isInstanceOf(DomainEvent.WolfSelectionChanged::class.java)
            val event = success.events[0] as DomainEvent.WolfSelectionChanged
            assertThat(event.selectedTargetUserId).isEqualTo("u2")

            val captor = argumentCaptor<NightPhase>()
            verify(nightPhaseRepository).save(captor.capture())
            assertThat(captor.firstValue.wolfTargetUserId).isEqualTo("u2")
        }

        @Test
        fun `wolf select - rejected when actor is dead`() {
            val np = nightPhase(NightSubPhase.WEREWOLF_PICK)
            val deadWolf = player("wolf", 1, PlayerRole.WEREWOLF, alive = false)
            val ctx = GameContext(game(), room(), listOf(deadWolf), nightPhase = np)
            val result = handler.handle(req("wolf", ActionType.WOLF_SELECT, "u2"), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("dead")
        }

        @Test
        fun `wolf select - rejected when target is dead`() {
            val np = nightPhase(NightSubPhase.WEREWOLF_PICK)
            val wolf = player("wolf", 1, PlayerRole.WEREWOLF)
            val deadTarget = player("u2", 2, PlayerRole.VILLAGER, alive = false)
            val ctx = GameContext(game(), room(), listOf(wolf, deadTarget), nightPhase = np)
            val result = handler.handle(req("wolf", ActionType.WOLF_SELECT, "u2"), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        }

        @Test
        fun `wolf select - rejected when actor is not werewolf`() {
            val np = nightPhase(NightSubPhase.WEREWOLF_PICK)
            val villager = player("v1", 1, PlayerRole.VILLAGER)
            val ctx = GameContext(game(), room(), listOf(villager), nightPhase = np)
            val result = handler.handle(req("v1", ActionType.WOLF_SELECT, "u2"), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("werewolf")
        }
    }

    // ── SeerHandler ───────────────────────────────────────────────────────────

    @Nested
    inner class SeerHandlerTests {

        private lateinit var handler: SeerHandler

        @BeforeEach
        fun setUp() {
            handler = SeerHandler(nightPhaseRepository)
        }

        private fun seerCtx(
            subPhase: NightSubPhase = NightSubPhase.SEER_PICK,
            targetRole: PlayerRole = PlayerRole.VILLAGER,
        ): GameContext {
            val np = nightPhase(subPhase)
            val seer = player("seer", 1, PlayerRole.SEER)
            val target = player("u2", 2, targetRole)
            return GameContext(game(), room(), listOf(seer, target), nightPhase = np)
        }

        @Test
        fun `seer check - detects werewolf target correctly`() {
            val ctx = seerCtx(targetRole = PlayerRole.WEREWOLF)
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(req("seer", ActionType.SEER_CHECK, "u2"), ctx)

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val events = (result as GameActionResult.Success).events
            assertThat(events).hasSize(1)
            val seerResult = events[0] as DomainEvent.SeerResult
            assertThat(seerResult.checkedUserId).isEqualTo("u2")
            assertThat(seerResult.isWerewolf).isTrue()
        }

        @Test
        fun `seer check - detects villager target correctly`() {
            val ctx = seerCtx(targetRole = PlayerRole.VILLAGER)
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(req("seer", ActionType.SEER_CHECK, "u2"), ctx)

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val seerResult = (result as GameActionResult.Success).events[0] as DomainEvent.SeerResult
            assertThat(seerResult.isWerewolf).isFalse()
        }

        @Test
        fun `seer check - saves seerCheckedUserId and seerResultIsWerewolf`() {
            val ctx = seerCtx(targetRole = PlayerRole.WEREWOLF)
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            handler.handle(req("seer", ActionType.SEER_CHECK, "u2"), ctx)

            val captor = argumentCaptor<NightPhase>()
            verify(nightPhaseRepository).save(captor.capture())
            assertThat(captor.firstValue.seerCheckedUserId).isEqualTo("u2")
            assertThat(captor.firstValue.seerResultIsWerewolf).isTrue()
        }

        @Test
        fun `seer confirm - succeeds without side effects`() {
            val ctx = seerCtx(subPhase = NightSubPhase.SEER_RESULT)
            val result = handler.handle(req("seer", ActionType.SEER_CONFIRM), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            assertThat((result as GameActionResult.Success).events).isEmpty()
            verifyNoInteractions(nightPhaseRepository)
        }
    }

    // ── WitchHandler ──────────────────────────────────────────────────────────

    @Nested
    inner class WitchHandlerTests {

        private lateinit var handler: WitchHandler

        @BeforeEach
        fun setUp() {
            handler = WitchHandler(nightPhaseRepository)
        }

        private fun witchCtx(
            allNightPhases: List<NightPhase> = emptyList(),
            wolfTarget: String? = "u2",
        ): GameContext {
            val np = nightPhase(NightSubPhase.WITCH_ACT).also {
                it.wolfTargetUserId = wolfTarget
            }
            val witch = player("witch", 1, PlayerRole.WITCH)
            val victim = player("u2", 2, PlayerRole.VILLAGER)
            val other = player("u3", 3, PlayerRole.VILLAGER)
            return GameContext(game(), room(), listOf(witch, victim, other), nightPhase = np, allNightPhases = allNightPhases)
        }

        @Test
        fun `witch antidote - rejected when antidote already used in previous round`() {
            // id=1 so it.id != nightPhase.id (null) evaluates true, making antidoteEverUsed=true
            val previousNight = NightPhase(id = 1, gameId = gameId, dayNumber = 1).also {
                it.witchAntidoteUsed = true
            }
            val ctx = witchCtx(allNightPhases = listOf(previousNight))
            val result = handler.handle(req("witch", ActionType.WITCH_ACT, payload = mapOf("useAntidote" to true)), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("Antidote already used")
        }

        @Test
        fun `witch - rejected when using antidote and poison on the same night`() {
            val ctx = witchCtx()
            val result = handler.handle(
                req("witch", ActionType.WITCH_ACT, payload = mapOf("useAntidote" to true, "poisonTargetUserId" to "u3")),
                ctx,
            )
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("antidote and poison")
        }

        @Test
        fun `witch antidote - sets witchAntidoteUsed and saves`() {
            val ctx = witchCtx()
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(req("witch", ActionType.WITCH_ACT, payload = mapOf("useAntidote" to true)), ctx)

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val captor = argumentCaptor<NightPhase>()
            verify(nightPhaseRepository).save(captor.capture())
            assertThat(captor.firstValue.witchAntidoteUsed).isTrue()
        }

        @Test
        fun `witch poison - sets witchPoisonTargetUserId and saves`() {
            val ctx = witchCtx()
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(
                req("witch", ActionType.WITCH_ACT, payload = mapOf("poisonTargetUserId" to "u3")),
                ctx,
            )

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val captor = argumentCaptor<NightPhase>()
            verify(nightPhaseRepository).save(captor.capture())
            assertThat(captor.firstValue.witchPoisonTargetUserId).isEqualTo("u3")
        }

        @Test
        fun `witch no action - succeeds and saves without changing fields`() {
            val ctx = witchCtx()
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(req("witch", ActionType.WITCH_ACT, payload = emptyMap()), ctx)

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val captor = argumentCaptor<NightPhase>()
            verify(nightPhaseRepository).save(captor.capture())
            assertThat(captor.firstValue.witchAntidoteUsed).isFalse()
            assertThat(captor.firstValue.witchPoisonTargetUserId).isNull()
        }
    }

    // ── GuardHandler ──────────────────────────────────────────────────────────

    @Nested
    inner class GuardHandlerTests {

        private lateinit var handler: GuardHandler

        @BeforeEach
        fun setUp() {
            handler = GuardHandler(nightPhaseRepository)
        }

        private fun guardCtx(prevTarget: String? = null): GameContext {
            val np = nightPhase(NightSubPhase.GUARD_PICK, prevGuardTarget = prevTarget)
            val guard = player("guard", 1, PlayerRole.GUARD)
            val target = player("u2", 2, PlayerRole.VILLAGER)
            return GameContext(game(), room(), listOf(guard, target), nightPhase = np)
        }

        @Test
        fun `guard protect - rejected when same target as previous night`() {
            val ctx = guardCtx(prevTarget = "u2")
            val result = handler.handle(req("guard", ActionType.GUARD_PROTECT, "u2"), ctx)
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("same player two nights")
        }

        @Test
        fun `guard protect - sets guardTargetUserId when different target`() {
            val ctx = guardCtx(prevTarget = "u3") // previous was someone else
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(req("guard", ActionType.GUARD_PROTECT, "u2"), ctx)

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val captor = argumentCaptor<NightPhase>()
            verify(nightPhaseRepository).save(captor.capture())
            assertThat(captor.firstValue.guardTargetUserId).isEqualTo("u2")
        }

        @Test
        fun `guard skip - sets guardTargetUserId to null`() {
            val ctx = guardCtx()
            whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

            val result = handler.handle(req("guard", ActionType.GUARD_SKIP), ctx)

            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
            val captor = argumentCaptor<NightPhase>()
            verify(nightPhaseRepository).save(captor.capture())
            assertThat(captor.firstValue.guardTargetUserId).isNull()
        }
    }
}
