package com.werewolf.game.action

import com.werewolf.game.DomainEvent

sealed class GameActionResult {
    data class Success(val events: List<DomainEvent> = emptyList()) : GameActionResult()
    data class Rejected(val reason: String) : GameActionResult()
}
