package com.werewolf.dto

import com.werewolf.model.ActionType

data class StartGameRequest(val roomId: Int)

data class GameActionRequestDto(
    val gameId: Int,
    val actionType: ActionType,
    val targetUserId: String? = null,
    val payload: Map<String, Any?>? = null,
)
