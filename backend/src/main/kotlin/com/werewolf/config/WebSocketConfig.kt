package com.werewolf.config

import com.werewolf.util.JwtUtil
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    @Value("\${websocket.allowed-origins}") private val allowedOrigins: String,
    private val jwtUtil: JwtUtil,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(*origins.toTypedArray())
            .withSockJS()
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // 10s/10s broker heartbeats keep idle mobile sockets alive past NATs and cellular proxies; without them the broker negotiates [0,0] and idle connections silently die.
        // "/queue" (not "/user/queue"): convertAndSendToUser resolves to /queue/private-user{session}.
        registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(longArrayOf(10_000, 10_000))
            .setTaskScheduler(webSocketHeartbeatScheduler())
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(StompAuthInterceptor(jwtUtil))
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

/**
 * CONNECT must carry a valid JWT; its subject becomes the session user, so
 * /user/queue/private reaches only that player. Clients only listen: a SEND to
 * a broker destination would let anyone forge server events (e.g. GameOver).
 */
class StompAuthInterceptor(private val jwtUtil: JwtUtil) : ChannelInterceptor {
    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message
        when (accessor.command) {
            StompCommand.CONNECT -> {
                val token = accessor.getFirstNativeHeader("Authorization")?.removePrefix("Bearer ")
                val userId = token?.let { jwtUtil.extractUserId(it) }
                if (userId.isNullOrBlank()) throw MessageDeliveryException("Missing or invalid token")
                accessor.user = UsernamePasswordAuthenticationToken(userId, null, emptyList())
            }
            StompCommand.SEND ->
                if (accessor.destination?.startsWith("/app/") != true)
                    throw MessageDeliveryException("Clients may only send to /app")
            // Private queues are reached only through /user/queue/..., which resolves to the caller's own session.
            StompCommand.SUBSCRIBE ->
                if (accessor.destination?.startsWith("/queue") == true)
                    throw MessageDeliveryException("Subscribe to /user/queue/... instead")
            else -> {}
        }
        return message
    }
}
