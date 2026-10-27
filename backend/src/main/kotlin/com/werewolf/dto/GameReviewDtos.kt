package com.werewolf.dto

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide

/** God's-eye replay (上帝视角复盘) of a finished game, every player's true role shown. */
data class GameReview(
    val winner: WinnerSide?,
    val players: List<ReviewPlayer>,
    val rounds: List<ReviewRound>,
    val rewards: List<ReviewReward>,
)

/** Credits a player earned from this game, read back from the ledger. Empty when the game was never settled. */
data class ReviewReward(val player: ReviewPlayer, val amount: Int)

data class ReviewPlayer(val userId: String, val nickname: String, val seatIndex: Int, val role: PlayerRole)

/** One day number: the night that opens it (null when not completed) and that day's events in order. */
data class ReviewRound(val day: Int, val night: ReviewNight?, val events: List<ReviewDayEvent>)

/** [seer], [witch] and [guard] are null when the role is not in the game. */
data class ReviewNight(
    val wolf: WolfTurn,
    val seer: SeerTurn?,
    val witch: WitchTurn?,
    val guard: GuardTurn?,
    val dawn: Dawn,
)

enum class WolfAction { KILL, SKIP, SELF_KILL }

data class WolfTurn(val actors: List<ReviewPlayer>, val action: WolfAction, val target: ReviewPlayer?)
data class SeerTurn(val actor: ReviewPlayer, val dead: Boolean, val target: ReviewPlayer?, val isWerewolf: Boolean)
data class WitchTurn(val actor: ReviewPlayer, val dead: Boolean, val antidote: ReviewPlayer?, val poison: ReviewPlayer?)
data class GuardTurn(val actor: ReviewPlayer, val dead: Boolean, val target: ReviewPlayer?)

enum class DeathCause { WOLF, POISON, BOTH_SAVES }
enum class SaveSource { GUARD, ANTIDOTE, PERK }

data class NightDeath(val player: ReviewPlayer, val cause: DeathCause)
data class NightSave(val player: ReviewPlayer, val by: SaveSource)
data class Dawn(val deaths: List<NightDeath>, val saved: NightSave?)

enum class VoteKind { SHERIFF, EXILE }

/** PUSH = 冲票, BUS = 倒钩 */
enum class WolfPlay { PUSH, BUS }

data class TallyEntry(val target: ReviewPlayer, val votes: Double, val voters: List<ReviewPlayer>)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface ReviewDayEvent {
    /**
     * [round] 2 = PK re-vote. [result] = exiled player / sheriff, null = tie or no sheriff.
     * [appointed] = the host picked the sheriff after a tie or a vote nobody won (sheriff votes only).
     */
    @JsonTypeName("VOTE")
    data class Vote(
        val kind: VoteKind,
        val round: Int,
        val tally: List<TallyEntry>,
        val abstain: List<ReviewPlayer>,
        val result: ReviewPlayer?,
        val appointed: Boolean,
        val wolfPlay: WolfPlay?,
    ) : ReviewDayEvent

    @JsonTypeName("HUNTER_SHOT")
    data class HunterShot(val hunter: ReviewPlayer, val target: ReviewPlayer) : ReviewDayEvent

    @JsonTypeName("SELF_DESTRUCT")
    data class SelfDestruct(val player: ReviewPlayer, val taken: ReviewPlayer?) : ReviewDayEvent

    @JsonTypeName("IDIOT_REVEAL")
    data class IdiotReveal(val player: ReviewPlayer) : ReviewDayEvent

    /** [to] null = the badge was destroyed. */
    @JsonTypeName("BADGE_HANDOVER")
    data class BadgeHandover(val from: ReviewPlayer, val to: ReviewPlayer?) : ReviewDayEvent
}
