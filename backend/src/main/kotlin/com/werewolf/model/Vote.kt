package com.werewolf.model

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "votes",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_vote",
            columnNames = ["game_id", "vote_context", "day_number", "voter_user_id"],
        ),
    ],
)
class Vote(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "game_id", nullable = false)
    val gameId: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "vote_context", nullable = false, length = 20)
    val voteContext: VoteContext,

    @Column(name = "day_number", nullable = false)
    val dayNumber: Int,

    @Column(name = "voter_user_id", nullable = false, length = 128)
    val voterUserId: String,

    // NULL = abstain or skip
    @Column(name = "target_user_id", length = 128)
    val targetUserId: String? = null,

    @Column(name = "voted_at", nullable = false, updatable = false)
    @CreationTimestamp
    val votedAt: LocalDateTime? = null,
) {
    init {
        require(gameId > 0) { "gameId must be a valid ID, got $gameId" }
        require(dayNumber > 0) { "dayNumber must be > 0, got $dayNumber" }
        require(voterUserId.isNotBlank()) { "voterUserId must not be blank" }
    }
}
