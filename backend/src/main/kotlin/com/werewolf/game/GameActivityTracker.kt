package com.werewolf.game

import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * When each in-flight game last had an accepted action, so [com.werewolf.service.AbandonedGameSweep]
 * can tell a game everyone walked away from. In memory only: a restart already cancels every
 * in-flight game (OrphanedGameRecovery), so nothing here needs to survive one.
 */
@Component
class GameActivityTracker {
    private val lastActivity = ConcurrentHashMap<Int, LocalDateTime>()

    fun touch(gameId: Int, at: LocalDateTime = LocalDateTime.now()) {
        lastActivity[gameId] = at
    }

    fun lastActivity(gameId: Int): LocalDateTime? = lastActivity[gameId]

    fun forget(gameId: Int) {
        lastActivity.remove(gameId)
    }
}
