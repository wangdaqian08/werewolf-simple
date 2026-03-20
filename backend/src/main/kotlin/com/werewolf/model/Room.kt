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
    var roomId: Int? = null,

    @Column(name = "room_code", nullable = false, length = 4, unique = true)
    var roomCode: String = "",

    @Column(name = "host_user_id", nullable = false, length = 128)
    var hostUserId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: RoomStatus = RoomStatus.WAITING,

    @Column(name = "total_players", nullable = false)
    var totalPlayers: Int = 0,

    @Column(name = "has_seer", nullable = false)
    var hasSeer: Boolean = false,

    @Column(name = "has_witch", nullable = false)
    var hasWitch: Boolean = false,

    @Column(name = "has_hunter", nullable = false)
    var hasHunter: Boolean = false,

    @Column(name = "has_guard", nullable = false)
    var hasGuard: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    var createdAt: LocalDateTime? = null,

    @Column(name = "closed_at")
    var closedAt: LocalDateTime? = null,
)
