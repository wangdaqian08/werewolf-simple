package com.werewolf.dto

import com.werewolf.model.PlayerRole
import com.werewolf.model.WinConditionMode
import jakarta.validation.constraints.Size

data class RoomConfigRequest(
    val totalPlayers: Int = 6,
    /**
     * Required and must be > 0. The host explicitly sets this in the UI; there
     * is no server-side fallback — every caller of /api/room/create owns the
     * choice.
     */
    val wolfCount: Int = 2,
    val roles: List<PlayerRole> = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER),
    val hasSheriff: Boolean = true,
    val winCondition: WinConditionMode = WinConditionMode.CLASSIC,
    val bgmTrack: String? = null,
    val witchSelfSaveAllowed: Boolean = true,
    /** Whether players may activate paid perks in this room (host fairness toggle). */
    val perksAllowed: Boolean = true,
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

data class RoomConfigDto(val totalPlayers: Int, val wolfCount: Int, val roles: List<PlayerRole>, val hasSheriff: Boolean = true, val winCondition: WinConditionMode = WinConditionMode.CLASSIC, val bgmTrack: String? = null, val witchSelfSaveAllowed: Boolean = true, val perksAllowed: Boolean = true)

/** A live (ACTIVE) perk activation in the room — visible to every room member. */
data class PerkActivationDto(
    val userId: String,
    val perkCode: String,
    val perkName: String,
)

data class RoomDto(
    val roomId: String,
    val roomCode: String,
    val hostId: String,
    val status: String,
    val players: List<RoomPlayerDto>,
    val config: RoomConfigDto,
    val activeGameId: Int? = null,
    val perkActivations: List<PerkActivationDto> = emptyList(),
)
