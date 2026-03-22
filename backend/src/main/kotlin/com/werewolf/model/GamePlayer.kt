package com.werewolf.model

import jakarta.persistence.*

@Entity
@Table(
    name = "game_players",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_game_user", columnNames = ["game_id", "user_id"]),
        UniqueConstraint(name = "uq_game_seat", columnNames = ["game_id", "seat_index"]),
    ],
)
class GamePlayer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "game_id", nullable = false)
    val gameId: Int,

    @Column(name = "user_id", nullable = false, length = 128)
    val userId: String,

    @Column(name = "seat_index", nullable = false)
    val seatIndex: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val role: PlayerRole,

    @Column(name = "is_alive", nullable = false)
    var alive: Boolean = true,

    @Column(name = "is_sheriff", nullable = false)
    var sheriff: Boolean = false,

    @Column(name = "confirmed_role", nullable = false)
    var confirmedRole: Boolean = false,
) {
    init {
        require(gameId > 0) { "gameId must be a valid ID, got $gameId" }
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(seatIndex > 0) { "seatIndex must be > 0, got $seatIndex" }
    }
}
