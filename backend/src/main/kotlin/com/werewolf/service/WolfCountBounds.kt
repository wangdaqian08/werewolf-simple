package com.werewolf.service

object WolfCountBounds {
    fun max(totalPlayers: Int): Int = totalPlayers / 3
    fun min(totalPlayers: Int): Int = maxOf(1, max(totalPlayers) - 1)
    fun default(totalPlayers: Int): Int = max(totalPlayers)
    fun isValid(totalPlayers: Int, wolfCount: Int): Boolean =
        wolfCount in min(totalPlayers)..max(totalPlayers)
}
