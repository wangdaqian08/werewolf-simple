package com.werewolf.unit.game

import com.werewolf.game.RoleDealer
import com.werewolf.model.PlayerRole
import com.werewolf.model.PlayerRole.*
import com.werewolf.model.RolePickChoice
import org.junit.jupiter.api.Test
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class RoleDealerTest {

    private val board9 = listOf(WEREWOLF, WEREWOLF, WEREWOLF, SEER, WITCH, HUNTER, VILLAGER, VILLAGER, VILLAGER)
    private val ids9 = (1..9).map { "p$it" }

    @Test
    fun `every role is dealt exactly once`() {
        val picks = mapOf("p1" to RolePickChoice.WOLF, "p2" to RolePickChoice.GOD, "p3" to RolePickChoice.VILLAGER,
            "p4" to RolePickChoice.WOLF, "p5" to RolePickChoice.NONE)
        for (seed in 0..199) {
            val dealt = RoleDealer.deal(board9, ids9, picks, Random(seed))
            assertEquals(ids9.toSet(), dealt.keys)
            assertEquals(board9.sorted(), dealt.values.sorted())
        }
    }

    @Test
    fun `no picks is a uniform shuffle`() {
        val wolfCount = IntArray(9)
        for (seed in 0 until 2000) {
            val dealt = RoleDealer.deal(board9, ids9, emptyMap(), Random(seed))
            ids9.forEachIndexed { i, id -> if (dealt.getValue(id) == WEREWOLF) wolfCount[i]++ }
        }
        wolfCount.forEach { assertTrue(it in 500..820, "wolf count $it outside 25-41%") }
    }

    @Test
    fun `under-subscribed camp gives every holder that camp`() {
        val picks = mapOf("p1" to RolePickChoice.WOLF, "p2" to RolePickChoice.WOLF)
        for (seed in 0..199) {
            val dealt = RoleDealer.deal(board9, ids9, picks, Random(seed))
            assertTrue(dealt.getValue("p1").isWolf)
            assertTrue(dealt.getValue("p2").isWolf)
        }
    }

    @Test
    fun `over-subscribed camp draws exactly seats-many winners at random`() {
        val pickers = listOf("p1", "p2", "p3", "p4", "p5")
        val picks = pickers.associateWith { RolePickChoice.WOLF }
        val wins = IntArray(5)
        for (seed in 0 until 2000) {
            val dealt = RoleDealer.deal(board9, ids9, picks, Random(seed))
            val winners = pickers.filterIndexed { i, id -> dealt.getValue(id).isWolf.also { if (it) wins[i]++ } }
            assertEquals(3, winners.size)
            // all 3 wolves went to pickers, so the rest hold no wolf
            assertTrue(ids9.filter { it !in pickers }.none { dealt.getValue(it).isWolf })
        }
        wins.forEach { assertTrue(it in 800..1600, "win count $it outside 40-80%") }
    }

    @Test
    fun `zero-seat camp never matches`() {
        val board = listOf(WEREWOLF, WEREWOLF, SEER, WITCH)
        val ids = listOf("a", "b", "c", "d")
        for (seed in 0..199) {
            val role = RoleDealer.deal(board, ids, mapOf("a" to RolePickChoice.VILLAGER), Random(seed)).getValue("a")
            assertFalse(RolePickChoice.VILLAGER.matches(role))
        }
    }

    @Test
    fun `exact role inside a camp is random`() {
        val board = listOf(WEREWOLF, WEREWOLF, SEER, WITCH, HUNTER, VILLAGER)
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val seen = mutableMapOf<PlayerRole, Int>()
        for (seed in 0 until 600) {
            val role = RoleDealer.deal(board, ids, mapOf("a" to RolePickChoice.GOD), Random(seed)).getValue("a")
            seen.merge(role, 1, Int::plus)
        }
        listOf(SEER, WITCH, HUNTER).forEach { assertTrue((seen[it] ?: 0) >= 120, "$it seen ${seen[it]}") }
    }

    @Test
    fun `campSeats counts each camp`() {
        val seats = RoleDealer.campSeats(listOf(WHITE_WOLF_KING, MECHANICAL_WOLF, WEREWOLF, SEER, MEDIUM, VILLAGER))
        assertEquals(mapOf(RolePickChoice.WOLF to 3, RolePickChoice.GOD to 2, RolePickChoice.VILLAGER to 1), seats)
    }

    @Test
    fun `matches and campOf`() {
        assertEquals(RolePickChoice.WOLF, RolePickChoice.campOf(MECHANICAL_WOLF))
        assertEquals(RolePickChoice.GOD, RolePickChoice.campOf(MEDIUM))
        assertEquals(RolePickChoice.GOD, RolePickChoice.campOf(IDIOT))
        assertEquals(RolePickChoice.VILLAGER, RolePickChoice.campOf(VILLAGER))
        PlayerRole.entries.forEach { assertFalse(RolePickChoice.NONE.matches(it)) }
    }
}
