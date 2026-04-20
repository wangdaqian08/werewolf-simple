package com.werewolf.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    @Value("\${websocket.allowed-origins}") private val allowedOrigins: String,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(*origins.toTypedArray())
            .withSockJS()
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // /topic  → broadcast (public game events, room updates)
        // /user/queue → per-player private messages (role, werewolf channel)
        registry.enableSimpleBroker("/topic", "/user/queue")
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
    }
}
