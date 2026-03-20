package com.werewolf.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    @Id
    @Column(name = "user_id", length = 128)
    var userId: String = "",

    @Column(nullable = false, length = 50)
    var nickname: String = "",

    @Column(name = "avatar_url", length = 500)
    var avatarUrl: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    var createdAt: LocalDateTime? = null,

    @Column(name = "last_seen_at", nullable = false)
    @UpdateTimestamp
    var lastSeenAt: LocalDateTime? = null,
)
