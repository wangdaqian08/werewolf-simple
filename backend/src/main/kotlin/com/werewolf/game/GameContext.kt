package com.werewolf.game

import com.werewolf.model.*

data class GameContext(
    val game: Game,
    val room: Room,
    val players: List<GamePlayer>,
    val nightPhase: NightPhase? = null,
    val election: SheriffElection? = null,
    /** All night phases for this game — used to check historical state (e.g., witch antidote). */
    val allNightPhases: List<NightPhase> = emptyList(),
) {
    val gameId: Int get() = game.gameId!!
    val alivePlayers: List<GamePlayer> get() = players.filter { it.alive }
    val alivePlayerIds: Set<String> get() = alivePlayers.map { it.userId }.toSet()

    fun playerById(userId: String): GamePlayer? = players.find { it.userId == userId }
    fun alivePlayerById(userId: String): GamePlayer? = alivePlayers.find { it.userId == userId }
}
