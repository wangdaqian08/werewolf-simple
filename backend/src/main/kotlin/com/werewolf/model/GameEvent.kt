package com.werewolf.model

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "game_events",
    indexes = [Index(name = "idx_game_events", columnList = "game_id")],
)
class GameEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(name = "game_id", nullable = false)
    var gameId: Int = 0,

    @Column(name = "event_type", nullable = false, length = 50)
    var eventType: String = "",

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    var message: String = "",

    @Column(name = "target_user_id", length = 128)
    var targetUserId: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    var createdAt: LocalDateTime? = null,
)
