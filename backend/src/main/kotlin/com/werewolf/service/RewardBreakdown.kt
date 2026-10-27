package com.werewolf.service

import com.werewolf.config.RewardProperties
import com.werewolf.dto.RewardReason
import com.werewolf.dto.RewardGroup
import com.werewolf.dto.RewardRule
import com.werewolf.model.GameEvent
import com.werewolf.model.GamePlayer
import com.werewolf.model.PlayerRole
import com.werewolf.model.Vote
import com.werewolf.model.WinnerSide
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The game-end credit rules, in one place so that settlement and 玩家视角复盘 can
 * never drift: [RewardSettlementService] sums these items into the ledger, and
 * GameReviewService replays them to say which action earned what.
 *
 * A player's total is their base — the side they were on and how they fared —
 * plus a bonus for every badge they held and for every day their exile vote
 * landed on a wolf.
 *
 * [catalog] is the same set of rules written out for players to read, so the
 * scoring table and what actually gets paid are one thing. A penalty is a single
 * entry with a negative amount; [totalFor] floors a game at zero, so a penalty
 * can cancel out what this game earned but never reach credits already banked.
 */
object RewardBreakdown {

    private val mapper = jacksonObjectMapper()

    /** One line of a player's total. [count]/[each] and [days]/[perDay] show the working where there is any. */
    data class Item(
        val reason: RewardReason,
        val amount: Int,
        val count: Int? = null,
        val each: Int? = null,
        val days: Int? = null,
        val perDay: Int? = null,
        /** The days this item was earned on, so the replay can mark the right rounds. */
        val onDays: List<Int> = emptyList(),
    )

    /** What the whole game contributes, gathered once and then asked about per player. */
    class Facts(
        val winner: WinnerSide,
        val finalDay: Int,
        /** Everyone who ever wore the badge: the elected sheriff and each later recipient. */
        val badgeHolders: Set<String>,
        /** userId → the days on which that player's exile vote landed on a wolf. */
        val wolfVoteDays: Map<String, List<Int>>,
    )

    /**
     * The badge can change hands, and everyone who held it is paid: the elected sheriff
     * from the election row, each successor from the handover the table witnessed.
     */
    fun badgeHolders(electedSheriffUserId: String?, events: List<GameEvent>): Set<String> =
        buildSet {
            electedSheriffUserId?.let(::add)
            events.filter { it.eventType == "BADGE_HANDOVER" }.forEach { e ->
                @Suppress("UNCHECKED_CAST")
                val payload = mapper.readValue(e.message, Map::class.java) as Map<String, Any?>
                // to = null means the badge was torn up rather than passed on.
                (payload["toUserId"] as? String)?.let(::add)
            }
        }

    /**
     * Each player's final word on a day, paid at most once a day. It pays for the read, not
     * the result: the table need not have voted that wolf out.
     *
     * Only the good side is paid. A wolf voting a wolf is voting a teammate — 倒钩 is a play,
     * not a read, and paying for it would let two wolves farm the bonus off each other.
     *
     * The log leads and the vote rows follow. A PK re-vote deletes the round-1 rows and bars
     * the tied players from voting again, so the table alone would lose a PK candidate's
     * round-1 read entirely; the tallies still hold it. Walking the tallies in order keeps
     * each voter's last recorded vote — the re-vote for everyone who could cast one, round 1
     * for those who could not. A day that never reached a tally has no event, and there the
     * rows that were never cleared are all there is.
     */
    fun wolfVoteDays(
        votes: List<Vote>,
        events: List<GameEvent>,
        roleOf: (String) -> PlayerRole?,
    ): Map<String, List<Int>> {
        val finalVote = mutableMapOf<Pair<String, Int>, String?>()
        val daysTallied = mutableSetOf<Int>()
        events.filter { it.eventType == "VOTE_RESULT" }.forEach { e ->
            @Suppress("UNCHECKED_CAST")
            val p = mapper.readValue(e.message, Map::class.java) as Map<String, Any?>
            val day = (p["dayNumber"] as Number).toInt()
            daysTallied += day
            @Suppress("UNCHECKED_CAST")
            (p["tally"] as? List<Map<String, Any?>>).orEmpty().forEach { t ->
                val target = t["userId"] as? String
                @Suppress("UNCHECKED_CAST")
                (t["voters"] as? List<Map<String, Any?>>).orEmpty().forEach { v ->
                    (v["userId"] as? String)?.let { finalVote[it to day] = target }
                }
            }
            // Only players who actually cast an empty vote are listed here, so a PK candidate
            // — who has no row at all in the re-vote — is never mistaken for abstaining.
            @Suppress("UNCHECKED_CAST")
            (p["abstainVoters"] as? List<Map<String, Any?>>).orEmpty().forEach { v ->
                (v["userId"] as? String)?.let { finalVote[it to day] = null }
            }
        }
        votes.filter { it.dayNumber !in daysTallied }
            .forEach { finalVote[it.voterUserId to it.dayNumber] = it.targetUserId }

        return finalVote.entries
            .filter { (who, target) ->
                roleOf(who.first)?.isWolf == false && target?.let { roleOf(it)?.isWolf } == true
            }
            .groupBy({ it.key.first }, { it.key.second })
            .mapValues { (_, days) -> days.sorted() }
    }

    fun itemsFor(player: GamePlayer, facts: Facts, rewards: RewardProperties): List<Item> = buildList {
        val isWolf = player.role.isWolf
        val won = if (facts.winner == WinnerSide.WEREWOLF) isWolf else !isWolf
        when {
            won -> add(Item(RewardReason.WIN, rewards.winBonus))
            isWolf -> {
                val days = (player.diedDay ?: facts.finalDay).coerceAtLeast(1)
                add(Item(RewardReason.WOLF_SURVIVAL, rewards.wolfPerDaySurvived * days, days = days, perDay = rewards.wolfPerDaySurvived))
            }
            else -> add(Item(RewardReason.PARTICIPATION, rewards.participation))
        }
        if (player.userId in facts.badgeHolders) {
            add(Item(RewardReason.SHERIFF, rewards.sheriffBonus, count = 1, each = rewards.sheriffBonus))
        }
        facts.wolfVoteDays[player.userId]?.takeIf { it.isNotEmpty() }?.let { days ->
            add(Item(RewardReason.WOLF_VOTE, rewards.wolfVoteBonus * days.size, count = days.size, each = rewards.wolfVoteBonus, onDays = days))
        }
    }

    /** Floored at zero: a bad game can be worth nothing, never less than nothing. */
    fun totalFor(player: GamePlayer, facts: Facts, rewards: RewardProperties): Int =
        floor(itemsFor(player, facts, rewards).sumOf { it.amount })

    fun floor(sum: Int): Int = sum.coerceAtLeast(0)

    /**
     * The scoring table players can read. Every [RewardReason] must appear here —
     * a rule that pays but is not listed is a rule nobody can find out about.
     *
     * The BASE three are what [itemsFor] picks exactly one of, so they are grouped
     * apart from the bonuses that stack on top; listing all five flat read as though
     * a winning wolf collected both the win bonus and the survival score.
     */
    fun catalog(rewards: RewardProperties): List<RewardRule> = listOf(
        RewardRule(RewardReason.WIN, RewardGroup.BASE, "胜利方奖励", "本局获胜的阵营", rewards.winBonus, unit = null),
        RewardRule(RewardReason.PARTICIPATION, RewardGroup.BASE, "参与奖励", "失利的好人阵营", rewards.participation, unit = null),
        // Paid per day survived, so the rate is "/ 天": a wolf out on day 3 earns three days' worth.
        RewardRule(
            RewardReason.WOLF_SURVIVAL, RewardGroup.BASE, "狼人存活",
            "狼人失利时，活到第几天就算几天", rewards.wolfPerDaySurvived, unit = "天",
        ),
        RewardRule(RewardReason.SHERIFF, RewardGroup.BONUS, "当选／持有警徽", "当选警长，或之后接过警徽", rewards.sheriffBonus, unit = null),
        RewardRule(RewardReason.WOLF_VOTE, RewardGroup.BONUS, "投出狼人", "白天投票投中狼人，每天最多一次", rewards.wolfVoteBonus, unit = "次"),
    )
}
