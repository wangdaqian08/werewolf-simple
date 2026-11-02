package com.werewolf.unit.config

import com.werewolf.config.StompAuthInterceptor
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.util.JwtUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class StompAuthInterceptorTest {
    private val games = mock<GamePlayerRepository>()
    private val rooms = mock<RoomPlayerRepository>()
    private val interceptor = StompAuthInterceptor(mock<JwtUtil>(), games, rooms)

    @Test
    fun `a game topic needs the caller to be one of its players`() {
        whenever(games.existsByGameIdAndUserId(7, "guest:alice")).thenReturn(true)

        assertThat(interceptor.maySubscribe("guest:alice", "/topic/game/7")).isTrue()
        assertThat(interceptor.maySubscribe("guest:mallory", "/topic/game/7")).isFalse()
    }

    @Test
    fun `a room topic needs the caller to be in the room`() {
        whenever(rooms.existsByRoomIdAndUserId(3, "guest:alice")).thenReturn(true)

        assertThat(interceptor.maySubscribe("guest:alice", "/topic/room/3")).isTrue()
        assertThat(interceptor.maySubscribe("guest:mallory", "/topic/room/3")).isFalse()
    }

    @Test
    fun `own private queue only, and nothing else`() {
        assertThat(interceptor.maySubscribe("guest:alice", "/user/queue/private")).isTrue()
        assertThat(interceptor.maySubscribe("guest:alice", "/queue/private-userabc")).isFalse()
        assertThat(interceptor.maySubscribe("guest:alice", "/topic/game/7/extra")).isFalse()
        assertThat(interceptor.maySubscribe("guest:alice", "/topic/game/99999999999")).isFalse()
        assertThat(interceptor.maySubscribe("guest:alice", "/topic/other")).isFalse()
    }
}
