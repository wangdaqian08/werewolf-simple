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
    var id: Int? = null,

    @Column(name = "game_id", nullable = false)
    var gameId: Int = 0,

    @Column(name = "day_number", nullable = false)
    var dayNumber: Int = 0,

    @Column(name = "eliminated_user_id", length = 128)
    var eliminatedUserId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "eliminated_role", length = 10)
    var eliminatedRole: PlayerRole? = null,

    @Column(name = "hunter_shot_user_id", length = 128)
    var hunterShotUserId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "hunter_shot_role", length = 10)
    var hunterShotRole: PlayerRole? = null,

    @Column(name = "recorded_at", nullable = false, updatable = false)
    @CreationTimestamp
    var recordedAt: LocalDateTime? = null,
)
