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
class NightWaitingSchedulerTest {

    @Mock
    private lateinit var nightOrchestrator: NightOrchestrator

    @Mock
    private lateinit var stompPublisher: StompPublisher

    private lateinit var nightWaitingScheduler: NightWaitingScheduler

    private val gameId = 1

    @BeforeEach
    fun setUp() {
        nightWaitingScheduler = NightWaitingScheduler(nightOrchestrator, stompPublisher)
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
}