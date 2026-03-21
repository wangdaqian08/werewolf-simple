package com.werewolf.model

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "rooms")
class Room(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    val roomId: Int? = null,

    @Column(name = "room_code", nullable = false, length = 4, unique = true)
    val roomCode: String = "",

    @Column(name = "host_user_id", nullable = false, length = 128)
    val hostUserId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: RoomStatus = RoomStatus.WAITING,

    @Column(name = "total_players", nullable = false)
    val totalPlayers: Int = 0,

    @Column(name = "has_seer", nullable = false)
    val hasSeer: Boolean = false,

    @Column(name = "has_witch", nullable = false)
    val hasWitch: Boolean = false,

    @Column(name = "has_hunter", nullable = false)
    val hasHunter: Boolean = false,

    @Column(name = "has_guard", nullable = false)
    val hasGuard: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    val createdAt: LocalDateTime? = null,

    @Column(name = "closed_at")
    var closedAt: LocalDateTime? = null,
)
