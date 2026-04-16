package com.werewolf.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.werewolf.model.ActionType

data class StartGameRequest(val roomId: Int)

data class GameActionRequestDto(
    val gameId: Int,
    val actionType: ActionType,
    @JsonAlias("targetId")
    val targetUserId: String? = null,
    val payload: Map<String, Any?>? = null,
)

data class ActionLogEntryDto(
    val id: Int,
    val eventType: String,
    val message: String,
    val targetUserId: String?,
    val createdAt: String?,
)
