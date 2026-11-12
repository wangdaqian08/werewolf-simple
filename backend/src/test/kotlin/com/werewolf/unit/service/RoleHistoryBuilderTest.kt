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

    private val mech = player("mech", 8, PlayerRole.MECHANICAL_WOLF)
    private val medium = player("medium", 9, PlayerRole.MEDIUM)

    private fun build(me: GamePlayer, game: Game, nights: List<NightPhase>, roster: List<GamePlayer> = players + mech + medium) =
        RoleHistoryBuilder.build(me, game, nights, roster) { "N-$it" }

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
    fun `gravekeeper history lists exiles with camp, skips NONE nights and tonight`() {
        val keeper = player("keeper", 10, PlayerRole.GRAVEKEEPER)
        val nights = listOf(
            night(1) { gravekeeperResult = GravekeeperResult.NONE },
            night(2) { gravekeeperResult = GravekeeperResult.WOLF; gravekeeperExiledUserId = "wolf" },
            night(3) { gravekeeperResult = GravekeeperResult.GOOD; gravekeeperExiledUserId = "v1" },
            night(4) { gravekeeperResult = GravekeeperResult.WOLF; gravekeeperExiledUserId = "king" },
        )
        val h = build(keeper, game(GamePhase.NIGHT, 4), nights, players + keeper)

        assertThat(h!!["role"]).isEqualTo("GRAVEKEEPER")
        val e = entries(h)
        assertThat(e.map { it["round"] to it["action"] }).containsExactly(2 to "GRAVE", 3 to "GRAVE")
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
    fun `witch holding the antidote sees every night's wolf victim, 空刀 nights included`() {
        val nights = listOf(
            night(1) { wolfTargetUserId = "v1" },
            night(2),
            night(3) { wolfTargetUserId = "v2"; witchPoisonTargetUserId = "wolf" },
        )
        val e = entries(build(witch, game(GamePhase.DAY_DISCUSSION, 3), nights))

        assertThat(e.map { it["round"] to it["action"] })
            .containsExactly(1 to "ATTACKED", 2 to "ATTACKED", 3 to "ATTACKED", 3 to "POISON")
        assertThat(e.map { target(it)?.get("userId") }).containsExactly("v1", null, "v2", "wolf")
    }

    @Test
    fun `witch keeps the victims she saw up to the antidote night but is told no victim after it`() {
        val nights = listOf(
            night(1) { wolfTargetUserId = "v1" },
            night(2) { wolfTargetUserId = "v2"; witchAntidoteUsed = true },
            night(3) { wolfTargetUserId = "seer"; witchPoisonTargetUserId = "wolf" },
        )
        val e = entries(build(witch, game(GamePhase.DAY_VOTING, 3), nights))

        assertThat(e.map { it["round"] to it["action"] })
            .containsExactly(1 to "ATTACKED", 2 to "ANTIDOTE", 3 to "POISON")
        assertThat(e.map { target(it)?.get("userId") }).containsExactly("v1", "v2", "wolf")
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

    @Test
    fun `mechanical wolf history shows its own actions, never the pack's kills`() {
        mech.learnedRole = PlayerRole.MEDIUM
        val nights = listOf(
            night(1) { wolfTargetUserId = "v1"; mechLearnTargetUserId = "medium" },
            night(2) { wolfTargetUserId = "v2"; mechCheckTargetUserId = "wolf"; mechCheckResultRole = PlayerRole.WEREWOLF },
            night(3) { mechPoisonTargetUserId = "v1"; mechGuardTargetUserId = "v2" },
        )
        val h = build(mech, game(GamePhase.DAY_DISCUSSION, 3), nights)

        assertThat(h!!["role"]).isEqualTo("MECHANICAL_WOLF")
        val e = entries(h)
        assertThat(e.map { it["round"] to it["action"] })
            .containsExactly(1 to "LEARN", 2 to "CHECK", 3 to "POISON", 3 to "PROTECT")
        assertThat(target(e[0])!!["userId"]).isEqualTo("medium")
        assertThat(e[1]["role"]).isEqualTo(PlayerRole.WEREWOLF)
    }

    @Test
    fun `mechanical wolf that learned SEER gets an alignment check - isWerewolf and no role`() {
        mech.learnedRole = PlayerRole.SEER
        val nights = listOf(
            night(1) { mechLearnTargetUserId = "seer" },
            night(2) { mechCheckTargetUserId = "wolf"; mechCheckResultRole = PlayerRole.WEREWOLF },
        )
        val e = entries(build(mech, game(GamePhase.DAY_DISCUSSION, 2), nights))

        val check = e.single { it["action"] == "CHECK" }
        assertThat(check["isWerewolf"]).isEqualTo(true)
        assertThat(check).doesNotContainKey("role")
    }

    @Test
    fun `mechanical wolf history carries a KILL only on nights it was the lone killer`() {
        val deadPack = listOf(
            player("wolf", 4, PlayerRole.WEREWOLF).also { it.alive = false; it.diedDay = 1 },
            player("king", 5, PlayerRole.WHITE_WOLF_KING).also { it.alive = false; it.diedDay = 1 },
            mech, v1, v2,
        )
        val nights = listOf(night(1) { wolfTargetUserId = "v1" }, night(2) { wolfTargetUserId = "v2" })
        val e = entries(build(mech, game(GamePhase.DAY_DISCUSSION, 2), nights, deadPack))

        assertThat(e.map { it["round"] to it["action"] }).containsExactly(2 to "KILL")
        assertThat(target(e[0])!!["userId"]).isEqualTo("v2")
    }

    @Test
    fun `medium history carries roles`() {
        val nights = listOf(
            night(1),
            night(2) { mediumCheckedUserId = "seer"; mediumResultRole = PlayerRole.SEER },
        )
        val h = build(medium, game(GamePhase.DAY_DISCUSSION, 2), nights)

        assertThat(h!!["role"]).isEqualTo("MEDIUM")
        val e = entries(h)
        assertThat(e.map { it["round"] to it["action"] }).containsExactly(2 to "CHECK")
        assertThat(e.single()["role"]).isEqualTo(PlayerRole.SEER)
        assertThat(target(e.single())!!["userId"]).isEqualTo("seer")
    }
}
