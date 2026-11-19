package com.werewolf.unit.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.WerewolfHandler
import com.werewolf.model.*
import com.werewolf.repository.NightPhaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/** The mechanical wolf is wolf-side but outside the pack: it kills only once no pack wolf is alive. */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WerewolfHandlerPackTest {

    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private val gameId = 1

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun ctx(vararg players: GamePlayer): GameContext {
        val game = Game(roomId = 1, hostUserId = "host").also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = GamePhase.NIGHT
        }
        val np = NightPhase(gameId = gameId, dayNumber = 1).also { it.subPhase = NightSubPhase.WEREWOLF_PICK }
        return GameContext(game, Room(roomCode = "ABCD", hostUserId = "host", totalPlayers = 6), players.toList(), nightPhase = np)
    }

    private fun kill(actor: String, target: String = "v") =
        GameActionRequest(gameId = gameId, actorUserId = actor, actionType = ActionType.WOLF_KILL, targetUserId = target)

    @Test
    fun `mechanical wolf cannot WOLF_KILL while a pack wolf lives`() {
        val c = ctx(player("w", 1, PlayerRole.WEREWOLF), player("m", 2, PlayerRole.MECHANICAL_WOLF), player("v", 3, PlayerRole.VILLAGER))

        val result = WerewolfHandler(nightPhaseRepository).handle(kill("m"), c)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(c.nightPhase?.wolfTargetUserId).isNull()
    }

    @Test
    fun `lone mechanical wolf kills once the pack is gone`() {
        val c = ctx(
            player("w", 1, PlayerRole.WEREWOLF, alive = false), player("m", 2, PlayerRole.MECHANICAL_WOLF),
            player("v", 3, PlayerRole.VILLAGER),
        )

        val result = WerewolfHandler(nightPhaseRepository).handle(kill("m"), c)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(c.nightPhase?.wolfTargetUserId).isEqualTo("v")
    }

    @Test
    fun `G8 - the gargoyle cannot WOLF_KILL while a pack wolf lives`() {
        val c = ctx(player("w", 1, PlayerRole.WEREWOLF), player("g", 2, PlayerRole.GARGOYLE), player("v", 3, PlayerRole.VILLAGER))

        val result = WerewolfHandler(nightPhaseRepository).handle(kill("g"), c)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).doesNotContain("mechanical")
        assertThat(c.nightPhase?.wolfTargetUserId).isNull()
    }

    @Test
    fun `G8 - the gargoyle cannot WOLF_KILL while a wolf king lives`() {
        val c = ctx(player("k", 1, PlayerRole.BLACK_WOLF_KING), player("g", 2, PlayerRole.GARGOYLE), player("v", 3, PlayerRole.VILLAGER))

        assertThat(WerewolfHandler(nightPhaseRepository).handle(kill("g"), c)).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `G8 - the lone gargoyle kills once every other wolf is gone`() {
        val c = ctx(
            player("w", 1, PlayerRole.WEREWOLF, alive = false), player("g", 2, PlayerRole.GARGOYLE),
            player("v", 3, PlayerRole.VILLAGER),
        )

        val result = WerewolfHandler(nightPhaseRepository).handle(kill("g"), c)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(c.nightPhase?.wolfTargetUserId).isEqualTo("v")
    }

    @Test
    fun `G2 - pack wolf may pick the living gargoyle as the kill target`() {
        val c = ctx(player("w", 1, PlayerRole.WEREWOLF), player("g", 2, PlayerRole.GARGOYLE), player("v", 3, PlayerRole.VILLAGER))

        val result = WerewolfHandler(nightPhaseRepository).handle(kill("w", target = "g"), c)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(c.nightPhase?.wolfTargetUserId).isEqualTo("g")
    }

    @Test
    fun `pack wolf still kills`() {
        val c = ctx(player("w", 1, PlayerRole.WEREWOLF), player("m", 2, PlayerRole.MECHANICAL_WOLF), player("v", 3, PlayerRole.VILLAGER))

        val result = WerewolfHandler(nightPhaseRepository).handle(kill("w"), c)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(c.nightPhase?.wolfTargetUserId).isEqualTo("v")
    }

    @Test
    fun `pack wolf may pick the living mechanical wolf as the kill target`() {
        val c = ctx(player("w", 1, PlayerRole.WEREWOLF), player("m", 2, PlayerRole.MECHANICAL_WOLF), player("v", 3, PlayerRole.VILLAGER))

        val result = WerewolfHandler(nightPhaseRepository).handle(kill("w", target = "m"), c)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(c.nightPhase?.wolfTargetUserId).isEqualTo("m")
    }

    @Test
    fun `W8 - the pack cannot WOLF_KILL or WOLF_SELECT the wolf beauty, and the kill stays open`() {
        val c = ctx(player("w", 1, PlayerRole.WEREWOLF), player("b", 2, PlayerRole.WOLF_BEAUTY), player("v", 3, PlayerRole.VILLAGER))
        val handler = WerewolfHandler(nightPhaseRepository)

        val killBeauty = handler.handle(kill("w", "b"), c)
        val selectBeauty = handler.handle(kill("w", "b").copy(actionType = ActionType.WOLF_SELECT), c)

        assertThat(killBeauty).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((killBeauty as GameActionResult.Rejected).reason).isEqualTo("The Wolf Beauty cannot be knifed")
        assertThat(selectBeauty).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat(c.nightPhase?.wolfTargetUserId).isNull()
        // the rejection did not lock the kill: a valid one still goes through
        assertThat(handler.handle(kill("w", "v"), c)).isInstanceOf(GameActionResult.Success::class.java)
    }

    @Test
    fun `W8 - the pack can still WOLF_KILL another pack wolf`() {
        val c = ctx(player("w", 1, PlayerRole.WEREWOLF), player("w2", 2, PlayerRole.WEREWOLF), player("b", 3, PlayerRole.WOLF_BEAUTY))

        assertThat(WerewolfHandler(nightPhaseRepository).handle(kill("w", "w2"), c)).isInstanceOf(GameActionResult.Success::class.java)
    }
}
