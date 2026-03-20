package com.werewolf.game

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed class DomainEvent {
    // subPhase is String? because it carries DaySubPhase, VotingSubPhase, ElectionSubPhase, or NightSubPhase
    data class PhaseChanged(val gameId: Int, val phase: GamePhase, val subPhase: String? = null) : DomainEvent()
    data class NightSubPhaseChanged(val gameId: Int, val subPhase: NightSubPhase) : DomainEvent()
    data class RoleAssigned(val gameId: Int, val userId: String, val role: PlayerRole) : DomainEvent()
    data class NightResult(val gameId: Int, val kills: List<String>) : DomainEvent()
    data class SeerResult(val gameId: Int, val checkedUserId: String, val isWerewolf: Boolean) : DomainEvent()
    data class VoteSubmitted(val gameId: Int, val voterUserId: String) : DomainEvent()
    data class VoteTally(val gameId: Int, val eliminatedUserId: String?, val tally: Map<String, Int>) : DomainEvent()
    data class PlayerEliminated(val gameId: Int, val userId: String, val role: PlayerRole) : DomainEvent()
    data class HunterShot(val gameId: Int, val hunterUserId: String, val targetUserId: String) : DomainEvent()
    data class BadgeHandover(val gameId: Int, val fromUserId: String, val toUserId: String?) : DomainEvent()
    data class SheriffElected(val gameId: Int, val sheriffUserId: String?) : DomainEvent()
    data class GameOver(val gameId: Int, val winner: WinnerSide) : DomainEvent()
    data class RoleConfirmed(val gameId: Int, val userId: String) : DomainEvent()
}
