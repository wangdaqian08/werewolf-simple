package com.werewolf.unit.service

import com.werewolf.config.RewardProperties
import com.werewolf.dto.RewardGroup
import com.werewolf.dto.RewardReason
import com.werewolf.model.*
import com.werewolf.service.RewardBreakdown
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** The game-end credit rules: a base for how you fared, plus the badge and your wolf votes. */
class RewardBreakdownTest {

    private val gameId = 1
    private val mapper = jacksonObjectMapper()
    private val rewards = RewardProperties() // 20 win / 5 participation / 4 per wolf day / 5 badge / 3 per wolf vote

    private val roles = mapOf(
        "w1" to PlayerRole.WEREWOLF,
        "king" to PlayerRole.WHITE_WOLF_KING,
        "seer" to PlayerRole.SEER,
        "v" to PlayerRole.VILLAGER,
    )

    private fun player(id: String, diedDay: Int? = null) =
        GamePlayer(gameId = gameId, userId = id, seatIndex = 1, role = roles.getValue(id))
            .also { p -> diedDay?.let { p.alive = false; p.diedDay = it } }

    private fun event(type: String, payload: Map<String, Any?>) =
        GameEvent(gameId = gameId, eventType = type, message = mapper.writeValueAsString(payload))

    private fun vote(day: Int, voter: String, target: String?) = Vote(
        gameId = gameId,
        voteContext = VoteContext.ELIMINATION,
        dayNumber = day,
        voterUserId = voter,
        targetUserId = target,
    )

    /** [tally]: target to the voters who picked them. Mirrors ActionLogService.recordVoteResult. */
    private fun voteResult(day: Int, tally: List<Pair<String, List<String>>>, abstain: List<String> = emptyList()) =
        event(
            "VOTE_RESULT",
            mapOf(
                "dayNumber" to day,
                "tally" to tally.map { (target, voters) ->
                    mapOf(
                        "userId" to target,
                        "votes" to voters.size,
                        "voters" to voters.map { mapOf("userId" to it) },
                    )
                },
                "abstainVoters" to abstain.map { mapOf("userId" to it) },
            ),
        )

    private fun facts(
        winner: WinnerSide = WinnerSide.VILLAGER,
        finalDay: Int = 3,
        badgeHolders: Set<String> = emptySet(),
        wolfVoteDays: Map<String, List<Int>> = emptyMap(),
    ) = RewardBreakdown.Facts(winner, finalDay, badgeHolders, wolfVoteDays)

    @Test
    fun `every badge holder counts - the elected sheriff and each later recipient`() {
        val events = listOf(
            event("BADGE_HANDOVER", mapOf("dayNumber" to 2, "fromUserId" to "seer", "toUserId" to "v")),
            // A torn badge reaches nobody.
            event("BADGE_HANDOVER", mapOf("dayNumber" to 3, "fromUserId" to "v", "toUserId" to null)),
        )

        assertThat(RewardBreakdown.badgeHolders("seer", events)).containsExactlyInAnyOrder("seer", "v")
        assertThat(RewardBreakdown.badgeHolders(null, emptyList())).isEmpty()
    }

    @Test
    fun `only exile votes that landed on a wolf count, and a vote row is one a day`() {
        val votes = listOf(
            vote(1, "v", "w1"),
            vote(2, "v", "seer"), // a villager: no credit
            vote(3, "v", null), // abstained
            vote(1, "seer", "king"), // the 白狼王 is still a wolf
        )

        assertThat(RewardBreakdown.wolfVoteDays(votes, emptyList()) { roles[it] })
            .isEqualTo(mapOf("v" to listOf(1), "seer" to listOf(1)))
    }

    @Test
    fun `the base is the side you were on and how you fared`() {
        val won = RewardBreakdown.itemsFor(player("v"), facts(), rewards)
        val lostWolf = RewardBreakdown.itemsFor(player("w1", diedDay = 2), facts(), rewards)
        val lostVillager = RewardBreakdown.itemsFor(player("v"), facts(winner = WinnerSide.WEREWOLF), rewards)

        assertThat(won.map { it.reason to it.amount }).containsExactly(RewardReason.WIN to 20)
        assertThat(lostWolf.map { it.reason to it.amount }).containsExactly(RewardReason.WOLF_SURVIVAL to 8)
        assertThat(lostWolf.single().days).isEqualTo(2)
        assertThat(lostVillager.map { it.reason to it.amount }).containsExactly(RewardReason.PARTICIPATION to 5)
    }

    @Test
    fun `a wolf voting a teammate is a play, not a read, and is not paid for it`() {
        val votes = listOf(
            vote(1, "w1", "king"), // 倒钩: a wolf bussing a teammate
            vote(1, "v", "king"), // a villager reading the same wolf
        )

        // Otherwise two wolves could farm the bonus off each other every day.
        assertThat(RewardBreakdown.wolfVoteDays(votes, emptyList()) { roles[it] }).isEqualTo(mapOf("v" to listOf(1)))
    }

    @Test
    fun `a PK candidate keeps the round-1 read that the re-vote deleted`() {
        // Round 1: v and seer both find the wolf. v is then tied onto the PK stage, barred
        // from voting, and the re-vote deletes every round-1 row — so the table has lost v.
        val events = listOf(
            voteResult(1, listOf("king" to listOf("v", "seer"))),
            voteResult(1, listOf("king" to listOf("seer"))),
        )
        val tableAfterRevote = listOf(vote(1, "seer", "king"))

        assertThat(RewardBreakdown.wolfVoteDays(tableAfterRevote, events) { roles[it] })
            .isEqualTo(mapOf("v" to listOf(1), "seer" to listOf(1)))
    }

    @Test
    fun `a re-vote that moves off the wolf is the voter's final word`() {
        val events = listOf(
            voteResult(1, listOf("king" to listOf("v"))),
            voteResult(1, listOf("seer" to listOf("v"))),
        )

        // v could vote again and chose a villager, so there is nothing to pay for.
        assertThat(RewardBreakdown.wolfVoteDays(emptyList(), events) { roles[it] }).isEmpty()
    }

    @Test
    fun `every reason a player can be paid for is in the table players can read`() {
        // A rule that pays but is not listed is a rule nobody can find out about.
        assertThat(RewardBreakdown.catalog(rewards).map { it.reason })
            .containsExactlyInAnyOrderElementsOf(RewardReason.entries)
    }

    @Test
    fun `the one-of base rules are grouped apart from the bonuses that stack on them`() {
        val byGroup = RewardBreakdown.catalog(rewards).groupBy({ it.group }, { it.reason })

        // Exactly the three itemsFor picks between; everything else adds on top.
        assertThat(byGroup[RewardGroup.BASE]).containsExactlyInAnyOrder(
            RewardReason.WIN, RewardReason.PARTICIPATION, RewardReason.WOLF_SURVIVAL,
        )
        assertThat(byGroup[RewardGroup.BONUS]).containsExactlyInAnyOrder(
            RewardReason.SHERIFF, RewardReason.WOLF_VOTE,
        )
    }

    @Test
    fun `a game is floored at zero, so a penalty can never reach credits already banked`() {
        assertThat(RewardBreakdown.floor(-7)).isZero()
        assertThat(RewardBreakdown.floor(0)).isZero()
        assertThat(RewardBreakdown.floor(12)).isEqualTo(12)
    }

    @Test
    fun `the badge and the wolf votes stack on top of the base`() {
        val f = facts(badgeHolders = setOf("v"), wolfVoteDays = mapOf("v" to listOf(1, 3)))
        val items = RewardBreakdown.itemsFor(player("v"), f, rewards)

        assertThat(items.map { it.reason to it.amount }).containsExactly(
            RewardReason.WIN to 20,
            RewardReason.SHERIFF to 5,
            RewardReason.WOLF_VOTE to 6,
        )
        assertThat(items.last().onDays).containsExactly(1, 3)
        assertThat(RewardBreakdown.totalFor(player("v"), f, rewards)).isEqualTo(31)
    }
}
