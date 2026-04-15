package com.werewolf.unit.service

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.night.NightWaitingScheduler
import com.werewolf.model.AudioSequence
import com.werewolf.model.NightSubPhase
import com.werewolf.service.StompPublisher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class NightWaitingSchedulerTest {

    @Mock
    private lateinit var nightOrchestrator: NightOrchestrator

    @Mock
    private lateinit var stompPublisher: StompPublisher

    @Mock
    private lateinit var contextLoader: com.werewolf.service.GameContextLoader

    @Mock
    private lateinit var nightPhaseRepository: com.werewolf.repository.NightPhaseRepository

    private lateinit var nightWaitingScheduler: NightWaitingScheduler

    private val gameId = 1

    @BeforeEach
    fun setUp() {
        nightWaitingScheduler = NightWaitingScheduler(nightOrchestrator, stompPublisher, contextLoader, nightPhaseRepository, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
    }

    @AfterEach
    fun tearDown() {
        // No cleanup needed with Mockito
    }

    @Test
    fun `scheduleAdvance - schedules 5 second delay and calls advanceFromWaiting when targetSubPhase is null`() {
        
        nightWaitingScheduler.scheduleAdvance(gameId, 5_000L, null)
        
        // Should complete after approximately 5 seconds
        Thread.sleep(5_500) // Wait for the async call to complete
        
        // Verify advanceFromWaiting was called after delay
        verify(nightOrchestrator).advanceFromWaiting(gameId)
    }

    @Test
    fun `scheduleAdvance - schedules 5 second delay and calls advanceToSubPhase when targetSubPhase is not null`() {
        val targetSubPhase = NightSubPhase.SEER_PICK
        
        nightWaitingScheduler.scheduleAdvance(gameId, 5_000L, targetSubPhase)
        
        // Should complete after approximately 5 seconds
        Thread.sleep(5_500) // Wait for the async call to complete
        
        // Verify advanceToSubPhase was called after delay
        verify(nightOrchestrator).advanceToSubPhase(gameId, targetSubPhase)
    }

    @Test
    fun `scheduleAdvance - logs error when advanceFromWaiting throws exception`() {
        
        // Mock exception from orchestrator
        whenever(nightOrchestrator.advanceFromWaiting(gameId)) 
            .thenThrow(RuntimeException("Test exception"))
        
        nightWaitingScheduler.scheduleAdvance(gameId, 100L, null)
        
        // Should not throw exception to caller, just log it
        Thread.sleep(500) // Wait for the async call to complete
        
        // Verify the method was still called despite the exception
        verify(nightOrchestrator).advanceFromWaiting(gameId)
    }

    @Test
    fun `scheduleAudioDelay - schedules audio sequence broadcast after delay`() {
        val audioSequence = AudioSequence(
            id = "test-sequence-1",
            phase = com.werewolf.model.GamePhase.NIGHT,
            subPhase = NightSubPhase.SEER_PICK.name,
            audioFiles = listOf("seer_open_eyes.mp3"),
            priority = 1,
            timestamp = System.currentTimeMillis()
        )
        
        nightWaitingScheduler.scheduleAudioDelay(gameId, audioSequence, 2_000L)
        
        // Should complete after approximately 2 seconds
        Thread.sleep(2_500) // Wait for the async call to complete
        
        // Verify AudioSequence event was broadcast
        verify(stompPublisher).broadcastGame(eq(gameId), argThat { event ->
            event is com.werewolf.game.DomainEvent.AudioSequence && 
            event.audioSequence.id == audioSequence.id
        })
    }

    @Test
    fun `scheduleAudioDelay - logs error when broadcasting throws exception`() {
        val audioSequence = AudioSequence(
            id = "test-sequence-1",
            phase = com.werewolf.model.GamePhase.NIGHT,
            subPhase = NightSubPhase.SEER_PICK.name,
            audioFiles = listOf("seer_open_eyes.mp3"),
            priority = 1,
            timestamp = System.currentTimeMillis()
        )

        // Mock exception from stompPublisher
        whenever(stompPublisher.broadcastGame(eq(gameId), any()))
            .thenThrow(RuntimeException("Test exception"))

        nightWaitingScheduler.scheduleAudioDelay(gameId, audioSequence, 100L)

        // Should not throw exception to caller, just log it
        Thread.sleep(500) // Wait for the async call to complete

        // Verify the method was still called despite the exception
        verify(stompPublisher).broadcastGame(eq(gameId), any())
    }

    // ── Bug fix: scheduleDeadRoleAudio must use dayNumber to find correct NightPhase ──

    @Test
    fun `scheduleDeadRoleAudio - uses dayNumber to find correct NightPhase for multi-night game`() {
        val dayNumber = 2
        val audioSequence = AudioSequence(
            id = "dead-role-audio",
            phase = com.werewolf.model.GamePhase.NIGHT,
            subPhase = NightSubPhase.SEER_PICK.name,
            audioFiles = listOf("seer_open_eyes.mp3", "seer_close_eyes.mp3"),
            priority = 5,
            timestamp = System.currentTimeMillis()
        )

        // Setup: two NightPhase records exist (night 1 and night 2)
        val nightPhaseNight1 = com.werewolf.model.NightPhase(
            gameId = gameId, dayNumber = 1, subPhase = NightSubPhase.COMPLETE,
        )
        val nightPhaseNight2 = com.werewolf.model.NightPhase(
            gameId = gameId, dayNumber = 2, subPhase = NightSubPhase.SEER_RESULT,
        )

        // Mock: findByGameIdAndDayNumber returns the correct night phase
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber))
            .thenReturn(java.util.Optional.of(nightPhaseNight2))

        // Mock: contextLoader.load returns a dummy context (needed for resolveNightKills)
        val dummyGame = com.werewolf.model.Game(roomId = 1, hostUserId = "host")
        val dummyRoom = com.werewolf.model.Room(roomCode = "ABCD", hostUserId = "host", totalPlayers = 6)
        val dummyCtx = com.werewolf.game.GameContext(dummyGame, dummyRoom, emptyList(), nightPhase = nightPhaseNight2)
        whenever(contextLoader.load(gameId)).thenReturn(dummyCtx)

        // Schedule dead role audio with autoAdvanceToDay = true
        nightWaitingScheduler.scheduleDeadRoleAudio(
            gameId = gameId,
            dayNumber = dayNumber,
            audioSequence = audioSequence,
            delayMs = 100L, // Short delay for test
            nextSubPhase = null,
            autoAdvanceToDay = true,
        )

        // Wait for async chain to complete (2s open audio + delay + 2s close audio + processing)
        Thread.sleep(5_000)

        // Verify: scheduler used findByGameIdAndDayNumber (NOT findByGameId[0])
        verify(nightPhaseRepository).findByGameIdAndDayNumber(gameId, dayNumber)
        verify(nightPhaseRepository, never()).findByGameId(gameId)

        // Verify: resolveNightKills was called with the correct night 2 phase
        verify(nightOrchestrator).resolveNightKills(any(), eq(nightPhaseNight2))
    }

    @Test
    fun `scheduleDeadRoleAudio - advances to next sub-phase using correct dayNumber`() {
        val dayNumber = 3
        val audioSequence = AudioSequence(
            id = "dead-role-audio",
            phase = com.werewolf.model.GamePhase.NIGHT,
            subPhase = NightSubPhase.WITCH_ACT.name,
            audioFiles = listOf("witch_open_eyes.mp3", "witch_close_eyes.mp3"),
            priority = 5,
            timestamp = System.currentTimeMillis()
        )

        val nightPhaseNight3 = com.werewolf.model.NightPhase(
            gameId = gameId, dayNumber = 3, subPhase = NightSubPhase.WITCH_ACT,
        )

        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber))
            .thenReturn(java.util.Optional.of(nightPhaseNight3))

        // Schedule dead role audio with nextSubPhase (not last role)
        nightWaitingScheduler.scheduleDeadRoleAudio(
            gameId = gameId,
            dayNumber = dayNumber,
            audioSequence = audioSequence,
            delayMs = 100L,
            nextSubPhase = NightSubPhase.GUARD_PICK,
            autoAdvanceToDay = false,
        )

        // Wait for async chain: 2s open audio + 100ms pause + 2s close audio + 5s inter-role gap + processing
        Thread.sleep(12_000)

        // Verify: advance called with the current subPhase from correct night
        verify(nightPhaseRepository).findByGameIdAndDayNumber(gameId, dayNumber)
        verify(nightOrchestrator).advance(gameId, NightSubPhase.WITCH_ACT)
    }

    @Test
    fun `scheduleLastSpecialRoleCloseEyesAndAdvanceToDay - uses dayNumber to find correct NightPhase`() {
        val dayNumber = 2
        val nightPhaseNight2 = com.werewolf.model.NightPhase(
            gameId = gameId, dayNumber = 2, subPhase = NightSubPhase.GUARD_PICK,
        )

        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber))
            .thenReturn(java.util.Optional.of(nightPhaseNight2))

        // Mock: contextLoader.load for resolveNightKills
        val dummyGame = com.werewolf.model.Game(roomId = 1, hostUserId = "host")
        val dummyRoom = com.werewolf.model.Room(roomCode = "ABCD", hostUserId = "host", totalPlayers = 6)
        val dummyCtx = com.werewolf.game.GameContext(dummyGame, dummyRoom, emptyList(), nightPhase = nightPhaseNight2)
        whenever(contextLoader.load(gameId)).thenReturn(dummyCtx)

        // Mock: save returns the same object
        whenever(nightPhaseRepository.save(any<com.werewolf.model.NightPhase>()))
            .thenAnswer { it.arguments[0] }

        nightWaitingScheduler.scheduleLastSpecialRoleCloseEyesAndAdvanceToDay(
            gameId = gameId,
            dayNumber = dayNumber,
            closeEyesAudio = "guard_close_eyes.mp3",
        )

        // Wait for async chain: save + broadcast + 2s audio delay + processing
        Thread.sleep(5_000)

        // Verify: used findByGameIdAndDayNumber, not findByGameId[0]
        verify(nightPhaseRepository).findByGameIdAndDayNumber(gameId, dayNumber)
        verify(nightPhaseRepository, never()).findByGameId(gameId)

        // Verify: resolveNightKills called with correct night phase
        verify(nightOrchestrator).resolveNightKills(any(), eq(nightPhaseNight2))
    }
}