package com.werewolf.unit.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.HunterHandler
import com.werewolf.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class HunterHandlerTest {

    private lateinit var handler: HunterHandler

    @BeforeEach
    fun setUp() {
        handler = HunterHandler()
    }

    private val gameId = 1
    private val hostId = "host:001"

    private fun game(phase: GamePhase = GamePhase.VOTING, subPhase: String = VotingSubPhase.HUNTER_SHOOT.name) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = phase
            it.subPhase = subPhase
        }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.HUNTER) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role)

    private fun req(actionType: ActionType, target: String? = null) =
        GameActionRequest(gameId = gameId, actorUserId = "hunter", actionType = actionType, targetUserId = target)

    private fun ctx(phase: GamePhase = GamePhase.VOTING, subPhase: String = VotingSubPhase.HUNTER_SHOOT.name) =
        GameContext(game(phase, subPhase), room(), emptyList())

    // ── acceptedActions ───────────────────────────────────────────────────────

    @Nested
    inner class AcceptedActionsTests {

        @Test
        fun `returns HUNTER_SHOOT and HUNTER_SKIP during VOTING HUNTER_SHOOT sub-phase`() {
            val actions = handler.acceptedActions(GamePhase.VOTING, VotingSubPhase.HUNTER_SHOOT.name)
            assertThat(actions).containsExactlyInAnyOrder(ActionType.HUNTER_SHOOT, ActionType.HUNTER_PASS)
        }

        @Test
        fun `returns empty set during NIGHT phase`() {
            val actions = handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.WEREWOLF_PICK.name)
            assertThat(actions).isEmpty()
        }

        @Test
        fun `returns empty set during DAY phase`() {
            val actions = handler.acceptedActions(GamePhase.DAY, DaySubPhase.RESULT_HIDDEN.name)
            assertThat(actions).isEmpty()
        }

        @Test
        fun `returns empty set during VOTING VOTING sub-phase (not HUNTER_SHOOT)`() {
            val actions = handler.acceptedActions(GamePhase.VOTING, VotingSubPhase.VOTING.name)
            assertThat(actions).isEmpty()
        }

        @Test
        fun `returns empty set during VOTING RE_VOTING sub-phase`() {
            val actions = handler.acceptedActions(GamePhase.VOTING, VotingSubPhase.RE_VOTING.name)
            assertThat(actions).isEmpty()
        }

        @Test
        fun `returns empty set during ROLE_REVEAL phase`() {
            val actions = handler.acceptedActions(GamePhase.ROLE_REVEAL, null)
            assertThat(actions).isEmpty()
        }
    }

    // ── nightSubPhases ────────────────────────────────────────────────────────

    @Test
    fun `nightSubPhases returns empty list - hunter has no night action`() {
        assertThat(handler.nightSubPhases()).isEmpty()
    }

    // ── handle ────────────────────────────────────────────────────────────────

    @Nested
    inner class HandleTests {

        @Test
        fun `handle HUNTER_SHOOT always returns Rejected - routed through VotingPipeline`() {
            val result = handler.handle(req(ActionType.HUNTER_SHOOT, "u2"), ctx())
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("VotingPipeline")
        }

        @Test
        fun `handle HUNTER_SKIP always returns Rejected - routed through VotingPipeline`() {
            val result = handler.handle(req(ActionType.HUNTER_PASS), ctx())
            assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
            assertThat((result as GameActionResult.Rejected).reason).contains("VotingPipeline")
        }
    }

    // ── role ──────────────────────────────────────────────────────────────────

    @Test
    fun `role is HUNTER`() {
        assertThat(handler.role).isEqualTo(PlayerRole.HUNTER)
    }
}
