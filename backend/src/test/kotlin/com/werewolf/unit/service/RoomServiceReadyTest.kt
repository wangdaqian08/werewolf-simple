package com.werewolf.unit.service

import com.werewolf.auth.AuthService
import com.werewolf.model.ReadyStatus
import com.werewolf.model.Room
import com.werewolf.model.RoomPlayer
import com.werewolf.model.RoomStatus
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.*
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class RoomServiceReadyTest {

    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var authService: AuthService
    @Mock lateinit var stompPublisher: StompPublisher
    @org.mockito.Spy val timing: com.werewolf.config.GameTimingProperties = com.werewolf.config.GameTimingProperties()
    @InjectMocks lateinit var roomService: RoomService

    private val roomId = 1
    private val userId = "guest:abc"

    private fun waitingRoom() = Room(
        roomId = roomId,
        roomCode = "ABCD",
        hostUserId = "host:123",
        totalPlayers = 6,
    ).also { it.status = RoomStatus.WAITING }

    private fun seatedPlayer() = RoomPlayer(roomId = roomId, userId = userId, seatIndex = 3)
    private fun unseatedPlayer() = RoomPlayer(roomId = roomId, userId = userId)

    // ── setReady ──────────────────────────────────────────────────────────────

    @Test
    fun `setReady calls setReadyIfSeated and broadcasts ROOM_UPDATE`() {
        val room = waitingRoom()
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.setReadyIfSeated(roomId, userId)).thenReturn(1)
        whenever(roomPlayerRepository.findByRoomId(roomId)).thenReturn(listOf(seatedPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.setReady(userId, roomId, ready = true)

        verify(roomPlayerRepository).setReadyIfSeated(roomId, userId)
        verify(stompPublisher).broadcastRoom(eq(roomId), any())
    }

    @Test
    fun `setReady calls updateStatus with NOT_READY on cancel and broadcasts`() {
        val room = waitingRoom()
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.updateStatus(roomId, userId, ReadyStatus.NOT_READY)).thenReturn(1)
        whenever(roomPlayerRepository.findByRoomId(roomId)).thenReturn(listOf(seatedPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.setReady(userId, roomId, ready = false)

        verify(roomPlayerRepository).updateStatus(roomId, userId, ReadyStatus.NOT_READY)
        verify(stompPublisher).broadcastRoom(eq(roomId), any())
    }

    @Test
    fun `setReady throws RoomNotFoundException when room absent`() {
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.empty())

        assertThatThrownBy { roomService.setReady(userId, roomId, true) }
            .isInstanceOf(RoomNotFoundException::class.java)

        verifyNoInteractions(roomPlayerRepository)
    }

    @Test
    fun `setReady throws PlayerNotInRoomException when seat not claimed`() {
        val room = waitingRoom()
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.setReadyIfSeated(roomId, userId)).thenReturn(0)

        assertThatThrownBy { roomService.setReady(userId, roomId, true) }
            .isInstanceOf(PlayerNotInRoomException::class.java)

        verifyNoInteractions(stompPublisher)
    }

    @Test
    fun `setReady throws RoomNotOpenException when room is IN_GAME`() {
        val room = waitingRoom().also { it.status = RoomStatus.IN_GAME }
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))

        assertThatThrownBy { roomService.setReady(userId, roomId, true) }
            .isInstanceOf(RoomNotOpenException::class.java)

        verifyNoInteractions(roomPlayerRepository)
    }

    // ── claimSeat ─────────────────────────────────────────────────────────────

    @Test
    fun `claimSeat calls updateSeatIndex and broadcasts ROOM_UPDATE`() {
        val room = waitingRoom()
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.updateSeatIndex(roomId, userId, 2)).thenReturn(1)
        whenever(roomPlayerRepository.findByRoomId(roomId)).thenReturn(listOf(unseatedPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.claimSeat(userId, roomId, seatIndex = 2)

        verify(roomPlayerRepository).updateSeatIndex(roomId, userId, 2)
        verify(stompPublisher).broadcastRoom(eq(roomId), any())
    }

    @Test
    fun `claimSeat throws RoomNotOpenException when room is not WAITING`() {
        val room = waitingRoom().also { it.status = RoomStatus.IN_GAME }
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))

        assertThatThrownBy { roomService.claimSeat(userId, roomId, 2) }
            .isInstanceOf(RoomNotOpenException::class.java)

        verifyNoInteractions(roomPlayerRepository)
    }

    @Test
    fun `claimSeat throws PlayerNotInRoomException when player not in room`() {
        val room = waitingRoom()
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.updateSeatIndex(roomId, userId, 2)).thenReturn(0)

        assertThatThrownBy { roomService.claimSeat(userId, roomId, 2) }
            .isInstanceOf(PlayerNotInRoomException::class.java)

        verifyNoInteractions(stompPublisher)
    }
}
