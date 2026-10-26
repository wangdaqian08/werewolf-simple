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
 * The host changes the player count and roles of a waiting room, so a room
 * that filled up (or emptied out) keeps its code instead of being recreated.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoomBoardIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate

    companion object {
        const val BOARD_URL = "/api/room/board"
        const val SEAT_URL = "/api/room/seat"
        const val READY_URL = "/api/room/ready"
        const val START_URL = "/api/game/start"
        val ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)
    }

    private data class Room(val id: Int, val code: String, val hostToken: String)

    private fun login(nick: String) =
        rest.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nick), Map::class.java).body!![FIELD_TOKEN] as String

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun post(url: String, body: Any, token: String): ResponseEntity<Map<*, *>> =
        rest.postForEntity(url, HttpEntity(body, headers(token)), Map::class.java)

    private fun join(code: String, token: String) = post(JOIN_ROOM_URL, mapOf("roomCode" to code), token)
    private fun sit(room: Room, seat: Int, token: String) = post(SEAT_URL, mapOf("seatIndex" to seat, "roomId" to room.id), token)
    private fun ready(room: Room, token: String) = post(READY_URL, mapOf("ready" to true, "roomId" to room.id), token)

    private fun board(room: Room, total: Int, wolves: Int = 2, roles: List<PlayerRole> = ROLES, token: String = room.hostToken) =
        post(BOARD_URL, mapOf("roomId" to room.id, "totalPlayers" to total, "wolfCount" to wolves, "roles" to roles), token)

    /** [seats]-seat room; host at seat 1, guests at 2..[filledUpTo], each ready. Returns guest tokens. */
    private fun room(prefix: String, seats: Int = 6, filledUpTo: Int = seats): Pair<Room, List<String>> {
        val host = login("${prefix}Host")
        val body = post(
            CREATE_ROOM_URL,
            mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to seats, "wolfCount" to 2, FIELD_ROLES to ROLES)),
            host,
        ).body!!
        val room = Room((body[FIELD_ROOM_ID] as String).toInt(), body[FIELD_ROOM_CODE] as String, host)
        sit(room, 1, host)
        val guests = (2..filledUpTo).map { seat ->
            login("${prefix}Guest$seat").also { t -> join(room.code, t); sit(room, seat, t); ready(room, t) }
        }
        return room to guests
    }

    @Suppress("UNCHECKED_CAST")
    private fun roomState(room: Room) =
        rest.exchange("/api/room/${room.id}", HttpMethod.GET, HttpEntity<Any>(headers(room.hostToken)), Map::class.java).body!!

    @Suppress("UNCHECKED_CAST")
    private fun players(room: Room) = roomState(room)["players"] as List<Map<String, Any?>>

    @Test
    fun `host grows a full room and the player who was turned away gets in`() {
        val (room, _) = room("BdGrow")
        val late = login("BdGrowLate")
        assertThat(join(room.code, late).body!!["error"]).isEqualTo("Room is full")

        val res = board(room, 7)

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((res.body!!["config"] as Map<*, *>)["totalPlayers"]).isEqualTo(7)
        assertThat(join(room.code, late).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(sit(room, 7, late).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `the new roles are what the room shows`() {
        val (room, _) = room("BdRoles", filledUpTo = 1)

        board(room, 9, wolves = 3, roles = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.GUARD))

        val config = roomState(room)["config"] as Map<*, *>
        assertThat(config["totalPlayers"]).isEqualTo(9)
        assertThat(config["wolfCount"]).isEqualTo(3)
        assertThat(config["roles"] as List<*>).contains("GUARD").doesNotContain("HUNTER")
    }

    @Test
    fun `a board change asks every guest to ready up again`() {
        val (room, _) = room("BdReady")

        board(room, 7)

        assertThat(players(room).filter { it["isHost"] == false }.map { it["status"] }).containsOnly("NOT_READY")
    }

    @Test
    fun `only the host can change the board`() {
        val (room, guests) = room("BdHost")

        assertThat(board(room, 7, token = guests.first()).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `cannot shrink below the members who have joined, seated or not`() {
        val (room, _) = room("BdMin", seats = 7, filledUpTo = 5)
        join(room.code, login("BdMinUnseated"))

        val res = board(room, 5)

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(res.body!!["error"]).isEqualTo("6 players have joined")
    }

    @Test
    fun `cannot remove a seat someone is sitting in`() {
        val (room, _) = room("BdSeat", seats = 8, filledUpTo = 5)
        val back = login("BdSeatBack")
        join(room.code, back)
        sit(room, 8, back)

        val res = board(room, 7)

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(res.body!!["error"]).isEqualTo("Seat 8 is taken")
    }

    @Test
    fun `an invalid role mix is refused`() {
        val (room, _) = room("BdMix", filledUpTo = 1)

        val res = board(room, 6, wolves = 0)

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `the board is locked once the game has started`() {
        val (room, _) = room("BdLocked")
        assertThat(post(START_URL, mapOf("roomId" to room.id), room.hostToken).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(board(room, 7).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `start is refused until every seat is filled`() {
        val (room, _) = room("BdStart", seats = 7, filledUpTo = 6)

        val res = post(START_URL, mapOf("roomId" to room.id), room.hostToken)

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(res.body!!["error"]).isEqualTo("Need 7 players, have 6")
    }

    @Test
    fun `a seat beyond the room size cannot be claimed`() {
        val (room, _) = room("BdRange", filledUpTo = 1)
        val t = login("BdRangeGuest")
        join(room.code, t)

        val res = sit(room, 7, t)

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(res.body!!["error"]).isEqualTo("Seat 7 does not exist")
    }
}
