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
    val id: Int? = null,

    @Column(name = "room_id", nullable = false)
    val roomId: Int,

    @Column(name = "user_id", nullable = false, length = 128)
    val userId: String,

    @Column(name = "seat_index")
    var seatIndex: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: ReadyStatus = ReadyStatus.NOT_READY,

    @Column(name = "is_host", nullable = false)
    var host: Boolean = false,
) {
    init {
        require(roomId > 0) { "roomId must be a valid ID, got $roomId" }
        require(userId.isNotBlank()) { "userId must not be blank" }
    }
}
