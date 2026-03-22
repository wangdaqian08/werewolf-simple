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
    val id: Int? = null,

    @Column(name = "election_id", nullable = false)
    val electionId: Int,

    @Column(name = "user_id", nullable = false, length = 128)
    val userId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: CandidateStatus = CandidateStatus.RUNNING,
) {
    init {
        require(electionId > 0) { "electionId must be a valid ID, got $electionId" }
        require(userId.isNotBlank()) { "userId must not be blank" }
    }
}
