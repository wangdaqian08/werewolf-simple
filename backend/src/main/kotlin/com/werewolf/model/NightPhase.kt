package com.werewolf.model

import jakarta.persistence.*

@Entity
@Table(
    name = "night_phases",
    uniqueConstraints = [UniqueConstraint(name = "uq_game_night", columnNames = ["game_id", "day_number"])],
)
class NightPhase(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "game_id", nullable = false)
    val gameId: Int,

    @Column(name = "day_number", nullable = false)
    val dayNumber: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_phase", nullable = false, length = 20)
    var subPhase: NightSubPhase = NightSubPhase.WEREWOLF_PICK,

    @Column(name = "wolf_target_user_id", length = 128)
    var wolfTargetUserId: String? = null,

    @Column(name = "seer_checked_user_id", length = 128)
    var seerCheckedUserId: String? = null,

    @Column(name = "seer_result_is_werewolf")
    var seerResultIsWerewolf: Boolean? = null,

    @Column(name = "witch_antidote_used", nullable = false)
    var witchAntidoteUsed: Boolean = false,

    @Column(name = "witch_poison_target_user_id", length = 128)
    var witchPoisonTargetUserId: String? = null,

    @Column(name = "guard_target_user_id", length = 128)
    var guardTargetUserId: String? = null,

    @Column(name = "prev_guard_target_user_id", length = 128)
    var prevGuardTargetUserId: String? = null,

    /**
     * Set true once a wolf-killed hunter has shot OR passed during the
     * day-reveal flow. Keeps [com.werewolf.game.phase.DayRevealAdvancer] from
     * re-entering HUNTER_SHOOT_NIGHT_DEATH in the hunter-is-sheriff cascade.
     */
    @Column(name = "hunter_night_shoot_resolved", nullable = false)
    var hunterNightShootResolved: Boolean = false,
) {
    init {
        require(gameId > 0) { "gameId must be a valid ID, got $gameId" }
        require(dayNumber > 0) { "dayNumber must be > 0, got $dayNumber" }
    }
}
