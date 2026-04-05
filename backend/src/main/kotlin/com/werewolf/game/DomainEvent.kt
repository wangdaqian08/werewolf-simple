package com.werewolf.game

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed class DomainEvent {
    // subPhase is String? because it carries DaySubPhase, VotingSubPhase, ElectionSubPhase, or NightSubPhase
    @JsonTypeName("PhaseChanged")
    data class PhaseChanged(val gameId: Int, val phase: GamePhase, val subPhase: String? = null) : DomainEvent()
    @JsonTypeName("NightSubPhaseChanged")
    data class NightSubPhaseChanged(val gameId: Int, val subPhase: NightSubPhase) : DomainEvent()
    @JsonTypeName("RoleAssigned")
    data class RoleAssigned(val gameId: Int, val userId: String, val role: PlayerRole) : DomainEvent()
    @JsonTypeName("NightResult")
    data class NightResult(val gameId: Int, val kills: List<String>) : DomainEvent()
    @JsonTypeName("SeerResult")
    data class SeerResult(val gameId: Int, val checkedUserId: String, val isWerewolf: Boolean) : DomainEvent()
    @JsonTypeName("VoteSubmitted")
    data class VoteSubmitted(val gameId: Int, val voterUserId: String) : DomainEvent()
    @JsonTypeName("VoteTally")
    data class VoteTally(val gameId: Int, val eliminatedUserId: String?, val tally: Map<String, Int>) : DomainEvent()
    @JsonTypeName("PlayerEliminated")
    data class PlayerEliminated(val gameId: Int, val userId: String, val role: PlayerRole) : DomainEvent()
    @JsonTypeName("HunterShot")
    data class HunterShot(val gameId: Int, val hunterUserId: String, val targetUserId: String) : DomainEvent()
    @JsonTypeName("BadgeHandover")
    data class BadgeHandover(val gameId: Int, val fromUserId: String, val toUserId: String?) : DomainEvent()
    @JsonTypeName("SheriffElected")
    data class SheriffElected(val gameId: Int, val sheriffUserId: String?) : DomainEvent()
    @JsonTypeName("GameOver")
    data class GameOver(val gameId: Int, val winner: WinnerSide) : DomainEvent()
    @JsonTypeName("RoleConfirmed")
    data class RoleConfirmed(val gameId: Int, val userId: String) : DomainEvent()
    @JsonTypeName("IdiotRevealed")
    data class IdiotRevealed(val gameId: Int, val userId: String) : DomainEvent()
    @JsonTypeName("WolfSelectionChanged")
    data class WolfSelectionChanged(val gameId: Int, val selectedTargetUserId: String) : DomainEvent()
}
