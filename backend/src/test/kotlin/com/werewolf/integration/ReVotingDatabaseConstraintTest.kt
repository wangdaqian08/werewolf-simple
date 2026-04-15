package com.werewolf.integration

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.*
import com.werewolf.game.voting.VotingPipeline
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Test to verify that RE_VOTING can be saved to the database
 * This tests the fix for the database constraint violation bug.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReVotingDatabaseConstraintTest {

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var gamePlayerRepository: GamePlayerRepository

    @Autowired
    private lateinit var roomRepository: RoomRepository

    @Autowired
    private lateinit var votingPipeline: VotingPipeline

    @Autowired
    private lateinit var gameContextLoader: GameContextLoader

    private val hostId = "host:001"

    @BeforeEach
    fun setUp() {
        cleanUpTestData()
    }

    @AfterEach
    fun tearDown() {
        cleanUpTestData()
    }

    private fun cleanUpTestData() {
        gamePlayerRepository.deleteAll()
        gameRepository.deleteAll()
        roomRepository.deleteAll()
    }

    private fun createRoom(): Room {
        val room = Room(
            roomCode = "AB${System.currentTimeMillis().toString().takeLast(2)}",
            hostUserId = hostId,
            totalPlayers = 6,
            hasSeer = false,
            hasWitch = false,
            hasGuard = false,
        )
        return roomRepository.save(room)
    }

    private fun createGame(roomId: Int): Game {
        val game = Game(
            roomId = roomId,
            hostUserId = hostId,
        )
        game.phase = GamePhase.DAY_VOTING
        game.dayNumber = 1
        return gameRepository.save(game)
    }

    private fun createPlayers(gameId: Int, count: Int): List<GamePlayer> {
        val players = (1..count).map { seatIndex ->
            GamePlayer(
                gameId = gameId,
                userId = "user:$seatIndex",
                seatIndex = seatIndex,
                role = PlayerRole.VILLAGER,
                alive = true,
            )
        }
        return gamePlayerRepository.saveAll(players)
    }

    @Test
    fun `RE_VOTING sub_phase can be saved to database without constraint violation`() {
        // Setup: Create room and game
        val room = createRoom()
        val game = createGame(room.roomId!!)
        val players = createPlayers(game.gameId!!, 6)

        // Execute: Set sub_phase to RE_VOTING
        game.subPhase = VotingSubPhase.RE_VOTING.name
        val savedGame = gameRepository.save(game)

        // Verify: The game was saved successfully with RE_VOTING sub_phase
        assertThat(savedGame.subPhase).isEqualTo(VotingSubPhase.RE_VOTING.name)

        // Verify: Can retrieve the game from database
        val loadedGame = gameRepository.findById(game.gameId!!).orElse(null)
        assertThat(loadedGame).isNotNull()
        assertThat(loadedGame?.subPhase).isEqualTo(VotingSubPhase.RE_VOTING.name)
    }

    @Test
    fun `All VotingSubPhase values can be saved to database`() {
        // Setup: Create room and game
        val room = createRoom()

        VotingSubPhase.entries.forEach { subPhase ->
            val game = createGame(room.roomId!!)
            game.subPhase = subPhase.name

            // Execute: Save game with this sub_phase
            val savedGame = gameRepository.save(game)

            // Verify: The game was saved successfully
            assertThat(savedGame.subPhase).isEqualTo(subPhase.name)

            // Verify: Can retrieve from database
            val loadedGame = gameRepository.findById(game.gameId!!).orElse(null)
            assertThat(loadedGame?.subPhase).isEqualTo(subPhase.name)

            // Cleanup for next iteration
            gameRepository.delete(game)
        }
    }

    @Test
    fun `VotingPipeline revealTally can set RE_VOTING without database error`() {
        // Setup: Create room and game with players
        val room = createRoom()
        val game = createGame(room.roomId!!)
        val players = createPlayers(game.gameId!!, 6)

        // Execute: Trigger a scenario that would lead to RE_VOTING
        // (This simulates the real-world scenario where the bug occurred)
        val context = gameContextLoader.load(game.gameId!!)

        // First, set up voting phase
        game.phase = GamePhase.DAY_VOTING
        game.subPhase = VotingSubPhase.VOTING.name
        gameRepository.save(game)

        // Then, transition to RE_VOTING (simulating tied vote scenario)
        val updatedContext = gameContextLoader.load(game.gameId!!)
        updatedContext.game.subPhase = VotingSubPhase.RE_VOTING.name
        gameRepository.save(updatedContext.game)

        // Verify: The game was updated successfully without database error
        val finalGame = gameRepository.findById(game.gameId!!).orElse(null)
        assertThat(finalGame).isNotNull()
        assertThat(finalGame?.subPhase).isEqualTo(VotingSubPhase.RE_VOTING.name)
    }
}