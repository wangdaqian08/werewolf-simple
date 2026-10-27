package com.werewolf.service

import com.werewolf.config.RewardProperties
import com.werewolf.dto.RewardReason
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
     * A vote row is unique per voter per day, so this is each player's final word that day
     * and the bonus can be earned at most once a day. It pays for the read, not the result:
     * the table need not have voted that wolf out.
     */
    fun wolfVoteDays(votes: List<Vote>, roleOf: (String) -> PlayerRole?): Map<String, List<Int>> =
        votes
            .filter { it.targetUserId?.let { id -> roleOf(id)?.isWolf } == true }
            .groupBy({ it.voterUserId }, { it.dayNumber })
            .mapValues { (_, days) -> days.sorted() }

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

    fun totalFor(player: GamePlayer, facts: Facts, rewards: RewardProperties): Int =
        itemsFor(player, facts, rewards).sumOf { it.amount }
}
