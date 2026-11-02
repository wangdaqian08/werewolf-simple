package com.werewolf.integration

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.service.StompPublisher
import com.werewolf.util.JwtUtil
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
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
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
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
 * (forged GameOver etc.), a room/game topic needs membership, and
 * /user/queue/private reaches only its owner.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StompAuthTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired private lateinit var jwtUtil: JwtUtil
    @Autowired private lateinit var stompPublisher: StompPublisher
    @Autowired private lateinit var restTemplate: TestRestTemplate

    private fun login(nickname: String): String =
        restTemplate.postForEntity(LOGIN_URL, mapOf("nickname" to nickname), Map::class.java).body!![FIELD_TOKEN] as String

    /** A room [token]'s user hosts (so is a member of). */
    private fun createRoom(token: String): Int {
        val body = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to 6, "wolfCount" to 2, "roles" to listOf("SEER", "WITCH"))),
                HttpHeaders().also { it.setBearerAuth(token) },
            ),
            Map::class.java,
        ).body!!
        return (body[FIELD_ROOM_ID] as String).toInt()
    }

    private fun connect(userId: String?): StompSession =
        connectWith(userId?.let { jwtUtil.generateToken(it, it, null) })

    private fun connectWith(token: String?): StompSession {
        val client = WebSocketStompClient(SockJsClient(listOf(WebSocketTransport(StandardWebSocketClient()))))
        client.messageConverter = TextConverter
        val headers = StompHeaders()
        if (token != null) headers.add("Authorization", "Bearer $token")
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
    fun `a client SEND to a room topic is dropped, the server's broadcast still arrives`() {
        val hostToken = login("StompHost")
        val roomId = createRoom(hostToken)
        val listener = connectWith(hostToken)
        val forger = connect("guest:forger")
        val received = listener.listen("/topic/room/$roomId")
        Thread.sleep(300) // let the SUBSCRIBE reach the broker

        // text/plain: the SockJS client sends text frames only.
        forger.send(
            StompHeaders().apply { destination = "/topic/room/$roomId"; contentType = MimeTypeUtils.TEXT_PLAIN },
            """{"type":"PLAYER_KICKED"}""",
        )
        assertThat(received.poll(1, TimeUnit.SECONDS)).`as`("forged frame must not be relayed").isNull()

        stompPublisher.broadcastRoom(roomId, mapOf("type" to "ROOM_UPDATED"))
        assertThat(received.poll(5, TimeUnit.SECONDS)).contains("ROOM_UPDATED")
        listener.disconnect()
    }

    @Test
    fun `only a member can listen to a room topic`() {
        val roomId = createRoom(login("StompOwner"))
        val outsider = connect("guest:outsider").listen("/topic/room/$roomId")
        Thread.sleep(300)

        stompPublisher.broadcastRoom(roomId, mapOf("type" to "ROOM_UPDATED"))

        assertThat(outsider.poll(1, TimeUnit.SECONDS)).`as`("a non-member must not receive room events").isNull()
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
