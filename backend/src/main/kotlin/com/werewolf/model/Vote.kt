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
    var id: Int? = null,

    @Column(name = "game_id", nullable = false)
    var gameId: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "vote_context", nullable = false, length = 20)
    var voteContext: VoteContext = VoteContext.ELIMINATION,

    @Column(name = "day_number", nullable = false)
    var dayNumber: Int = 0,

    @Column(name = "voter_user_id", nullable = false, length = 128)
    var voterUserId: String = "",

    // NULL = abstain or skip
    @Column(name = "target_user_id", length = 128)
    var targetUserId: String? = null,

    @Column(name = "voted_at", nullable = false, updatable = false)
    @CreationTimestamp
    var votedAt: LocalDateTime? = null,
)
