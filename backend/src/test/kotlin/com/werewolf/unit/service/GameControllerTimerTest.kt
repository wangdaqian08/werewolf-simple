package com.werewolf.unit.service

import com.werewolf.controller.GameController
import com.werewolf.controller.TimerStartRequest
import com.werewolf.game.action.GameActionDispatcher
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.timer.HostTimerService
import com.werewolf.service.ActionLogService
import com.werewolf.service.GameService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication

@ExtendWith(MockitoExtension::class)
class GameControllerTimerTest {

    @Mock lateinit var gameService: GameService
    @Mock lateinit var gameActionDispatcher: GameActionDispatcher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var actionLogService: ActionLogService
    @Mock lateinit var hostTimerService: HostTimerService
    @Mock lateinit var authentication: Authentication

    private lateinit var controller: GameController

    private val gameId = 1
    private val hostId = "host:001"
    private val guestId = "guest:001"

    @BeforeEach
    fun setUp() {
        controller = GameController(
            gameService, gameActionDispatcher, nightOrchestrator,
            actionLogService, hostTimerService,
        )
        whenever(authentication.principal).thenReturn(hostId)
    }

    // ── startTimer ────────────────────────────────────────────────────────────

    @Test
    fun `startTimer returns 200 for host during DAY_DISCUSSION`() {
        whenever(hostTimerService.start(hostId, gameId, 60L)).thenReturn(GameActionResult.Success())
        val response = controller.startTimer(gameId, TimerStartRequest(60L), authentication)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `startTimer returns 200 for host during SHERIFF_ELECTION SPEECH`() {
        whenever(hostTimerService.start(hostId, gameId, 120L)).thenReturn(GameActionResult.Success())
        val response = controller.startTimer(gameId, TimerStartRequest(120L), authentication)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `startTimer returns 400 for host during disallowed phase`() {
        whenever(hostTimerService.start(hostId, gameId, 60L))
            .thenReturn(GameActionResult.Rejected("Timer not allowed in current phase/sub-phase"))
        val response = controller.startTimer(gameId, TimerStartRequest(60L), authentication)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `startTimer returns 400 for invalid duration`() {
        whenever(hostTimerService.start(hostId, gameId, 30L))
            .thenReturn(GameActionResult.Rejected("durationSeconds must be 60 or 120"))
        val response = controller.startTimer(gameId, TimerStartRequest(30L), authentication)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `startTimer returns 400 for non-host`() {
        whenever(authentication.principal).thenReturn(guestId)
        whenever(hostTimerService.start(guestId, gameId, 60L))
            .thenReturn(GameActionResult.Rejected("Only the host can control the timer"))
        val response = controller.startTimer(gameId, TimerStartRequest(60L), authentication)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    // ── stopTimer ─────────────────────────────────────────────────────────────

    @Test
    fun `stopTimer returns 200 for host`() {
        whenever(hostTimerService.stop(hostId, gameId)).thenReturn(GameActionResult.Success())
        val response = controller.stopTimer(gameId, authentication)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `stopTimer returns 400 for non-host`() {
        whenever(authentication.principal).thenReturn(guestId)
        whenever(hostTimerService.stop(guestId, gameId))
            .thenReturn(GameActionResult.Rejected("Only the host can control the timer"))
        val response = controller.stopTimer(gameId, authentication)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
