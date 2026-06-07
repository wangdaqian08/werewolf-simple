package com.werewolf.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(name = "rooms")
class Room(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    val roomId: Int? = null,

    // Not globally unique: 3-digit codes are recycled once a room's game ends,
    // so finished rooms may share a code with a newer active room. Uniqueness is
    // enforced among *active* rooms in RoomService.generateCode.
    @Column(name = "room_code", nullable = false, length = 3)
    val roomCode: String,

    @Column(name = "host_user_id", nullable = false, length = 128)
    val hostUserId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: RoomStatus = RoomStatus.WAITING,

    @Column(name = "total_players", nullable = false)
    val totalPlayers: Int,

    @Column(name = "wolf_count", nullable = false)
    val wolfCount: Int = 2,

    @Column(name = "has_seer", nullable = false)
    val hasSeer: Boolean = false,

    @Column(name = "has_witch", nullable = false)
    val hasWitch: Boolean = false,

    @Column(name = "has_hunter", nullable = false)
    val hasHunter: Boolean = false,

    @Column(name = "has_guard", nullable = false)
    val hasGuard: Boolean = false,

    @Column(name = "has_idiot", nullable = false)
    val hasIdiot: Boolean = false,

    @Column(name = "has_sheriff", nullable = false)
    val hasSheriff: Boolean = true,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config")
    var config: GameConfig? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "win_condition", nullable = false, length = 20)
    val winCondition: WinConditionMode = WinConditionMode.CLASSIC,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    val createdAt: LocalDateTime? = null,

    @Column(name = "closed_at")
    var closedAt: LocalDateTime? = null,
) {
    init {
        require(roomCode.isNotBlank()) { "roomCode must not be blank" }
        require(hostUserId.isNotBlank()) { "hostUserId must not be blank" }
        require(totalPlayers > 0) { "totalPlayers must be > 0, got $totalPlayers" }
        require(wolfCount > 0) { "wolfCount must be > 0, got $wolfCount" }
    }
}
