package com.werewolf.integration

import com.werewolf.model.Game
import com.werewolf.model.GameConfig
import com.werewolf.model.Room
import com.werewolf.model.RoomStatus
import com.werewolf.model.User
import com.werewolf.repository.GameRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.StaleRoomSweep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * Rooms nobody came back to stay WAITING forever otherwise: only the last
 * member tapping Leave closes a room, and a backend restart turns a mid-game
 * room back into WAITING. Prod had 63 WAITING rooms, all older than a day.
 */
@SpringBootTest
@ActiveProfiles("test")
class StaleRoomSweepIntegrationTest {

    @Autowired lateinit var sweep: StaleRoomSweep
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var games: GameRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private var seq = 0

    private fun room(createdHoursAgo: Long, status: RoomStatus = RoomStatus.WAITING): Int {
        val host = "guest:stale-${++seq}-${System.nanoTime()}"
        users.save(User(userId = host, nickname = "s"))
        val id = rooms.save(
            Room(roomCode = TestConstants.nextSeededRoomCode(), hostUserId = host, totalPlayers = 6, status = status, config = GameConfig()),
        ).roomId ?: error("room not persisted")
        jdbc.update("UPDATE rooms SET created_at = ? WHERE room_id = ?", hoursAgo(createdHoursAgo), id)
        return id
    }

    private fun game(roomId: Int, endedHoursAgo: Long?) {
        val g = games.save(Game(roomId = roomId, hostUserId = rooms.findById(roomId).orElseThrow().hostUserId))
        if (endedHoursAgo != null)
            jdbc.update("UPDATE games SET ended_at = ? WHERE game_id = ?", hoursAgo(endedHoursAgo), g.gameId)
    }

    private fun hoursAgo(h: Long) = Timestamp.valueOf(LocalDateTime.now().minusHours(h))
    private fun reload(id: Int) = rooms.findById(id).orElseThrow()

    @Test
    fun `a room waiting for more than 24 hours is closed`() {
        val id = room(createdHoursAgo = 25)

        sweep.closeStaleRooms()

        assertThat(reload(id).status).isEqualTo(RoomStatus.CLOSED)
        assertThat(reload(id).closedAt).isNotNull()
    }

    @Test
    fun `a room created today is left alone`() {
        val id = room(createdHoursAgo = 2)

        sweep.closeStaleRooms()

        assertThat(reload(id).status).isEqualTo(RoomStatus.WAITING)
    }

    @Test
    fun `an old room whose game ended recently is still waiting for a rematch`() {
        // e.g. a restart just cancelled its game and put it back to WAITING
        val id = room(createdHoursAgo = 30)
        game(id, endedHoursAgo = 1)

        sweep.closeStaleRooms()

        assertThat(reload(id).status).isEqualTo(RoomStatus.WAITING)
    }

    @Test
    fun `an old room whose last game ended more than 24 hours ago is closed`() {
        val id = room(createdHoursAgo = 50)
        game(id, endedHoursAgo = 40)

        sweep.closeStaleRooms()

        assertThat(reload(id).status).isEqualTo(RoomStatus.CLOSED)
    }

    @Test
    fun `a room with a game in progress is never closed`() {
        val id = room(createdHoursAgo = 30, status = RoomStatus.IN_GAME)
        game(id, endedHoursAgo = null)

        sweep.closeStaleRooms()

        assertThat(reload(id).status).isEqualTo(RoomStatus.IN_GAME)
    }
}
