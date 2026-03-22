package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.DEFAULT_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ERROR
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.PlayerRole
import com.werewolf.model.ReadyStatus
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
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoomReadySeatControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository

    companion object {
        const val READY_URL = "/api/room/ready"
        const val SEAT_URL = "/api/room/seat"
        val DEFAULT_ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)
    }

    private fun login(nickname: String): String {
        val response = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!![FIELD_TOKEN] as String
    }

    private fun authHeaders(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    @Suppress("UNCHECKED_CAST")
    private fun createAndJoin(hostNick: String, guestNick: String): Triple<String, String, Int> {
        val hostToken = login(hostNick)
        val createBody = mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to DEFAULT_TOTAL_PLAYERS, FIELD_ROLES to DEFAULT_ROLES))
        val room = restTemplate.postForEntity(
            CREATE_ROOM_URL, HttpEntity(createBody, authHeaders(hostToken)), Map::class.java
        ).body!! as Map<String, Any?>

        val roomId = (room[FIELD_ROOM_ID] as String).toInt()
        val roomCode = room[FIELD_ROOM_CODE] as String

        val guestToken = login(guestNick)
        restTemplate.postForEntity(
            JOIN_ROOM_URL,
            HttpEntity(mapOf("roomCode" to roomCode), authHeaders(guestToken)),
            Map::class.java,
        )

        return Triple(hostToken, guestToken, roomId)
    }

    // ── /seat ─────────────────────────────────────────────────────────────────

    @Test
    fun `POST seat saves seatIndex to DB`() {
        val (_, guestToken, roomId) = createAndJoin("HostS1", "GuestS1")

        val response = restTemplate.postForEntity(
            SEAT_URL,
            HttpEntity(mapOf("seatIndex" to 2, "roomId" to roomId), authHeaders(guestToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val players = roomPlayerRepository.findByRoomId(roomId)
        val guest = players.first { !it.host }
        assertThat(guest.seatIndex).isEqualTo(2)
    }

    // ── /ready ────────────────────────────────────────────────────────────────

    @Test
    fun `POST ready saves READY to DB after seat is claimed`() {
        val (_, guestToken, roomId) = createAndJoin("HostR1", "GuestR1")

        // Claim seat first
        restTemplate.postForEntity(
            SEAT_URL,
            HttpEntity(mapOf("seatIndex" to 1, "roomId" to roomId), authHeaders(guestToken)),
            Map::class.java,
        )

        val response = restTemplate.postForEntity(
            READY_URL,
            HttpEntity(mapOf("ready" to true, "roomId" to roomId), authHeaders(guestToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val guest = roomPlayerRepository.findByRoomId(roomId).first { !it.host }
        assertThat(guest.status).isEqualTo(ReadyStatus.READY)
    }

    @Test
    fun `POST ready cancel saves NOT_READY to DB`() {
        val (_, guestToken, roomId) = createAndJoin("HostR2", "GuestR2")

        // Claim seat and go ready first
        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 1, "roomId" to roomId), authHeaders(guestToken)), Map::class.java)
        restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), authHeaders(guestToken)), Map::class.java)

        // Cancel ready
        val response = restTemplate.postForEntity(
            READY_URL,
            HttpEntity(mapOf("ready" to false, "roomId" to roomId), authHeaders(guestToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val guest = roomPlayerRepository.findByRoomId(roomId).first { !it.host }
        assertThat(guest.status).isEqualTo(ReadyStatus.NOT_READY)
    }

    @Test
    fun `POST ready returns 400 when seat not claimed`() {
        val (_, guestToken, roomId) = createAndJoin("HostR3", "GuestR3")

        val response = restTemplate.postForEntity(
            READY_URL,
            HttpEntity(mapOf("ready" to true, "roomId" to roomId), authHeaders(guestToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        @Suppress("UNCHECKED_CAST")
        assertThat(response.body!![FIELD_ERROR] as String).isNotBlank()
    }

    @Test
    fun `POST ready without token is rejected`() {
        val response = restTemplate.postForEntity(
            READY_URL,
            mapOf("ready" to true, "roomId" to 1),
            Map::class.java,
        )

        assertThat(response.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }
}
