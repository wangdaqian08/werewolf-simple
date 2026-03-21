package com.werewolf.game.phase

import com.werewolf.model.GamePlayer
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import org.springframework.stereotype.Component

@Component
class WinConditionChecker {
    fun check(alivePlayers: List<GamePlayer>): WinnerSide? {
        val wolves = alivePlayers.count { it.role == PlayerRole.WEREWOLF }
        val others = alivePlayers.count { it.role != PlayerRole.WEREWOLF }
        // TODO check idiot rules for winning
        return when {
            wolves == 0 -> WinnerSide.VILLAGER
            wolves >= others -> WinnerSide.WEREWOLF
            else -> null
        }
    }
}
