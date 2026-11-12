package com.werewolf.game

import com.werewolf.model.PlayerRole
import com.werewolf.model.Room
import com.werewolf.model.RolePickChoice
import kotlin.random.Random

object RoleDealer {

    fun roleList(room: Room, playerCount: Int): MutableList<PlayerRole> {
        val roles = mutableListOf<PlayerRole>()
        if (room.hasWhiteWolfKing) roles.add(PlayerRole.WHITE_WOLF_KING)
        if (room.hasBlackWolfKing) roles.add(PlayerRole.BLACK_WOLF_KING)
        if (room.hasMechanicalWolf) roles.add(PlayerRole.MECHANICAL_WOLF)
        while (roles.size < room.wolfCount) roles.add(PlayerRole.WEREWOLF)
        if (room.hasSeer) roles.add(PlayerRole.SEER)
        if (room.hasWitch) roles.add(PlayerRole.WITCH)
        if (room.hasHunter) roles.add(PlayerRole.HUNTER)
        if (room.hasGuard) roles.add(PlayerRole.GUARD)
        if (room.hasIdiot) roles.add(PlayerRole.IDIOT)
        if (room.hasMedium) roles.add(PlayerRole.MEDIUM)
        if (room.hasKnight) roles.add(PlayerRole.KNIGHT)
        if (room.hasGravekeeper) roles.add(PlayerRole.GRAVEKEEPER)
        while (roles.size < playerCount) roles.add(PlayerRole.VILLAGER)
        return roles
    }

    fun campSeats(roles: List<PlayerRole>): Map<RolePickChoice, Int> {
        val counts = roles.groupingBy { RolePickChoice.campOf(it) }.eachCount()
        return listOf(RolePickChoice.WOLF, RolePickChoice.GOD, RolePickChoice.VILLAGER).associateWith { counts[it] ?: 0 }
    }

    /** Picked-camp winners are drawn at random per camp, then get a random role of that camp; everyone else shares the leftovers. */
    fun deal(
        roles: List<PlayerRole>,
        playerIds: List<String>,
        picks: Map<String, RolePickChoice>,
        random: Random,
    ): Map<String, PlayerRole> {
        val remaining = roles.toMutableList()
        val result = mutableMapOf<String, PlayerRole>()
        for (camp in listOf(RolePickChoice.WOLF, RolePickChoice.GOD, RolePickChoice.VILLAGER)) {
            val holders = playerIds.filter { picks[it] == camp }.shuffled(random)
            for (id in holders) {
                val slot = remaining.filter { camp.matches(it) }.randomOrNull(random) ?: break
                result[id] = slot
                remaining.remove(slot)
            }
        }
        remaining.shuffle(random)
        playerIds.filter { it !in result }.forEach { result[it] = remaining.removeAt(remaining.lastIndex) }
        return result
    }
}
