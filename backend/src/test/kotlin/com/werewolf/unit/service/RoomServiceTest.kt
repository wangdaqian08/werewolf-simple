package com.werewolf.unit.service

import com.werewolf.auth.AuthService
import com.werewolf.dto.RoomConfigRequest
import com.werewolf.model.*
import com.werewolf.repository.GameRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class RoomServiceTest {

    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var authService: AuthService
    @Mock lateinit var stompPublisher: StompPublisher
    @org.mockito.Spy val timing: com.werewolf.config.GameTimingProperties = com.werewolf.config.GameTimingProperties()
    @InjectMocks lateinit var roomService: RoomService

    private val userId = "user:001"
    private val hostId = "host:001"

    private fun room(
        roomId: Int = 1,
        status: RoomStatus = RoomStatus.WAITING,
        totalPlayers: Int = 6,
    ) = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = totalPlayers, status = status).also {
        val f = Room::class.java.getDeclaredField("roomId"); f.isAccessible = true; f.set(it, roomId)
    }

    // ── createRoom ───────────────────────────────────────────────────────────

    @Test
    fun `createRoom - persists room with correct role config flags`() {
        val cfg = RoomConfigRequest(
            totalPlayers = 8,
            roles = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.GUARD),
            hasSheriff = false,
            winCondition = WinConditionMode.HARD_MODE,
        )
        whenever(roomRepository.save(any<Room>())).thenAnswer {
            val r = it.arguments[0] as Room
            val f = Room::class.java.getDeclaredField("roomId"); f.isAccessible = true; f.set(r, 1)
            r
        }
        whenever(roomPlayerRepository.save(any<RoomPlayer>())).thenAnswer { it.arguments[0] }
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        val result = roomService.createRoom(hostId, "Host", null, cfg)

        val roomCaptor = argumentCaptor<Room>()
        verify(roomRepository).save(roomCaptor.capture())
        val saved = roomCaptor.firstValue
        assertThat(saved.totalPlayers).isEqualTo(8)
        assertThat(saved.hasSeer).isTrue()
        assertThat(saved.hasWitch).isTrue()
        assertThat(saved.hasGuard).isTrue()
        assertThat(saved.hasHunter).isFalse()
        assertThat(saved.hasIdiot).isFalse()
        assertThat(saved.hasSheriff).isFalse()
        assertThat(saved.winCondition).isEqualTo(WinConditionMode.HARD_MODE)
    }

    @Test
    fun `createRoom - host is added as room player with host=true`() {
        whenever(roomRepository.save(any<Room>())).thenAnswer {
            val r = it.arguments[0] as Room
            val f = Room::class.java.getDeclaredField("roomId"); f.isAccessible = true; f.set(r, 1)
            r
        }
        whenever(roomPlayerRepository.save(any<RoomPlayer>())).thenAnswer { it.arguments[0] }
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.createRoom(hostId, "Host", null, RoomConfigRequest())

        val captor = argumentCaptor<RoomPlayer>()
        verify(roomPlayerRepository).save(captor.capture())
        assertThat(captor.firstValue.userId).isEqualTo(hostId)
        assertThat(captor.firstValue.host).isTrue()
    }

    @Test
    fun `createRoom - does not broadcast STOMP on create`() {
        whenever(roomRepository.save(any<Room>())).thenAnswer {
            val r = it.arguments[0] as Room
            val f = Room::class.java.getDeclaredField("roomId"); f.isAccessible = true; f.set(r, 1)
            r
        }
        whenever(roomPlayerRepository.save(any<RoomPlayer>())).thenAnswer { it.arguments[0] }
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.createRoom(hostId, "Host", null, RoomConfigRequest())

        verifyNoInteractions(stompPublisher)
    }

    // ── joinRoom ─────────────────────────────────────────────────────────────

    @Test
    fun `joinRoom - throws RoomNotFoundException when room code not found`() {
        whenever(roomRepository.findByRoomCode("XXXX")).thenReturn(Optional.empty())

        assertThatThrownBy { roomService.joinRoom(userId, "Nick", null, "XXXX") }
            .isInstanceOf(RoomNotFoundException::class.java)
    }

    @Test
    fun `joinRoom - throws RoomNotOpenException when room is IN_GAME`() {
        val room = room(status = RoomStatus.IN_GAME)
        whenever(roomRepository.findByRoomCode("ABCD")).thenReturn(Optional.of(room))

        assertThatThrownBy { roomService.joinRoom(userId, "Nick", null, "ABCD") }
            .isInstanceOf(RoomNotOpenException::class.java)
    }

    @Test
    fun `joinRoom - throws RoomFullException when room is full`() {
        val room = room(totalPlayers = 2)
        whenever(roomRepository.findByRoomCode("ABCD")).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, userId)).thenReturn(Optional.empty())
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(
            listOf(RoomPlayer(roomId = 1, userId = hostId), RoomPlayer(roomId = 1, userId = "u2"))
        )

        assertThatThrownBy { roomService.joinRoom(userId, "Nick", null, "ABCD") }
            .isInstanceOf(RoomFullException::class.java)
    }

    @Test
    fun `joinRoom - idempotent when player already in room`() {
        val room = room()
        whenever(roomRepository.findByRoomCode("ABCD")).thenReturn(Optional.of(room))
        // Player already exists
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, userId))
            .thenReturn(Optional.of(RoomPlayer(roomId = 1, userId = userId)))
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(
            listOf(RoomPlayer(roomId = 1, userId = userId))
        )
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        val result = roomService.joinRoom(userId, "Nick", null, "ABCD")

        // No new player saved (idempotent)
        verify(roomPlayerRepository, never()).save(any<RoomPlayer>())
        assertThat(result.roomCode).isEqualTo("ABCD")
    }

    @Test
    fun `joinRoom - new player saved when not already in room`() {
        val room = room()
        whenever(roomRepository.findByRoomCode("ABCD")).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, userId)).thenReturn(Optional.empty())
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(
            listOf(RoomPlayer(roomId = 1, userId = hostId)) // 1 player, room not full
        )
        whenever(roomPlayerRepository.save(any<RoomPlayer>())).thenAnswer { it.arguments[0] }
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.joinRoom(userId, "Nick", null, "ABCD")

        val captor = argumentCaptor<RoomPlayer>()
        verify(roomPlayerRepository).save(captor.capture())
        assertThat(captor.firstValue.userId).isEqualTo(userId)
        assertThat(captor.firstValue.roomId).isEqualTo(1)
    }

    // ── getRoom ──────────────────────────────────────────────────────────────

    @Test
    fun `getRoom - throws RoomNotFoundException when room not found`() {
        whenever(roomRepository.findById(999)).thenReturn(Optional.empty())

        assertThatThrownBy { roomService.getRoom(999) }
            .isInstanceOf(RoomNotFoundException::class.java)
    }

    @Test
    fun `getRoom - returns room DTO when room exists`() {
        val room = room()
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        val result = roomService.getRoom(1)

        assertThat(result.roomCode).isEqualTo("ABCD")
        assertThat(result.hostId).isEqualTo(hostId)
    }
}
