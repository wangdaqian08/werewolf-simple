package com.werewolf.service

import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GamePlayerRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

data class CampRecord(val wins: Int, val losses: Int)

data class UserStats(
    val games: Int, val wins: Int, val losses: Int, val winRate: Int,
    val wolf: CampRecord, val good: CampRecord, val recent: List<String>,
    val percentile: Int?, val gamesToRank: Int,
)

/** One ranked player's counted games and wins; the aggregate JPQL target (count/sum are Long). */
data class RankedRecord(val userId: String, val games: Long, val wins: Long)

/** One finished game from one player's seat; the JPQL constructor target. */
data class FinishedResult(val gameId: Int, val role: PlayerRole, val winner: WinnerSide, val endedAt: LocalDateTime) {
    val won: Boolean get() = role.isWolf == (winner == WinnerSide.WEREWOLF)
}

private const val MIN_GAMES_TO_RANK = 5
private const val RECENT_LIMIT = 20

/**
 * Share of the other ranked players (>= 5 games) with a strictly lower win rate, floored.
 * [others] are (wins, games) pairs; players under 5 games are ignored. Rates compare by cross-multiplying.
 */
fun percentileOf(wins: Int, games: Int, others: List<Pair<Int, Int>>): Int {
    val ranked = others.filter { it.second >= MIN_GAMES_TO_RANK }
    if (ranked.isEmpty()) return 0
    val beaten = ranked.count { (w, g) -> w * games < wins * g }
    return 100 * beaten / ranked.size
}

@Service
class UserStatsService(private val gamePlayerRepository: GamePlayerRepository) {

    fun statsFor(userId: String): UserStats {
        val mine = gamePlayerRepository.findFinishedResultsFor(userId).sortedWith(compareByDescending<FinishedResult> { it.endedAt }.thenByDescending { it.gameId })
        val wins = mine.count { it.won }
        val games = mine.size
        val (wolfGames, goodGames) = mine.partition { it.role.isWolf }
        val percentile = if (games < MIN_GAMES_TO_RANK) null else {
            val others = gamePlayerRepository.findRankedRecords(
                userId, PlayerRole.entries.filter { it.isWolf }, WinnerSide.WEREWOLF, WinnerSide.VILLAGER,
            ).map { it.wins.toInt() to it.games.toInt() }
            percentileOf(wins, games, others)
        }
        return UserStats(
            games = games, wins = wins, losses = games - wins,
            winRate = if (games == 0) 0 else 100 * wins / games,
            wolf = wolfGames.camp(), good = goodGames.camp(),
            recent = mine.take(RECENT_LIMIT).map { if (it.won) "W" else "L" },
            percentile = percentile,
            gamesToRank = maxOf(0, MIN_GAMES_TO_RANK - games),
        )
    }

    private fun List<FinishedResult>.camp() = count { it.won }.let { CampRecord(it, size - it) }
}
