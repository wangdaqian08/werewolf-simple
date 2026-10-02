package com.werewolf.unit.service

import com.werewolf.auth.AuthService
import com.werewolf.config.GameTimingProperties
import com.werewolf.controller.BgmTrackRegistry
import com.werewolf.model.*
import com.werewolf.repository.GameRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.PerkRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class RoomServiceLeaveTest {

    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var authService: AuthService
    @Mock lateinit var stompPublisher: StompPublisher
    @org.mockito.Spy val timing: GameTimingProperties = GameTimingProperties()
    @Mock(strictness = Mock.Strictness.LENIENT) lateinit var bgmRegistry: BgmTrackRegistry
    @Mock lateinit var perkActivationRepository: PerkActivationRepository
    @Mock lateinit var perkRepository: PerkRepository
    @Mock lateinit var perkService: PerkService
    @InjectMocks lateinit var roomService: RoomService

    private val hostId = "host:001"
    private val guestId = "guest:001"

    private fun room(status: RoomStatus = RoomStatus.WAITING, host: String = hostId) =
        Room(roomCode = "123", hostUserId = host, totalPlayers = 6, status = status).also {
            val f = Room::class.java.getDeclaredField("roomId"); f.isAccessible = true; f.set(it, 1)
        }

    private fun player(id: Int, userId: String, host: Boolean = false) =
        RoomPlayer(id = id, roomId = 1, userId = userId, host = host)

    @Test
    fun `leaveRoom - not in an active room is a no-op`() {
        whenever(roomRepository.findActiveRoomsForUser(guestId)).thenReturn(emptyList())

        roomService.leaveRoom(guestId)

        verify(roomPlayerRepository, never()).delete(any())
        verifyNoInteractions(stompPublisher)
    }

    @Test
    fun `leaveRoom - IN_GAME room never removes the player`() {
        whenever(roomRepository.findActiveRoomsForUser(guestId))
            .thenReturn(listOf(room(status = RoomStatus.IN_GAME)))

        roomService.leaveRoom(guestId)

        verify(roomPlayerRepository, never()).delete(any())
        verifyNoInteractions(stompPublisher)
    }

    @Test
    fun `leaveRoom - non-host leaves - row deleted, perks refunded, ROOM_UPDATE broadcast, host unchanged`() {
        val r = room()
        val guest = player(id = 2, userId = guestId)
        whenever(roomRepository.findActiveRoomsForUser(guestId)).thenReturn(listOf(r))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, guestId)).thenReturn(Optional.of(guest))
        whenever(roomPlayerRepository.findByRoomId(1))
            .thenReturn(listOf(player(id = 1, userId = hostId, host = true)))
        whenever(userRepository.findAllById(any<List<String>>())).thenReturn(emptyList())

        roomService.leaveRoom(guestId)

        verify(roomPlayerRepository).delete(guest)
        verify(perkService).refundActiveForUser(1, guestId)
        verify(stompPublisher).broadcastRoomAfterCommit(eq(1), any())
        assertThat(r.hostUserId).isEqualTo(hostId)
        verify(roomRepository, never()).save(any())
    }

    @Test
    fun `leaveRoom - host leaves with others - transfers to earliest-joined remaining player`() {
        val r = room(host = hostId)
        val host = player(id = 1, userId = hostId, host = true)
        val early = player(id = 2, userId = "guest:early")
        val late = player(id = 3, userId = "guest:late")
        whenever(roomRepository.findActiveRoomsForUser(hostId)).thenReturn(listOf(r))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, hostId)).thenReturn(Optional.of(host))
        // Returned unordered on purpose: transfer must pick the lowest id, not the first row.
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(listOf(late, early))
        whenever(userRepository.findAllById(any<List<String>>())).thenReturn(emptyList())

        roomService.leaveRoom(hostId)

        verify(roomPlayerRepository).delete(host)
        assertThat(early.host).isTrue()
        verify(roomPlayerRepository).save(early)
        assertThat(r.hostUserId).isEqualTo("guest:early")
        verify(roomRepository).save(r)
        verify(stompPublisher).broadcastRoomAfterCommit(eq(1), any())
    }

    @Test
    fun `leaveRoom - last player leaving closes the room and does not broadcast`() {
        val r = room(host = hostId)
        val host = player(id = 1, userId = hostId, host = true)
        whenever(roomRepository.findActiveRoomsForUser(hostId)).thenReturn(listOf(r))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, hostId)).thenReturn(Optional.of(host))
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())

        roomService.leaveRoom(hostId)

        verify(roomPlayerRepository).delete(host)
        assertThat(r.status).isEqualTo(RoomStatus.CLOSED)
        assertThat(r.closedAt).isNotNull()
        verify(roomRepository).save(r)
        verifyNoInteractions(stompPublisher)
    }
}
