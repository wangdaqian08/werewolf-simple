package com.werewolf.service

import com.werewolf.model.Game
import com.werewolf.model.GamePhase
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole

/**
 * Private, per-role record of past night actions, shown to the player from the
 * role card ("历史记录"). Only alive players whose role has a night tool get one:
 *
 *  - SEER:  every check (round, player, result)
 *  - GUARD: every protected player (null target = skipped)
 *  - WOLF:  every wolf-team kill (null target = 空刀), for all wolves incl. 白狼王
 *  - WITCH: antidote save + poison (round, player), plus last night's victim
 *           while the antidote is still unused
 *
 * Built from the NightPhase rows; the night still in progress is excluded.
 */
object RoleHistoryBuilder {

    fun build(
        me: GamePlayer,
        game: Game,
        nights: List<NightPhase>,
        players: List<GamePlayer>,
        displayNameFor: (String) -> String,
    ): Map<String, Any?>? {
        if (!me.alive) return null
        val done = nights
            .filterNot { game.phase == GamePhase.NIGHT && it.dayNumber == game.dayNumber }
            .sortedBy { it.dayNumber }
        val seatOf = players.associate { it.userId to it.seatIndex }
        fun ref(userId: String?) = userId?.let {
            mapOf("userId" to it, "nickname" to displayNameFor(it), "seatIndex" to (seatOf[it] ?: 0))
        }
        fun entry(round: Int, action: String, target: String?) =
            mapOf("round" to round, "action" to action, "target" to ref(target))

        return when {
            me.role == PlayerRole.SEER -> mapOf(
                "role" to "SEER",
                "entries" to done.filter { it.seerCheckedUserId != null }.map {
                    entry(it.dayNumber, "CHECK", it.seerCheckedUserId) + ("isWerewolf" to (it.seerResultIsWerewolf ?: false))
                },
            )
            me.role == PlayerRole.GUARD -> mapOf(
                "role" to "GUARD",
                "entries" to done.map { entry(it.dayNumber, "GUARD", it.guardTargetUserId) },
            )
            me.role.isWolf -> mapOf(
                "role" to "WOLF",
                "entries" to done.map { entry(it.dayNumber, "KILL", it.wolfTargetUserId) },
            )
            me.role == PlayerRole.WITCH -> {
                val antidoteUnused = done.none { it.witchAntidoteUsed }
                val lastNight = done.lastOrNull()
                mapOf(
                    "role" to "WITCH",
                    "lastNightAttacked" to if (antidoteUnused && lastNight?.wolfTargetUserId != null) {
                        mapOf("round" to lastNight.dayNumber, "target" to ref(lastNight.wolfTargetUserId))
                    } else null,
                    "entries" to done.flatMap { n ->
                        listOfNotNull(
                            if (n.witchAntidoteUsed) entry(n.dayNumber, "ANTIDOTE", n.wolfTargetUserId) else null,
                            n.witchPoisonTargetUserId?.let { entry(n.dayNumber, "POISON", it) },
                        )
                    },
                )
            }
            else -> null
        }
    }
}
