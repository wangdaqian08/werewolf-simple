package com.werewolf.game.phase

import com.werewolf.model.GamePlayer
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinConditionMode
import com.werewolf.model.WinnerSide
import org.springframework.stereotype.Component

@Component
class WinConditionChecker {
    fun check(alivePlayers: List<GamePlayer>, mode: WinConditionMode = WinConditionMode.CLASSIC): WinnerSide? {
        val wolves = alivePlayers.count { it.role == PlayerRole.WEREWOLF }
        val others = alivePlayers.count { it.role != PlayerRole.WEREWOLF }
        return when {
            wolves == 0 -> WinnerSide.VILLAGER
            mode == WinConditionMode.HARD_MODE -> if (others == 0) WinnerSide.WEREWOLF else null
            else -> if (wolves >= others) WinnerSide.WEREWOLF else null // CLASSIC
        }
    }
}
