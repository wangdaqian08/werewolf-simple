package com.werewolf.service

// Mirrors frontend/src/utils/wolfBounds.ts: canonical floor(N/3) wolves, host may go ±1.
object WolfCountBounds {
    fun default(totalPlayers: Int): Int = totalPlayers / 3
    fun max(totalPlayers: Int): Int = default(totalPlayers) + 1
    fun min(totalPlayers: Int): Int = maxOf(1, default(totalPlayers) - 1)
    fun isValid(totalPlayers: Int, wolfCount: Int): Boolean =
        wolfCount in min(totalPlayers)..max(totalPlayers)
}
