package com.werewolf.service

import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.UserRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

data class CampRecord(val wins: Int, val losses: Int)

data class UserStats(
    val games: Int, val wins: Int, val losses: Int, val winRate: Int,
    val wolf: CampRecord, val good: CampRecord, val recent: List<String>,
    val percentile: Int?, val gamesToRank: Int,
    /** 战绩分: earned by play only — never bought, never spent. */
    val score: Int,
)

/** One ranked player's counted games and wins; the aggregate JPQL target (count/sum are Long). */
data class RankedRecord(val userId: String, val games: Long, val wins: Long)

/** One finished game from one player's seat; the JPQL constructor target. */
data class FinishedResult(val gameId: Int, val role: PlayerRole, val winner: WinnerSide, val endedAt: LocalDateTime) {
    val won: Boolean get() = role.isWolf == (winner == WinnerSide.WEREWOLF)
}

/** One player's 戰績分 and when the last game that added to it ended (earlier = reached it first). */
data class ScoreRow(val userId: String, val score: Long, val reachedAt: LocalDateTime)

data class LeaderboardEntry(val rank: Int, val nickname: String, val score: Int, val me: Boolean)

/** [top] is the first three; [me] is the caller's own row, null while they have no score. */
data class Leaderboard(val top: List<LeaderboardEntry>, val me: LeaderboardEntry?)

private const val LEADERBOARD_SIZE = 3

/** Ranks [rows] by score, an equal score going to whoever reached it first (then by user id, so the order is stable); [nicknameOf] is asked only for shown rows. */
fun leaderboardOf(rows: List<ScoreRow>, userId: String, nicknameOf: (String) -> String): Leaderboard {
    val ranked = rows.sortedWith(compareByDescending<ScoreRow> { it.score }.thenBy { it.reachedAt }.thenBy { it.userId })
    fun entry(index: Int) = ranked[index].let {
        LeaderboardEntry(index + 1, nicknameOf(it.userId), it.score.toInt(), it.userId == userId)
    }
    val mine = ranked.indexOfFirst { it.userId == userId }
    return Leaderboard(
        top = ranked.indices.take(LEADERBOARD_SIZE).map(::entry),
        me = if (mine < 0) null else entry(mine),
    )
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
class UserStatsService(
    private val gamePlayerRepository: GamePlayerRepository,
    private val userRepository: UserRepository,
) {

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
            score = gamePlayerRepository.sumScore(userId).toInt(),
        )
    }

    // shortcut: loads every ranked player's total on each call; cache or rank in SQL when the player base grows.
    fun leaderboardFor(userId: String): Leaderboard =
        leaderboardOf(gamePlayerRepository.findScoreRows(), userId) { id ->
            userRepository.findById(id).map { it.nickname }.orElse("?")
        }

    private fun List<FinishedResult>.camp() = count { it.won }.let { CampRecord(it, size - it) }
}
