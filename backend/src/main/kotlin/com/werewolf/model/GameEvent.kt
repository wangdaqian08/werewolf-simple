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
    val id: Int? = null,

    @Column(name = "game_id", nullable = false)
    val gameId: Int,

    @Column(name = "event_type", nullable = false, length = 50)
    val eventType: String,

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    val message: String,

    @Column(name = "target_user_id", length = 128)
    val targetUserId: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    val createdAt: LocalDateTime? = null,
) {
    init {
        require(gameId > 0) { "gameId must be a valid ID, got $gameId" }
        require(eventType.isNotBlank()) { "eventType must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}
