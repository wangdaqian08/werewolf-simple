package com.werewolf.service

import com.werewolf.game.role.RoleRules
import com.werewolf.model.Game
import com.werewolf.model.GamePhase
import com.werewolf.model.GamePlayer
import com.werewolf.model.GravekeeperResult
import com.werewolf.model.LearnedSkill
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole

/**
 * Private, per-role record of past night actions, shown to the player from the
 * role card ("历史记录"). Only alive players whose role has a night tool get one:
 *
 *  - SEER:  every check (round, player, result)
 *  - GUARD: every protected player (null target = skipped)
 *  - WOLF:  every wolf-team kill (null target = 空刀), for the pack incl. 白狼王
 *  - MECHANICAL_WOLF: its own learn / check / poison / protect, and the kills of the nights
 *           it was the lone killer; never the pack's kills
 *  - MEDIUM: every check with the role it was told
 *  - GRAVEKEEPER: every vote-out it was told the camp of (a night with no vote-out has no entry)
 *  - WITCH: every night's wolf victim (null target = 空刀) she was told while
 *           holding the antidote, then the antidote save + poison (round, player)
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

        fun deadAt(p: GamePlayer, day: Int) = (p.diedDay ?: Int.MAX_VALUE) < day
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
            me.role == PlayerRole.MEDIUM -> mapOf(
                "role" to "MEDIUM",
                "entries" to done.filter { it.mediumCheckedUserId != null }.map {
                    entry(it.dayNumber, "CHECK", it.mediumCheckedUserId) + ("role" to it.mediumResultRole)
                },
            )
            me.role == PlayerRole.GRAVEKEEPER -> mapOf(
                "role" to "GRAVEKEEPER",
                "entries" to done.filter { it.gravekeeperExiledUserId != null }.map {
                    entry(it.dayNumber, "GRAVE", it.gravekeeperExiledUserId) + ("isWerewolf" to (it.gravekeeperResult == GravekeeperResult.WOLF))
                },
            )
            me.role == PlayerRole.MECHANICAL_WOLF -> mapOf(
                "role" to "MECHANICAL_WOLF",
                "entries" to done.flatMap { n ->
                    val loneKiller = RoleRules.wolfKillers(players.filter { !deadAt(it, n.dayNumber) }).any { it.userId == me.userId }
                    listOfNotNull(
                        n.mechLearnTargetUserId?.let { entry(n.dayNumber, "LEARN", it) + ("role" to me.learnedRole) },
                        n.mechCheckTargetUserId?.let {
                            // Only the role-check skill is told a role; the alignment check is told wolf or not.
                            val result = if (me.learnedRole?.learnedSkill == LearnedSkill.CHECK_ROLE)
                                "role" to n.mechCheckResultRole
                            else "isWerewolf" to (n.mechCheckResultRole?.isWolf ?: false)
                            entry(n.dayNumber, "CHECK", it) + result
                        },
                        n.mechPoisonTargetUserId?.let { entry(n.dayNumber, "POISON", it) },
                        n.mechGuardTargetUserId?.let { entry(n.dayNumber, "PROTECT", it) },
                        if (loneKiller) entry(n.dayNumber, "KILL", n.wolfTargetUserId) else null,
                    )
                },
            )
            me.role.isPackWolf -> mapOf(
                "role" to "WOLF",
                "entries" to done.map { entry(it.dayNumber, "KILL", it.wolfTargetUserId) },
            )
            me.role == PlayerRole.WITCH -> {
                // Mirrors GameService's WITCH_ACT view: the victim is told only while she holds the antidote.
                val antidoteNight = done.firstOrNull { it.witchAntidoteUsed }?.dayNumber ?: Int.MAX_VALUE
                mapOf(
                    "role" to "WITCH",
                    "entries" to done.flatMap { n ->
                        listOfNotNull(
                            when {
                                n.witchAntidoteUsed -> entry(n.dayNumber, "ANTIDOTE", n.wolfTargetUserId)
                                n.dayNumber < antidoteNight -> entry(n.dayNumber, "ATTACKED", n.wolfTargetUserId)
                                else -> null
                            },
                            n.witchPoisonTargetUserId?.let { entry(n.dayNumber, "POISON", it) },
                        )
                    },
                )
            }
            else -> null
        }
    }
}
