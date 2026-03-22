package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.DEFAULT_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ERROR
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_STATUS
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.INVALID_ROOM_CODE
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.integration.TestConstants.ROOM_CODE_LENGTH
import com.werewolf.model.PlayerRole
import com.werewolf.model.RoomStatus
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
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
import org.springframework.test.context.ActiveProfiles
import java.util.*

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoomControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository
    @Autowired lateinit var userRepository: UserRepository

    companion object {
        val DEFAULT_ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /** Decodes the JWT payload locally to extract the `sub` (userId) claim. */
    private fun extractUserId(token: String): String {
        val raw = token.split(".")[1]
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        val payload = String(Base64.getUrlDecoder().decode(padded))
        return payload.substringAfter("\"sub\":\"").substringBefore("\"")
    }

    @Suppress("UNCHECKED_CAST")
    private fun createRoom(token: String, totalPlayers: Int = DEFAULT_TOTAL_PLAYERS): Map<String, Any?> {
        val body = mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to totalPlayers, FIELD_ROLES to DEFAULT_ROLES))
        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(body, authHeaders(token)),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!! as Map<String, Any?>
    }

    private fun roomIdFrom(room: Map<String, Any?>) = (room[FIELD_ROOM_ID] as String).toInt()

    // ── Create Room ───────────────────────────────────────────────────────────

    @Test
    fun `host creates room and receives room details`() {
        val token = login("Alice")

        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to DEFAULT_TOTAL_PLAYERS, FIELD_ROLES to DEFAULT_ROLES)),
                authHeaders(token),
            ),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body[FIELD_ROOM_ID]).isNotNull()
        assertThat(body[FIELD_ROOM_CODE] as String).hasSize(ROOM_CODE_LENGTH)
        assertThat(body[FIELD_STATUS]).isEqualTo(RoomStatus.WAITING.name)
    }

    @Test
    fun `created room is persisted in the database with correct total players and role flags`() {
        val token = login("Bob")
        val room = createRoom(token)

        val persisted = roomRepository.findById(roomIdFrom(room)).get()

        assertThat(persisted.totalPlayers).isEqualTo(DEFAULT_TOTAL_PLAYERS)
        assertThat(persisted.status).isEqualTo(RoomStatus.WAITING)
        // DEFAULT_ROLES = [SEER, WITCH, HUNTER] — verify each flag stored correctly
        assertThat(persisted.hasSeer).isTrue()
        assertThat(persisted.hasWitch).isTrue()
        assertThat(persisted.hasHunter).isTrue()
        assertThat(persisted.hasGuard).isFalse()
        assertThat(persisted.hasIdiot).isFalse()
    }

    @Test
    fun `host is added as a player when room is created`() {
        val token = login("Carol")
        val room = createRoom(token)

        val players = roomPlayerRepository.findByRoomId(roomIdFrom(room))

        assertThat(players).hasSize(1)
        assertThat(players.first().host).isTrue()
    }

    @Test
    fun `host is persisted in users table even when login was skipped`() {
        // Simulate hasValidSession=true on the frontend: the client reuses a JWT
        // without calling POST /api/user/login, so the users table has no row yet.
        val token = login("Grace")
        val userId = extractUserId(token)
        userRepository.deleteById(userId) // simulate skipped login — row gone

        val room = createRoom(token) // createRoom must upsert the user itself

        val hostUserId = roomPlayerRepository.findByRoomId(roomIdFrom(room))
            .first { it.host }.userId

        val user = userRepository.findById(hostUserId)
        assertThat(user).isPresent
        assertThat(user.get().nickname).isEqualTo("Grace")
    }

    @Test
    fun `guest is persisted in users table even when login was skipped`() {
        val hostToken = login("Host4")
        val room = createRoom(hostToken)
        val roomCode = room[FIELD_ROOM_CODE] as String

        val guestToken = login("Guest4")
        val guestId = extractUserId(guestToken)
        userRepository.deleteById(guestId) // simulate skipped login — row gone

        restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(mapOf(FIELD_ROOM_CODE to roomCode), authHeaders(guestToken)),
            Map::class.java,
        )

        val guestUserId = roomPlayerRepository.findByRoomId(roomIdFrom(room))
            .first { !it.host }.userId

        val user = userRepository.findById(guestUserId)
        assertThat(user).isPresent
        assertThat(user.get().nickname).isEqualTo("Guest4")
    }

    @Test
    fun `room config reflects requested roles`() {
        val token = login("Dave")
        val room = createRoom(token, totalPlayers = 8)

        @Suppress("UNCHECKED_CAST")
        val config = room[FIELD_CONFIG] as Map<String, Any?>
        assertThat(config[FIELD_TOTAL_PLAYERS]).isEqualTo(8)

        @Suppress("UNCHECKED_CAST")
        val roles = config[FIELD_ROLES] as List<String>
        assertThat(roles).contains(*DEFAULT_ROLES.map { it.name }.toTypedArray())
    }

    @Test
    fun `room config includes IDIOT when requested`() {
        val token = login("Henry")
        val rolesWithIdiot = DEFAULT_ROLES + PlayerRole.IDIOT
        val body = mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to DEFAULT_TOTAL_PLAYERS, FIELD_ROLES to rolesWithIdiot))
        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(body, authHeaders(token)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val roles = (response.body!![FIELD_CONFIG] as Map<String, Any?>)[FIELD_ROLES] as List<String>
        assertThat(roles).contains(PlayerRole.IDIOT.name)
    }

    @Test
    fun `room config excludes IDIOT when not requested`() {
        val token = login("Ivan")
        val room = createRoom(token)

        @Suppress("UNCHECKED_CAST")
        val roles = (room[FIELD_CONFIG] as Map<String, Any?>)[FIELD_ROLES] as List<String>
        assertThat(roles).doesNotContain(PlayerRole.IDIOT.name)
    }

    @Test
    fun `create room without token is rejected`() {
        val response = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to DEFAULT_TOTAL_PLAYERS, FIELD_ROLES to emptyList<String>())),
            Map::class.java,
        )

        assertThat(response.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    // ── Join Room ─────────────────────────────────────────────────────────────

    @Test
    fun `guest joins room with valid code`() {
        val hostToken = login("Host")
        val room = createRoom(hostToken)
        val roomCode = room[FIELD_ROOM_CODE] as String

        val guestToken = login("Guest")
        val response = restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(mapOf(FIELD_ROOM_CODE to roomCode), authHeaders(guestToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val players = response.body!![FIELD_PLAYERS] as List<*>
        assertThat(players).hasSize(2)
    }

    @Test
    fun `guest is persisted in room_players after joining`() {
        val hostToken = login("Host2")
        val room = createRoom(hostToken)
        val roomCode = room[FIELD_ROOM_CODE] as String
        val roomId = roomIdFrom(room)

        val guestToken = login("Guest2")
        restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(mapOf(FIELD_ROOM_CODE to roomCode), authHeaders(guestToken)),
            Map::class.java,
        )

        val players = roomPlayerRepository.findByRoomId(roomId)
        assertThat(players).hasSize(2)
        assertThat(players.count { it.host }).isEqualTo(1)
        assertThat(players.count { !it.host }).isEqualTo(1)
    }

    @Test
    fun `join with invalid room code returns 400`() {
        val token = login("Eve")

        val response = restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(mapOf(FIELD_ROOM_CODE to INVALID_ROOM_CODE), authHeaders(token)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!![FIELD_ERROR] as String).contains("not found")
    }

    @Test
    fun `join with empty room code returns 400`() {
        val token = login("Frank")

        val response = restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(mapOf(FIELD_ROOM_CODE to ""), authHeaders(token)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `joining the same room twice is idempotent`() {
        val hostToken = login("Host3")
        val room = createRoom(hostToken)
        val roomCode = room[FIELD_ROOM_CODE] as String
        val roomId = roomIdFrom(room)

        val guestToken = login("Guest3")
        val headers = authHeaders(guestToken)

        restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf(FIELD_ROOM_CODE to roomCode), headers), Map::class.java)
        restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf(FIELD_ROOM_CODE to roomCode), headers), Map::class.java)

        assertThat(roomPlayerRepository.findByRoomId(roomId)).hasSize(2)
    }

    @Test
    fun `join room without token is rejected`() {
        val response = restTemplate.postForEntity(
            JOIN_ROOM_URL,
            mapOf(FIELD_ROOM_CODE to INVALID_ROOM_CODE),
            Map::class.java,
        )

        assertThat(response.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }
}
