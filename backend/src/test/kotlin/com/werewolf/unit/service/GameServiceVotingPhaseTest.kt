package com.werewolf.unit.service

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.GameService
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.util.*

@ExtendWith(MockitoExtension::class)
@Suppress("UNCHECKED_CAST")
class GameServiceVotingPhaseTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var sheriffService: SheriffService
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @InjectMocks lateinit var gameService: GameService

    private val gameId = 1
    private val hostId = "host:001"
    private val day = 1

    private fun game(subPhase: String = VotingSubPhase.VOTING.name) =
        Game(roomId = 1, hostUserId = hostId).also { g ->
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(g, gameId)
            g.phase = GamePhase.VOTING
            g.subPhase = subPhase
            g.dayNumber = day
        }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSheriff = false)
    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role)
    private fun user(userId: String, nick: String) = User(userId = userId, nickname = nick)
    private fun vote(voter: String, target: String?) =
        Vote(gameId = gameId, voteContext = VoteContext.ELIMINATION, dayNumber = day, voterUserId = voter, targetUserId = target)

    @BeforeEach
    fun setupCommon() {
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room()))
    }

    private fun setupGameAndPlayers(game: Game, players: List<GamePlayer>, users: List<User>) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(userRepository.findAllById(players.map { it.userId })).thenReturn(users)
    }

    private fun votingResult(result: Map<String, Any?>): Map<String, Any?> =
        result["votingPhase"] as Map<String, Any?>

    // ── Basic voting state ───────────────────────────────────────────────────

    @Test
    fun `getGameState VOTING - returns subPhase and dayNumber`() {
        val players = listOf(player(hostId, 0), player("u2", 1))
        val users = listOf(user(hostId, "Host"), user("u2", "Bob"))
        setupGameAndPlayers(game(), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(emptyList())
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["subPhase"]).isEqualTo(VotingSubPhase.VOTING.name)
        assertThat(voting["dayNumber"]).isEqualTo(day)
    }

    @Test
    fun `getGameState VOTING - totalVoters counts only alive players with canVote=true`() {
        val alive1 = player(hostId, 0)
        val alive2 = player("u2", 1)
        val dead = player("u3", 2).also { it.alive = false }
        val idiotNoVote = player("u4", 3, PlayerRole.IDIOT).also { it.canVote = false; it.idiotRevealed = true }
        val players = listOf(alive1, alive2, dead, idiotNoVote)
        val users = listOf(user(hostId, "Host"), user("u2", "Bob"), user("u3", "Dead"), user("u4", "Idiot"))
        setupGameAndPlayers(game(), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(emptyList())
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["totalVoters"]).isEqualTo(2) // alive1 + alive2
    }

    // ── myVote and votedPlayerIds ─────────────────────────────────────────────

    @Test
    fun `getGameState VOTING - myVote reflects requesting player's vote target`() {
        val players = listOf(player(hostId, 0), player("u2", 1))
        val users = listOf(user(hostId, "Host"), user("u2", "Bob"))
        setupGameAndPlayers(game(), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(listOf(vote(hostId, "u2")))
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["myVote"]).isEqualTo("u2")
        assertThat(voting["myVoteSkipped"]).isEqualTo(false)
    }

    @Test
    fun `getGameState VOTING - myVoteSkipped=true when player voted with null target`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        setupGameAndPlayers(game(), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(listOf(vote(hostId, null))) // abstain vote
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["myVote"]).isNull()
        assertThat(voting["myVoteSkipped"]).isEqualTo(true)
    }

    @Test
    fun `getGameState VOTING - votedPlayerIds includes all voters`() {
        val players = listOf(player(hostId, 0), player("u2", 1), player("u3", 2))
        val users = listOf(user(hostId, "Host"), user("u2", "Bob"), user("u3", "Carol"))
        setupGameAndPlayers(game(), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(listOf(vote(hostId, "u2"), vote("u3", "u2")))
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["votedPlayerIds"] as List<String>).containsExactlyInAnyOrder(hostId, "u3")
        assertThat(voting["votesSubmitted"]).isEqualTo(2)
    }

    // ── Tally ────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState VOTING - tally is null before VOTE_RESULT`() {
        val players = listOf(player(hostId, 0))
        val users = listOf(user(hostId, "Host"))
        setupGameAndPlayers(game(VotingSubPhase.VOTING.name), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(listOf(vote(hostId, "u2")))
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["tally"]).isNull()
        assertThat(voting["tallyRevealed"]).isEqualTo(false)
    }

    @Test
    fun `getGameState VOTING - tally revealed in VOTE_RESULT with vote counts and voters`() {
        val p1 = player(hostId, 0)
        val p2 = player("u2", 1)
        val p3 = player("u3", 2)
        val players = listOf(p1, p2, p3)
        val users = listOf(user(hostId, "Host"), user("u2", "Bob"), user("u3", "Carol"))
        setupGameAndPlayers(game(VotingSubPhase.VOTE_RESULT.name), players, users)
        // host and u3 both voted for u2
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(listOf(vote(hostId, "u2"), vote("u3", "u2")))
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["tallyRevealed"]).isEqualTo(true)
        val tally = voting["tally"] as List<Map<String, Any?>>
        assertThat(tally).hasSize(1)
        assertThat(tally[0]["playerId"]).isEqualTo("u2")
        assertThat(tally[0]["votes"]).isEqualTo(2.0)
        val voters = tally[0]["voters"] as List<Map<String, Any?>>
        assertThat(voters).hasSize(2)
    }

    // ── Eliminated player ────────────────────────────────────────────────────

    @Test
    fun `getGameState VOTING - eliminatedPlayer populated from EliminationHistory`() {
        val p1 = player(hostId, 0)
        val p2 = player("u2", 1)
        val players = listOf(p1, p2)
        val users = listOf(user(hostId, "Host"), user("u2", "Bob"))
        setupGameAndPlayers(game(VotingSubPhase.VOTE_RESULT.name), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(listOf(vote(hostId, "u2")))
        val history = EliminationHistory(
            gameId = gameId, dayNumber = day, eliminatedUserId = "u2", eliminatedRole = PlayerRole.WEREWOLF
        )
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(history))

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["eliminatedPlayerId"]).isEqualTo("u2")
        assertThat(voting["eliminatedNickname"]).isEqualTo("Bob")
        assertThat(voting["eliminatedSeatIndex"]).isEqualTo(1)
        assertThat(voting["eliminatedRole"]).isEqualTo("WEREWOLF")
    }

    // ── Idiot revealed ───────────────────────────────────────────────────────

    @Test
    fun `getGameState VOTING - idiotRevealedPlayer detected when no elimination but top-voted idiot alive`() {
        val p1 = player(hostId, 0)
        val idiot = player("u2", 1, PlayerRole.IDIOT).also { it.idiotRevealed = true }
        val players = listOf(p1, idiot)
        val users = listOf(user(hostId, "Host"), user("u2", "IdiotPlayer"))
        setupGameAndPlayers(game(VotingSubPhase.VOTE_RESULT.name), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(listOf(vote(hostId, "u2")))
        // No elimination history (idiot survived)
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["idiotRevealedId"]).isEqualTo("u2")
        assertThat(voting["idiotRevealedNickname"]).isEqualTo("IdiotPlayer")
        assertThat(voting["idiotRevealedSeatIndex"]).isEqualTo(1)
    }

    @Test
    fun `getGameState VOTING - canVote is false for dead player`() {
        val dead = player(hostId, 0).also { it.alive = false }
        val players = listOf(dead, player("u2", 1))
        val users = listOf(user(hostId, "Host"), user("u2", "Bob"))
        setupGameAndPlayers(game(), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(emptyList())
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["canVote"]).isEqualTo(false)
    }

    @Test
    fun `getGameState VOTING - canVote is false for revealed idiot`() {
        val idiot = player(hostId, 0, PlayerRole.IDIOT).also { it.canVote = false; it.idiotRevealed = true }
        val players = listOf(idiot, player("u2", 1))
        val users = listOf(user(hostId, "Host"), user("u2", "Bob"))
        setupGameAndPlayers(game(), players, users)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, day))
            .thenReturn(emptyList())
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.empty())

        val result = gameService.getGameState(gameId, hostId)
        val voting = votingResult(result)

        assertThat(voting["canVote"]).isEqualTo(false)
    }
}
