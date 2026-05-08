package com.werewolf.dto

import com.werewolf.model.PlayerRole
import com.werewolf.model.WinConditionMode
import jakarta.validation.constraints.Size

data class RoomConfigRequest(
    val totalPlayers: Int = 6,
    val roles: List<PlayerRole> = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER),
    val hasSheriff: Boolean = true,
    val winCondition: WinConditionMode = WinConditionMode.CLASSIC,
    val bgmTrack: String? = null,
)

data class CreateRoomRequest(
    val config: RoomConfigRequest = RoomConfigRequest(),
    /**
     * Optional per-room nickname override. When present and non-blank, this is
     * the name shown on the host's RoomPlayer for this room only. Trimmed
     * server-side; whitespace-only is treated as null. Max 50 characters
     * (matches User.nickname).
     */
    @field:Size(max = 50, message = "Nickname must be at most 50 characters")
    val nickname: String? = null,
)

data class JoinRoomRequest(
    val roomCode: String,
    /** Same semantics as CreateRoomRequest.nickname. */
    @field:Size(max = 50, message = "Nickname must be at most 50 characters")
    val nickname: String? = null,
)

data class SetReadyRequest(val ready: Boolean, val roomId: Int)
data class ClaimSeatRequest(val seatIndex: Int, val roomId: Int)
data class KickPlayerRequest(val roomId: Int, val targetUserId: String)

data class RoomPlayerDto(
    val userId: String,
    val nickname: String,
    val avatar: String?,
    val seatIndex: Int?,
    val status: String,
    val isHost: Boolean,
)

data class RoomConfigDto(val totalPlayers: Int, val roles: List<PlayerRole>, val hasSheriff: Boolean = true, val winCondition: WinConditionMode = WinConditionMode.CLASSIC, val bgmTrack: String? = null)

data class RoomDto(
    val roomId: String,
    val roomCode: String,
    val hostId: String,
    val status: String,
    val players: List<RoomPlayerDto>,
    val config: RoomConfigDto,
    val activeGameId: Int? = null,
)
