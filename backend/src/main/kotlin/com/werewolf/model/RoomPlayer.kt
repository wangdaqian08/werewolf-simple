package com.werewolf.model

import jakarta.persistence.*

@Entity
@Table(
    name = "room_players",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_room_user", columnNames = ["room_id", "user_id"]),
        UniqueConstraint(name = "uq_room_seat", columnNames = ["room_id", "seat_index"]),
    ],
)
class RoomPlayer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(name = "room_id", nullable = false)
    var roomId: Int = 0,

    @Column(name = "user_id", nullable = false, length = 128)
    var userId: String = "",

    @Column(name = "seat_index")
    var seatIndex: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: ReadyStatus = ReadyStatus.NOT_READY,

    @Column(name = "is_host", nullable = false)
    var host: Boolean = false,
)
