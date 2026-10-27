package com.werewolf.unit.service

import com.werewolf.dto.*
import com.werewolf.model.*
import com.werewolf.service.PlayerReviewBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * One player's replay (玩家视角复盘). Same board as [GameReviewBuilderTest], seats 1-9:
 * w1, w2 (狼人), king (白狼王), seer, witch, guard, hunter, idiot, v (村民).
 */
class PlayerReviewBuilderTest {

    private val gameId = 1
    private val mapper = jacksonObjectMapper()

    private val roster = listOf(
        "w1" to PlayerRole.WEREWOLF, "w2" to PlayerRole.WEREWOLF, "king" to PlayerRole.WHITE_WOLF_KING,
        "seer" to PlayerRole.SEER, "witch" to PlayerRole.WITCH, "guard" to PlayerRole.GUARD,
        "hunter" to PlayerRole.HUNTER, "idiot" to PlayerRole.IDIOT, "v" to PlayerRole.VILLAGER,
    )

    /** [diedDay]: userId → day of death (a night-N kill and a day-N exile are both day N). */
    private fun board(diedDay: Map<String, Int> = emptyMap()) =
        roster.mapIndexed { i, (id, role) ->
            GamePlayer(gameId = gameId, userId = id, seatIndex = i + 1, role = role).also { p ->
                diedDay[id]?.let { p.alive = false; p.diedDay = it }
            }
        }

    /** Default role and camp null: the seat the viewer never worked out. */
    private fun seat(id: String, role: PlayerRole? = null, camp: KnownCamp? = null) =
        board().first { it.userId == id }.let { PlayerSeat(id, "N-$id", it.seatIndex, role, camp) }

    private fun night(day: Int, block: NightPhase.() -> Unit = {}) =
        NightPhase(gameId = gameId, dayNumber = day).also { it.subPhase = NightSubPhase.COMPLETE; it.block() }

    private fun event(type: String, payload: Map<String, Any?>) =
        GameEvent(gameId = gameId, eventType = type, message = mapper.writeValueAsString(payload))

    private fun nightDeath(day: Int, userId: String) =
        event("NIGHT_DEATH", mapOf("dayNumber" to day, "userId" to userId))

    private fun voteResult(day: Int, eliminated: String?) = event(
        "VOTE_RESULT",
        mapOf(
            "dayNumber" to day,
            "eliminatedUserId" to eliminated,
            "tally" to emptyList<Map<String, Any?>>(),
            "abstainVoters" to emptyList<Map<String, Any?>>(),
        ),
    )

    private fun selfDestruct(day: Int, userId: String, taken: String? = null) = event(
        "SELF_DESTRUCT",
        buildMap {
            put("dayNumber", day)
            put("userId", userId)
            if (taken != null) put("takenUserId", taken)
        },
    )

    private fun idiotReveal(day: Int, userId: String) =
        event("IDIOT_REVEAL", mapOf("dayNumber" to day, "userId" to userId))

    private fun build(
        viewer: String,
        players: List<GamePlayer>,
        nights: List<NightPhase>,
        events: List<GameEvent> = emptyList(),
        perkSaved: Set<String> = emptySet(),
        winner: WinnerSide? = WinnerSide.WEREWOLF,
        rewards: Map<String, Int> = emptyMap(),
        wolfPerDay: Int = 4,
    ) = PlayerReviewBuilder.build(
        Game(roomId = 1, hostUserId = "w1").also { it.phase = GamePhase.GAME_OVER; it.winner = winner },
        players, nights, events, perkSaved, viewer, rewards, wolfPerDay,
    ) { "N-$it" }

    @Test
    fun `the winning side's points are explained as the win bonus`() {
        val review = build("w1", board(), listOf(night(1)), rewards = mapOf("w1" to 20))

        assertThat(review.reward).isEqualTo(MyReward(20, RewardReason.WIN, days = null, perDay = null))
    }

    @Test
    fun `a losing wolf's points are explained by the days they survived`() {
        val review = build(
            "w1", board(mapOf("w1" to 3)), listOf(night(1)),
            rewards = mapOf("w1" to 12), winner = WinnerSide.VILLAGER,
        )

        assertThat(review.reward).isEqualTo(MyReward(12, RewardReason.WOLF_SURVIVAL, days = 3, perDay = 4))
    }

    @Test
    fun `the per-day rate is dropped when it no longer multiplies out to the settled amount`() {
        // The configured rate was changed after this game was settled; the settled amount still wins.
        val review = build(
            "w1", board(mapOf("w1" to 3)), listOf(night(1)),
            rewards = mapOf("w1" to 99), winner = WinnerSide.VILLAGER,
        )

        assertThat(review.reward).isEqualTo(MyReward(99, RewardReason.WOLF_SURVIVAL, days = 3, perDay = null))
    }

    @Test
    fun `a losing villager gets the participation reason, and an unsettled game carries no reward`() {
        val settled = build("v", board(), listOf(night(1)), rewards = mapOf("v" to 5))

        assertThat(settled.reward).isEqualTo(MyReward(5, RewardReason.PARTICIPATION, days = null, perDay = null))
        assertThat(build("v", board(), listOf(night(1))).reward).isNull()
    }

    @Test
    fun `a villager knows their own role and nobody else's`() {
        val review = build("v", board().shuffled(), listOf(night(1)))

        assertThat(review.me).isEqualTo(seat("v", PlayerRole.VILLAGER))
        assertThat(review.seats.map { it.seatIndex }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertThat(review.seats.filter { it.role != null || it.camp != null })
            .containsExactly(seat("v", PlayerRole.VILLAGER))
    }

    @Test
    fun `a villager sleeps through the night and hears a dawn that names the dead, never the cause`() {
        val nights = listOf(night(1) {
            wolfTargetUserId = "hunter"
            seerCheckedUserId = "w1"; seerResultIsWerewolf = true
            witchPoisonTargetUserId = "idiot"
        })
        val review = build("v", board(mapOf("hunter" to 1, "idiot" to 1)), nights, listOf(nightDeath(1, "hunter"), nightDeath(1, "idiot")))

        val n = review.rounds.single().night!!
        assertThat(n.action).isNull()
        // Seats in death order by seat index, with no cause attached and no roles given away.
        assertThat(n.dawn).isEqualTo(PublicDawn(listOf(seat("hunter"), seat("idiot")), saved = null))
    }

    @Test
    fun `a wolf sees the whole wolf team and the night's kill`() {
        val review = build("w1", board(), listOf(night(1) { wolfTargetUserId = "v" }))
        val team = listOf(seat("w1", PlayerRole.WEREWOLF), seat("w2", PlayerRole.WEREWOLF), seat("king", PlayerRole.WHITE_WOLF_KING))

        assertThat(review.rounds.single().night!!.action).isEqualTo(MyNightAction.Wolf(team, seat("v"), selfKill = false))
        assertThat(review.seats.filter { it.role != null }).isEqualTo(team)
    }

    @Test
    fun `the seer learns a camp from each of their own checks and nothing more`() {
        val review = build("seer", board(), listOf(
            night(1) { seerCheckedUserId = "w1"; seerResultIsWerewolf = true },
            night(2) { seerCheckedUserId = "v"; seerResultIsWerewolf = false },
        ))

        assertThat(review.seats.filter { it.camp != null })
            .containsExactly(seat("w1", camp = KnownCamp.WOLF), seat("v", camp = KnownCamp.GOOD))
        assertThat(review.rounds.first().night!!.action)
            .isEqualTo(MyNightAction.Seer(seat("w1", camp = KnownCamp.WOLF), isWerewolf = true))
    }

    @Test
    fun `the witch is shown the night's attack before she chooses`() {
        val review = build("witch", board(), listOf(night(1) { wolfTargetUserId = "v"; witchAntidoteUsed = true }))

        assertThat(review.rounds.single().night!!.action)
            .isEqualTo(MyNightAction.Witch(attacked = seat("v"), antidote = seat("v"), poison = null))
    }

    @Test
    fun `only the wolves and the witch are told the attack failed`() {
        val nights = listOf(night(1) { wolfTargetUserId = "v"; guardTargetUserId = "v" })

        assertThat(build("w1", board(), nights).rounds.single().night!!.dawn.saved).isEqualTo(seat("v"))
        assertThat(build("witch", board(), nights).rounds.single().night!!.dawn.saved).isEqualTo(seat("v"))
        // The guard made the save but was never told an attack came, and the village heard a quiet night.
        assertThat(build("guard", board(), nights).rounds.single().night!!.dawn.saved).isNull()
        assertThat(build("v", board(), nights).rounds.single().night!!.dawn.saved).isNull()
    }

    @Test
    fun `dying at dawn ends the replay there, before that day is heard`() {
        val review = build(
            "v",
            board(mapOf("v" to 2)),
            listOf(night(1), night(2) { wolfTargetUserId = "v" }),
            listOf(nightDeath(2, "v"), voteResult(2, "w1")),
        )

        assertThat(review.diedOnDay).isEqualTo(2)
        assertThat(review.rounds.map { it.day }).containsExactly(1, 2)
        assertThat(review.rounds.last().night!!.dawn.deaths).containsExactly(seat("v", PlayerRole.VILLAGER))
        assertThat(review.rounds.last().events).isEmpty()
    }

    @Test
    fun `being voted out ends the replay on that vote, keeping what came before it`() {
        val review = build(
            "v",
            board(mapOf("v" to 1)),
            listOf(night(1)),
            listOf(idiotReveal(1, "idiot"), voteResult(1, "v"), selfDestruct(1, "w2")),
        )

        // The reveal and the fatal vote are heard; the self-destruct after it is not.
        assertThat(review.rounds.single().events.map { it::class.simpleName }).containsExactly("IdiotReveal", "Vote")
        assertThat(review.seats.first { it.userId == "idiot" }.role).isEqualTo(PlayerRole.IDIOT)
        assertThat(review.seats.first { it.userId == "w2" }.camp).isNull()
    }

    @Test
    fun `a self-destruct tells the table that seat was a wolf`() {
        val review = build("v", board(), listOf(night(1)), listOf(selfDestruct(1, "w2", taken = "seer")))

        assertThat(review.seats.first { it.userId == "w2" }.camp).isEqualTo(KnownCamp.WOLF)
        assertThat(review.seats.first { it.userId == "seer" }.camp).isNull()
    }

    @Test
    fun `a player still alive at the end sees every round`() {
        val review = build(
            "v",
            board(mapOf("hunter" to 1)),
            listOf(night(1) { wolfTargetUserId = "hunter" }, night(2)),
            listOf(nightDeath(1, "hunter"), voteResult(2, "w1")),
        )

        assertThat(review.diedOnDay).isNull()
        assertThat(review.rounds.map { it.day }).containsExactly(1, 2)
        assertThat(review.rounds.last().events).hasSize(1)
    }
}
