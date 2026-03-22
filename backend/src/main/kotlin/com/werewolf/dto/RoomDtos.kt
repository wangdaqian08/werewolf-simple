package com.werewolf.dto

import com.werewolf.model.PlayerRole

data class RoomConfigRequest(
    val totalPlayers: Int = 6,
    val roles: List<PlayerRole> = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER),
)

data class CreateRoomRequest(val config: RoomConfigRequest = RoomConfigRequest())

data class JoinRoomRequest(val roomCode: String)

data class RoomPlayerDto(
    val userId: String,
    val nickname: String,
    val avatar: String?,
    val seatIndex: Int?,
    val status: String,
    val isHost: Boolean,
)

data class RoomConfigDto(val totalPlayers: Int, val roles: List<PlayerRole>)

data class RoomDto(
    val roomId: String,
    val roomCode: String,
    val hostId: String,
    val status: String,
    val players: List<RoomPlayerDto>,
    val config: RoomConfigDto,
)
