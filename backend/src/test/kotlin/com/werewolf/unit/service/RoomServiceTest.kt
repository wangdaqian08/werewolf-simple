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
    @Mock(strictness = org.mockito.Mock.Strictness.LENIENT) lateinit var bgmRegistry: com.werewolf.controller.BgmTrackRegistry
    @Mock lateinit var perkActivationRepository: com.werewolf.repository.PerkActivationRepository
    @Mock lateinit var perkRepository: com.werewolf.repository.PerkRepository
    @Mock lateinit var perkService: com.werewolf.service.PerkService
    @InjectMocks lateinit var roomService: RoomService

    @org.junit.jupiter.api.BeforeEach
    fun stubBgmRegistry() {
        whenever(bgmRegistry.isValidTrack(anyOrNull())).thenReturn(true)
    }

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
    fun `createRoom - generates a 3-digit numeric room code`() {
        whenever(roomRepository.findActiveByRoomCode(any())).thenReturn(Optional.empty())
        whenever(roomRepository.save(any<Room>())).thenAnswer {
            val r = it.arguments[0] as Room
            val f = Room::class.java.getDeclaredField("roomId"); f.isAccessible = true; f.set(r, 1)
            r
        }
        whenever(roomPlayerRepository.save(any<RoomPlayer>())).thenAnswer { it.arguments[0] }
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.createRoom(hostId, "Host", null, RoomConfigRequest())

        val captor = argumentCaptor<Room>()
        verify(roomRepository).save(captor.capture())
        assertThat(captor.firstValue.roomCode).matches("\\d{3}")
    }

    @Test
    fun `createRoom - retries code generation when the code is taken by an active room`() {
        // First random code collides with an active room, second is free → reuse
        // works without a globally-unique constraint.
        whenever(roomRepository.findActiveByRoomCode(any()))
            .thenReturn(Optional.of(room()), Optional.empty())
        whenever(roomRepository.save(any<Room>())).thenAnswer {
            val r = it.arguments[0] as Room
            val f = Room::class.java.getDeclaredField("roomId"); f.isAccessible = true; f.set(r, 1)
            r
        }
        whenever(roomPlayerRepository.save(any<RoomPlayer>())).thenAnswer { it.arguments[0] }
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.createRoom(hostId, "Host", null, RoomConfigRequest())

        verify(roomRepository, atLeast(2)).findActiveByRoomCode(any())
        verify(roomRepository).save(any<Room>())
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
    fun `createRoom - persists wolfCount and rejects out-of-bounds value`() {
        whenever(roomRepository.save(any<Room>())).thenAnswer {
            val r = it.arguments[0] as Room
            val f = Room::class.java.getDeclaredField("roomId"); f.isAccessible = true; f.set(r, 1)
            r
        }
        whenever(roomPlayerRepository.save(any<RoomPlayer>())).thenAnswer { it.arguments[0] }
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        // Happy: 9 players + wolfCount=2 is at the lower bound.
        val cfg = RoomConfigRequest(totalPlayers = 9, wolfCount = 2, roles = listOf(PlayerRole.SEER))
        roomService.createRoom(hostId, "Host", null, cfg)

        val captor = argumentCaptor<Room>()
        verify(roomRepository).save(captor.capture())
        assertThat(captor.firstValue.wolfCount).isEqualTo(2)
    }

    @Test
    fun `createRoom - rejects wolfCount of 0`() {
        val cfg = RoomConfigRequest(totalPlayers = 6, wolfCount = 0, roles = listOf(PlayerRole.SEER))
        assertThatThrownBy { roomService.createRoom(hostId, "Host", null, cfg) }
            .isInstanceOf(InvalidRoleCompositionException::class.java)
            .hasMessageContaining("must be > 0")
    }

    @Test
    fun `createRoom - rejects negative wolfCount`() {
        val cfg = RoomConfigRequest(totalPlayers = 6, wolfCount = -1, roles = listOf(PlayerRole.SEER))
        assertThatThrownBy { roomService.createRoom(hostId, "Host", null, cfg) }
            .isInstanceOf(InvalidRoleCompositionException::class.java)
            .hasMessageContaining("must be > 0")
    }

    @Test
    fun `createRoom - rejects composition overflow (wolves + gods exceed seats)`() {
        val cfg = RoomConfigRequest(
            totalPlayers = 6,
            wolfCount = 2,
            roles = listOf(
                PlayerRole.SEER,
                PlayerRole.WITCH,
                PlayerRole.HUNTER,
                PlayerRole.GUARD,
                PlayerRole.IDIOT,
            ),
        )
        assertThatThrownBy { roomService.createRoom(hostId, "Host", null, cfg) }
            .isInstanceOf(InvalidRoleCompositionException::class.java)
            .hasMessageContaining("overflow")
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
        whenever(roomRepository.findActiveByRoomCode("XXXX")).thenReturn(Optional.empty())

        assertThatThrownBy { roomService.joinRoom(userId, "Nick", null, "XXXX") }
            .isInstanceOf(RoomNotFoundException::class.java)
    }

    @Test
    fun `joinRoom - throws RoomNotOpenException when NEW user joins IN_GAME room`() {
        // The WAITING guard only applies to brand-new joiners. Existing
        // members (already in room_players) bypass it — see the rejoin
        // tests below.
        val room = room(status = RoomStatus.IN_GAME)
        whenever(roomRepository.findActiveByRoomCode("ABCD")).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, userId)).thenReturn(Optional.empty())

        assertThatThrownBy { roomService.joinRoom(userId, "Nick", null, "ABCD") }
            .isInstanceOf(RoomNotOpenException::class.java)
    }

    @Test
    fun `joinRoom - existing member can rejoin IN_GAME room (mid-game resume)`() {
        // Player who is already in room_players (whether they disconnected,
        // backgrounded their phone, or just refreshed) re-runs joinRoom and
        // gets back the current room snapshot — even after the host has
        // started the game.
        val room = room(status = RoomStatus.IN_GAME)
        whenever(roomRepository.findActiveByRoomCode("ABCD")).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, userId))
            .thenReturn(Optional.of(RoomPlayer(roomId = 1, userId = userId)))
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(
            listOf(RoomPlayer(roomId = 1, userId = userId))
        )
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        val result = roomService.joinRoom(userId, "Nick", null, "ABCD")

        verify(roomPlayerRepository, never()).save(any<RoomPlayer>())
        assertThat(result.roomCode).isEqualTo("ABCD")
        assertThat(result.status).isEqualTo("IN_GAME")
    }

    @Test
    fun `joinRoom - throws RoomFullException when room is full`() {
        val room = room(totalPlayers = 2)
        whenever(roomRepository.findActiveByRoomCode("ABCD")).thenReturn(Optional.of(room))
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
        whenever(roomRepository.findActiveByRoomCode("ABCD")).thenReturn(Optional.of(room))
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
        whenever(roomRepository.findActiveByRoomCode("ABCD")).thenReturn(Optional.of(room))
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

    // ── findActiveRoomForUser (reconnect / quick-rejoin) ───────────────────────

    @Test
    fun `findActiveRoomForUser returns the user's active room`() {
        whenever(roomRepository.findActiveRoomsForUser(userId)).thenReturn(listOf(room()))
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        val result = roomService.findActiveRoomForUser(userId)

        assertThat(result).isNotNull
        assertThat(result!!.roomCode).isEqualTo("ABCD")
    }

    @Test
    fun `findActiveRoomForUser returns null when the user has no active room`() {
        whenever(roomRepository.findActiveRoomsForUser(userId)).thenReturn(emptyList())

        assertThat(roomService.findActiveRoomForUser(userId)).isNull()
    }

    // ── kickPlayer ───────────────────────────────────────────────────────────

    @Test
    fun `kickPlayer - throws RoomNotFoundException when room missing`() {
        whenever(roomRepository.findById(999)).thenReturn(Optional.empty())

        assertThatThrownBy { roomService.kickPlayer(hostId, 999, userId) }
            .isInstanceOf(RoomNotFoundException::class.java)
    }

    @Test
    fun `kickPlayer - throws RoomNotOpenException after game has started`() {
        val room = room(status = RoomStatus.IN_GAME)
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room))

        assertThatThrownBy { roomService.kickPlayer(hostId, 1, userId) }
            .isInstanceOf(RoomNotOpenException::class.java)
    }

    @Test
    fun `kickPlayer - throws NotHostException when caller is not the host`() {
        val room = room()
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room))

        assertThatThrownBy { roomService.kickPlayer(userId, 1, "u2") }
            .isInstanceOf(NotHostException::class.java)
    }

    @Test
    fun `kickPlayer - throws CannotKickHostException when host targets themselves`() {
        val room = room()
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room))

        assertThatThrownBy { roomService.kickPlayer(hostId, 1, hostId) }
            .isInstanceOf(CannotKickHostException::class.java)
    }

    @Test
    fun `kickPlayer - throws PlayerNotInRoomException when target is not in room`() {
        val room = room()
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, userId)).thenReturn(Optional.empty())

        assertThatThrownBy { roomService.kickPlayer(hostId, 1, userId) }
            .isInstanceOf(PlayerNotInRoomException::class.java)
    }

    @Test
    fun `kickPlayer - deletes target row and broadcasts PLAYER_KICKED + ROOM_UPDATE`() {
        val room = room()
        val targetRow = RoomPlayer(roomId = 1, userId = userId)
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room))
        whenever(roomPlayerRepository.findByRoomIdAndUserId(1, userId)).thenReturn(Optional.of(targetRow))
        whenever(roomPlayerRepository.findByRoomId(1)).thenReturn(
            listOf(RoomPlayer(roomId = 1, userId = hostId))  // host left after kick
        )
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())

        roomService.kickPlayer(hostId, 1, userId)

        verify(roomPlayerRepository).delete(targetRow)
        verify(stompPublisher).broadcastRoomAfterCommit(eq(1), argThat<Map<String, Any>> {
            this["type"] == "PLAYER_KICKED" &&
            (this["payload"] as? Map<*, *>)?.get("userId") == userId
        })
        verify(stompPublisher).broadcastRoomAfterCommit(eq(1), argThat<Map<String, Any>> {
            this["type"] == "ROOM_UPDATE"
        })
    }
}
