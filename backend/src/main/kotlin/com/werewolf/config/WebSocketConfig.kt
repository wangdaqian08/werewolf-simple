package com.werewolf.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
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
        // 10s/10s broker heartbeats keep idle mobile sockets alive past NATs and cellular proxies; without them the broker negotiates [0,0] and idle connections silently die.
        registry.enableSimpleBroker("/topic", "/user/queue")
            .setHeartbeatValue(longArrayOf(10_000, 10_000))
            .setTaskScheduler(webSocketHeartbeatScheduler())
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
    }

    @Bean
    fun webSocketHeartbeatScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("ws-heartbeat-")
        scheduler.initialize()
        return scheduler
    }
}
