package com.werewolf.dto

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide

/**
 * One player's replay of a finished game (玩家视角复盘): only what that viewer
 * legitimately knew, and only up to the moment they left the game.
 *
 * Where [GameReview] shows every role's night turn, this shows the viewer's own
 * turn and the dawn the table saw. Roles stay hidden unless the viewer knew them,
 * and the rounds stop at their death — [diedOnDay] null means they were still
 * alive at the end, so the replay runs the whole game.
 */
data class PlayerReview(
    val winner: WinnerSide?,
    val me: PlayerSeat,
    val seats: List<PlayerSeat>,
    val rounds: List<PlayerRound>,
    val diedOnDay: Int?,
    val reward: MyReward?,
)

/** Why the viewer earned what they did — the lines of [RewardBreakdown]. */
enum class RewardReason { WIN, WOLF_SURVIVAL, PARTICIPATION, SHERIFF, WOLF_VOTE }

/**
 * What the viewer earned from this game. [total] is the settled amount read back from
 * the ledger and is always the truth; [items] is the working, recomputed from the same
 * rules, and is empty when it no longer adds up to [total] — the configured rates can
 * be changed after a game has been settled, and the replay will not show arithmetic
 * that does not hold.
 */
data class MyReward(val total: Int, val items: List<RewardItem>)

/** [count]/[each] and [days]/[perDay] show the working where a line has any. */
data class RewardItem(
    val reason: RewardReason,
    val amount: Int,
    val count: Int? = null,
    val each: Int? = null,
    val days: Int? = null,
    val perDay: Int? = null,
)

/** WOLF covers every wolf-camp role: a seer check cannot tell them apart. */
enum class KnownCamp { WOLF, GOOD }

/**
 * One seat as the viewer came to know it. [role] is set only where they knew the exact
 * role — their own, a wolf teammate's, or one the table saw revealed; [camp] only where
 * they knew the side but not the role, which is what a seer check gives. Both reflect
 * everything the viewer had learned by the end of their replay.
 */
data class PlayerSeat(
    val userId: String,
    val nickname: String,
    val seatIndex: Int,
    val role: PlayerRole?,
    val camp: KnownCamp?,
)

/** One day number: the night that opens it (null once the viewer is dead) and that day's public events. */
data class PlayerRound(val day: Int, val night: PlayerNight?, val events: List<PlayerDayEvent>)

/** [action] is the viewer's own turn — null for the roles that sleep through the night. */
data class PlayerNight(val action: MyNightAction?, val dawn: PublicDawn)

/** The viewer's own night turn. Only the variant matching their role is ever built. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface MyNightAction {
    /** [teammates] = the wolves still alive that night, the viewer included. [target] null = the wolves skipped. */
    @JsonTypeName("WOLF")
    data class Wolf(val teammates: List<PlayerSeat>, val target: PlayerSeat?, val selfKill: Boolean) : MyNightAction

    /** [target] null = the seer did not check. [isWerewolf] is the answer they were given. */
    @JsonTypeName("SEER")
    data class Seer(val target: PlayerSeat?, val isWerewolf: Boolean) : MyNightAction

    /** [attacked] = the wolves' target, which the witch is shown before she acts. */
    @JsonTypeName("WITCH")
    data class Witch(val attacked: PlayerSeat?, val antidote: PlayerSeat?, val poison: PlayerSeat?) : MyNightAction

    @JsonTypeName("GUARD")
    data class Guard(val target: PlayerSeat?) : MyNightAction
}

/**
 * Dawn as the table saw it: who died, never why — a death is announced, its cause is not.
 * [saved] names a player who survived the wolves, and is set only for the viewers who knew
 * an attack happened at all: the wolves who chose the target, and the witch who was shown it.
 */
data class PublicDawn(val deaths: List<PlayerSeat>, val saved: PlayerSeat?)

/** Everything here happened in the open, so it is shown exactly as the god's-eye replay has it, minus the roles. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface PlayerDayEvent {
    /** [round] 2 = PK re-vote. [appointed] = the host picked after a tie. No 冲票/倒钩 read: that needs the roles. */
    @JsonTypeName("VOTE")
    data class Vote(
        val kind: VoteKind,
        val round: Int,
        val tally: List<PlayerTally>,
        val abstain: List<PlayerSeat>,
        val result: PlayerSeat?,
        val appointed: Boolean,
        /** Credits this very vote earned the viewer, null when it earned nothing. */
        val points: Int? = null,
    ) : PlayerDayEvent

    @JsonTypeName("HUNTER_SHOT")
    data class HunterShot(val hunter: PlayerSeat, val target: PlayerSeat) : PlayerDayEvent

    @JsonTypeName("SELF_DESTRUCT")
    data class SelfDestruct(val player: PlayerSeat, val taken: PlayerSeat?) : PlayerDayEvent

    @JsonTypeName("IDIOT_REVEAL")
    data class IdiotReveal(val player: PlayerSeat) : PlayerDayEvent

    /** [to] null = the badge was destroyed. */
    /** [points] is set when the viewer is the one receiving the badge. */
    @JsonTypeName("BADGE_HANDOVER")
    data class BadgeHandover(val from: PlayerSeat, val to: PlayerSeat?, val points: Int? = null) : PlayerDayEvent
}

data class PlayerTally(val target: PlayerSeat, val votes: Double, val voters: List<PlayerSeat>)
