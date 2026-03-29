package com.werewolf.unit.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.role.VillagerHandler
import com.werewolf.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class VillagerHandlerTest {

    private lateinit var handler: VillagerHandler

    @BeforeEach
    fun setUp() {
        handler = VillagerHandler()
    }

    private val gameId = 1
    private val hostId = "host:001"

    private fun game(phase: GamePhase = GamePhase.NIGHT) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = phase
        }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun req(actionType: ActionType) =
        GameActionRequest(gameId = gameId, actorUserId = "v1", actionType = actionType)

    private fun ctx(phase: GamePhase = GamePhase.NIGHT) =
        GameContext(game(phase), room(), emptyList())

    // ── acceptedActions ───────────────────────────────────────────────────────

    @Test
    fun `acceptedActions is always empty during NIGHT phase`() {
        assertThat(handler.acceptedActions(GamePhase.NIGHT, NightSubPhase.WAITING.name)).isEmpty()
    }

    @Test
    fun `acceptedActions is always empty during DAY phase`() {
        assertThat(handler.acceptedActions(GamePhase.DAY, DaySubPhase.RESULT_HIDDEN.name)).isEmpty()
    }

    @Test
    fun `acceptedActions is always empty during VOTING phase`() {
        assertThat(handler.acceptedActions(GamePhase.VOTING, VotingSubPhase.VOTING.name)).isEmpty()
    }

    @Test
    fun `acceptedActions is always empty during ROLE_REVEAL phase`() {
        assertThat(handler.acceptedActions(GamePhase.ROLE_REVEAL, null)).isEmpty()
    }

    // ── nightSubPhases ────────────────────────────────────────────────────────

    @Test
    fun `nightSubPhases returns empty list - villager has no night action`() {
        assertThat(handler.nightSubPhases()).isEmpty()
    }

    // ── handle ────────────────────────────────────────────────────────────────

    @Test
    fun `handle always returns Rejected - villager has no special actions`() {
        val result = handler.handle(req(ActionType.CONFIRM_ROLE), ctx())
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Villager")
    }

    // ── role ──────────────────────────────────────────────────────────────────

    @Test
    fun `role is VILLAGER`() {
        assertThat(handler.role).isEqualTo(PlayerRole.VILLAGER)
    }
}
