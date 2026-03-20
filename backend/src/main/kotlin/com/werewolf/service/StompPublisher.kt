package com.werewolf.service

import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

@Service
class StompPublisher(private val template: SimpMessagingTemplate) {

    /** Broadcast a public game event to all subscribers of this game. */
    fun broadcastGame(gameId: Int, event: Any) {
        template.convertAndSend("/topic/game/$gameId", event)
    }

    /** Send a private message to a specific user (role assignment, seer result, etc.). */
    fun sendPrivate(userId: String, event: Any) {
        template.convertAndSendToUser(userId, "/queue/private", event)
    }
}
