package com.werewolf.integration

import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.service.ActionLogService
import com.werewolf.service.WalletService
import com.werewolf.service.PERK_NIGHT1_IMMUNITY
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
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

/**
 * GET /api/game/{id}/review and /review/me: the God's-eye replay and the player's own replay.
 * Both open only to the game's players, and only after the game.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Suppress("UNCHECKED_CAST")
class GameReviewIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var actionLogService: ActionLogService
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository
    @Autowired lateinit var walletService: WalletService

    private class Login(val token: String, val userId: String, val nickname: String)

    private fun login(nickname: String): Login {
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java).body!!
        return Login(body[FIELD_TOKEN] as String, (body[FIELD_USER] as Map<*, *>)[FIELD_USER_ID] as String, nickname)
    }

    private fun getReview(gameId: Int, token: String) = restTemplate.exchange(
        "/api/game/$gameId/review", HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().also { it.setBearerAuth(token) }), Map::class.java,
    )

    private fun getPlayerReview(gameId: Int, token: String) = restTemplate.exchange(
        "/api/game/$gameId/review/me", HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().also { it.setBearerAuth(token) }), Map::class.java,
    )

    /** Night 1: the wolf (seat 1) kills the seer (seat 2), who checked the wolf. Day 1: nobody is exiled. */
    private fun seedGame(wolf: Login, seer: Login, phase: GamePhase): Int {
        val room = roomRepository.save(Room(roomCode = TestConstants.nextSeededRoomCode(), hostUserId = wolf.userId, totalPlayers = 6))
        val gameId = gameRepository.save(Game(roomId = room.roomId!!, hostUserId = wolf.userId).also {
            it.phase = phase
            if (phase == GamePhase.GAME_OVER) { it.winner = WinnerSide.WEREWOLF; it.endedAt = LocalDateTime.now() }
        }).gameId!!
        gamePlayerRepository.saveAll(listOf(
            GamePlayer(gameId = gameId, userId = wolf.userId, seatIndex = 1, role = PlayerRole.WEREWOLF),
            GamePlayer(gameId = gameId, userId = seer.userId, seatIndex = 2, role = PlayerRole.SEER).also { it.alive = false; it.diedDay = 1 },
        ))
        nightPhaseRepository.save(NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = seer.userId
            it.seerCheckedUserId = wolf.userId
            it.seerResultIsWerewolf = true
        })
        actionLogService.recordNightDeaths(gameId, 1, listOf(seer.userId))
        actionLogService.recordVoteResult(
            gameId, 1, listOf(Vote(gameId = gameId, voteContext = VoteContext.ELIMINATION, dayNumber = 1, voterUserId = wolf.userId)),
            emptyMap(), null, null,
        )
        return gameId
    }

    @Test
    fun `a player gets the review after the game is over`() {
        val wolf = login("ReviewWolf")
        val seer = login("ReviewSeer")
        val gameId = seedGame(wolf, seer, GamePhase.GAME_OVER)

        val response = getReview(gameId, seer.token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["winner"]).isEqualTo("WEREWOLF")
        val round = (body["rounds"] as List<Map<String, Any?>>).single()
        val night = round["night"] as Map<String, Any?>
        val wolfTurn = night["wolf"] as Map<String, Any?>
        assertThat(wolfTurn["action"]).isEqualTo("KILL")
        assertThat(wolfTurn["target"]).isEqualTo(
            mapOf("userId" to seer.userId, "nickname" to seer.nickname, "seatIndex" to 2, "role" to "SEER"),
        )
        assertThat((night["seer"] as Map<*, *>)["isWerewolf"]).isEqualTo(true)
        assertThat(((night["dawn"] as Map<*, *>)["deaths"] as List<Map<*, *>>).single()["cause"]).isEqualTo("WOLF")
        val vote = (round["events"] as List<Map<String, Any?>>).single()
        assertThat(listOf(vote["type"], vote["kind"], vote["round"], vote["result"], vote["appointed"])).containsExactly("VOTE", "EXILE", 1, null, false)
        assertThat((vote["abstain"] as List<Map<*, *>>).single()["seatIndex"]).isEqualTo(1)
    }

    @Test
    fun `every action-log event type written by the game is read back`() {
        val wolf = login("ReviewTypesWolf")
        val seer = login("ReviewTypesSeer")
        val gameId = seedGame(wolf, seer, GamePhase.GAME_OVER)
        // Through the real writer, so a payload format change breaks this test; the story need not be consistent.
        actionLogService.recordSheriffResult(
            gameId, 1, wolf.userId,
            listOf(Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1, voterUserId = seer.userId, targetUserId = wolf.userId)),
            mapOf(wolf.userId to 1),
        )
        actionLogService.recordHunterShot(gameId, 1, seer.userId, wolf.userId)
        actionLogService.recordSelfDestruct(gameId, 1, wolf.userId, wolf.nickname, 1)
        actionLogService.recordIdiotReveal(gameId, 1, seer.userId)

        val events = ((getReview(gameId, wolf.token).body!!["rounds"] as List<Map<String, Any?>>).single()["events"] as List<Map<String, Any?>>)

        assertThat(events.map { it["type"] to it["kind"] }).containsExactly(
            "VOTE" to "EXILE", "VOTE" to "SHERIFF", "HUNTER_SHOT" to null, "SELF_DESTRUCT" to null, "IDIOT_REVEAL" to null,
        )
    }

    @Test
    fun `a night-1 save by the immunity perk is read from the perk record`() {
        val wolf = login("ReviewPerkWolf")
        val holder = login("ReviewPerkHolder")
        val room = roomRepository.save(Room(roomCode = TestConstants.nextSeededRoomCode(), hostUserId = wolf.userId, totalPlayers = 6))
        val gameId = gameRepository.save(Game(roomId = room.roomId!!, hostUserId = wolf.userId).also {
            it.phase = GamePhase.GAME_OVER; it.winner = WinnerSide.VILLAGER; it.endedAt = LocalDateTime.now()
        }).gameId!!
        gamePlayerRepository.saveAll(listOf(
            GamePlayer(gameId = gameId, userId = wolf.userId, seatIndex = 1, role = PlayerRole.WEREWOLF),
            GamePlayer(gameId = gameId, userId = holder.userId, seatIndex = 2, role = PlayerRole.VILLAGER),
        ))
        nightPhaseRepository.save(NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = holder.userId
        })
        perkActivationRepository.save(
            PerkActivation(roomId = room.roomId!!, gameId = gameId, userId = holder.userId, perkCode = PERK_NIGHT1_IMMUNITY, pricePaid = 30)
                .also { it.triggeredAt = LocalDateTime.now() },
        )

        val night = (getReview(gameId, wolf.token).body!!["rounds"] as List<Map<String, Any?>>).single()["night"] as Map<*, *>

        assertThat(((night["dawn"] as Map<*, *>)["saved"] as Map<*, *>?)?.get("by")).isEqualTo("PERK")
    }

    @Test
    fun `the review stays closed while the game is running`() {
        val wolf = login("ReviewEarlyWolf")
        val seer = login("ReviewEarlySeer")
        val gameId = seedGame(wolf, seer, GamePhase.DAY_DISCUSSION)

        assertThat(getReview(gameId, wolf.token).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a user who was not in the game cannot open its review`() {
        val gameId = seedGame(login("ReviewOwnWolf"), login("ReviewOwnSeer"), GamePhase.GAME_OVER)

        assertThat(getReview(gameId, login("ReviewStranger").token).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `an unknown game is 404`() {
        val response = getReview(99_999, login("ReviewNobody").token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body?.get("error")).isEqualTo("Game 99999 not found")
    }

    @Test
    fun `a player's own replay shows their night turn and stops at the death that ended their game`() {
        val wolf = login("MyReviewSeerWolf")
        val seer = login("MyReviewSeerSeer")
        val gameId = seedGame(wolf, seer, GamePhase.GAME_OVER)

        val body = getPlayerReview(gameId, seer.token).body!!

        assertThat(body["diedOnDay"]).isEqualTo(1)
        assertThat((body["me"] as Map<*, *>)["role"]).isEqualTo("SEER")
        val round = (body["rounds"] as List<Map<String, Any?>>).single()
        val action = (round["night"] as Map<String, Any?>)["action"] as Map<String, Any?>
        assertThat(action["type"]).isEqualTo("SEER")
        assertThat(action["isWerewolf"]).isEqualTo(true)
        // The checked wolf is known by camp, never by role.
        val checked = action["target"] as Map<*, *>
        assertThat(listOf(checked["seatIndex"], checked["role"], checked["camp"])).containsExactly(1, null, "WOLF")
        // They died at this dawn, so the day's vote was never theirs to hear.
        assertThat(round["events"] as List<*>).isEmpty()
    }

    @Test
    fun `the surviving wolf sees their kill, and the day that followed it, without the victim's role`() {
        val wolf = login("MyReviewWolfWolf")
        val seer = login("MyReviewWolfSeer")
        val gameId = seedGame(wolf, seer, GamePhase.GAME_OVER)

        val body = getPlayerReview(gameId, wolf.token).body!!

        assertThat(body["diedOnDay"]).isNull()
        val round = (body["rounds"] as List<Map<String, Any?>>).single()
        val night = round["night"] as Map<String, Any?>
        val action = night["action"] as Map<String, Any?>
        assertThat(listOf(action["type"], action["selfKill"])).containsExactly("WOLF", false)
        assertThat((action["target"] as Map<*, *>)["seatIndex"]).isEqualTo(2)
        // Dawn names the dead seat and nothing else: no role, and no cause of death.
        val dead = ((night["dawn"] as Map<*, *>)["deaths"] as List<Map<*, *>>).single()
        assertThat(listOf(dead["seatIndex"], dead["role"], dead["cause"])).containsExactly(2, null, null)
        assertThat((round["events"] as List<Map<String, Any?>>).single()["type"]).isEqualTo("VOTE")
    }

    @Test
    fun `the player replay is shut to strangers, to a running game and to an unknown game`() {
        val wolf = login("MyReviewShutWolf")
        val running = seedGame(wolf, login("MyReviewShutSeer"), GamePhase.DAY_DISCUSSION)
        val over = seedGame(login("MyReviewShutWolf2"), login("MyReviewShutSeer2"), GamePhase.GAME_OVER)

        assertThat(getPlayerReview(running, wolf.token).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(getPlayerReview(over, login("MyReviewShutStranger").token).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(getPlayerReview(99_999, wolf.token).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `both replays report the same points, the god's-eye one for everybody and the player's own for themselves`() {
        val wolf = login("MyReviewPointsWolf")
        val seer = login("MyReviewPointsSeer")
        val gameId = seedGame(wolf, seer, GamePhase.GAME_OVER)
        // The wolves won this seeded game: the wolf takes the win bonus, the dead seer the
        // participation credit. Both replays read these same ledger rows.
        walletService.credit(wolf.userId, 20, CreditTxType.GAME_REWARD, gameId = gameId)
        walletService.credit(seer.userId, 5, CreditTxType.GAME_REWARD, gameId = gameId)

        val everyone = (getReview(gameId, seer.token).body!!["rewards"] as List<Map<String, Any?>>)
            .associate { ((it["player"] as Map<*, *>)["userId"] as String) to it["amount"] }
        assertThat(everyone).isEqualTo(mapOf(wolf.userId to 20, seer.userId to 5))

        // The seer lost as a villager-side player, so their own replay explains it as participation,
        // and the working adds up to the very number the god's-eye replay reports for them.
        val mine = getPlayerReview(gameId, seer.token).body!!["reward"] as Map<*, *>
        val items = mine["items"] as List<Map<String, Any?>>
        assertThat(items.map { it["reason"] to it["amount"] }).containsExactly("PARTICIPATION" to 5)
        assertThat(items.sumOf { it["amount"] as Int }).isEqualTo(mine["total"])
        assertThat(mine["total"]).isEqualTo(everyone[seer.userId])
    }
}
