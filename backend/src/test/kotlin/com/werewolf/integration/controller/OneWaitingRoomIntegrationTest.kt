package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.PlayerRole
import com.werewolf.model.RoomStatus
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles

/**
 * A player waits in at most one room. Creating or joining another room takes
 * them out of the old one, so the lobby's Rejoin button always points at the
 * room they are actually in, and abandoned rooms close once everyone moved on.
 * (Local DB had one guest in 21 waiting rooms; prod 68 users in 2+.)
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OneWaitingRoomIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var roomPlayers: RoomPlayerRepository

    companion object {
        const val SEAT_URL = "/api/room/seat"
        const val READY_URL = "/api/room/ready"
        const val START_URL = "/api/game/start"
        const val ACTIVE_URL = "/api/room/active"
        val ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)
    }

    private data class Room(val id: Int, val code: String)

    private fun login(nick: String) =
        rest.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nick), Map::class.java).body!![FIELD_TOKEN] as String

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun post(url: String, body: Any, token: String): ResponseEntity<Map<*, *>> =
        rest.postForEntity(url, HttpEntity(body, headers(token)), Map::class.java)

    private fun create(token: String, seats: Int = 6, wolves: Int = 2, roles: List<PlayerRole> = ROLES): Room {
        val body = post(CREATE_ROOM_URL, mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to seats, "wolfCount" to wolves, FIELD_ROLES to roles)), token).body!!
        return Room((body[FIELD_ROOM_ID] as String).toInt(), body[FIELD_ROOM_CODE] as String)
    }

    private fun join(room: Room, token: String) = post(JOIN_ROOM_URL, mapOf("roomCode" to room.code), token)

    private fun activeCode(token: String) =
        rest.exchange(ACTIVE_URL, HttpMethod.GET, HttpEntity<Any>(headers(token)), Map::class.java).body?.get("roomCode")

    private fun members(room: Room) = roomPlayers.findByRoomId(room.id).map { it.userId }
    private fun status(room: Room) = rooms.findById(room.id).orElseThrow().status

    @Test
    fun `creating a new room takes the host out of their old room, which a guest now hosts`() {
        val host = login("OwrHostA")
        val old = create(host)
        val guest = login("OwrGuestA")
        join(old, guest)

        val new = create(host)

        assertThat(members(old)).containsExactly("guest:owrguesta")
        assertThat(rooms.findById(old.id).orElseThrow().hostUserId).isEqualTo("guest:owrguesta")
        assertThat(activeCode(host)).isEqualTo(new.code)
        assertThat(activeCode(guest)).isEqualTo(old.code)
    }

    @Test
    fun `an old room left empty is closed and frees its code`() {
        val host = login("OwrHostB")
        val old = create(host)

        create(host)

        assertThat(status(old)).isEqualTo(RoomStatus.CLOSED)
    }

    @Test
    fun `joining another room leaves the room you were waiting in`() {
        val first = create(login("OwrHostC1"))
        val second = create(login("OwrHostC2"))
        val player = login("OwrPlayerC")
        join(first, player)

        assertThat(join(second, player).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(members(first)).doesNotContain("guest:owrplayerc")
        assertThat(activeCode(player)).isEqualTo(second.code)
    }

    @Test
    fun `a refused join keeps you in the room you are in`() {
        val mine = create(login("OwrHostD1"))
        val full = create(login("OwrHostD2"), seats = 1, wolves = 1, roles = emptyList()) // full with just the host
        val player = login("OwrPlayerD")
        join(mine, player)

        assertThat(join(full, player).body!!["error"]).isEqualTo("Room is full")

        assertThat(members(mine)).contains("guest:owrplayerd")
    }

    @Test
    fun `re-joining your own room keeps you in it`() {
        val room = create(login("OwrHostE"))
        val player = login("OwrPlayerE")
        join(room, player)

        join(room, player)

        assertThat(members(room)).contains("guest:owrplayere")
    }

    @Test
    fun `a room with a game in progress is never touched`() {
        val host = login("OwrHostF")
        val playing = create(host)
        post(SEAT_URL, mapOf("seatIndex" to 1, "roomId" to playing.id), host)
        (2..6).forEach { seat ->
            val t = login("OwrGuestF$seat")
            join(playing, t)
            post(SEAT_URL, mapOf("seatIndex" to seat, "roomId" to playing.id), t)
            post(READY_URL, mapOf("ready" to true, "roomId" to playing.id), t)
        }
        assertThat(post(START_URL, mapOf("roomId" to playing.id), host).statusCode).isEqualTo(HttpStatus.OK)

        create(host)

        assertThat(members(playing)).contains("guest:owrhostf")
        assertThat(status(playing)).isEqualTo(RoomStatus.IN_GAME)
    }
}
