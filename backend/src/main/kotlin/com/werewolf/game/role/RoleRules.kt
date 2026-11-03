package com.werewolf.game.role

import com.werewolf.model.GamePlayer
import com.werewolf.model.LearnedSkill
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole

enum class MechNightSkill { LEARN, CHECK_ROLE, CHECK_ALIGNMENT, POISON, PROTECT, NONE }

/** Role predicates that depend on a player's state (a mechanical wolf's learned role), not just its role. */
object RoleRules {
    fun shootsOnDeath(p: GamePlayer): Boolean = p.role.shootsOnDeath || p.learnedRole?.shootsOnDeath == true

    /** What the medium sees: the mechanical wolf shows its learned role once it has one. */
    fun mediumSees(p: GamePlayer): PlayerRole = p.learnedRole ?: p.role

    /** The wolves that make tonight's kill: the pack, or the mechanical wolf once it is the only wolf left. */
    fun wolfKillers(alive: List<GamePlayer>): List<GamePlayer> =
        alive.filter { it.role.isPackWolf }.ifEmpty { alive.filter { it.role == PlayerRole.MECHANICAL_WOLF } }

    /** LEARN until learned, else the skill usable tonight or NONE (SHOOT is never a night action). */
    fun mechNightSkill(mech: GamePlayer, tonight: Int, allNights: List<NightPhase>): MechNightSkill {
        val learned = mech.learnedRole ?: return MechNightSkill.LEARN
        // A skill is usable from the night after learning.
        if (tonight <= allNights.first { it.mechLearnTargetUserId != null }.dayNumber) return MechNightSkill.NONE
        return when (learned.learnedSkill) {
            LearnedSkill.CHECK_ROLE -> MechNightSkill.CHECK_ROLE
            LearnedSkill.CHECK_ALIGNMENT -> MechNightSkill.CHECK_ALIGNMENT
            LearnedSkill.POISON ->
                if (allNights.any { it.mechPoisonTargetUserId != null }) MechNightSkill.NONE else MechNightSkill.POISON
            LearnedSkill.PROTECT ->
                if (allNights.any { it.mechGuardTargetUserId != null }) MechNightSkill.NONE else MechNightSkill.PROTECT
            LearnedSkill.SHOOT, LearnedSkill.NONE -> MechNightSkill.NONE
        }
    }
}
