package com.werewolf.model

import jakarta.persistence.*

@Entity
@Table(
    name = "sheriff_candidates",
    uniqueConstraints = [UniqueConstraint(name = "uq_election_user", columnNames = ["election_id", "user_id"])],
)
class SheriffCandidate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(name = "election_id", nullable = false)
    var electionId: Int = 0,

    @Column(name = "user_id", nullable = false, length = 128)
    var userId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: CandidateStatus = CandidateStatus.RUNNING,
)
