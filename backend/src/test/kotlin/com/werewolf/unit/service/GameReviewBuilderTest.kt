package com.werewolf.unit.service

import com.werewolf.dto.*
import com.werewolf.model.*
import com.werewolf.service.GameReviewBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * God's-eye replay (上帝视角复盘). Board, seats 1-9:
 * w1, w2 (狼人), king (白狼王), seer, witch, guard, hunter, idiot, v (村民).
 */
class GameReviewBuilderTest {

    private val gameId = 1
    private val mapper = jacksonObjectMapper()

    private val roster = listOf(
        "w1" to PlayerRole.WEREWOLF, "w2" to PlayerRole.WEREWOLF, "king" to PlayerRole.WHITE_WOLF_KING,
        "seer" to PlayerRole.SEER, "witch" to PlayerRole.WITCH, "guard" to PlayerRole.GUARD,
        "hunter" to PlayerRole.HUNTER, "idiot" to PlayerRole.IDIOT, "v" to PlayerRole.VILLAGER,
    )

    /** [diedDay]: userId → day of death (a night-N kill and a day-N exile are both day N). */
    private fun board(diedDay: Map<String, Int> = emptyMap(), without: Set<PlayerRole> = emptySet()) =
        roster.mapIndexedNotNull { i, (id, role) ->
            if (role in without) null
            else GamePlayer(gameId = gameId, userId = id, seatIndex = i + 1, role = role).also { p ->
                diedDay[id]?.let { p.alive = false; p.diedDay = it }
            }
        }

    private fun ref(id: String) = board().first { it.userId == id }.let { ReviewPlayer(id, "N-$id", it.seatIndex, it.role) }

    private fun night(day: Int, block: NightPhase.() -> Unit = {}) =
        NightPhase(gameId = gameId, dayNumber = day).also { it.subPhase = NightSubPhase.COMPLETE; it.block() }

    /** Payload keys mirror ActionLogService. "stale" nicknames prove names come from displayNameFor. */
    private fun event(type: String, payload: Map<String, Any?>) =
        GameEvent(gameId = gameId, eventType = type, message = mapper.writeValueAsString(payload))

    private fun nightDeath(day: Int, userId: String) =
        event("NIGHT_DEATH", mapOf("dayNumber" to day, "userId" to userId, "nickname" to "stale", "seatIndex" to 0))

    /** [perkSaved]: users whose night-1 immunity perk was the decisive save. [winner] null = cancelled game. */
    private fun build(
        players: List<GamePlayer>,
        nights: List<NightPhase>,
        events: List<GameEvent> = emptyList(),
        perkSaved: Set<String> = emptySet(),
        winner: WinnerSide? = WinnerSide.WEREWOLF,
    ) = GameReviewBuilder.build(
        Game(roomId = 1, hostUserId = "w1").also { it.phase = GamePhase.GAME_OVER; it.winner = winner },
        players, nights, events, perkSaved,
    ) { "N-$it" }

    @Test
    fun `the review lists every player by seat with their true role, and the winner`() {
        val review = build(board().shuffled(), emptyList())

        assertThat(review.winner).isEqualTo(WinnerSide.WEREWOLF)
        assertThat(review.players.map { it.seatIndex }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertThat(review.players.first()).isEqualTo(ReviewPlayer("w1", "N-w1", 1, PlayerRole.WEREWOLF))
        assertThat(review.rounds).isEmpty()
    }

    @Test
    fun `a night shows each role's action and who died at dawn, with the cause`() {
        val nights = listOf(night(1) {
            wolfTargetUserId = "v"
            seerCheckedUserId = "w1"; seerResultIsWerewolf = true
            witchPoisonTargetUserId = "hunter"
            guardTargetUserId = "seer"
        })
        val review = build(board(mapOf("v" to 1, "hunter" to 1)), nights, listOf(nightDeath(1, "v"), nightDeath(1, "hunter")))

        val n = review.rounds.single().night!!
        assertThat(n.wolf).isEqualTo(WolfTurn(listOf(ref("w1"), ref("w2"), ref("king")), WolfAction.KILL, ref("v")))
        assertThat(n.seer).isEqualTo(SeerTurn(ref("seer"), dead = false, target = ref("w1"), isWerewolf = true))
        assertThat(n.witch).isEqualTo(WitchTurn(ref("witch"), dead = false, antidote = null, poison = ref("hunter")))
        assertThat(n.guard).isEqualTo(GuardTurn(ref("guard"), dead = false, target = ref("seer")))
        assertThat(n.dawn).isEqualTo(
            Dawn(listOf(NightDeath(ref("hunter"), DeathCause.POISON), NightDeath(ref("v"), DeathCause.WOLF)), saved = null),
        )
    }

    @Test
    fun `空刀 and 自刀 are told apart from a normal kill`() {
        val review = build(board(), listOf(night(1), night(2) { wolfTargetUserId = "w2"; witchAntidoteUsed = true }))

        assertThat(review.rounds.map { it.night!!.wolf.action }).containsExactly(WolfAction.SKIP, WolfAction.SELF_KILL)
        assertThat(review.rounds.map { it.night!!.wolf.target }).containsExactly(null, ref("w2"))
    }

    @Test
    fun `a wolf target who lived shows who saved them - guard, antidote or the night-1 perk`() {
        val review = build(board(), listOf(
            night(1) { wolfTargetUserId = "v" },
            night(2) { wolfTargetUserId = "seer"; guardTargetUserId = "seer" },
            night(3) { wolfTargetUserId = "witch"; witchAntidoteUsed = true },
        ), perkSaved = setOf("v"))

        assertThat(review.rounds.map { it.night!!.dawn }).containsExactly(
            Dawn(emptyList(), NightSave(ref("v"), SaveSource.PERK)),
            Dawn(emptyList(), NightSave(ref("seer"), SaveSource.GUARD)),
            Dawn(emptyList(), NightSave(ref("witch"), SaveSource.ANTIDOTE)),
        )
    }

    @Test
    fun `without the perk record a surviving wolf target is never shown as a 首夜免死 save`() {
        val review = build(board(), listOf(
            night(1),
            night(2) { wolfTargetUserId = "v" },
            // saved under the pre-2026-10-14 rule, when guard + antidote on one target both saved
            night(3) { wolfTargetUserId = "seer"; guardTargetUserId = "seer"; witchAntidoteUsed = true },
        ))

        assertThat(review.rounds.map { it.night!!.dawn.saved })
            .containsExactly(null, null, NightSave(ref("seer"), SaveSource.ANTIDOTE))
    }

    @Test
    fun `a cancelled game's last night, never revealed at dawn, still shows who died`() {
        val review = build(
            board(mapOf("v" to 1)),
            listOf(night(1) { wolfTargetUserId = "v" }, night(2) { wolfTargetUserId = "seer"; witchPoisonTargetUserId = "w1" }),
            listOf(nightDeath(1, "v")),
            winner = null,
        )

        assertThat(review.rounds.map { it.night!!.dawn }).containsExactly(
            Dawn(listOf(NightDeath(ref("v"), DeathCause.WOLF)), saved = null),
            Dawn(listOf(NightDeath(ref("w1"), DeathCause.POISON), NightDeath(ref("seer"), DeathCause.WOLF)), saved = null),
        )
    }

    @Test
    fun `a game cancelled before night 1's dawn keeps the 首夜免死 save from the perk record`() {
        val review = build(
            board(),
            listOf(night(1) { wolfTargetUserId = "v"; witchPoisonTargetUserId = "w2" }),
            perkSaved = setOf("v"),
            winner = null,
        )

        assertThat(review.rounds.single().night!!.dawn)
            .isEqualTo(Dawn(listOf(NightDeath(ref("w2"), DeathCause.POISON)), NightSave(ref("v"), SaveSource.PERK)))
    }

    @Test
    fun `同守同救 - guard and antidote on the same target cancel out and the target dies`() {
        val review = build(
            board(mapOf("v" to 1)),
            listOf(night(1) { wolfTargetUserId = "v"; guardTargetUserId = "v"; witchAntidoteUsed = true }),
            listOf(nightDeath(1, "v")),
        )

        assertThat(review.rounds.single().night!!.dawn)
            .isEqualTo(Dawn(listOf(NightDeath(ref("v"), DeathCause.BOTH_SAVES)), saved = null))
    }

    @Test
    fun `a role holder dead before the night shows as dead, one who died that night still acted`() {
        // seer killed on night 1; witch and w2 voted out on day 1
        val players = board(mapOf("seer" to 1, "witch" to 1, "w2" to 1))
        val review = build(
            players,
            listOf(night(1) { wolfTargetUserId = "seer"; seerCheckedUserId = "w1"; seerResultIsWerewolf = true }, night(2)),
            listOf(nightDeath(1, "seer")),
        )

        val (n1, n2) = review.rounds.map { it.night!! }
        assertThat(n1.seer!!.dead).isFalse()
        assertThat(n1.witch!!.dead).isFalse()
        assertThat(n2.seer).isEqualTo(SeerTurn(ref("seer"), dead = true, target = null, isWerewolf = false))
        assertThat(n2.witch).isEqualTo(WitchTurn(ref("witch"), dead = true, antidote = null, poison = null))
        assertThat(n2.guard).isEqualTo(GuardTurn(ref("guard"), dead = false, target = null))
        assertThat(n2.wolf.actors).containsExactly(ref("w1"), ref("king"))
    }

    @Test
    fun `roles that are not in the game get no line`() {
        val players = board(without = setOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.GUARD))
        val n = build(players, listOf(night(1) { wolfTargetUserId = "v" })).rounds.single().night!!

        assertThat(listOf(n.seer, n.witch, n.guard)).containsOnlyNulls()
    }

    @Test
    fun `only completed nights are replayed - a night cut off by a cancelled game is left out`() {
        val review = build(board(), listOf(night(1), night(2) { wolfTargetUserId = "v"; subPhase = NightSubPhase.WITCH_ACT }))

        assertThat(review.rounds.map { it.day }).containsExactly(1)
    }

    private fun voter(userId: String) = mapOf("userId" to userId, "nickname" to "stale", "seatIndex" to 0)

    private fun voteResult(day: Int, tally: Map<String, List<String>>, eliminated: String? = null, abstain: List<String> = emptyList()) =
        event("VOTE_RESULT", mapOf(
            "dayNumber" to day,
            "tally" to tally.map { (target, voters) -> voter(target) + mapOf("votes" to voters.size.toDouble(), "voters" to voters.map(::voter)) },
            "eliminatedUserId" to eliminated,
            "abstainCount" to abstain.size,
            "abstainVoters" to abstain.map(::voter),
        ))

    private fun sheriffResult(day: Int, tally: Map<String, List<String>>, winner: String?) =
        event("SHERIFF_RESULT", mapOf(
            "dayNumber" to day,
            "tally" to tally.map { (target, voters) -> voter(target) + mapOf("votes" to voters.size, "voters" to voters.map(::voter)) },
            "winnerUserId" to winner,
            "abstainCount" to 0,
            "abstainVoters" to emptyList<Any>(),
        ))

    private fun hunterShot(day: Int, hunter: String, target: String) =
        event("HUNTER_SHOT", mapOf("dayNumber" to day, "hunterUserId" to hunter, "targetUserId" to target))

    private fun selfDestruct(day: Int, userId: String, taken: String? = null) =
        event("SELF_DESTRUCT", mapOf("dayNumber" to day, "userId" to userId) + (taken?.let { mapOf("takenUserId" to it) } ?: emptyMap()))

    private fun idiotReveal(day: Int, userId: String) = event("IDIOT_REVEAL", mapOf("dayNumber" to day, "userId" to userId))

    private fun votes(review: GameReview, day: Int) =
        review.rounds.first { it.day == day }.events.filterIsInstance<ReviewDayEvent.Vote>()

    @Test
    fun `an exile vote keeps each target's voters, the abstainers and who was voted out`() {
        val review = build(board(mapOf("v" to 1)), listOf(night(1)), listOf(
            voteResult(1, mapOf("v" to listOf("w1", "seer"), "w1" to listOf("guard")), eliminated = "v", abstain = listOf("hunter")),
        ))

        assertThat(votes(review, 1).single()).isEqualTo(ReviewDayEvent.Vote(
            kind = VoteKind.EXILE,
            round = 1,
            tally = listOf(TallyEntry(ref("v"), 2.0, listOf(ref("w1"), ref("seer"))), TallyEntry(ref("w1"), 1.0, listOf(ref("guard")))),
            abstain = listOf(ref("hunter")),
            result = ref("v"),
            appointed = false,
            wolfPlay = null,
        ))
    }

    @Test
    fun `a sheriff the host picked after a tie or with no votes is marked appointed, an elected one is not`() {
        fun sheriff(tally: Map<String, List<String>>, winner: String) =
            votes(build(board(), listOf(night(1)), listOf(sheriffResult(1, tally, winner))), 1).single()

        assertThat(sheriff(mapOf("seer" to listOf("w1", "witch"), "v" to listOf("guard")), winner = "seer").appointed).isFalse()
        assertThat(sheriff(mapOf("seer" to listOf("w1", "witch"), "v" to listOf("w2", "guard")), winner = "v").appointed).isTrue()
        assertThat(sheriff(emptyMap(), winner = "v").appointed).isTrue()
    }

    @Test
    fun `a tie followed by a PK re-vote is round 1 with no result, then round 2`() {
        val review = build(board(), listOf(night(1)), listOf(
            voteResult(1, mapOf("v" to listOf("seer"), "w1" to listOf("guard"))),
            voteResult(1, mapOf("v" to listOf("seer", "guard")), eliminated = "v"),
        ))

        assertThat(votes(review, 1).map { it.round to it.result?.userId }).containsExactly(1 to null, 2 to "v")
    }

    @Test
    fun `2+ wolves all voting for the same good player is 冲票`() {
        val review = build(board(), listOf(night(1)), listOf(
            voteResult(1, mapOf("seer" to listOf("w1", "w2", "king", "v")), eliminated = "seer"),
        ))

        assertThat(votes(review, 1).single().wolfPlay).isEqualTo(WolfPlay.PUSH)
    }

    @Test
    fun `a wolf voting for a wolf teammate is 倒钩`() {
        val review = build(board(), listOf(night(1)), listOf(
            voteResult(1, mapOf("w1" to listOf("w2", "seer"), "v" to listOf("king"))),
        ))

        assertThat(votes(review, 1).single().wolfPlay).isEqualTo(WolfPlay.BUS)
    }

    @Test
    fun `wolves splitting their votes on good players, or a lone wolf vote, gets no tag`() {
        val review = build(board(), listOf(night(1), night(2)), listOf(
            voteResult(1, mapOf("v" to listOf("w1"), "seer" to listOf("w2"))),
            voteResult(2, mapOf("v" to listOf("w1", "seer"))),
        ))

        assertThat(votes(review, 1).single().wolfPlay).isNull()
        assertThat(votes(review, 2).single().wolfPlay).isNull()
    }

    @Test
    fun `wolves backing a candidate for sheriff get no 倒钩 or 冲票 tag - those are for exile votes`() {
        val backingTeammate = build(board(), listOf(night(1)), listOf(
            sheriffResult(1, mapOf("king" to listOf("w1", "w2"), "seer" to listOf("v")), winner = "king"),
        ))
        val allOnOneGood = build(board(), listOf(night(1)), listOf(
            sheriffResult(1, mapOf("seer" to listOf("w1", "w2", "v")), winner = "seer"),
        ))

        assertThat(votes(backingTeammate, 1).single().wolfPlay).isNull()
        assertThat(votes(allOnOneGood, 1).single().wolfPlay).isNull()
    }

    @Test
    fun `sheriff vote, hunter shot, self-destruct and idiot reveal come in the order they happened`() {
        val review = build(board(), listOf(night(1), night(2)), listOf(
            sheriffResult(1, mapOf("seer" to listOf("witch", "guard")), winner = "seer"),
            hunterShot(1, "hunter", "w1"),
            voteResult(1, mapOf("idiot" to listOf("w2", "king")), eliminated = "idiot"),
            idiotReveal(1, "idiot"),
            selfDestruct(2, "king", taken = "seer"),
            selfDestruct(2, "w2"),
        ))

        val day1 = review.rounds.first { it.day == 1 }.events
        assertThat(day1).hasSize(4)
        val sheriff = day1[0] as ReviewDayEvent.Vote
        assertThat(listOf(sheriff.kind, sheriff.round, sheriff.result)).containsExactly(VoteKind.SHERIFF, 1, ref("seer"))
        assertThat(sheriff.tally.single().votes).isEqualTo(2.0)
        assertThat(day1[1]).isEqualTo(ReviewDayEvent.HunterShot(ref("hunter"), ref("w1")))
        val exile = day1[2] as ReviewDayEvent.Vote
        assertThat(listOf(exile.kind, exile.round, exile.wolfPlay)).containsExactly(VoteKind.EXILE, 1, WolfPlay.PUSH)
        assertThat(day1[3]).isEqualTo(ReviewDayEvent.IdiotReveal(ref("idiot")))
        assertThat(review.rounds.first { it.day == 2 }.events).containsExactly(
            ReviewDayEvent.SelfDestruct(ref("king"), taken = ref("seer")),
            ReviewDayEvent.SelfDestruct(ref("w2"), taken = null),
        )
    }

    @Test
    fun `a game that ends at night has a last round with the night and no day events`() {
        val review = build(board(mapOf("seer" to 2)), listOf(night(1), night(2) { wolfTargetUserId = "seer" }), listOf(
            voteResult(1, mapOf("v" to listOf("seer"))),
            nightDeath(2, "seer"),
        ))

        assertThat(review.rounds.map { Triple(it.day, it.night != null, it.events.size) })
            .containsExactly(Triple(1, true, 1), Triple(2, true, 0))
    }
}
