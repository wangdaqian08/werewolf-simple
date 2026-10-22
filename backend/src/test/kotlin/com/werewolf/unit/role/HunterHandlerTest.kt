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

    private fun game(phase: GamePhase = GamePhase.DAY_VOTING, subPhase: String = VotingSubPhase.VOTE_RESULT.name) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = phase
            it.subPhase = subPhase
        }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun req(actionType: ActionType, target: String? = null) =
        GameActionRequest(gameId = gameId, actorUserId = "hunter", actionType = actionType, targetUserId = target)

    private fun ctx(phase: GamePhase = GamePhase.DAY_VOTING, subPhase: String = VotingSubPhase.VOTE_RESULT.name) =
        GameContext(game(phase, subPhase), room(), emptyList())

    // ── acceptedActions ───────────────────────────────────────────────────────

    @Nested
    inner class AcceptedActionsTests {

        @Test
        fun `returns HUNTER_SHOOT and HUNTER_PASS after the tally and after the night reveal`() {
            for ((phase, sub) in listOf(
                GamePhase.DAY_VOTING to VotingSubPhase.VOTE_RESULT.name,
                GamePhase.DAY_VOTING to VotingSubPhase.BADGE_HANDOVER.name,
                GamePhase.DAY_DISCUSSION to DaySubPhase.RESULT_REVEALED.name,
                GamePhase.DAY_DISCUSSION to DaySubPhase.BADGE_HANDOVER.name,
            )) {
                assertThat(handler.acceptedActions(phase, sub))
                    .containsExactlyInAnyOrder(ActionType.HUNTER_SHOOT, ActionType.HUNTER_PASS)
            }
        }

        @Test
        fun `returns empty set during NIGHT phase`() {
            val actions = handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.WEREWOLF_PICK.name)
            assertThat(actions).isEmpty()
        }

        @Test
        fun `returns empty set before the night result is revealed`() {
            val actions = handler.acceptedActions(GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name)
            assertThat(actions).isEmpty()
        }

        @Test
        fun `returns empty set during VOTING VOTING sub-phase`() {
            val actions = handler.acceptedActions(GamePhase.DAY_VOTING, VotingSubPhase.VOTING.name)
            assertThat(actions).isEmpty()
        }

        @Test
        fun `returns empty set during VOTING RE_VOTING sub-phase`() {
            val actions = handler.acceptedActions(GamePhase.DAY_VOTING, VotingSubPhase.RE_VOTING.name)
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
