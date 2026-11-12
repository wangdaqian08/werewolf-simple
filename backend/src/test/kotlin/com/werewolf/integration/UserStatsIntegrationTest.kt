package com.werewolf.integration

import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.service.UserStatsService
import com.werewolf.service.WalletService
import com.werewolf.service.percentileOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.util.UUID

/** GET /api/user/stats and UserStatsService: own win/loss record and win-rate percentile. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Suppress("UNCHECKED_CAST")
class UserStatsIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var userStatsService: UserStatsService
    @Autowired lateinit var walletService: WalletService

    private class Login(val token: String, val userId: String)

    private fun login(): Login {
        val body = restTemplate.postForEntity(
            LOGIN_URL, mapOf(FIELD_NICKNAME to "st" + UUID.randomUUID().toString().take(8)), Map::class.java,
        ).body!!
        return Login(body[FIELD_TOKEN] as String, (body[FIELD_USER] as Map<*, *>)[FIELD_USER_ID] as String)
    }

    private fun seed(
        user: Login, role: PlayerRole, winner: WinnerSide?,
        endedAt: LocalDateTime? = LocalDateTime.now(), score: Int = 0,
    ) {
        val room = roomRepository.save(Room(roomCode = TestConstants.nextSeededRoomCode(), hostUserId = user.userId, totalPlayers = 6))
        val gameId = gameRepository.save(Game(roomId = room.roomId!!, hostUserId = user.userId).also {
            it.winner = winner
            it.endedAt = endedAt
        }).gameId!!
        gamePlayerRepository.save(
            GamePlayer(gameId = gameId, userId = user.userId, seatIndex = 1, role = role).also { it.scoreEarned = score },
        )
    }

    @Test
    fun `counts only finished games with a winner`() {
        val u = login()
        seed(u, PlayerRole.SEER, WinnerSide.VILLAGER)
        seed(u, PlayerRole.SEER, null) // cancelled: ended, no winner
        seed(u, PlayerRole.SEER, WinnerSide.WEREWOLF, endedAt = null) // still running
        val s = userStatsService.statsFor(u.userId)
        assertThat(s.games).isEqualTo(1)
        assertThat(s.wins).isEqualTo(1)
        assertThat(s.losses).isEqualTo(0)
    }

    @Test
    fun `wolf and good wins by camp`() {
        val u = login()
        seed(u, PlayerRole.WHITE_WOLF_KING, WinnerSide.WEREWOLF)
        seed(u, PlayerRole.MECHANICAL_WOLF, WinnerSide.VILLAGER)
        seed(u, PlayerRole.SEER, WinnerSide.VILLAGER)
        val s = userStatsService.statsFor(u.userId)
        assertThat(s.wolf.wins).isEqualTo(1)
        assertThat(s.wolf.losses).isEqualTo(1)
        assertThat(s.good.wins).isEqualTo(1)
        assertThat(s.good.losses).isEqualTo(0)
        assertThat(s.winRate).isEqualTo(66)
    }

    @Test
    fun `recent is newest first and capped at 20`() {
        val u = login()
        val base = LocalDateTime.now().minusDays(30)
        // game i ends at base+i; only the last one (i=21) is a loss
        for (i in 0 until 22) {
            seed(u, PlayerRole.SEER, if (i == 21) WinnerSide.WEREWOLF else WinnerSide.VILLAGER, base.plusHours(i.toLong()))
        }
        val s = userStatsService.statsFor(u.userId)
        assertThat(s.recent).hasSize(20)
        assertThat(s.recent.first()).isEqualTo("L")
        assertThat(s.recent.drop(1)).containsOnly("W")
    }

    @Test
    fun `under 5 games gives null percentile and gamesToRank`() {
        val u = login()
        repeat(3) { seed(u, PlayerRole.SEER, WinnerSide.VILLAGER) }
        val s = userStatsService.statsFor(u.userId)
        assertThat(s.percentile).isNull()
        assertThat(s.gamesToRank).isEqualTo(2)
    }

    @Test
    fun `zero games`() {
        val s = userStatsService.statsFor(login().userId)
        assertThat(s.games).isEqualTo(0)
        assertThat(s.wins).isEqualTo(0)
        assertThat(s.losses).isEqualTo(0)
        assertThat(s.winRate).isEqualTo(0)
        assertThat(s.recent).isEmpty()
        assertThat(s.percentile).isNull()
        assertThat(s.gamesToRank).isEqualTo(5)
    }

    @Test
    fun `score is the sum of the score each game recorded`() {
        val u = login()
        val other = login()
        seed(u, PlayerRole.SEER, WinnerSide.VILLAGER, score = 20)
        seed(u, PlayerRole.WEREWOLF, WinnerSide.VILLAGER, score = 4)
        seed(u, PlayerRole.SEER, WinnerSide.WEREWOLF, score = 0)
        seed(other, PlayerRole.SEER, WinnerSide.VILLAGER, score = 99)
        assertThat(userStatsService.statsFor(u.userId).score).isEqualTo(24)
    }

    @Test
    fun `coin movements never change the score`() {
        val u = login()
        seed(u, PlayerRole.SEER, WinnerSide.VILLAGER, score = 20)
        walletService.credit(u.userId, 20, CreditTxType.GAME_REWARD) // coins paid for that game
        walletService.credit(u.userId, 100, CreditTxType.PURCHASE)
        walletService.credit(u.userId, 50, CreditTxType.ADMIN_GRANT)
        walletService.debit(u.userId, 170, CreditTxType.PERK_SPEND)
        walletService.credit(u.userId, 30, CreditTxType.REFUND)
        assertThat(walletService.balance(u.userId)).isEqualTo(30)
        assertThat(userStatsService.statsFor(u.userId).score).isEqualTo(20)
    }

    @Test
    fun `score is 0 with no games, bought coins included`() {
        val u = login()
        assertThat(userStatsService.statsFor(u.userId).score).isEqualTo(0)
        walletService.credit(u.userId, 100, CreditTxType.PURCHASE)
        assertThat(userStatsService.statsFor(u.userId).score).isEqualTo(0)
    }

    @Test
    fun `percentile is wired for a player with 5 games`() {
        val u = login()
        repeat(5) { seed(u, PlayerRole.SEER, WinnerSide.VILLAGER) }
        val s = userStatsService.statsFor(u.userId)
        assertThat(s.percentile).isNotNull()
        assertThat(s.gamesToRank).isEqualTo(0)
    }

    @Test
    fun `percentile counts strictly lower win rates among others`() {
        // caller 3/5 = 60%; A 40% (2/5), B 60% (6/10, a tie), C 80% (4/5), D 100% but 2 games (excluded)
        val others = listOf(2 to 5, 6 to 10, 4 to 5, 2 to 2)
        assertThat(percentileOf(3, 5, others)).isEqualTo(33)
    }

    @Test
    fun `recent breaks endedAt ties by newest game id`() {
        val u = login()
        val t = LocalDateTime.now().minusDays(1)
        seed(u, PlayerRole.SEER, WinnerSide.VILLAGER, t) // lower gameId: W
        seed(u, PlayerRole.SEER, WinnerSide.WEREWOLF, t) // higher gameId: L
        assertThat(userStatsService.statsFor(u.userId).recent).containsExactly("L", "W")
    }

    @Test
    fun `aggregate query ranks others only and the percentile follows it`() {
        val caller = login()
        val loser = login()
        val short = login()
        repeat(5) { seed(caller, PlayerRole.SEER, WinnerSide.VILLAGER) } // 5/5
        repeat(5) { seed(loser, PlayerRole.WEREWOLF, WinnerSide.VILLAGER) } // 0/5
        repeat(4) { seed(short, PlayerRole.SEER, WinnerSide.VILLAGER) } // under 5 games
        val rows = gamePlayerRepository.findRankedRecords(
            caller.userId, PlayerRole.entries.filter { it.isWolf }, WinnerSide.WEREWOLF, WinnerSide.VILLAGER,
        )
        assertThat(rows.map { it.userId }).doesNotContain(caller.userId, short.userId)
        assertThat(rows.single { it.userId == loser.userId }.let { it.games to it.wins }).isEqualTo(5L to 0L)
        val expected = percentileOf(5, 5, rows.map { it.wins.toInt() to it.games.toInt() })
        assertThat(userStatsService.statsFor(caller.userId).percentile).isEqualTo(expected)
        assertThat(expected).isGreaterThanOrEqualTo(1) // beats the 0/5 loser
    }

    @Test
    fun `leaderboard rows sum recorded scores, and say who reached theirs first`() {
        val first = login()
        val second = login()
        val zero = login()
        val t = LocalDateTime.now().minusDays(2)
        seed(first, PlayerRole.SEER, WinnerSide.VILLAGER, t, score = 20)
        seed(first, PlayerRole.WEREWOLF, WinnerSide.VILLAGER, t.plusHours(1), score = 5)
        seed(second, PlayerRole.SEER, WinnerSide.VILLAGER, t.plusHours(2), score = 25)
        seed(zero, PlayerRole.SEER, WinnerSide.WEREWOLF, t, score = 0)
        walletService.credit(zero.userId, 500, CreditTxType.PURCHASE)
        val rows = gamePlayerRepository.findScoreRows().associateBy { it.userId }
        assertThat(rows[first.userId]?.score).isEqualTo(25L)
        assertThat(rows[second.userId]?.score).isEqualTo(25L)
        assertThat(rows[first.userId]!!.reachedAt).isBefore(rows[second.userId]!!.reachedAt)
        assertThat(rows).doesNotContainKey(zero.userId)
    }

    @Test
    fun `a game that added nothing does not move the time a score was reached`() {
        val u = login()
        val t = LocalDateTime.now().minusDays(3)
        seed(u, PlayerRole.SEER, WinnerSide.VILLAGER, t, score = 20)
        seed(u, PlayerRole.SEER, WinnerSide.WEREWOLF, t.plusDays(1), score = 0)
        val row = gamePlayerRepository.findScoreRows().single { it.userId == u.userId }
        assertThat(row.reachedAt).isEqualToIgnoringNanos(t)
    }

    private fun leaderboard(l: Login) = restTemplate.exchange(
        "/api/user/leaderboard", HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().also { it.setBearerAuth(l.token) }), Map::class.java,
    ).body!!

    @Test
    fun `leaderboard endpoint - my row has my score and my rank among all players`() {
        val u = login()
        seed(u, PlayerRole.SEER, WinnerSide.VILLAGER, score = 20)
        val body = leaderboard(u)
        val me = body["me"] as Map<String, Any>
        val top = body["top"] as List<Map<String, Any>>
        val ahead = gamePlayerRepository.findScoreRows()
            .count { it.userId != u.userId && it.score >= 20 } // everyone else at 20+ got there before this user
        assertThat(me["score"]).isEqualTo(20)
        assertThat(me["rank"]).isEqualTo(ahead + 1)
        assertThat(me["me"]).isEqualTo(true)
        assertThat(me["nickname"]).isEqualTo(u.userId.removePrefix("guest:")) // the display name, never the id
        assertThat(top.size).isBetween(1, 3)
        assertThat(top.map { it["rank"] }).isEqualTo((1..top.size).toList())
        assertThat(top.map { it["score"] as Int }).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(top.all { it.keys == setOf("rank", "nickname", "score", "me") }).isTrue() // no user ids
    }

    @Test
    fun `leaderboard endpoint - a player with no score is unranked`() {
        val u = login()
        walletService.credit(u.userId, 100, CreditTxType.PURCHASE)
        assertThat(leaderboard(u)["me"]).isNull()
    }

    @Test
    fun `leaderboard endpoint needs a login`() {
        val status = restTemplate.getForEntity("/api/user/leaderboard", Map::class.java).statusCode
        assertThat(status.is4xxClientError).isTrue()
    }

    @Test
    fun `population of one gives 0`() {
        assertThat(percentileOf(3, 5, emptyList())).isEqualTo(0)
        assertThat(percentileOf(3, 5, listOf(2 to 2))).isEqualTo(0)
    }

    @Test
    fun `controller returns only the caller's stats`() {
        val x = login()
        val y = login()
        seed(x, PlayerRole.SEER, WinnerSide.VILLAGER, score = 20)
        repeat(2) { seed(y, PlayerRole.SEER, WinnerSide.WEREWOLF) }
        fun get(l: Login) = restTemplate.exchange(
            "/api/user/stats", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().also { it.setBearerAuth(l.token) }), Map::class.java,
        ).body!!
        val bx = get(x)
        assertThat(bx["games"]).isEqualTo(1)
        assertThat(bx["wins"]).isEqualTo(1)
        assertThat(bx["score"]).isEqualTo(20)
        val by = get(y)
        assertThat(by["games"]).isEqualTo(2)
        assertThat(by["losses"]).isEqualTo(2)
        assertThat(by["score"]).isEqualTo(0)
        assertThat((by["good"] as Map<String, Any>)["losses"]).isEqualTo(2)
    }
}
