package com.werewolf.unit.service

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.GameContextLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.util.*

@ExtendWith(MockitoExtension::class)
class GameContextLoaderTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var sheriffElectionRepository: SheriffElectionRepository

    @InjectMocks lateinit var loader: GameContextLoader

    private val gameId = 1
    private val roomId = 10
    private val hostId = "host:001"

    private fun game(phase: GamePhase = GamePhase.DAY, dayNumber: Int = 1): Game {
        val g = Game(roomId = roomId, hostUserId = hostId)
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(g, gameId)
        g.phase = phase
        g.dayNumber = dayNumber
        return g
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 4)

    private fun players() = listOf(
        GamePlayer(gameId = gameId, userId = hostId, seatIndex = 0, role = PlayerRole.VILLAGER),
        GamePlayer(gameId = gameId, userId = "g1", seatIndex = 1, role = PlayerRole.WEREWOLF),
    )

    private fun stubBasics(phase: GamePhase = GamePhase.DAY) {
        val g = game(phase)
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(g))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players())
        whenever(roomRepository.findById(roomId)).thenReturn(Optional.of(room()))
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(emptyList())
    }

    @Test
    fun `load - returns GameContext with correct game, room, and players`() {
        stubBasics()

        val ctx = loader.load(gameId)

        assertThat(ctx.gameId).isEqualTo(gameId)
        assertThat(ctx.room.roomCode).isEqualTo("ABCD")
        assertThat(ctx.players).hasSize(2)
    }

    @Test
    fun `load - nightPhase is included when game phase is NIGHT`() {
        stubBasics(GamePhase.NIGHT)
        val nightPhase = NightPhase(gameId = gameId, dayNumber = 1)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(nightPhase))

        val ctx = loader.load(gameId)

        assertThat(ctx.nightPhase).isNotNull
        assertThat(ctx.nightPhase!!.gameId).isEqualTo(gameId)
    }

    @Test
    fun `load - nightPhase is null when game phase is not NIGHT`() {
        stubBasics(GamePhase.DAY)

        val ctx = loader.load(gameId)

        assertThat(ctx.nightPhase).isNull()
    }

    @Test
    fun `load - election is included when game phase is SHERIFF_ELECTION`() {
        stubBasics(GamePhase.SHERIFF_ELECTION)
        val election = SheriffElection(gameId = gameId)
        whenever(sheriffElectionRepository.findByGameId(gameId)).thenReturn(Optional.of(election))

        val ctx = loader.load(gameId)

        assertThat(ctx.election).isNotNull
        assertThat(ctx.election!!.gameId).isEqualTo(gameId)
    }

    @Test
    fun `load - election is null when game phase is not SHERIFF_ELECTION`() {
        stubBasics(GamePhase.DAY)

        val ctx = loader.load(gameId)

        assertThat(ctx.election).isNull()
    }

    @Test
    fun `load - allNightPhases is populated from repository`() {
        stubBasics(GamePhase.DAY)
        val phase1 = NightPhase(gameId = gameId, dayNumber = 1)
        val phase2 = NightPhase(gameId = gameId, dayNumber = 2)
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(phase1, phase2))

        val ctx = loader.load(gameId)

        assertThat(ctx.allNightPhases).hasSize(2)
    }

    @Test
    fun `load - throws IllegalArgumentException when game not found`() {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.empty())

        assertThatThrownBy { loader.load(gameId) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("$gameId")
    }
}
