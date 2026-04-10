package com.werewolf.game.voting

import com.werewolf.model.Vote
import com.werewolf.model.VoteContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TallyCalculatorTest {

    private val gameId = 1
    private val dayNumber = 1

    // ── Helper functions ───────────────────────────────────────────────────────

    private fun createVote(voterId: String, targetId: String): Vote {
        return Vote(
            gameId = gameId,
            voteContext = VoteContext.ELIMINATION,
            dayNumber = dayNumber,
            voterUserId = voterId,
            targetUserId = targetId,
            votedAt = LocalDateTime.now()
        )
    }

    private fun createAbstainVote(voterId: String): Vote {
        return Vote(
            gameId = gameId,
            voteContext = VoteContext.ELIMINATION,
            dayNumber = dayNumber,
            voterUserId = voterId,
            targetUserId = null, // Abstain
            votedAt = LocalDateTime.now()
        )
    }

    // ── calculateWeightedTally tests ───────────────────────────────────────────

    @Test
    fun sheriffVoteHas1_5xWeight() {
        val sheriffId = "sheriff"
        val votes = listOf(
            createVote(voterId = sheriffId, targetId = "playerA"),
            createVote(voterId = "playerB", targetId = "playerA"),
            createVote(voterId = "playerC", targetId = "playerB")
        )

        val tally = TallyCalculator.calculateWeightedTally(votes, sheriffId)

        assertThat(tally).hasSize(2)
        assertThat(tally["playerA"]).isEqualTo(2.5) // Sheriff (1.5) + playerB (1.0)
        assertThat(tally["playerB"]).isEqualTo(1.0) // playerC (1.0)
    }

    @Test
    fun regularVoteHas1_0xWeight() {
        val sheriffId = "sheriff"
        val votes = listOf(
            createVote(voterId = "playerA", targetId = "playerX"),
            createVote(voterId = "playerB", targetId = "playerX"),
            createVote(voterId = "playerC", targetId = "playerY")
        )

        val tally = TallyCalculator.calculateWeightedTally(votes, sheriffId)

        assertThat(tally).hasSize(2)
        assertThat(tally["playerX"]).isEqualTo(2.0) // Two regular votes
        assertThat(tally["playerY"]).isEqualTo(1.0) // One regular vote
    }

    @Test
    fun sheriffAbstentionDoesNotAddWeight() {
        val sheriffId = "sheriff"
        val votes = listOf(
            createAbstainVote(voterId = sheriffId), // Sheriff abstains
            createVote(voterId = "playerB", targetId = "playerA"),
            createVote(voterId = "playerC", targetId = "playerB")
        )

        val tally = TallyCalculator.calculateWeightedTally(votes, sheriffId)

        assertThat(tally).hasSize(2)
        assertThat(tally["playerA"]).isEqualTo(1.0) // Only playerB's vote
        assertThat(tally["playerB"]).isEqualTo(1.0) // Only playerC's vote
    }

    @Test
    fun noSheriffUsesRegularWeights() {
        val votes = listOf(
            createVote(voterId = "playerA", targetId = "playerX"),
            createVote(voterId = "playerB", targetId = "playerX"),
            createVote(voterId = "playerC", targetId = "playerY")
        )

        val tally = TallyCalculator.calculateWeightedTally(votes, null)

        assertThat(tally).hasSize(2)
        assertThat(tally["playerX"]).isEqualTo(2.0) // Two regular votes
        assertThat(tally["playerY"]).isEqualTo(1.0) // One regular vote
    }

    @Test
    fun emptyVotesReturnsEmptyTally() {
        val votes = emptyList<Vote>()

        val tally = TallyCalculator.calculateWeightedTally(votes, "sheriff")

        assertThat(tally).isEmpty()
    }

    @Test
    fun allAbstainVotesReturnsEmptyTally() {
        val votes = listOf(
            createAbstainVote(voterId = "playerA"),
            createAbstainVote(voterId = "playerB"),
            createAbstainVote(voterId = "playerC")
        )

        val tally = TallyCalculator.calculateWeightedTally(votes, "sheriff")

        assertThat(tally).isEmpty()
    }

    @Test
    fun multipleSheriffVotesEdgeCase() {
        // This shouldn't happen in real gameplay, but test defensive behavior
        val sheriffId = "sheriff1"
        val votes = listOf(
            createVote(voterId = sheriffId, targetId = "playerA"),
            createVote(voterId = "sheriff2", targetId = "playerA"), // Another "sheriff"
            createVote(voterId = "playerB", targetId = "playerB")
        )

        val tally = TallyCalculator.calculateWeightedTally(votes, sheriffId)

        assertThat(tally).hasSize(2)
        assertThat(tally["playerA"]).isEqualTo(2.5) // sheriff1 (1.5) + sheriff2 (1.0, not the sheriff)
        assertThat(tally["playerB"]).isEqualTo(1.0) // playerB (1.0)
    }

    @Test
    fun floatingPointPrecisionHandling() {
        val sheriffId = "sheriff"
        val votes = listOf(
            createVote(voterId = sheriffId, targetId = "playerA"),
            createVote(voterId = "playerB", targetId = "playerA"),
            createVote(voterId = "playerC", targetId = "playerA")
        )

        val tally = TallyCalculator.calculateWeightedTally(votes, sheriffId)

        assertThat(tally["playerA"]).isEqualTo(3.5) // 1.5 + 1.0 + 1.0
    }

    // ── findTopCandidate tests ─────────────────────────────────────────────────

    @Test
    fun findTopCandidateSingleWinner() {
        val tally = mapOf(
            "playerA" to 3.5,
            "playerB" to 2.0,
            "playerC" to 1.0
        )

        val winner = TallyCalculator.findTopCandidate(tally)

        assertThat(winner).isEqualTo("playerA")
    }

    @Test
    fun findTopCandidateTieReturnsNull() {
        val tally = mapOf(
            "playerA" to 2.0,
            "playerB" to 2.0,
            "playerC" to 1.0
        )

        val winner = TallyCalculator.findTopCandidate(tally)

        assertThat(winner).isNull()
    }

    @Test
    fun findTopCandidateSheriffTieBreaker() {
        val tally = mapOf(
            "playerA" to 1.5, // Sheriff vote
            "playerB" to 2.0  // Two regular votes
        )

        val winner = TallyCalculator.findTopCandidate(tally)

        assertThat(winner).isEqualTo("playerB") // 2.0 > 1.5
    }

    @Test
    fun findTopCandidateSheriffWins() {
        val tally = mapOf(
            "playerA" to 1.5, // Sheriff vote
            "playerB" to 1.0  // One regular vote
        )

        val winner = TallyCalculator.findTopCandidate(tally)

        assertThat(winner).isEqualTo("playerA") // 1.5 > 1.0
    }

    @Test
    fun findTopCandidateEmptyTallyReturnsNull() {
        val tally = emptyMap<String, Double>()

        val winner = TallyCalculator.findTopCandidate(tally)

        assertThat(winner).isNull()
    }

    @Test
    fun findTopCandidateAllZeroVotesReturnsNull() {
        val tally = mapOf(
            "playerA" to 0.0,
            "playerB" to 0.0
        )

        val winner = TallyCalculator.findTopCandidate(tally)

        assertThat(winner).isNull()
    }

    @Test
    fun findTopCandidateSingleCandidateWins() {
        val tally = mapOf(
            "playerA" to 1.0
        )

        val winner = TallyCalculator.findTopCandidate(tally)

        assertThat(winner).isEqualTo("playerA")
    }

    @Test
    fun findTopCandidateThreeWayTieReturnsNull() {
        val tally = mapOf(
            "playerA" to 2.0,
            "playerB" to 2.0,
            "playerC" to 2.0
        )

        val winner = TallyCalculator.findTopCandidate(tally)

        assertThat(winner).isNull()
    }

    @Test
    fun findTopCandidateFloatingPointToleranceForTieDetection() {
        val tally = mapOf(
            "playerA" to 2.000001, // Slightly above 2.0 due to floating point
            "playerB" to 2.0
        )

        val winner = TallyCalculator.findTopCandidate(tally)

        // Should detect as tie (within epsilon tolerance)
        assertThat(winner).isNull()
    }
}