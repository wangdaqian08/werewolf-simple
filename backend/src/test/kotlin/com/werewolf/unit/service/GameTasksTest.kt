package com.werewolf.unit.service

import com.werewolf.model.GamePlayer
import com.werewolf.model.GameTask
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.PlayerRole.*
import com.werewolf.model.WinnerSide
import com.werewolf.service.GameTasks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** 单局任务: who can draw which task, and when a task counts as done. */
class GameTasksTest {

    private fun seat(id: String, role: PlayerRole, diedDay: Int? = null) =
        GamePlayer(gameId = 1, userId = id, seatIndex = 1, role = role).also { it.diedDay = diedDay }

    private fun night(day: Int, block: NightPhase.() -> Unit = {}) = NightPhase(gameId = 1, dayNumber = day).apply(block)

    private val roles = mapOf(
        "w1" to WEREWOLF, "w2" to WHITE_WOLF_KING, "seer" to SEER, "witch" to WITCH,
        "guard" to GUARD, "v1" to VILLAGER, "v2" to VILLAGER, "hunter" to HUNTER,
    )

    private fun facts(
        winner: WinnerSide = WinnerSide.VILLAGER,
        finalDay: Int = 3,
        badgeHolders: Set<String> = emptySet(),
        wolfVoteDays: Map<String, List<Int>> = emptyMap(),
        nights: List<NightPhase> = emptyList(),
        nightDeathDay: Map<String, Int> = emptyMap(),
        exiled: Set<String> = emptySet(),
        finalVotes: Map<Pair<String, Int>, String?> = emptyMap(),
        talliedDays: Set<Int> = finalVotes.keys.map { it.second }.toSet(),
        idiotRevealDay: Map<String, Int> = emptyMap(),
    ) = GameTasks.Facts(
        winner, finalDay, roles, badgeHolders, wolfVoteDays, nights, nightDeathDay, exiled,
        finalVotes, talliedDays, idiotRevealDay,
    )

    private fun done(task: GameTask, p: GamePlayer, f: GameTasks.Facts) = GameTasks.isDone(task, p, f)

    // ── who can draw what ─────────────────────────────────────────────

    @Test
    fun `each role draws from its own tasks plus the general ones`() {
        assertThat(GameTasks.eligible(SEER, hasSheriff = true))
            .contains(GameTask.SEER_FIND_TWO_WOLVES, GameTask.SURVIVE_TO_DAY3, GameTask.HOLD_BADGE, GameTask.VOTE_WOLF_TWICE)
            .doesNotContain(GameTask.WITCH_POISON_WOLF, GameTask.WOLF_WIN_ALIVE, GameTask.VILLAGER_SURVIVE_WIN)
        assertThat(GameTasks.eligible(VILLAGER, hasSheriff = true)).contains(GameTask.VILLAGER_SURVIVE_WIN)
        assertThat(GameTasks.eligible(WITCH, hasSheriff = true)).contains(GameTask.WITCH_POISON_WOLF, GameTask.WITCH_SAVE_GOOD)
        assertThat(GameTasks.eligible(GUARD, hasSheriff = true)).contains(GameTask.GUARD_BLOCK_KILL)
    }

    @Test
    fun `every wolf, special wolves included, draws wolf tasks and never the good-side vote task`() {
        for (wolf in PlayerRole.entries.filter { it.isWolf }) {
            assertThat(GameTasks.eligible(wolf, hasSheriff = true)).`as`(wolf.name)
                .contains(GameTask.WOLF_NEVER_EXILED, GameTask.WOLF_WIN_ALIVE)
                .doesNotContain(GameTask.VOTE_WOLF_TWICE, GameTask.SEER_FIND_TWO_WOLVES)
        }
    }

    @Test
    fun `the night-1 knife task goes only to wolves that knife with the pack, never the mechanical wolf`() {
        for (wolf in PlayerRole.entries.filter { it.isWolf }) {
            val tasks = GameTasks.eligible(wolf, hasSheriff = true)
            if (wolf.isPackWolf) assertThat(tasks).`as`(wolf.name).contains(GameTask.WOLF_FIRST_NIGHT_GOD)
            else assertThat(tasks).`as`(wolf.name).doesNotContain(GameTask.WOLF_FIRST_NIGHT_GOD)
        }
    }

    /**
     * No task may ask for a skill the role does not have (owner rule, 2026-11-13): a villager can
     * never be asked to check a wolf. Each task names the only roles that can ever complete it.
     */
    @Test
    fun `no role is ever dealt a task beyond its own skills`() {
        val canDoIt: Map<GameTask, (PlayerRole) -> Boolean> = mapOf(
            GameTask.SURVIVE_TO_DAY3 to { _ -> true },
            GameTask.HOLD_BADGE to { _ -> true },
            GameTask.VOTE_WOLF_TWICE to { r -> !r.isWolf }, // a wolf voting a wolf is never counted
            GameTask.VILLAGER_SURVIVE_WIN to { r -> r == VILLAGER },
            GameTask.SEER_FIND_TWO_WOLVES to { r -> r == SEER },
            GameTask.SEER_WOLF_EVERY_NIGHT to { r -> r == SEER },
            GameTask.NEVER_VOTE_GOOD to { r -> !r.isWolf }, // only the good side votes for "the wolves"
            GameTask.WITCH_POISON_WOLF to { r -> r == WITCH },
            GameTask.WITCH_SAVE_GOOD to { r -> r == WITCH },
            GameTask.GUARD_BLOCK_KILL to { r -> r == GUARD },
            GameTask.WOLF_NEVER_EXILED to { r -> r.isWolf },
            GameTask.WOLF_WIN_ALIVE to { r -> r.isWolf },
            GameTask.WOLF_FIRST_NIGHT_GOD to { r -> r.isPackWolf },
        )
        assertThat(canDoIt.keys).containsExactlyInAnyOrder(*GameTask.entries.toTypedArray()) // a new task must be listed here
        for (role in PlayerRole.entries) for (sheriff in listOf(true, false)) {
            GameTasks.eligible(role, sheriff).forEach { task ->
                assertThat(canDoIt.getValue(task)(role)).`as`("$role must not be dealt $task").isTrue()
            }
        }
    }

    @Test
    fun `the badge task is only drawn when the room elects a sheriff`() {
        assertThat(GameTasks.eligible(VILLAGER, hasSheriff = false)).doesNotContain(GameTask.HOLD_BADGE)
        assertThat(GameTasks.eligible(WEREWOLF, hasSheriff = false)).doesNotContain(GameTask.HOLD_BADGE)
    }

    @Test
    fun `every role has something to draw`() {
        for (role in PlayerRole.entries) {
            assertThat(GameTasks.eligible(role, hasSheriff = false)).`as`(role.name).isNotEmpty()
        }
    }

    @Test
    fun `assign gives every seat one task it is eligible for`() {
        val seats = roles.map { (id, r) -> seat(id, r) }
        GameTasks.assign(seats, hasSheriff = true, random = Random(42))
        seats.forEach { s ->
            assertThat(s.taskCode).`as`(s.userId).isNotNull()
            assertThat(GameTasks.eligible(s.role, hasSheriff = true)).contains(s.taskCode)
        }
    }

    @Test
    fun `assign skips games under six players`() {
        val seats = listOf(seat("a", WEREWOLF), seat("b", SEER), seat("c", VILLAGER), seat("d", WITCH), seat("e", VILLAGER))
        GameTasks.assign(seats, hasSheriff = true, random = Random(1))
        assertThat(seats.map { it.taskCode }).containsOnlyNulls()
    }

    @Test
    fun `two seats with the same role may draw the same task`() {
        // One general + one villager task (no sheriff): ten villagers can only spread over two tasks.
        val seats = (1..10).map { seat("v$it", VILLAGER) }
        GameTasks.assign(seats, hasSheriff = false, random = Random(3))
        assertThat(seats.map { it.taskCode }.toSet().size).isLessThan(10)
    }

    @Test
    fun `the seer can draw the every-night task, every good seat the never-vote-good task`() {
        assertThat(GameTasks.eligible(SEER, hasSheriff = false)).contains(GameTask.SEER_WOLF_EVERY_NIGHT)
        for (role in PlayerRole.entries.filter { !it.isWolf }) {
            assertThat(GameTasks.eligible(role, hasSheriff = false)).`as`(role.name).contains(GameTask.NEVER_VOTE_GOOD)
        }
    }

    @Test
    fun `scores follow difficulty - 5, 10, 20`() {
        assertThat(GameTask.SURVIVE_TO_DAY3.score).isEqualTo(5)
        assertThat(GameTask.HOLD_BADGE.score).isEqualTo(10)
        assertThat(GameTask.WOLF_WIN_ALIVE.score).isEqualTo(20)
    }

    // ── when a task is done ───────────────────────────────────────────

    @Test
    fun `survive to day 3 - alive at the dawn of day 3`() {
        val f = facts(finalDay = 3)
        assertThat(done(GameTask.SURVIVE_TO_DAY3, seat("v1", VILLAGER), f)).isTrue()
        // voted out on day 3: they did see day 3
        assertThat(done(GameTask.SURVIVE_TO_DAY3, seat("v1", VILLAGER, diedDay = 3), f)).isTrue()
        // killed on night 3: day 3 never dawned for them
        assertThat(done(GameTask.SURVIVE_TO_DAY3, seat("v1", VILLAGER, diedDay = 3), facts(nightDeathDay = mapOf("v1" to 3)))).isFalse()
        // voted out on day 2
        assertThat(done(GameTask.SURVIVE_TO_DAY3, seat("v1", VILLAGER, diedDay = 2), f)).isFalse()
        // the game ended on day 2
        assertThat(done(GameTask.SURVIVE_TO_DAY3, seat("v1", VILLAGER), facts(finalDay = 2))).isFalse()
    }

    @Test
    fun `hold the badge - elected or handed it`() {
        assertThat(done(GameTask.HOLD_BADGE, seat("v1", VILLAGER), facts(badgeHolders = setOf("v1")))).isTrue()
        assertThat(done(GameTask.HOLD_BADGE, seat("v1", VILLAGER), facts(badgeHolders = setOf("v2")))).isFalse()
    }

    @Test
    fun `vote out a wolf on two different days`() {
        assertThat(done(GameTask.VOTE_WOLF_TWICE, seat("v1", VILLAGER), facts(wolfVoteDays = mapOf("v1" to listOf(1, 3))))).isTrue()
        assertThat(done(GameTask.VOTE_WOLF_TWICE, seat("v1", VILLAGER), facts(wolfVoteDays = mapOf("v1" to listOf(2))))).isFalse()
    }

    @Test
    fun `villager - alive at the end of a village win`() {
        assertThat(done(GameTask.VILLAGER_SURVIVE_WIN, seat("v1", VILLAGER), facts())).isTrue()
        assertThat(done(GameTask.VILLAGER_SURVIVE_WIN, seat("v1", VILLAGER, diedDay = 2), facts())).isFalse()
        assertThat(done(GameTask.VILLAGER_SURVIVE_WIN, seat("v1", VILLAGER), facts(winner = WinnerSide.WEREWOLF))).isFalse()
    }

    @Test
    fun `seer - two different wolves checked`() {
        val twice = listOf(
            night(1) { seerCheckedUserId = "w1"; seerResultIsWerewolf = true },
            night(2) { seerCheckedUserId = "w2"; seerResultIsWerewolf = true },
        )
        val sameTwice = listOf(
            night(1) { seerCheckedUserId = "w1"; seerResultIsWerewolf = true },
            night(2) { seerCheckedUserId = "w1"; seerResultIsWerewolf = true },
        )
        assertThat(done(GameTask.SEER_FIND_TWO_WOLVES, seat("seer", SEER), facts(nights = twice))).isTrue()
        assertThat(done(GameTask.SEER_FIND_TWO_WOLVES, seat("seer", SEER), facts(nights = sameTwice))).isFalse()
    }

    @Test
    fun `witch - poison lands on a wolf`() {
        assertThat(done(GameTask.WITCH_POISON_WOLF, seat("witch", WITCH), facts(nights = listOf(night(2) { witchPoisonTargetUserId = "w2" })))).isTrue()
        assertThat(done(GameTask.WITCH_POISON_WOLF, seat("witch", WITCH), facts(nights = listOf(night(2) { witchPoisonTargetUserId = "v1" })))).isFalse()
    }

    @Test
    fun `witch - antidote saves a good player`() {
        val savedGood = listOf(night(1) { wolfTargetUserId = "v1"; witchAntidoteUsed = true })
        val savedWolf = listOf(night(1) { wolfTargetUserId = "w1"; witchAntidoteUsed = true }) // 自刀
        val noSave = listOf(night(1) { wolfTargetUserId = "v1" })
        assertThat(done(GameTask.WITCH_SAVE_GOOD, seat("witch", WITCH), facts(nights = savedGood))).isTrue()
        assertThat(done(GameTask.WITCH_SAVE_GOOD, seat("witch", WITCH), facts(nights = savedWolf))).isFalse()
        assertThat(done(GameTask.WITCH_SAVE_GOOD, seat("witch", WITCH), facts(nights = noSave))).isFalse()
    }

    @Test
    fun `guard - guarded the wolves' target and that player lived the night`() {
        val blocked = listOf(night(2) { wolfTargetUserId = "seer"; guardTargetUserId = "seer" })
        assertThat(done(GameTask.GUARD_BLOCK_KILL, seat("guard", GUARD), facts(nights = blocked))).isTrue()
        // 同守同救: guarded and saved, still died that night
        assertThat(done(GameTask.GUARD_BLOCK_KILL, seat("guard", GUARD), facts(nights = blocked, nightDeathDay = mapOf("seer" to 2)))).isFalse()
        val missed = listOf(night(2) { wolfTargetUserId = "seer"; guardTargetUserId = "v1" })
        assertThat(done(GameTask.GUARD_BLOCK_KILL, seat("guard", GUARD), facts(nights = missed))).isFalse()
    }

    @Test
    fun `wolf - never voted out`() {
        assertThat(done(GameTask.WOLF_NEVER_EXILED, seat("w1", WEREWOLF, diedDay = 3), facts(nightDeathDay = mapOf("w1" to 3)))).isTrue()
        assertThat(done(GameTask.WOLF_NEVER_EXILED, seat("w1", WEREWOLF, diedDay = 2), facts(exiled = setOf("w1")))).isFalse()
    }

    @Test
    fun `wolf - the wolves win and I am alive`() {
        assertThat(done(GameTask.WOLF_WIN_ALIVE, seat("w1", WEREWOLF), facts(winner = WinnerSide.WEREWOLF))).isTrue()
        assertThat(done(GameTask.WOLF_WIN_ALIVE, seat("w1", WEREWOLF, diedDay = 2), facts(winner = WinnerSide.WEREWOLF))).isFalse()
        assertThat(done(GameTask.WOLF_WIN_ALIVE, seat("w1", WEREWOLF), facts(winner = WinnerSide.VILLAGER))).isFalse()
    }

    @Test
    fun `wolf - the night-1 knife kills a god`() {
        val godKilled = facts(nights = listOf(night(1) { wolfTargetUserId = "seer" }), nightDeathDay = mapOf("seer" to 1))
        val godSaved = facts(nights = listOf(night(1) { wolfTargetUserId = "seer"; witchAntidoteUsed = true }))
        val villagerKilled = facts(nights = listOf(night(1) { wolfTargetUserId = "v1" }), nightDeathDay = mapOf("v1" to 1))
        val godKilledNight2 = facts(nights = listOf(night(2) { wolfTargetUserId = "seer" }), nightDeathDay = mapOf("seer" to 2))
        assertThat(done(GameTask.WOLF_FIRST_NIGHT_GOD, seat("w1", WEREWOLF), godKilled)).isTrue()
        assertThat(done(GameTask.WOLF_FIRST_NIGHT_GOD, seat("w1", WEREWOLF), godSaved)).isFalse()
        assertThat(done(GameTask.WOLF_FIRST_NIGHT_GOD, seat("w1", WEREWOLF), villagerKilled)).isFalse()
        assertThat(done(GameTask.WOLF_FIRST_NIGHT_GOD, seat("w1", WEREWOLF), godKilledNight2)).isFalse()
    }

    // ── 活著的每一晚都查驗到狼人: per night, nights after death do not count ──

    private fun checks(vararg wolfEachNight: Boolean?) = wolfEachNight.mapIndexed { i, wolf ->
        night(i + 1) {
            if (wolf != null) { seerCheckedUserId = if (wolf) "w1" else "v1"; seerResultIsWerewolf = wolf }
        }
    }

    private fun score(task: GameTask, p: GamePlayer, f: GameTasks.Facts) = GameTasks.scoreFor(task, p, f)

    @Test
    fun `seer alive two nights, a wolf both nights - done, the score doubles`() {
        val f = facts(nights = checks(true, true))
        assertThat(done(GameTask.SEER_WOLF_EVERY_NIGHT, seat("seer", SEER, diedDay = 2), f)).isTrue()
        assertThat(score(GameTask.SEER_WOLF_EVERY_NIGHT, seat("seer", SEER, diedDay = 2), f))
            .isEqualTo(2 * GameTask.SEER_WOLF_EVERY_NIGHT.score)
    }

    @Test
    fun `seer - nights after the seer died do not count`() {
        // died on night 2 (diedDay 2): nights 1 and 2 count; night 3 had no seer
        val f = facts(nights = checks(true, true, null), nightDeathDay = mapOf("seer" to 2))
        assertThat(done(GameTask.SEER_WOLF_EVERY_NIGHT, seat("seer", SEER, diedDay = 2), f)).isTrue()
        assertThat(score(GameTask.SEER_WOLF_EVERY_NIGHT, seat("seer", SEER, diedDay = 2), f)).isEqualTo(20)
    }

    @Test
    fun `seer alive three nights, a wolf every night - three times the score`() {
        val f = facts(nights = checks(true, true, true))
        assertThat(score(GameTask.SEER_WOLF_EVERY_NIGHT, seat("seer", SEER), f)).isEqualTo(30)
    }

    @Test
    fun `seer - one night that found no wolf, or no check at all, fails the task`() {
        assertThat(done(GameTask.SEER_WOLF_EVERY_NIGHT, seat("seer", SEER), facts(nights = checks(true, false)))).isFalse()
        assertThat(done(GameTask.SEER_WOLF_EVERY_NIGHT, seat("seer", SEER), facts(nights = checks(true, null)))).isFalse()
        assertThat(score(GameTask.SEER_WOLF_EVERY_NIGHT, seat("seer", SEER), facts(nights = checks(true, false)))).isEqualTo(0)
    }

    @Test
    fun `fixed tasks score their base when done and 0 when not`() {
        assertThat(score(GameTask.HOLD_BADGE, seat("v1", VILLAGER), facts(badgeHolders = setOf("v1")))).isEqualTo(10)
        assertThat(score(GameTask.HOLD_BADGE, seat("v1", VILLAGER), facts())).isEqualTo(0)
    }

    // ── 每一輪放逐投票都不投好人: abstaining fails, a PK re-vote counts ──

    @Test
    fun `a wolf in every round I could vote in - done`() {
        val f = facts(finalVotes = mapOf(("v1" to 1) to "w1", ("v1" to 2) to "w2"))
        assertThat(done(GameTask.NEVER_VOTE_GOOD, seat("v1", VILLAGER), f)).isTrue()
        assertThat(score(GameTask.NEVER_VOTE_GOOD, seat("v1", VILLAGER), f)).isEqualTo(GameTask.NEVER_VOTE_GOOD.score)
    }

    @Test
    fun `abstaining in a round fails the task`() {
        val f = facts(finalVotes = mapOf(("v1" to 1) to "w1", ("v1" to 2) to null))
        assertThat(done(GameTask.NEVER_VOTE_GOOD, seat("v1", VILLAGER), f)).isFalse()
    }

    @Test
    fun `not voting at all in a round I could vote in fails the task`() {
        val f = facts(finalVotes = mapOf(("v1" to 1) to "w1", ("v2" to 2) to "w1"), talliedDays = setOf(1, 2))
        assertThat(done(GameTask.NEVER_VOTE_GOOD, seat("v1", VILLAGER), f)).isFalse()
    }

    @Test
    fun `voting for a good player fails the task`() {
        val f = facts(finalVotes = mapOf(("v1" to 1) to "w1", ("v1" to 2) to "seer"))
        assertThat(done(GameTask.NEVER_VOTE_GOOD, seat("v1", VILLAGER), f)).isFalse()
    }

    @Test
    fun `rounds after I died do not count`() {
        // killed on night 2: day 2 and 3 votes are not mine to cast
        val f = facts(finalVotes = mapOf(("v1" to 1) to "w1", ("v2" to 2) to "seer"), talliedDays = setOf(1, 2, 3), nightDeathDay = mapOf("v1" to 2))
        assertThat(done(GameTask.NEVER_VOTE_GOOD, seat("v1", VILLAGER, diedDay = 2), f)).isTrue()
    }

    @Test
    fun `voted out on a day after voting for a wolf that day - done`() {
        val f = facts(finalVotes = mapOf(("v1" to 1) to "w1", ("v1" to 2) to "w2"), talliedDays = setOf(1, 2, 3))
        assertThat(done(GameTask.NEVER_VOTE_GOOD, seat("v1", VILLAGER, diedDay = 2), f)).isTrue()
    }

    @Test
    fun `an idiot does not vote after the reveal - those rounds do not count`() {
        val f = facts(finalVotes = mapOf(("idiot" to 1) to "w1"), talliedDays = setOf(1, 2, 3), idiotRevealDay = mapOf("idiot" to 1))
        assertThat(done(GameTask.NEVER_VOTE_GOOD, seat("idiot", IDIOT), f)).isTrue()
    }

    @Test
    fun `no round to vote in - not done`() {
        assertThat(done(GameTask.NEVER_VOTE_GOOD, seat("v1", VILLAGER), facts())).isFalse()
    }
}
