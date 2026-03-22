package com.werewolf.model

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "games")
class Game(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "game_id")
    val gameId: Int? = null,

    @Column(name = "room_id", nullable = false)
    val roomId: Int,

    @Column(name = "host_user_id", nullable = false, length = 128)
    val hostUserId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var phase: GamePhase = GamePhase.ROLE_REVEAL,

    @Column(name = "day_number", nullable = false)
    var dayNumber: Int = 1,

    @Column(name = "sheriff_user_id", length = 128)
    var sheriffUserId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    var winner: WinnerSide? = null,

    // Sub-phase for DAY (RESULT_HIDDEN, RESULT_REVEALED) and VOTING (VOTING, VOTE_RESULT, HUNTER_SHOOT, BADGE_HANDOVER).
    // NULL when phase is NIGHT or SHERIFF_ELECTION (those entities own sub-phase).
    @Column(name = "sub_phase", length = 25)
    var subPhase: String? = null,

    @Column(name = "started_at", nullable = false, updatable = false)
    @CreationTimestamp
    val startedAt: LocalDateTime? = null,

    @Column(name = "ended_at")
    var endedAt: LocalDateTime? = null,
) {
    init {
        require(roomId > 0) { "roomId must be a valid ID, got $roomId" }
        require(hostUserId.isNotBlank()) { "hostUserId must not be blank" }
    }
}
