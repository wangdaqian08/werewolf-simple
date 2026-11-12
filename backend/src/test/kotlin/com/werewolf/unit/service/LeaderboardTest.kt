package com.werewolf.unit.service

import com.werewolf.service.ScoreRow
import com.werewolf.service.leaderboardOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** The ranking rule of the 戰績分 leaderboard: score first, then whoever got there first. */
class LeaderboardTest {

    private val t0 = LocalDateTime.of(2026, 11, 1, 20, 0)
    private fun row(user: String, score: Long, reachedAt: Int) = ScoreRow(user, score, t0.plusMinutes(reachedAt.toLong()))
    private fun board(me: String, vararg rows: ScoreRow) = leaderboardOf(rows.toList(), me) { it.uppercase() }

    @Test
    fun `top three by score, highest first`() {
        val b = board("z", row("a", 10, 1), row("b", 40, 2), row("c", 30, 3), row("d", 20, 4))
        assertThat(b.top.map { it.nickname }).containsExactly("B", "C", "D")
        assertThat(b.top.map { it.rank }).containsExactly(1, 2, 3)
        assertThat(b.top.map { it.score }).containsExactly(40, 30, 20)
    }

    @Test
    fun `equal scores - whoever reached it first ranks higher`() {
        val b = board("z", row("late", 30, 9), row("early", 30, 4), row("top", 50, 7))
        assertThat(b.top.map { it.nickname }).containsExactly("TOP", "EARLY", "LATE")
        assertThat(b.top.map { it.rank }).containsExactly(1, 2, 3)
    }

    @Test
    fun `same score reached in the same game - the order is stable, whatever order the rows come in`() {
        val rows = listOf(row("guest:b", 20, 5), row("guest:c", 20, 5), row("guest:a", 20, 5))
        val orders = listOf(rows, rows.reversed(), rows.shuffled(java.util.Random(7)))
            .map { leaderboardOf(it, "z") { id -> id }.top.map { e -> e.nickname } }
        assertThat(orders.toSet()).hasSize(1)
    }

    @Test
    fun `a caller in the top three is flagged there and reported as me`() {
        val b = board("b", row("a", 50, 1), row("b", 40, 2), row("c", 30, 3))
        assertThat(b.top.map { it.me }).containsExactly(false, true, false)
        assertThat(b.me?.rank).isEqualTo(2)
        assertThat(b.me?.score).isEqualTo(40)
        assertThat(b.me?.nickname).isEqualTo("B")
    }

    @Test
    fun `a caller outside the top three gets their real rank`() {
        val b = board("e", row("a", 50, 1), row("b", 40, 2), row("c", 30, 3), row("d", 20, 4), row("e", 10, 5))
        assertThat(b.top.map { it.me }).containsOnly(false)
        assertThat(b.me?.rank).isEqualTo(5)
        assertThat(b.me?.score).isEqualTo(10)
        assertThat(b.me?.me).isTrue()
    }

    @Test
    fun `a caller with no score is unranked`() {
        val b = board("nobody", row("a", 50, 1))
        assertThat(b.me).isNull()
        assertThat(b.top).hasSize(1)
    }

    @Test
    fun `nobody has played yet`() {
        val b = board("me")
        assertThat(b.top).isEmpty()
        assertThat(b.me).isNull()
    }
}
