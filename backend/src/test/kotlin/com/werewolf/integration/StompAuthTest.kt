package com.werewolf.integration

import com.werewolf.service.StompPublisher
import com.werewolf.util.JwtUtil
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.converter.AbstractMessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.springframework.web.socket.sockjs.client.SockJsClient
import org.springframework.web.socket.sockjs.client.WebSocketTransport
import org.springframework.util.MimeTypeUtils
import java.lang.reflect.Type
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * STOMP auth: CONNECT needs a JWT, clients cannot publish to broker topics
 * (forged GameOver etc.), and /user/queue/private reaches only its owner.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StompAuthTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired private lateinit var jwtUtil: JwtUtil
    @Autowired private lateinit var stompPublisher: StompPublisher

    private fun connect(userId: String?): StompSession {
        val client = WebSocketStompClient(SockJsClient(listOf(WebSocketTransport(StandardWebSocketClient()))))
        client.messageConverter = TextConverter
        val headers = StompHeaders()
        if (userId != null) headers.add("Authorization", "Bearer ${jwtUtil.generateToken(userId, userId, null)}")
        return client.connectAsync("ws://localhost:$port/ws", null, headers, object : StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS)
    }

    private fun StompSession.listen(destination: String): LinkedBlockingQueue<String> {
        val queue = LinkedBlockingQueue<String>()
        subscribe(destination, object : StompFrameHandler {
            override fun getPayloadType(headers: StompHeaders): Type = String::class.java
            override fun handleFrame(headers: StompHeaders, payload: Any?) {
                queue.add(payload as String)
            }
        })
        return queue
    }

    @Test
    fun `CONNECT without a token is refused`() {
        assertThatThrownBy { connect(null) }.isNotNull()
    }

    @Test
    fun `a client SEND to a game topic is dropped, the server's broadcast still arrives`() {
        val listener = connect("guest:listener")
        val forger = connect("guest:forger")
        val received = listener.listen("/topic/game/424242")
        Thread.sleep(300) // let the SUBSCRIBE reach the broker

        // text/plain: the SockJS client sends text frames only.
        forger.send(
            StompHeaders().apply { destination = "/topic/game/424242"; contentType = MimeTypeUtils.TEXT_PLAIN },
            """{"type":"GameOver"}""",
        )
        assertThat(received.poll(1, TimeUnit.SECONDS)).`as`("forged frame must not be relayed").isNull()

        stompPublisher.broadcastGame(424242, mapOf("type" to "PhaseChanged"))
        assertThat(received.poll(5, TimeUnit.SECONDS)).contains("PhaseChanged")
        listener.disconnect()
    }

    @Test
    fun `a private message reaches only its owner`() {
        val alice = connect("guest:alice-pm")
        val bob = connect("guest:bob-pm")
        val aliceQ = alice.listen("/user/queue/private")
        val bobQ = bob.listen("/user/queue/private")
        Thread.sleep(300)

        stompPublisher.sendPrivate("guest:alice-pm", mapOf("type" to "RoleAssigned", "role" to "SEER"))

        assertThat(aliceQ.poll(5, TimeUnit.SECONDS)).contains("RoleAssigned")
        assertThat(bobQ.poll(1, TimeUnit.SECONDS)).isNull()
        alice.disconnect()
        bob.disconnect()
    }
}

/** Frames as plain strings, whatever their content-type (the server sends application/json). */
private object TextConverter : AbstractMessageConverter(MimeTypeUtils.ALL) {
    override fun supports(clazz: Class<*>) = clazz == String::class.java
    override fun supportsMimeType(headers: MessageHeaders?) = true
    override fun convertFromInternal(message: Message<*>, targetClass: Class<*>, conversionHint: Any?): Any =
        String(message.payload as ByteArray)
    override fun convertToInternal(payload: Any, headers: MessageHeaders?, conversionHint: Any?): Any =
        (payload as String).toByteArray()
}
