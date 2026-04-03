package com.werewolf.unit.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.WitchHandler
import com.werewolf.model.*
import com.werewolf.repository.NightPhaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class WitchHandlerTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private lateinit var witchHandler: WitchHandler

    private val gameId = 1
    private val hostId = "host:001"
    private val witchId = "witch:001"
    private val targetId = "target:001"

    @BeforeEach
    fun setUp() {
        witchHandler = WitchHandler(nightPhaseRepository)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun game(phase: GamePhase = GamePhase.NIGHT, subPhase: String = NightSubPhase.WITCH_ACT.name) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = phase
            it.subPhase = subPhase
        }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun nightPhase() = NightPhase(gameId = gameId, dayNumber = 1).also {
        it.subPhase = NightSubPhase.WITCH_ACT
    }

    private fun ctx(vararg players: GamePlayer, nightPhase: NightPhase = nightPhase(), allNightPhases: List<NightPhase> = emptyList()) =
        GameContext(game(), room(), players.toList(), nightPhase = nightPhase, allNightPhases = allNightPhases)

    private fun req(actionType: ActionType = ActionType.WITCH_ACT, payload: Map<String, Any> = emptyMap(), actorId: String = witchId) =
        GameActionRequest(gameId = gameId, actorUserId = actorId, actionType = actionType, payload = payload)

    // ── Witch Poison Usage Limit Tests ──────────────────────────────────────

    @Test
    fun `Poison can only be used once per game - First use succeeds`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val target = player(targetId, 2)
        val np = nightPhase()
        val context = ctx(witch, target, nightPhase = np)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        val result = witchHandler.handle(req(payload = mapOf("poisonTargetUserId" to targetId)), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(np.witchPoisonTargetUserId).isEqualTo(targetId)
        verify(nightPhaseRepository).save(np)
    }

    @Test
    fun `Poison can only be used once per game - Second use rejected`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val poisoned1 = player("poisoned1", 2)
        val poisoned2 = player("poisoned2", 3)

        // First use of poison
        val night1 = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WITCH_ACT
            it.witchPoisonTargetUserId = "poisoned1"
        }
        val context1 = ctx(witch, poisoned1, poisoned2, nightPhase = night1, allNightPhases = emptyList())
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        val result1 = witchHandler.handle(req(payload = mapOf("poisonTargetUserId" to "poisoned1")), context1)
        assertThat(result1).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(night1.witchPoisonTargetUserId).isEqualTo("poisoned1")

        // Set night1 ID to simulate it being saved to database
        val idField = NightPhase::class.java.getDeclaredField("id")
        idField.isAccessible = true
        idField.set(night1, 1)

        // Second attempt to use poison (different night)
        val night2 = NightPhase(gameId = gameId, dayNumber = 2).also {
            it.subPhase = NightSubPhase.WITCH_ACT
        }
        val context2 = ctx(witch, poisoned1, poisoned2, nightPhase = night2, allNightPhases = listOf(night1))

        val result2 = witchHandler.handle(req(payload = mapOf("poisonTargetUserId" to "poisoned2")), context2)
        assertThat(result2).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result2 as GameActionResult.Rejected).reason).contains("Poison already used")
    }

    @Test
    fun `Antidote and poison cannot be used on the same night - Using both rejected`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val target = player(targetId, 2)
        val np = nightPhase()
        val context = ctx(witch, target, nightPhase = np)

        val result = witchHandler.handle(req(payload = mapOf(
            "useAntidote" to true,
            "poisonTargetUserId" to targetId
        )), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Cannot use antidote and poison on the same night")
    }

    // ── Witch Action Auto-advance Tests ───────────────────────────────────

    @Test
    fun `When witch has no available items (both used previously) - Using false and null should succeed and allow game to advance`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val np = nightPhase()

        // Previous night: witch used both antidote and poison
        val previousNight = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.witchAntidoteUsed = true
            it.witchPoisonTargetUserId = "some:target"
        }

        val context = ctx(witch, nightPhase = np, allNightPhases = listOf(previousNight))
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        val payload = mapOf<String, Any>("useAntidote" to false)
        val result = witchHandler.handle(req(payload = payload), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(np.witchAntidoteUsed).isFalse()
        assertThat(np.witchPoisonTargetUserId).isNull()
        verify(nightPhaseRepository).save(np)

        // Verify that GameActionDispatcher would advance the game
        // In the actual flow, hasAntidote and hasPoison would both be false (used in previous night)
        // So antidoteDecided = true and poisonDecided = true, allowing advance
    }

    @Test
    fun `When witch only has antidote - Using antidote should succeed`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val np = nightPhase()
        val context = ctx(witch, nightPhase = np)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        val result = witchHandler.handle(req(payload = mapOf("useAntidote" to true)), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(np.witchAntidoteUsed).isTrue()
        verify(nightPhaseRepository).save(np)
    }

    @Test
    fun `When witch only has poison - Using poison should succeed`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val target = player(targetId, 2)
        val np = nightPhase()
        val context = ctx(witch, target, nightPhase = np)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        val result = witchHandler.handle(req(payload = mapOf("poisonTargetUserId" to targetId)), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(np.witchPoisonTargetUserId).isEqualTo(targetId)
        verify(nightPhaseRepository).save(np)
    }

    @Test
    fun `When witch has both potions - Can only use one`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val target = player(targetId, 2)
        val np = nightPhase()
        val context = ctx(witch, target, nightPhase = np)

        // Try to use both potions
        val result = witchHandler.handle(req(payload = mapOf(
            "useAntidote" to true,
            "poisonTargetUserId" to targetId
        )), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Cannot use antidote and poison on the same night")
    }

    // ── Other Boundary Tests ──────────────────────────────────────────────

    @Test
    fun `Witch is not the current player - Action rejected`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val villager = player("villager:001", 2, PlayerRole.VILLAGER)
        val np = nightPhase()
        val context = ctx(witch, villager, nightPhase = np)

        val result = witchHandler.handle(req(actorId = "villager:001", payload = mapOf("useAntidote" to true)), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Not the witch")
    }

    @Test
    fun `Witch is dead - Action rejected`() {
        val deadWitch = player(witchId, 1, PlayerRole.WITCH, alive = false)
        val np = nightPhase()
        val context = ctx(deadWitch, nightPhase = np)

        val result = witchHandler.handle(req(payload = mapOf("useAntidote" to true)), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Actor is dead")
    }

    @Test
    fun `Poison target is already dead - Action rejected`() {
        val witch = player(witchId, 1, PlayerRole.WITCH)
        val deadTarget = player(targetId, 2, alive = false)
        val np = nightPhase()
        val context = ctx(witch, deadTarget, nightPhase = np)

        val result = witchHandler.handle(req(payload = mapOf("poisonTargetUserId" to targetId)), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Poison target not found or dead")
    }
}