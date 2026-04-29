package com.werewolf.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.springframework.web.socket.sockjs.client.SockJsClient
import org.springframework.web.socket.sockjs.client.WebSocketTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Without server heartbeats the broker advertises [0,0] and idle sockets across
 * mobile NATs / cellular proxies silently die — this test pins the configured
 * value so the regression that caused production "stuck at stage" reports
 * cannot return unnoticed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StompHeartbeatTest {

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `CONNECTED frame advertises 10s_10s heartbeat`() {
        val transports = listOf(WebSocketTransport(StandardWebSocketClient()))
        val stompClient = WebSocketStompClient(SockJsClient(transports))
        val negotiated = CompletableFuture<String?>()

        val handler = object : StompSessionHandlerAdapter() {
            override fun afterConnected(session: StompSession, connectedHeaders: StompHeaders) {
                negotiated.complete(connectedHeaders.getFirst("heart-beat"))
            }
        }

        val session: StompSession = stompClient
            .connectAsync("ws://localhost:$port/ws", handler)
            .get(5, TimeUnit.SECONDS)

        try {
            assertThat(negotiated.get(5, TimeUnit.SECONDS))
                .`as`("Server must advertise 10s/10s heartbeats so idle mobile sockets stay alive")
                .isEqualTo("10000,10000")
        } finally {
            session.disconnect()
        }
    }
}
