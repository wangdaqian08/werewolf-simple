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

    /**
     * The wolves that make tonight's kill: the pack, or the lone wolf (mechanical wolf or gargoyle)
     * once it is the only wolf left. The two lone wolves never share a board.
     */
    fun wolfKillers(alive: List<GamePlayer>): List<GamePlayer> =
        alive.filter { it.role.isPackWolf }.ifEmpty { alive.filter { it.role.isWolf } }

    /** The player who 殉情 when [outUserId] goes out now: today's night charm, if [outUserId] is the
     *  Wolf Beauty and the charmed player is still alive. Callers never pass a duel or a declare-out. */
    fun charmVictim(outUserId: String, players: List<GamePlayer>, todaysNight: NightPhase?): String? =
        charmed(outUserId, players, todaysNight) { it.alive }

    /**
     * The night path: the 殉情 victim when the 狼美人 is among [nightKills], the deaths of [night].
     * "Still alive" means alive when that night began, because the dawn list is recomputed after its
     * kills are applied (the day view, the reveal advancer, the review).
     */
    fun charmVictimAtNight(night: NightPhase, players: List<GamePlayer>, nightKills: Collection<String>): String? {
        val beauty = players.find { it.role == PlayerRole.WOLF_BEAUTY && it.userId in nightKills } ?: return null
        return charmed(beauty.userId, players, night) { it.alive || (it.diedDay ?: 0) >= night.dayNumber }
    }

    private fun charmed(outUserId: String, players: List<GamePlayer>, night: NightPhase?, alive: (GamePlayer) -> Boolean): String? {
        if (players.find { it.userId == outUserId }?.role != PlayerRole.WOLF_BEAUTY) return null
        val charmedId = night?.wolfBeautyCharmUserId ?: return null
        return charmedId.takeIf { players.find { p -> p.userId == charmedId }?.let(alive) == true }
    }

    /** LEARN until learned, else the skill usable tonight or NONE (SHOOT is never a night action). */
    fun mechNightSkill(mech: GamePlayer, tonight: Int, allNights: List<NightPhase>): MechNightSkill {
        val learned = mech.learnedRole ?: return MechNightSkill.LEARN
        // A skill is usable from the night after learning.
        val learnNight = allNights.firstOrNull { it.mechLearnTargetUserId != null }?.dayNumber
            ?: error("mechanical wolf ${mech.userId} has learnedRole but no learn night")
        if (tonight <= learnNight) return MechNightSkill.NONE
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
