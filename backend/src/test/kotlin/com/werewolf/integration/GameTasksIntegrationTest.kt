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

    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var actionLogService: ActionLogService

    @Test
    fun `the seer found a wolf on both nights alive - paid twice the per-night score, never in coins`() {
        val r = roster.map { (id, role) -> "$id-${System.nanoTime()}" to role }
        val (wolf, seer) = r[0].first to r[2].first
        val gameId = finishedGame(r, mapOf(seer to GameTask.SEER_WOLF_EVERY_NIGHT))
        // game ended on day 3; the seer died on night 2 after checking the wolf on nights 1 and 2
        listOf(1, 2).forEach { d ->
            nightPhaseRepository.save(NightPhase(gameId = gameId, dayNumber = d).also {
                it.seerCheckedUserId = wolf; it.seerResultIsWerewolf = true
            })
        }
        gamePlayerRepository.findByGameId(gameId).single { it.userId == seer }.let { it.diedDay = 2; gamePlayerRepository.save(it) }
        actionLogService.recordNightDeaths(gameId, 2, listOf(seer))

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        val seat = gamePlayerRepository.findByGameId(gameId).single { it.userId == seer }
        val coins = creditTransactionRepository.findByGameIdAndType(gameId, CreditTxType.GAME_REWARD).single { it.userId == seer }.amount
        assertThat(seat.taskDone).isTrue()
        assertThat(seat.taskScore).isEqualTo(2 * GameTask.SEER_WOLF_EVERY_NIGHT.score)
        assertThat(seat.scoreEarned).isEqualTo(coins + 20)

        @Suppress("UNCHECKED_CAST")
        val myTask = gameService.getGameState(gameId, seer)["myTask"] as Map<String, Any?>
        assertThat(myTask["perNight"]).isEqualTo(true)
        assertThat(myTask["earned"]).isEqualTo(20)
        val reviewed = gameReviewService.getReview(gameId, seer).tasks.single { it.player.userId == seer }
        assertThat(reviewed.earned).isEqualTo(20)
        assertThat(reviewed.perNight).isTrue()
    }

    @Test
    fun `never voted good - an abstain fails it, a PK re-vote onto the wolf passes it`() {
        val r = roster.map { (id, role) -> "$id-${System.nanoTime()}" to role }
        val (wolf, villager, villager2) = Triple(r[0].first, r[1].first, r[5].first)
        val gameId = finishedGame(r, mapOf(villager to GameTask.NEVER_VOTE_GOOD, villager2 to GameTask.NEVER_VOTE_GOOD))
        // Not saved: a real re-vote deletes round 1 first; the judge reads the tallies the log recorded.
        fun vote(day: Int, voter: String, target: String?) =
            Vote(gameId = gameId, voteContext = VoteContext.ELIMINATION, dayNumber = day, voterUserId = voter, targetUserId = target)
        // Day 1: round 1 tie; villager votes the seer, then moves onto the wolf in the PK re-vote.
        actionLogService.recordVoteResult(gameId, 1, listOf(vote(1, villager, r[2].first), vote(1, villager2, wolf)), mapOf(r[2].first to 1.0, wolf to 1.0), null, null)
        actionLogService.recordVoteResult(gameId, 1, listOf(vote(1, villager, wolf), vote(1, villager2, wolf)), mapOf(wolf to 2.0), null, wolf)
        // Day 2: villager votes the wolf again; villager2 abstains.
        actionLogService.recordVoteResult(gameId, 2, listOf(vote(2, villager, wolf), vote(2, villager2, null)), mapOf(wolf to 1.0), null, null)

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        val seats = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }
        assertThat(seats.getValue(villager).taskDone).isTrue()
        assertThat(seats.getValue(villager).taskScore).isEqualTo(GameTask.NEVER_VOTE_GOOD.score)
        assertThat(seats.getValue(villager2).taskDone).isFalse()
        assertThat(seats.getValue(villager2).taskScore).isEqualTo(0)
    }

    @Test
    fun `batch 2 - shots, the self-destruct take, the duel and the idiot reveal are judged from the recorded events`() {
        val n = System.nanoTime()
        val r = listOf(
            "b2Wwk" to PlayerRole.WHITE_WOLF_KING, "b2Bwk" to PlayerRole.BLACK_WOLF_KING, "b2W" to PlayerRole.WEREWOLF,
            "b2H" to PlayerRole.HUNTER, "b2K" to PlayerRole.KNIGHT, "b2I" to PlayerRole.IDIOT,
            "b2S" to PlayerRole.SEER, "b2Wi" to PlayerRole.WITCH, "b2V" to PlayerRole.VILLAGER,
        ).map { (id, role) -> "$id-$n" to role }
        val (wwk, bwk, wolf, hunter, knight, idiot, seer, witch, villager) = r.map { it.first }
        val gameId = finishedGame(r, mapOf(
            wwk to GameTask.WWK_TAKE_GOD, bwk to GameTask.BWK_SHOOT_GOD, hunter to GameTask.HUNTER_SHOOT_WOLF,
            knight to GameTask.KNIGHT_DUEL_WOLF, idiot to GameTask.IDIOT_REVEAL_WIN,
        ))
        // day 1: the white wolf king self-destructs and takes the seer (a god)
        actionLogService.recordSelfDestruct(gameId, 1, wwk, wwk, 1, takenUserId = seer, takenNickname = seer, takenSeatIndex = 7)
        // day 2: voted out, the black wolf king shoots a villager (not a god); the idiot is revealed
        actionLogService.recordHunterShot(gameId, 2, bwk, villager)
        actionLogService.recordIdiotReveal(gameId, 2, idiot)
        // day 3: the knight duels the werewolf and wins; the hunter, voted out, shoots... the witch
        actionLogService.recordKnightDuel(gameId, 3, knight, knight, 5, wolf, wolf, 3, targetIsWolf = true)
        actionLogService.recordHunterShot(gameId, 3, hunter, witch)

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        val done = gamePlayerRepository.findByGameId(gameId).associate { it.userId to it.taskDone }
        assertThat(done[wwk]).`as`("white wolf king took the seer").isTrue()
        assertThat(done[bwk]).`as`("black wolf king shot a villager").isFalse()
        assertThat(done[hunter]).`as`("hunter shot the witch").isFalse()
        assertThat(done[knight]).`as`("knight's duel found a wolf").isTrue()
        assertThat(done[idiot]).`as`("idiot revealed, village won").isTrue()
    }

    @Test
    fun `wolf beauty - a night 殉情 of a god pays 20 戰績分, a day 殉情 of a villager pays nothing`() {
        fun game(tag: String): Pair<Int, Map<PlayerRole, String>> {
            val n = System.nanoTime()
            val r = listOf(
                "wb" to PlayerRole.WOLF_BEAUTY, "w" to PlayerRole.WEREWOLF, "s" to PlayerRole.SEER,
                "wi" to PlayerRole.WITCH, "v" to PlayerRole.VILLAGER, "v2" to PlayerRole.VILLAGER,
            ).map { (id, role) -> "$tag$id-$n" to role }
            val beauty = r.first().first
            return finishedGame(r, mapOf(beauty to GameTask.WOLF_BEAUTY_LINK_GOD)) to r.associate { (id, role) -> role to id }
        }
        // Q2: poisoned at night, the 狼美人 takes the seer along at dawn.
        val (nightGame, nightIds) = game("cn")
        actionLogService.recordNightDeaths(nightGame, 2, listOf(nightIds.getValue(PlayerRole.WOLF_BEAUTY), nightIds.getValue(PlayerRole.SEER)))
        actionLogService.recordCharmDeath(nightGame, 2, nightIds.getValue(PlayerRole.WOLF_BEAUTY), nightIds.getValue(PlayerRole.SEER))
        // Voted out by day, it takes a villager along.
        val (dayGame, dayIds) = game("cd")
        actionLogService.recordCharmDeath(dayGame, 2, dayIds.getValue(PlayerRole.WOLF_BEAUTY), dayIds.getValue(PlayerRole.VILLAGER))

        rewardSettlementService.settle(nightGame, WinnerSide.VILLAGER)
        rewardSettlementService.settle(dayGame, WinnerSide.VILLAGER)

        val nightSeat = gamePlayerRepository.findByGameId(nightGame).single { it.role == PlayerRole.WOLF_BEAUTY }
        assertThat(nightSeat.taskDone).isTrue()
        assertThat(nightSeat.taskScore).isEqualTo(20)
        val daySeat = gamePlayerRepository.findByGameId(dayGame).single { it.role == PlayerRole.WOLF_BEAUTY }
        assertThat(daySeat.taskDone).isFalse()
        assertThat(daySeat.taskScore).isEqualTo(0)
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]
    private operator fun <T> List<T>.component8() = this[7]
    private operator fun <T> List<T>.component9() = this[8]
}
