package com.werewolf.game.voting

import com.werewolf.model.Vote

/**
 * Utility class for calculating weighted voting tallies.
 *
 * This class handles vote counting with special weights for the sheriff.
 * The sheriff's vote counts as 1.5x during elimination voting phases.
 */
object TallyCalculator {

    /**
     * Calculates weighted vote tallies for a list of votes.
     *
     * @param votes List of votes to count
     * @param sheriffUserId User ID of the current sheriff (null if no sheriff)
     * @return Map of target user IDs to their weighted vote counts (as Double)
     */
    fun calculateWeightedTally(
        votes: List<Vote>,
        sheriffUserId: String?
    ): Map<String, Double> {
        val result = mutableMapOf<String, Double>()

        for (vote in votes) {
            val targetId = vote.targetUserId ?: continue // Skip abstain votes
            val weight = if (vote.voterUserId == sheriffUserId) 1.5 else 1.0
            result[targetId] = (result[targetId] ?: 0.0) + weight
        }

        return result
    }

    /**
     * Finds the top candidate from a weighted tally.
     *
     * @param tally Map of candidate IDs to their weighted vote counts
     * @return The user ID of the winner, or null if there's a tie or no votes
     */
    fun findTopCandidate(tally: Map<String, Double>): String? {
        if (tally.isEmpty()) return null

        val maxVotes = tally.values.maxOrNull() ?: return null
        if (maxVotes <= 0) return null

        // Find all candidates with the maximum votes (within floating-point tolerance)
        val epsilon = 0.001
        val topCandidates = tally.filterValues { abs(it - maxVotes) < epsilon }.keys

        // Return the winner only if there's exactly one top candidate
        return if (topCandidates.size == 1) topCandidates.first() else null
    }

    /**
     * Helper function to calculate absolute difference between two doubles.
     * This is used for floating-point comparison with tolerance.
     */
    private fun abs(value: Double): Double = if (value < 0) -value else value
}