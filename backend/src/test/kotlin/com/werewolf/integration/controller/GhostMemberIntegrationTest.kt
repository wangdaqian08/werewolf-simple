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
import com.werewolf.repository.RoomPlayerRepository
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
 * A "ghost" is a member who joined but never picked a seat (closed the app, or
 * was bounced to the lobby). Prod rooms 15/47/48/62/68 deadlocked on one: the
 * last real player got "Room is full" for a free seat, and the host could not
 * start. The ghost must stay visible and kickable, and be able to come back.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GhostMemberIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var roomPlayers: RoomPlayerRepository

    companion object {
        const val SEAT_URL = "/api/room/seat"
        const val READY_URL = "/api/room/ready"
        const val KICK_URL = "/api/room/kick"
        const val ACTIVE_URL = "/api/room/active"
        const val START_URL = "/api/game/start"
        const val SEATS = 6
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

    private fun get(url: String, token: String): ResponseEntity<Map<*, *>> =
        rest.exchange(url, HttpMethod.GET, HttpEntity<Any>(headers(token)), Map::class.java)

    private fun join(code: String, token: String) = post(JOIN_ROOM_URL, mapOf("roomCode" to code), token)
    private fun sit(room: Room, seat: Int, token: String) = post(SEAT_URL, mapOf("seatIndex" to seat, "roomId" to room.id), token)

    /** Host sits at seat 1; guests take seats 2..[seatedGuestsUpTo] and ready up. */
    private fun roomWithSeatedGuests(prefix: String, seatedGuestsUpTo: Int = SEATS - 1, hostSits: Boolean = true): Room {
        val host = login("${prefix}Host")
        val body = post(
            CREATE_ROOM_URL,
            mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to SEATS, FIELD_ROLES to listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER))),
            host,
        ).body!!
        val room = Room((body[FIELD_ROOM_ID] as String).toInt(), body[FIELD_ROOM_CODE] as String, host)
        if (hostSits) sit(room, 1, host)
        (2..seatedGuestsUpTo).forEach { seat ->
            val t = login("${prefix}Guest$seat")
            join(room.code, t)
            sit(room, seat, t)
            post(READY_URL, mapOf("ready" to true, "roomId" to room.id), t)
        }
        return room
    }

    @Suppress("UNCHECKED_CAST")
    private fun members(room: Room) = get("/api/room/${room.id}", room.hostToken).body!!["players"] as List<Map<String, Any?>>

    @Test
    fun `an unseated member is listed in the room the moment they join`() {
        val room = roomWithSeatedGuests("GhVis")
        join(room.code, login("GhVisGhost"))

        val ghost = members(room).single { it["nickname"] == "GhVisGhost" }
        assertThat(ghost["seatIndex"]).isNull()
    }

    @Test
    fun `host kicks the unseated ghost, then the last player can join and sit`() {
        val room = roomWithSeatedGuests("GhKick")
        join(room.code, login("GhKickGhost"))
        val last = login("GhKickLast")
        assertThat(join(room.code, last).body!!["error"]).isEqualTo("Room is full")

        val ghostId = members(room).single { it["nickname"] == "GhKickGhost" }["userId"]
        assertThat(post(KICK_URL, mapOf("roomId" to room.id, "targetUserId" to ghostId), room.hostToken).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(join(room.code, last).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(sit(room, SEATS, last).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `start is refused with a seat reason while a member is unseated`() {
        val room = roomWithSeatedGuests("GhStart")
        join(room.code, login("GhStartGhost"))

        val start = post(START_URL, mapOf("roomId" to room.id), room.hostToken)

        assertThat(start.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(start.body!!["error"]).isEqualTo("Not all players have a seat")
    }

    @Test
    fun `start with an unseated host is refused, not a server error`() {
        val room = roomWithSeatedGuests("GhHost", seatedGuestsUpTo = SEATS, hostSits = false) // full, host unseated

        val start = post(START_URL, mapOf("roomId" to room.id), room.hostToken)

        assertThat(start.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(start.body!!["error"]).isEqualTo("Not all players have a seat")
    }

    @Test
    fun `a member who closed the app before sitting comes back to the same place and sits`() {
        val room = roomWithSeatedGuests("GhBack")
        join(room.code, login("GhBackGhost"))

        // Reopens the app: logs in again with the same nickname → same guest id.
        val again = login("GhBackGhost")
        assertThat(get(ACTIVE_URL, again).body!!["roomCode"]).isEqualTo(room.code) // lobby "Rejoin" button
        assertThat(join(room.code, again).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(roomPlayers.findByRoomId(room.id)).hasSize(SEATS) // no duplicate row
        assertThat(sit(room, SEATS, again).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a kicked ghost who comes back to a now-full room is told it is full`() {
        val room = roomWithSeatedGuests("GhFull")
        val ghost = login("GhFullGhost")
        join(room.code, ghost)
        val ghostId = members(room).single { it["nickname"] == "GhFullGhost" }["userId"]
        post(KICK_URL, mapOf("roomId" to room.id, "targetUserId" to ghostId), room.hostToken)
        val last = login("GhFullLast")
        join(room.code, last)
        sit(room, SEATS, last)

        val back = join(room.code, ghost)

        assertThat(back.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(back.body!!["error"]).isEqualTo("Room is full")
    }
}
