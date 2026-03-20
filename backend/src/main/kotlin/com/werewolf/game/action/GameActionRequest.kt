package com.werewolf.game.action

import com.werewolf.model.ActionType

data class GameActionRequest(
    val gameId: Int,
    val actorUserId: String,
    val actionType: ActionType,
    val targetUserId: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)
