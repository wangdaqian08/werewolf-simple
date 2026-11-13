package com.werewolf.service

import com.werewolf.model.GamePlayer
import com.werewolf.model.GameTask
import com.werewolf.model.GameTask.*
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import kotlin.random.Random

/**
 * 单局任务: one private task per seat, drawn from what fits the role right after dealing,
 * judged at settlement from what the game already records, paid in 戰績分 only.
 */
object GameTasks {

    /** Tasks are skipped below this many seats, so a small private room cannot farm them. */
    const val MIN_PLAYERS = 6

    /** Everything a task can be judged on, read once per settlement. */
    class Facts(
        val winner: WinnerSide,
        /** The game's day number when it ended. */
        val finalDay: Int,
        val roleOf: Map<String, PlayerRole>,
        /** Everyone who ever wore the badge. */
        val badgeHolders: Set<String>,
        /** userId → the days that player's exile vote landed on a wolf (good side only). */
        val wolfVoteDays: Map<String, List<Int>>,
        val nights: List<NightPhase>,
        /** userId → the night they died on (NIGHT_DEATH); day deaths are not in here. */
        val nightDeathDay: Map<String, Int>,
        /** Everyone voted out by the table. */
        val exiled: Set<String>,
        /** (voter, day) → their final exile vote that day, null = abstained; tallied days only. */
        val finalVotes: Map<Pair<String, Int>, String?>,
        /** Days whose exile vote reached a tally. */
        val talliedDays: Set<Int>,
        /** userId → the day an idiot was revealed; it cannot vote after that. */
        val idiotRevealDay: Map<String, Int>,
    )

    fun eligible(role: PlayerRole, hasSheriff: Boolean): List<GameTask> = buildList {
        add(SURVIVE_TO_DAY3)
        if (hasSheriff) add(HOLD_BADGE)
        // Never a task beyond the role's own skills: a villager is never asked to check a wolf.
        if (role.isWolf) {
            addAll(listOf(WOLF_NEVER_EXILED, WOLF_WIN_ALIVE))
            // The mechanical wolf never knifes on night 1: the pack does (RoleRules.wolfKillers).
            if (role.isPackWolf) add(WOLF_FIRST_NIGHT_GOD)
        } else {
            addAll(listOf(VOTE_WOLF_TWICE, NEVER_VOTE_GOOD))
            when (role) {
                PlayerRole.VILLAGER -> add(VILLAGER_SURVIVE_WIN)
                PlayerRole.SEER -> addAll(listOf(SEER_FIND_TWO_WOLVES, SEER_WOLF_EVERY_NIGHT))
                PlayerRole.WITCH -> addAll(listOf(WITCH_POISON_WOLF, WITCH_SAVE_GOOD))
                PlayerRole.GUARD -> add(GUARD_BLOCK_KILL)
                else -> {}
            }
        }
    }

    /** Draws each seat's task independently, so two seats may share one. */
    fun assign(players: List<GamePlayer>, hasSheriff: Boolean, random: Random) {
        if (players.size < MIN_PLAYERS) return
        players.forEach { it.taskCode = eligible(it.role, hasSheriff).random(random) }
    }

    /** The 戰績分 this task pays: 0 when not done; a per-night task pays once for every night it counted. */
    fun scoreFor(task: GameTask, player: GamePlayer, f: Facts): Int = when {
        !isDone(task, player, f) -> 0
        task == SEER_WOLF_EVERY_NIGHT -> task.score * seerNights(player, f).size
        else -> task.score
    }

    /** The nights the seer was alive to check: night N comes before day N, so a seer dead on day N still checked on night N. */
    private fun seerNights(player: GamePlayer, f: Facts): List<NightPhase> =
        f.nights.filter { it.dayNumber <= (player.diedDay ?: Int.MAX_VALUE) }

    /** The tallied days this player could vote on: alive, and not an idiot past its reveal. */
    private fun votingDays(player: GamePlayer, f: Facts): List<Int> = f.talliedDays.sorted().filter { d ->
        val me = player.userId
        val diedOnDayD = player.diedDay == d && (f.nightDeathDay[me] ?: Int.MAX_VALUE) > d
        (f.nightDeathDay[me] ?: Int.MAX_VALUE) > d &&
            (player.diedDay ?: Int.MAX_VALUE) >= d &&
            // died by day on day d without a vote: shot or taken before the vote, not an abstain
            !(diedOnDayD && (me to d) !in f.finalVotes) &&
            (f.idiotRevealDay[me] ?: Int.MAX_VALUE) >= d
    }

    fun isDone(task: GameTask, player: GamePlayer, f: Facts): Boolean {
        val me = player.userId
        val alive = player.diedDay == null
        fun isWolf(id: String?) = id != null && f.roleOf[id]?.isWolf == true
        return when (task) {
            // Alive when day 3 dawns: a night-3 death misses it, a day-3 exile does not.
            SURVIVE_TO_DAY3 -> f.finalDay >= 3 &&
                (player.diedDay ?: Int.MAX_VALUE) >= 3 && (f.nightDeathDay[me] ?: Int.MAX_VALUE) > 3
            HOLD_BADGE -> me in f.badgeHolders
            VOTE_WOLF_TWICE -> (f.wolfVoteDays[me]?.distinct()?.size ?: 0) >= 2
            VILLAGER_SURVIVE_WIN -> alive && f.winner == WinnerSide.VILLAGER
            SEER_FIND_TWO_WOLVES -> f.nights
                .filter { it.seerResultIsWerewolf == true }.mapNotNull { it.seerCheckedUserId }.distinct().size >= 2
            WITCH_POISON_WOLF -> f.nights.any { isWolf(it.witchPoisonTargetUserId) }
            WITCH_SAVE_GOOD -> f.nights.any { it.witchAntidoteUsed && it.wolfTargetUserId != null && !isWolf(it.wolfTargetUserId) }
            GUARD_BLOCK_KILL -> f.nights.any { n ->
                n.wolfTargetUserId != null && n.guardTargetUserId == n.wolfTargetUserId &&
                    f.nightDeathDay[n.wolfTargetUserId] != n.dayNumber
            }
            WOLF_NEVER_EXILED -> me !in f.exiled
            WOLF_WIN_ALIVE -> alive && f.winner == WinnerSide.WEREWOLF
            SEER_WOLF_EVERY_NIGHT -> seerNights(player, f).let { nights ->
                nights.isNotEmpty() && nights.all { it.seerResultIsWerewolf == true }
            }
            // A missing vote is an abstain, and abstaining fails; a PK re-vote is the final word.
            NEVER_VOTE_GOOD -> votingDays(player, f).let { days ->
                days.isNotEmpty() && days.all { d -> isWolf(f.finalVotes[me to d]) }
            }
            WOLF_FIRST_NIGHT_GOD -> f.nights.firstOrNull { it.dayNumber == 1 }?.wolfTargetUserId?.let { target ->
                f.roleOf[target]?.isGod == true && f.nightDeathDay[target] == 1
            } ?: false
        }
    }
}
