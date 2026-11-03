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

    // Mechanical wolf: whom it learned from (once per game), and the nightly results of the learned skill
    @Column(name = "mech_learn_target_user_id", length = 128)
    var mechLearnTargetUserId: String? = null,

    @Column(name = "mech_check_target_user_id", length = 128)
    var mechCheckTargetUserId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "mech_check_result_role", length = 20)
    var mechCheckResultRole: PlayerRole? = null,

    @Column(name = "mech_poison_target_user_id", length = 128)
    var mechPoisonTargetUserId: String? = null,

    @Column(name = "mech_guard_target_user_id", length = 128)
    var mechGuardTargetUserId: String? = null,

    // Medium: whom it checked and the exact role it saw
    @Column(name = "medium_checked_user_id", length = 128)
    var mediumCheckedUserId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "medium_result_role", length = 20)
    var mediumResultRole: PlayerRole? = null,
) {
    init {
        require(gameId > 0) { "gameId must be a valid ID, got $gameId" }
        require(dayNumber > 0) { "dayNumber must be > 0, got $dayNumber" }
    }
}
