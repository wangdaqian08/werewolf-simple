package com.werewolf.unit.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.IdiotHandler
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.*

@ExtendWith(MockitoExtension::class)
class IdiotHandlerTest {

    @Mock lateinit var gamePlayerRepository: GamePlayerRepository

    private val gameId = 1
    private val hostId = "host:001"

    private fun handler() = IdiotHandler(gamePlayerRepository)

    private fun game() = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.VOTING
        it.subPhase = VotingSubPhase.VOTE_RESULT.name
        it.dayNumber = 1
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasIdiot = true)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun ctx(vararg players: GamePlayer) = GameContext(game(), room(), players.toList())

    private fun req(actorId: String) =
        GameActionRequest(gameId = gameId, actorUserId = actorId, actionType = ActionType.IDIOT_REVEAL)

    // ── acceptedActions ──────────────────────────────────────────────────────

    @Test
    fun `acceptedActions - returns IDIOT_REVEAL during VOTING VOTE_RESULT sub-phase`() {
        val accepted = handler().acceptedActions(GamePhase.VOTING, VotingSubPhase.VOTE_RESULT.name)
        assertThat(accepted).contains(ActionType.IDIOT_REVEAL)
    }

    @Test
    fun `acceptedActions - returns nothing during NIGHT phase`() {
        val accepted = handler().acceptedActions(GamePhase.NIGHT, NightSubPhase.WEREWOLF_PICK.name)
        assertThat(accepted).doesNotContain(ActionType.IDIOT_REVEAL)
    }

    @Test
    fun `acceptedActions - returns nothing during VOTING sub-phase other than VOTE_RESULT`() {
        val accepted = handler().acceptedActions(GamePhase.VOTING, VotingSubPhase.VOTING.name)
        assertThat(accepted).doesNotContain(ActionType.IDIOT_REVEAL)
    }

    // ── handle: first reveal ─────────────────────────────────────────────────

    @Test
    fun `handle IDIOT_REVEAL - idiot voted out first time, survives with canVote=false and idiotRevealed=true`() {
        val idiot = player("u1", 1, PlayerRole.IDIOT)
        val context = ctx(idiot)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(idiot))

        val result = handler().handle(req("u1"), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(idiot.alive).isTrue()           // still alive
        assertThat(idiot.canVote).isFalse()         // lost voting right
        assertThat(idiot.idiotRevealed).isTrue()    // revealed
        verify(gamePlayerRepository).save(idiot)
    }

    @Test
    fun `handle IDIOT_REVEAL - broadcasts IdiotRevealed event`() {
        val idiot = player("u1", 1, PlayerRole.IDIOT)
        val context = ctx(idiot)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(idiot))

        val result = handler().handle(req("u1"), context)

        val success = result as GameActionResult.Success
        assertThat(success.events).anyMatch { it is DomainEvent.IdiotRevealed }
        val event = success.events.filterIsInstance<DomainEvent.IdiotRevealed>().first()
        assertThat(event.userId).isEqualTo("u1")
        assertThat(event.gameId).isEqualTo(gameId)
    }

    @Test
    fun `handle IDIOT_REVEAL - actor not found returns Rejected`() {
        val context = ctx()

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.empty())

        val result = handler().handle(req("u1"), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        verify(gamePlayerRepository, never()).save(any())
    }

    @Test
    fun `handle IDIOT_REVEAL - actor is not IDIOT returns Rejected`() {
        val villager = player("u1", 1, PlayerRole.VILLAGER)
        val context = ctx(villager)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(villager))

        val result = handler().handle(req("u1"), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        verify(gamePlayerRepository, never()).save(any())
    }

    // ── WinConditionChecker — Idiot counting ─────────────────────────────────

    @Test
    fun `WinConditionChecker CLASSIC - revealed Idiot still counts as alive non-wolf`() {
        val checker = WinConditionChecker()
        val wolf = player("w1", 1, PlayerRole.WEREWOLF)
        val idiot = player("u1", 2, PlayerRole.IDIOT).also { it.canVote = false; it.idiotRevealed = true }
        val villager = player("v1", 3, PlayerRole.VILLAGER)

        // wolves(1) < others(2 = idiot + villager) → no win yet
        val result = checker.check(listOf(wolf, idiot, villager), WinConditionMode.CLASSIC)
        assertThat(result).isNull()
    }

    @Test
    fun `WinConditionChecker CLASSIC - wolves win when outnumbering alive players including revealed Idiot`() {
        val checker = WinConditionChecker()
        val wolf1 = player("w1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("w2", 2, PlayerRole.WEREWOLF)
        val idiot = player("u1", 3, PlayerRole.IDIOT).also { it.canVote = false; it.idiotRevealed = true }

        // wolves(2) > others(1 = idiot) → WEREWOLF wins
        val result = checker.check(listOf(wolf1, wolf2, idiot), WinConditionMode.CLASSIC)
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }
}
