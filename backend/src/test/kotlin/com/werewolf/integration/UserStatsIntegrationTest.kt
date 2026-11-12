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
        endedAt: LocalDateTime? = LocalDateTime.now(),
    ) {
        val room = roomRepository.save(Room(roomCode = TestConstants.nextSeededRoomCode(), hostUserId = user.userId, totalPlayers = 6))
        val gameId = gameRepository.save(Game(roomId = room.roomId!!, hostUserId = user.userId).also {
            it.winner = winner
            it.endedAt = endedAt
        }).gameId!!
        gamePlayerRepository.save(GamePlayer(gameId = gameId, userId = user.userId, seatIndex = 1, role = role))
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
    fun `score is the sum of game rewards and nothing else`() {
        val u = login()
        val other = login()
        walletService.credit(u.userId, 20, CreditTxType.GAME_REWARD)
        walletService.credit(u.userId, 5, CreditTxType.GAME_REWARD)
        walletService.credit(u.userId, 100, CreditTxType.PURCHASE)
        walletService.credit(u.userId, 50, CreditTxType.ADMIN_GRANT)
        walletService.debit(u.userId, 30, CreditTxType.PERK_SPEND)
        walletService.credit(u.userId, 30, CreditTxType.REFUND)
        walletService.credit(other.userId, 99, CreditTxType.GAME_REWARD)
        assertThat(userStatsService.statsFor(u.userId).score).isEqualTo(25)
    }

    @Test
    fun `spending coins never lowers the score`() {
        val u = login()
        walletService.credit(u.userId, 20, CreditTxType.GAME_REWARD)
        walletService.debit(u.userId, 20, CreditTxType.PERK_SPEND)
        assertThat(walletService.balance(u.userId)).isEqualTo(0)
        assertThat(userStatsService.statsFor(u.userId).score).isEqualTo(20)
    }

    @Test
    fun `score is 0 with no rewards, bought coins included`() {
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
    fun `population of one gives 0`() {
        assertThat(percentileOf(3, 5, emptyList())).isEqualTo(0)
        assertThat(percentileOf(3, 5, listOf(2 to 2))).isEqualTo(0)
    }

    @Test
    fun `controller returns only the caller's stats`() {
        val x = login()
        val y = login()
        seed(x, PlayerRole.SEER, WinnerSide.VILLAGER)
        walletService.credit(x.userId, 20, CreditTxType.GAME_REWARD)
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
