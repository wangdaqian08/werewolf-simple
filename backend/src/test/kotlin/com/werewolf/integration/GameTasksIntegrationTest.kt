package com.werewolf.integration

import com.werewolf.game.action.GameActionResult
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDateTime

/** 单局任务 end to end: dealt at start, private to its owner, judged at settlement, paid in 戰績分 only. */
@SpringBootTest
@ActiveProfiles("test")
class GameTasksIntegrationTest {

    @Autowired lateinit var gameService: GameService
    @Autowired lateinit var roomService: RoomService
    @Autowired lateinit var rewardSettlementService: RewardSettlementService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var gameReviewService: GameReviewService

    private val mapper = jacksonObjectMapper()
    private var seq = 0

    private fun user(prefix: String): String =
        "guest:$prefix-${++seq}-${System.nanoTime()}".also { userRepository.save(User(userId = it, nickname = prefix)) }

    /** A real 6-seat game started through GameService (no perks, no sheriff). */
    private fun startSixSeatGame(): Pair<Int, List<String>> {
        val host = user("th")
        val roomId = roomRepository.save(
            Room(
                roomCode = TestConstants.nextSeededRoomCode(), hostUserId = host, totalPlayers = 6,
                hasSeer = true, hasWitch = true, hasHunter = true, hasSheriff = false,
                config = GameConfig(perksAllowed = false),
            ),
        ).roomId!!
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = host, host = true, seatIndex = 1))
        val guests = (2..6).map { seat -> user("tg").also { roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = it, seatIndex = seat)) } }
        guests.forEach { roomService.setReady(it, roomId, true) }
        assertThat(gameService.startGame(host, roomId)).isInstanceOf(GameActionResult.Success::class.java)
        val gameId = gameRepository.findByRoomId(roomId).single().gameId!!
        return gameId to (listOf(host) + guests)
    }

    @Test
    fun `starting a game deals every seat a task that fits its role`() {
        val (gameId, _) = startSixSeatGame()
        gamePlayerRepository.findByGameId(gameId).forEach { p ->
            assertThat(p.taskCode).`as`(p.role.name).isNotNull()
            assertThat(GameTasks.eligible(p.role, hasSheriff = false)).contains(p.taskCode)
            assertThat(p.taskDone).isNull()
        }
    }

    @Test
    fun `a player's state carries their own task and nobody else's`() {
        val (gameId, users) = startSixSeatGame()
        val seats = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }
        users.forEach { u ->
            val state = gameService.getGameState(gameId, u)
            @Suppress("UNCHECKED_CAST")
            val myTask = state["myTask"] as Map<String, Any?>
            assertThat(myTask["code"]).isEqualTo(seats.getValue(u).taskCode!!.name)
            assertThat(myTask["score"]).isEqualTo(seats.getValue(u).taskCode!!.score)
            assertThat(myTask["done"]).isNull()
            // The only task anywhere in the payload is the requester's own.
            val json = mapper.writeValueAsString(state)
            assertThat(Regex("\"(code|taskCode|task)\"").findAll(json).count()).`as`(json).isEqualTo(1)
        }
    }

    // ── settlement ────────────────────────────────────────────────────

    /** A finished village win; [tasks] maps userId → its task. */
    private fun finishedGame(roster: List<Pair<String, PlayerRole>>, tasks: Map<String, GameTask>): Int {
        roster.forEach { (id, _) -> userRepository.save(User(userId = id, nickname = id)) }
        val room = roomRepository.save(Room(roomCode = TestConstants.nextSeededRoomCode(), hostUserId = roster.first().first, totalPlayers = roster.size))
        val gameId = gameRepository.save(
            Game(roomId = room.roomId!!, hostUserId = roster.first().first).also {
                it.phase = GamePhase.GAME_OVER
                it.winner = WinnerSide.VILLAGER
                it.dayNumber = 3
                it.endedAt = LocalDateTime.now()
            },
        ).gameId!!
        gamePlayerRepository.saveAll(roster.mapIndexed { i, (id, role) ->
            GamePlayer(gameId = gameId, userId = id, seatIndex = i + 1, role = role).also { it.taskCode = tasks[id] }
        })
        return gameId
    }

    private val roster = listOf(
        "tkW" to PlayerRole.WEREWOLF, "tkV" to PlayerRole.VILLAGER, "tkS" to PlayerRole.SEER,
        "tkWi" to PlayerRole.WITCH, "tkH" to PlayerRole.HUNTER, "tkV2" to PlayerRole.VILLAGER,
    )

    private fun ids(gameId: Int, base: String) = "$base-$gameId"

    @Test
    fun `a done task adds its score to 戰績分 but never to coins`() {
        val r = roster.map { (id, role) -> "$id-${System.nanoTime()}" to role }
        val (wolf, villager) = r[0].first to r[1].first
        val gameId = finishedGame(r, mapOf(villager to GameTask.VILLAGER_SURVIVE_WIN, wolf to GameTask.WOLF_WIN_ALIVE))

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        val seats = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }
        val coins = creditTransactionRepository.findByGameIdAndType(gameId, CreditTxType.GAME_REWARD).associate { it.userId to it.amount }
        // villager: alive in a village win → task done
        assertThat(seats.getValue(villager).taskDone).isTrue()
        assertThat(coins[villager]).isEqualTo(20)
        assertThat(seats.getValue(villager).scoreEarned).isEqualTo(20 + GameTask.VILLAGER_SURVIVE_WIN.score)
        // wolf: the village won → not done, score is the reward alone
        assertThat(seats.getValue(wolf).taskDone).isFalse()
        assertThat(seats.getValue(wolf).scoreEarned).isEqualTo(coins[wolf] ?: 0)
        // no task dealt → nothing judged
        assertThat(seats.getValue(r[2].first).taskDone).isNull()
    }

    @Test
    fun `a cancelled game judges no task`() {
        val r = roster.map { (id, role) -> "$id-${System.nanoTime()}" to role }
        val gameId = finishedGame(r, mapOf(r[1].first to GameTask.VILLAGER_SURVIVE_WIN))

        rewardSettlementService.settle(gameId, null)

        val seat = gamePlayerRepository.findByGameId(gameId).single { it.userId == r[1].first }
        assertThat(seat.taskDone).isNull()
        assertThat(seat.scoreEarned).isEqualTo(0)
    }

    @Test
    fun `after the game the result shows my score and my task outcome`() {
        val r = roster.map { (id, role) -> "$id-${System.nanoTime()}" to role }
        val villager = r[1].first
        val gameId = finishedGame(r, mapOf(villager to GameTask.VILLAGER_SURVIVE_WIN))
        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        val state = gameService.getGameState(gameId, villager)
        @Suppress("UNCHECKED_CAST")
        val settlement = state["settlement"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val myTask = state["myTask"] as Map<String, Any?>
        assertThat(settlement["myEarned"]).isEqualTo(20) // coins
        assertThat(settlement["myScore"]).isEqualTo(30) // 戰績分 = reward + task
        assertThat(myTask["done"]).isEqualTo(true)
        // The per-player list under 戰績分結算 shows each player's 戰績分, task included.
        @Suppress("UNCHECKED_CAST")
        val rows = (settlement["rewards"] as List<Map<String, Any?>>).associate { it["userId"] to it["amount"] }
        assertThat(rows[villager]).isEqualTo(30)
        assertThat(rows[r[2].first]).isEqualTo(20) // the seer: village win, no task
    }

    @Test
    fun `the god's-eye review lists every task and whether it was done, the player review only mine`() {
        val r = roster.map { (id, role) -> "$id-${System.nanoTime()}" to role }
        val (wolf, villager) = r[0].first to r[1].first
        val gameId = finishedGame(r, mapOf(villager to GameTask.VILLAGER_SURVIVE_WIN, wolf to GameTask.WOLF_WIN_ALIVE))
        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        val all = gameReviewService.getReview(gameId, villager).tasks.associateBy { it.player.userId }
        assertThat(all.keys).containsExactlyInAnyOrder(wolf, villager) // seats without a task are left out
        assertThat(all.getValue(villager).task).isEqualTo(GameTask.VILLAGER_SURVIVE_WIN)
        assertThat(all.getValue(villager).done).isTrue()
        assertThat(all.getValue(villager).score).isEqualTo(10)
        assertThat(all.getValue(wolf).done).isFalse()
        assertThat(all.getValue(wolf).player.seatIndex).isEqualTo(1)

        val mine = gameReviewService.getPlayerReview(gameId, wolf).task
        assertThat(mine?.task).isEqualTo(GameTask.WOLF_WIN_ALIVE)
        assertThat(mine?.done).isFalse()
        assertThat(gameReviewService.getPlayerReview(gameId, r[2].first).task).isNull() // no task dealt
    }
}
