package com.werewolf.model

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "elimination_history",
    uniqueConstraints = [UniqueConstraint(name = "uq_game_day", columnNames = ["game_id", "day_number"])],
)
class EliminationHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "game_id", nullable = false)
    val gameId: Int,

    @Column(name = "day_number", nullable = false)
    val dayNumber: Int,

    @Column(name = "eliminated_user_id", length = 128)
    val eliminatedUserId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "eliminated_role", length = 10)
    val eliminatedRole: PlayerRole? = null,

    @Column(name = "hunter_shot_user_id", length = 128)
    var hunterShotUserId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "hunter_shot_role", length = 10)
    var hunterShotRole: PlayerRole? = null,

    @Column(name = "recorded_at", nullable = false, updatable = false)
    @CreationTimestamp
    val recordedAt: LocalDateTime? = null,
) {
    init {
        require(gameId > 0) { "gameId must be a valid ID, got $gameId" }
        require(dayNumber > 0) { "dayNumber must be > 0, got $dayNumber" }
    }
}
