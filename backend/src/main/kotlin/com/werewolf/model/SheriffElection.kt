package com.werewolf.model

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "sheriff_elections",
    uniqueConstraints = [UniqueConstraint(name = "uq_se_game", columnNames = ["game_id"])],
)
class SheriffElection(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "game_id", nullable = false)
    val gameId: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_phase", nullable = false, length = 10)
    var subPhase: ElectionSubPhase = ElectionSubPhase.SIGNUP,

    // Comma-separated userIds in randomized speaking order; set once at SPEECH start
    @Column(name = "speaking_order", columnDefinition = "TEXT")
    var speakingOrder: String? = null,

    @Column(name = "current_speaker_idx", nullable = false)
    var currentSpeakerIdx: Int = 0,

    @Column(name = "elected_sheriff_user_id", length = 128)
    var electedSheriffUserId: String? = null,

    @Column(name = "started_at", nullable = false, updatable = false)
    @CreationTimestamp
    val startedAt: LocalDateTime? = null,

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,
)
