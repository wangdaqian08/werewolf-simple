package com.werewolf.integration

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.ActionLogService
import com.werewolf.service.RewardSettlementService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

/**
 * The reward chain end to end against a real database: real vote rows, real action-log
 * events, real settlement. The unit tests mock those away, so this is what proves the
 * badge and the wolf-vote bonus survive the round trip — including the PK re-vote, which
 * deletes the round-1 rows and so would lose a candidate's read if only the table were read.
 */
@SpringBootTest
@ActiveProfiles("test")
class RewardSettlementIntegrationTest {

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var voteRepository: VoteRepository
    @Autowired lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var actionLogService: ActionLogService
    @Autowired lateinit var rewardSettlementService: RewardSettlementService

    private fun user(id: String) = userRepository.save(User(userId = id, nickname = "N-$id"))

    /** Seats 1..n in roster order; the game is already over and the village has won. */
    private fun seed(roster: List<Pair<String, PlayerRole>>): Int {
        roster.forEach { (id, _) -> user(id) }
        val host = roster.first().first
        val room = roomRepository.save(
            Room(roomCode = TestConstants.nextSeededRoomCode(), hostUserId = host, totalPlayers = roster.size),
        )
        val gameId = gameRepository.save(
            Game(roomId = room.roomId!!, hostUserId = host).also {
                it.phase = GamePhase.GAME_OVER
                it.winner = WinnerSide.VILLAGER
                it.dayNumber = 1
                it.endedAt = LocalDateTime.now()
            },
        ).gameId!!
        gamePlayerRepository.saveAll(
            roster.mapIndexed { i, (id, role) -> GamePlayer(gameId = gameId, userId = id, seatIndex = i + 1, role = role) },
        )
        return gameId
    }

    private fun vote(gameId: Int, day: Int, voter: String, target: String?) = voteRepository.save(
        Vote(
            gameId = gameId,
            voteContext = VoteContext.ELIMINATION,
            dayNumber = day,
            voterUserId = voter,
            targetUserId = target,
        ),
    )

    private fun earned(gameId: Int): Map<String, Int> =
        creditTransactionRepository.findByGameIdAndType(gameId, CreditTxType.GAME_REWARD)
            .associate { it.userId to it.amount }

    @Test
    fun `the badge and a correct wolf vote are banked on top of the base`() {
        val gameId = seed(listOf("rsW" to PlayerRole.WEREWOLF, "rsV" to PlayerRole.VILLAGER, "rsS" to PlayerRole.SEER))
        sheriffElectionRepository.save(SheriffElection(gameId = gameId).also { it.electedSheriffUserId = "rsV" })
        val votes = listOf(vote(gameId, 1, "rsV", "rsW"), vote(gameId, 1, "rsS", "rsV"))
        actionLogService.recordVoteResult(gameId, 1, votes, mapOf("rsW" to 1.0, "rsV" to 1.0), null, null)

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        // rsV: 20 win + 5 badge + 3 for finding the wolf. rsS read a villager, so base only.
        assertThat(earned(gameId)).containsEntry("rsV", 28).containsEntry("rsS", 20)
        // The wolf lost on day 1, so one day of survival pay and nothing else.
        assertThat(earned(gameId)).containsEntry("rsW", 4)
    }

    @Test
    fun `a wolf is not paid for voting out a teammate`() {
        val gameId = seed(listOf("bsW1" to PlayerRole.WEREWOLF, "bsW2" to PlayerRole.WEREWOLF, "bsV" to PlayerRole.VILLAGER))
        val votes = listOf(vote(gameId, 1, "bsW1", "bsW2"))
        actionLogService.recordVoteResult(gameId, 1, votes, mapOf("bsW2" to 1.0), null, null)

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        // 倒钩 is a play, not a read: survival pay only, no +3 on top.
        assertThat(earned(gameId)).containsEntry("bsW1", 4)
    }

    @Test
    fun `a PK candidate keeps the round-1 read the re-vote deleted`() {
        val gameId = seed(listOf("pkW" to PlayerRole.WEREWOLF, "pkV" to PlayerRole.VILLAGER, "pkS" to PlayerRole.SEER))
        // Round 1: both good players find the wolf, and pkV is tied onto the PK stage.
        val round1 = listOf(vote(gameId, 1, "pkV", "pkW"), vote(gameId, 1, "pkS", "pkW"))
        actionLogService.recordVoteResult(gameId, 1, round1, mapOf("pkW" to 2.0), null, null)
        // The pipeline clears every round-1 row; pkV may not vote again, so it has no replacement.
        voteRepository.deleteAll(round1)
        val revote = listOf(vote(gameId, 1, "pkS", "pkW"))
        actionLogService.recordVoteResult(gameId, 1, revote, mapOf("pkW" to 1.0), null, "pkW")

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        // Standing on the PK stage must not cost pkV the read they had already made.
        assertThat(earned(gameId)).containsEntry("pkV", 23).containsEntry("pkS", 23)
    }
}
