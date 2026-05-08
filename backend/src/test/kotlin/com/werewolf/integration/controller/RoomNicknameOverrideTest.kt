package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.DEFAULT_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.PlayerRole
import com.werewolf.repository.RoomPlayerRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * Per-room nickname override (Option A): authenticated users can pass a
 * nickname on createRoom / joinRoom that displays as their name in this
 * specific room without mutating their User row. The User row keeps the
 * OAuth-provided nickname (still auto-refreshed on every login).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
class RoomNicknameOverrideTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository

    private val DEFAULT_ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)

    private fun login(nickname: String): String {
        val response = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to nickname),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!![FIELD_TOKEN] as String
    }

    private fun authHeaders(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun createRoomBody(nickname: String? = null): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>(
            FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to DEFAULT_TOTAL_PLAYERS, FIELD_ROLES to DEFAULT_ROLES),
        )
        if (nickname != null) body[FIELD_NICKNAME] = nickname
        return body
    }

    private fun joinRoomBody(roomCode: String, nickname: String? = null): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>(FIELD_ROOM_CODE to roomCode)
        if (nickname != null) body[FIELD_NICKNAME] = nickname
        return body
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstPlayer(room: Map<String, Any?>): Map<String, Any?> =
        (room[FIELD_PLAYERS] as List<Map<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    private fun playerByUserId(room: Map<String, Any?>, userId: String): Map<String, Any?> =
        (room[FIELD_PLAYERS] as List<Map<String, Any?>>).first { it[FIELD_USER_ID] == userId }

    private fun roomIdOf(room: Map<String, Any?>): Int = (room[FIELD_ROOM_ID] as String).toInt()

    // ── Create Room ───────────────────────────────────────────────────────────

    @Test
    fun `host creating room with nickname override stores displayName on RoomPlayer`() {
        val token = login("Daniel Wang")

        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(nickname = "DW"), authHeaders(token)),
            Map::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        val body = response.body!! as Map<String, Any?>
        val roomId = roomIdOf(body)
        val players = roomPlayerRepository.findByRoomId(roomId)

        assertThat(players).hasSize(1)
        assertThat(players.first().displayName).isEqualTo("DW")
    }

    @Test
    fun `host creating room with nickname override sees the override in roomDto players list`() {
        val token = login("Daniel Wang")

        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(nickname = "DW"), authHeaders(token)),
            Map::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        val body = response.body!! as Map<String, Any?>
        val player = firstPlayer(body)
        assertThat(player[FIELD_NICKNAME]).isEqualTo("DW")
    }

    @Test
    fun `host creating room without nickname leaves displayName null and uses user nickname in dto`() {
        val token = login("Alice")

        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(nickname = null), authHeaders(token)),
            Map::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        val body = response.body!! as Map<String, Any?>
        val roomId = roomIdOf(body)
        val players = roomPlayerRepository.findByRoomId(roomId)

        assertThat(players.first().displayName).isNull()
        assertThat(firstPlayer(body)[FIELD_NICKNAME]).isEqualTo("Alice")
    }

    @Test
    fun `host creating room with whitespace-only nickname is treated as no override`() {
        val token = login("Bob")

        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(nickname = "   "), authHeaders(token)),
            Map::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        val body = response.body!! as Map<String, Any?>
        val roomId = roomIdOf(body)
        val players = roomPlayerRepository.findByRoomId(roomId)

        assertThat(players.first().displayName).isNull()
        assertThat(firstPlayer(body)[FIELD_NICKNAME]).isEqualTo("Bob")
    }

    @Test
    fun `host creating room with nickname override trims surrounding whitespace`() {
        val token = login("Carol")

        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(nickname = "  Carol's Override  "), authHeaders(token)),
            Map::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        val body = response.body!! as Map<String, Any?>
        val roomId = roomIdOf(body)
        val players = roomPlayerRepository.findByRoomId(roomId)

        assertThat(players.first().displayName).isEqualTo("Carol's Override")
    }

    @Test
    fun `host creating room with nickname over 50 chars returns 400`() {
        val token = login("Dave")

        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(nickname = "A".repeat(51)), authHeaders(token)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    // ── Join Room ─────────────────────────────────────────────────────────────

    @Test
    fun `guest joining with nickname override stores displayName on guest's RoomPlayer only`() {
        val hostToken = login("HostUser")
        val createResp = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(nickname = null), authHeaders(hostToken)),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val createBody = createResp.body!! as Map<String, Any?>
        val roomCode = createBody[FIELD_ROOM_CODE] as String
        val roomId = roomIdOf(createBody)

        val guestToken = login("Eve Smith")
        val joinResp = restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(joinRoomBody(roomCode, nickname = "Eve"), authHeaders(guestToken)),
            Map::class.java,
        )

        assertThat(joinResp.statusCode).isEqualTo(HttpStatus.OK)
        val players = roomPlayerRepository.findByRoomId(roomId)
        val host = players.first { it.host }
        val guest = players.first { !it.host }

        // Host's row was created without an override — still null.
        assertThat(host.displayName).isNull()
        // Guest passed an override — captured.
        assertThat(guest.displayName).isEqualTo("Eve")
    }

    @Test
    fun `guest joining with nickname override sees the override in roomDto`() {
        val hostToken = login("Host10")
        val createResp = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(nickname = null), authHeaders(hostToken)),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val createBody = createResp.body!! as Map<String, Any?>
        val roomCode = createBody[FIELD_ROOM_CODE] as String

        val guestToken = login("Frank Sinatra")
        val joinResp = restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(joinRoomBody(roomCode, nickname = "FS"), authHeaders(guestToken)),
            Map::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        val body = joinResp.body!! as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val players = body[FIELD_PLAYERS] as List<Map<String, Any?>>
        val host = players.first { (it[FIELD_USER_ID] as String).contains("host10") }
        val guest = players.first { (it[FIELD_USER_ID] as String).contains("frank") }

        assertThat(host[FIELD_NICKNAME]).isEqualTo("Host10")
        assertThat(guest[FIELD_NICKNAME]).isEqualTo("FS")
    }

    @Test
    fun `joining without nickname uses user nickname in dto`() {
        val hostToken = login("Host11")
        val createResp = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(), authHeaders(hostToken)),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val createBody = createResp.body!! as Map<String, Any?>
        val roomCode = createBody[FIELD_ROOM_CODE] as String

        val guestToken = login("Grace Kelly")
        val joinResp = restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(joinRoomBody(roomCode), authHeaders(guestToken)),
            Map::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        val body = joinResp.body!! as Map<String, Any?>
        val guestUserId = "guest:grace-kelly"
        assertThat(playerByUserId(body, guestUserId)[FIELD_NICKNAME]).isEqualTo("Grace Kelly")
    }

    @Test
    fun `re-joining the same room is idempotent and does NOT update displayName`() {
        val hostToken = login("Host12")
        val createResp = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(), authHeaders(hostToken)),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val createBody = createResp.body!! as Map<String, Any?>
        val roomCode = createBody[FIELD_ROOM_CODE] as String
        val roomId = roomIdOf(createBody)

        val guestToken = login("Iris")
        // First join with override.
        restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(joinRoomBody(roomCode, nickname = "I"), authHeaders(guestToken)),
            Map::class.java,
        )
        // Second join with a different override — should be ignored.
        restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(joinRoomBody(roomCode, nickname = "Different"), authHeaders(guestToken)),
            Map::class.java,
        )

        val players = roomPlayerRepository.findByRoomId(roomId)
        val guest = players.first { !it.host }
        assertThat(guest.displayName).isEqualTo("I")  // first override sticks
    }

    @Test
    fun `joining with nickname over 50 chars returns 400`() {
        val hostToken = login("Host13")
        val createResp = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(createRoomBody(), authHeaders(hostToken)),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val createBody = createResp.body!! as Map<String, Any?>
        val roomCode = createBody[FIELD_ROOM_CODE] as String

        val guestToken = login("Joe")
        val joinResp = restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(joinRoomBody(roomCode, nickname = "B".repeat(51)), authHeaders(guestToken)),
            Map::class.java,
        )

        assertThat(joinResp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
