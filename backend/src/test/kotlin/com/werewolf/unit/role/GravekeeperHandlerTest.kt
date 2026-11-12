package com.werewolf.unit.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.GravekeeperHandler
import com.werewolf.model.*
import com.werewolf.repository.EliminationHistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.util.Optional

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GravekeeperHandlerTest {

    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository

    private lateinit var handler: GravekeeperHandler

    private val gameId = 1
    private val keeperId = "keeper:001"
    private val exiledId = "exiled:001"

    @BeforeEach
    fun setUp() {
        handler = GravekeeperHandler(eliminationHistoryRepository)
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(any(), any())).thenReturn(Optional.empty())
    }

    private fun game() = Game(roomId = 1, hostUserId = "host:001").also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = "host:001", totalPlayers = 6, hasGravekeeper = true)

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun keeper(alive: Boolean = true) = player(keeperId, 1, PlayerRole.GRAVEKEEPER, alive)

    private fun night(day: Int, sub: NightSubPhase = NightSubPhase.GRAVEKEEPER_RESULT) =
        NightPhase(gameId = gameId, dayNumber = day).also { it.subPhase = sub }

    private fun ctx(vararg players: GamePlayer, np: NightPhase? = null) =
        GameContext(game(), room(), players.toList(), nightPhase = np)

    /** Yesterday's vote-out, as VotingPipeline records it: the real role. */
    private fun exile(day: Int, role: PlayerRole) {
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(
            Optional.of(EliminationHistory(gameId = gameId, dayNumber = day, eliminatedUserId = exiledId, eliminatedRole = role)),
        )
    }

    private fun req(actor: String = keeperId) =
        GameActionRequest(gameId = gameId, actorUserId = actor, actionType = ActionType.GRAVEKEEPER_CONFIRM)

    // ── onNightInit ───────────────────────────────────────────────────────────

    @Test
    fun `night 1 stores NONE without reading history`() {
        val np = night(1)

        handler.onNightInit(ctx(keeper()), np)

        assertThat(np.gravekeeperResult).isEqualTo(GravekeeperResult.NONE)
        assertThat(np.gravekeeperExiledUserId).isNull()
        verify(eliminationHistoryRepository, never()).findByGameIdAndDayNumber(any(), any())
    }

    @ParameterizedTest
    @EnumSource(value = PlayerRole::class, names = ["WEREWOLF", "WHITE_WOLF_KING", "BLACK_WOLF_KING", "MECHANICAL_WOLF"])
    fun `wolf exiled yesterday reads WOLF`(role: PlayerRole) {
        exile(day = 2, role = role)
        val np = night(3)

        handler.onNightInit(ctx(keeper(), player(exiledId, 2, role, alive = false)), np)

        assertThat(np.gravekeeperResult).isEqualTo(GravekeeperResult.WOLF)
        assertThat(np.gravekeeperExiledUserId).isEqualTo(exiledId)
    }

    @ParameterizedTest
    @EnumSource(value = PlayerRole::class, names = ["VILLAGER", "HUNTER", "KNIGHT"])
    fun `good exiled yesterday reads GOOD`(role: PlayerRole) {
        exile(day = 1, role = role)
        val np = night(2)

        handler.onNightInit(ctx(keeper(), player(exiledId, 2, role, alive = false)), np)

        assertThat(np.gravekeeperResult).isEqualTo(GravekeeperResult.GOOD)
        assertThat(np.gravekeeperExiledUserId).isEqualTo(exiledId)
    }

    @Test
    fun `exiled mechanical wolf that learned SEER reads WOLF`() {
        exile(day = 1, role = PlayerRole.MECHANICAL_WOLF)
        val mech = player(exiledId, 2, PlayerRole.MECHANICAL_WOLF, alive = false).also { it.learnedRole = PlayerRole.SEER }
        val np = night(2)

        handler.onNightInit(ctx(keeper(), mech), np)

        assertThat(np.gravekeeperResult).isEqualTo(GravekeeperResult.WOLF)
    }

    @Test
    fun `no exile row reads NONE`() {
        // G2: shots, duels, takes, declare-outs and idiot reveals never write an elimination_history row.
        val np = night(2)

        handler.onNightInit(ctx(keeper(), player(exiledId, 2, PlayerRole.WEREWOLF, alive = false)), np)

        assertThat(np.gravekeeperResult).isEqualTo(GravekeeperResult.NONE)
        assertThat(np.gravekeeperExiledUserId).isNull()
        verify(eliminationHistoryRepository).findByGameIdAndDayNumber(gameId, 1)
    }

    @Test
    fun `dead gravekeeper gets nothing stored`() {
        exile(day = 1, role = PlayerRole.WEREWOLF)
        val np = night(2)

        handler.onNightInit(ctx(keeper(alive = false)), np)

        assertThat(np.gravekeeperResult).isNull()
        assertThat(np.gravekeeperExiledUserId).isNull()
        verify(eliminationHistoryRepository, never()).findByGameIdAndDayNumber(any(), any())
    }

    @Test
    fun `no gravekeeper in the game stores nothing`() {
        exile(day = 1, role = PlayerRole.WEREWOLF)
        val np = night(2)

        handler.onNightInit(ctx(player("seer:001", 1, PlayerRole.SEER)), np)

        assertThat(np.gravekeeperResult).isNull()
        assertThat(np.gravekeeperExiledUserId).isNull()
        verify(eliminationHistoryRepository, never()).findByGameIdAndDayNumber(any(), any())
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    @Test
    fun `accepts GRAVEKEEPER_CONFIRM only in GRAVEKEEPER_RESULT`() {
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.GRAVEKEEPER_RESULT.name))
            .containsExactly(ActionType.GRAVEKEEPER_CONFIRM)
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.SEER_RESULT.name)).isEmpty()
        assertThat(handler.acceptedActions(GamePhase.DAY_DISCUSSION, NightSubPhase.GRAVEKEEPER_RESULT.name)).isEmpty()
        assertThat(handler.nightSubPhases()).containsExactly(NightSubPhase.GRAVEKEEPER_RESULT)

        val outside = handler.handle(req(), ctx(keeper(), np = night(2, NightSubPhase.GUARD_PICK)))
        assertThat((outside as GameActionResult.Rejected).reason).isEqualTo("Not in GRAVEKEEPER_RESULT sub-phase")
    }

    @Test
    fun `rejects a non-gravekeeper`() {
        val result = handler.handle(req(actor = "seer:001"), ctx(keeper(), player("seer:001", 2, PlayerRole.SEER), np = night(2)))
        assertThat((result as GameActionResult.Rejected).reason).isEqualTo("Not the gravekeeper")
    }

    @Test
    fun `rejects a dead gravekeeper`() {
        val result = handler.handle(req(), ctx(keeper(alive = false), np = night(2)))
        assertThat((result as GameActionResult.Rejected).reason).isEqualTo("Actor is dead")
    }

    @Test
    fun `confirm succeeds in GRAVEKEEPER_RESULT`() {
        val result = handler.handle(req(), ctx(keeper(), np = night(2)))
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
    }
}
