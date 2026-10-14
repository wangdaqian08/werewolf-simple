package com.werewolf.unit.service

import com.werewolf.model.*
import com.werewolf.service.RoleHistoryBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RoleHistoryBuilderTest {

    private val gameId = 1

    private fun player(userId: String, seat: Int, role: PlayerRole, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private val witch = player("witch", 1, PlayerRole.WITCH)
    private val seer = player("seer", 2, PlayerRole.SEER)
    private val guard = player("guard", 3, PlayerRole.GUARD)
    private val wolf = player("wolf", 4, PlayerRole.WEREWOLF)
    private val king = player("king", 5, PlayerRole.WHITE_WOLF_KING)
    private val v1 = player("v1", 6, PlayerRole.VILLAGER)
    private val v2 = player("v2", 7, PlayerRole.VILLAGER)
    private val players = listOf(witch, seer, guard, wolf, king, v1, v2)

    private fun game(phase: GamePhase, day: Int) =
        Game(roomId = 1, hostUserId = "host").also { it.phase = phase; it.dayNumber = day }

    private fun night(day: Int, block: NightPhase.() -> Unit = {}) =
        NightPhase(gameId = gameId, dayNumber = day).also { it.subPhase = NightSubPhase.COMPLETE; it.block() }

    private fun build(me: GamePlayer, game: Game, nights: List<NightPhase>) =
        RoleHistoryBuilder.build(me, game, nights, players) { "N-$it" }

    @Suppress("UNCHECKED_CAST")
    private fun entries(h: Map<String, Any?>?) = h!!["entries"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun target(e: Map<String, Any?>) = e["target"] as Map<String, Any?>?

    @Test
    fun `seer sees every check with round, seat, name and result`() {
        val nights = listOf(
            night(1) { seerCheckedUserId = "wolf"; seerResultIsWerewolf = true },
            night(2) { seerCheckedUserId = "v1"; seerResultIsWerewolf = false },
        )
        val h = build(seer, game(GamePhase.DAY_DISCUSSION, 2), nights)

        assertThat(h!!["role"]).isEqualTo("SEER")
        val e = entries(h)
        assertThat(e.map { it["round"] }).containsExactly(1, 2)
        assertThat(target(e[0])).isEqualTo(mapOf("userId" to "wolf", "nickname" to "N-wolf", "seatIndex" to 4))
        assertThat(e.map { it["isWerewolf"] }).containsExactly(true, false)
    }

    @Test
    fun `guard sees who was guarded each night, including a skipped night`() {
        val nights = listOf(night(1) { guardTargetUserId = "v1" }, night(2))
        val e = entries(build(guard, game(GamePhase.DAY_VOTING, 2), nights))

        assertThat(e.map { it["round"] }).containsExactly(1, 2)
        assertThat(target(e[0])!!["userId"]).isEqualTo("v1")
        assertThat(target(e[1])).isNull()
    }

    @Test
    fun `every wolf, including the white wolf king, sees the wolf kills and 空刀 nights`() {
        val nights = listOf(night(1) { wolfTargetUserId = "v1" }, night(2))
        for (me in listOf(wolf, king)) {
            val h = build(me, game(GamePhase.DAY_DISCUSSION, 2), nights)
            assertThat(h!!["role"]).isEqualTo("WOLF")
            val e = entries(h)
            assertThat(target(e[0])!!["userId"]).isEqualTo("v1")
            assertThat(target(e[1])).isNull()
        }
    }

    @Test
    fun `witch sees antidote save and poison with their rounds`() {
        val nights = listOf(
            night(1) { wolfTargetUserId = "v1"; witchAntidoteUsed = true },
            night(2) { wolfTargetUserId = "v2"; witchPoisonTargetUserId = "wolf" },
        )
        val h = build(witch, game(GamePhase.DAY_DISCUSSION, 2), nights)

        val e = entries(h)
        assertThat(e.map { it["round"] to it["action"] }).containsExactly(1 to "ANTIDOTE", 2 to "POISON")
        assertThat(target(e[0])!!["userId"]).isEqualTo("v1")
        assertThat(target(e[1])!!["userId"]).isEqualTo("wolf")
        // Antidote already used → last night's victim is not shown.
        assertThat(h!!["lastNightAttacked"]).isNull()
    }

    @Test
    fun `witch sees last night's victim while the antidote is unused`() {
        val nights = listOf(night(1), night(2) { wolfTargetUserId = "v2" })
        val h = build(witch, game(GamePhase.DAY_DISCUSSION, 2), nights)

        @Suppress("UNCHECKED_CAST")
        val last = h!!["lastNightAttacked"] as Map<String, Any?>
        assertThat(last["round"]).isEqualTo(2)
        assertThat(target(last)!!["userId"]).isEqualTo("v2")
        assertThat(entries(h)).isEmpty()
    }

    @Test
    fun `the night still in progress is not part of the history`() {
        val nights = listOf(
            night(1) { seerCheckedUserId = "v1"; seerResultIsWerewolf = false },
            night(2) { seerCheckedUserId = "wolf"; seerResultIsWerewolf = true; subPhase = NightSubPhase.SEER_PICK },
        )
        val e = entries(build(seer, game(GamePhase.NIGHT, 2), nights))
        assertThat(e.map { it["round"] }).containsExactly(1)
    }

    @Test
    fun `dead players and roles without night tools get no history`() {
        val nights = listOf(night(1) { seerCheckedUserId = "v1"; seerResultIsWerewolf = false })
        val deadSeer = player("seer", 2, PlayerRole.SEER, alive = false)

        assertThat(build(deadSeer, game(GamePhase.DAY_DISCUSSION, 1), nights)).isNull()
        assertThat(build(v1, game(GamePhase.DAY_DISCUSSION, 1), nights)).isNull()
        assertThat(build(player("h", 8, PlayerRole.HUNTER), game(GamePhase.DAY_DISCUSSION, 1), nights)).isNull()
    }
}
