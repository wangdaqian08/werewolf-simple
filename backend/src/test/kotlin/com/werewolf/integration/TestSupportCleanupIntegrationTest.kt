package com.werewolf.integration

import com.werewolf.controller.TestSupportController
import com.werewolf.model.CreditTransaction
import com.werewolf.model.CreditTxType
import com.werewolf.model.ElectionSubPhase
import com.werewolf.model.EliminationHistory
import com.werewolf.model.Game
import com.werewolf.model.GameConfig
import com.werewolf.model.GameEvent
import com.werewolf.model.GamePlayer
import com.werewolf.model.GameSettlement
import com.werewolf.model.NightPhase
import com.werewolf.model.PerkActivation
import com.werewolf.model.PerkActivationStatus
import com.werewolf.model.PlayerRole
import com.werewolf.model.Room
import com.werewolf.model.RoomPlayer
import com.werewolf.model.SheriffCandidate
import com.werewolf.model.SheriffElection
import com.werewolf.model.User
import com.werewolf.model.Vote
import com.werewolf.model.VoteContext
import com.werewolf.model.Wallet
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GameEventRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.GameSettlementRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.SheriffCandidateRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.repository.UserRepository
import com.werewolf.repository.VoteRepository
import com.werewolf.repository.WalletRepository
import com.werewolf.service.PERK_NIGHT1_IMMUNITY
import com.werewolf.service.TestSupportService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * The e2e-only cleanup endpoint: DELETE /api/test-support/rooms/{roomCode}
 * removes every row a finished (or abandoned) test game left behind — rooms,
 * players, games, night phases, elections, votes, events, perk activations
 * and their game-scoped credit transactions — while deliberately KEEPING
 * users and wallets (guest identities are shared across rooms in a session).
 *
 * Runs in the ordinary "test" context: the beans are gated
 * @Profile("(e2e | test) & !prod"), so no extra profile (and no extra Spring
 * context) is needed — activating "e2e" here would mix application-e2e.yml's
 * H2 driver settings with CI's Postgres datasource override.
 */
@SpringBootTest
@ActiveProfiles("test")
class TestSupportCleanupIntegrationTest {

    @Autowired lateinit var service: TestSupportService
    @Autowired lateinit var controller: TestSupportController
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var walletRepository: WalletRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Autowired lateinit var sheriffCandidateRepository: SheriffCandidateRepository
    @Autowired lateinit var voteRepository: VoteRepository
    @Autowired lateinit var gameEventRepository: GameEventRepository
    @Autowired lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var gameSettlementRepository: GameSettlementRepository

    private var seq = 0

    private fun newUser(): String {
        val id = "guest:cleanup-${++seq}-${System.nanoTime()}"
        userRepository.save(User(userId = id, nickname = "c"))
        walletRepository.save(Wallet(userId = id, balance = 70))
        return id
    }

    private data class Seeded(
        val roomCode: String,
        val roomId: Int,
        val gameId: Int,
        val host: String,
        val p2: String,
        val electionId: Int,
        val activationId: Int,
    )

    /** One room + one finished game with a row in every game-scoped table. */
    private fun seedFullGraph(): Seeded {
        val host = newUser()
        val p2 = newUser()
        val roomCode = TestConstants.nextSeededRoomCode()
        val room = roomRepository.save(
            Room(roomCode = roomCode, hostUserId = host, totalPlayers = 6, config = GameConfig()),
        )
        val roomId = room.roomId ?: error("room not persisted")
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = host, host = true))
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = p2))

        val game = gameRepository.save(Game(roomId = roomId, hostUserId = host))
        val gameId = game.gameId ?: error("game not persisted")
        gamePlayerRepository.save(
            GamePlayer(gameId = gameId, userId = host, seatIndex = 1, role = PlayerRole.WEREWOLF),
        )
        gamePlayerRepository.save(
            GamePlayer(gameId = gameId, userId = p2, seatIndex = 2, role = PlayerRole.VILLAGER),
        )
        nightPhaseRepository.save(NightPhase(gameId = gameId, dayNumber = 1))
        val election = sheriffElectionRepository.save(
            SheriffElection(gameId = gameId, subPhase = ElectionSubPhase.SIGNUP),
        )
        val electionId = election.id ?: error("election not persisted")
        sheriffCandidateRepository.save(SheriffCandidate(electionId = electionId, userId = p2))
        voteRepository.save(
            Vote(gameId = gameId, voteContext = VoteContext.ELIMINATION, dayNumber = 1, voterUserId = host, targetUserId = p2),
        )
        gameEventRepository.save(GameEvent(gameId = gameId, eventType = "TEST", message = "seeded"))
        eliminationHistoryRepository.save(
            EliminationHistory(gameId = gameId, dayNumber = 1, eliminatedUserId = p2, eliminatedRole = PlayerRole.VILLAGER),
        )
        val activation = perkActivationRepository.save(
            PerkActivation(
                roomId = roomId, gameId = gameId, userId = p2,
                perkCode = PERK_NIGHT1_IMMUNITY, status = PerkActivationStatus.CONSUMED, pricePaid = 30,
            ),
        )
        val activationId = activation.id ?: error("activation not persisted")
        creditTransactionRepository.save(
            CreditTransaction(
                userId = p2, type = CreditTxType.PERK_SPEND, amount = -30, balanceAfter = 40,
                perkActivationId = activationId,
            ),
        )
        creditTransactionRepository.save(
            CreditTransaction(
                userId = host, type = CreditTxType.GAME_REWARD, amount = 10, balanceAfter = 80,
                gameId = gameId,
            ),
        )
        gameSettlementRepository.save(GameSettlement(gameId = gameId))
        return Seeded(roomCode, roomId, gameId, host, p2, electionId, activationId)
    }

    @Test
    fun `deletes the full room and game graph while keeping users and wallets`() {
        val s = seedFullGraph()

        val deleted = service.deleteRoomCascade(s.roomCode)

        assertThat(deleted).isNotNull
        assertThat(roomRepository.findByRoomCode(s.roomCode)).isEmpty
        assertThat(roomPlayerRepository.findByRoomId(s.roomId)).isEmpty()
        assertThat(gameRepository.findById(s.gameId)).isEmpty
        assertThat(gamePlayerRepository.findByGameId(s.gameId)).isEmpty()
        assertThat(nightPhaseRepository.findByGameId(s.gameId)).isEmpty()
        assertThat(sheriffElectionRepository.findByGameId(s.gameId)).isEmpty
        assertThat(sheriffCandidateRepository.findByElectionId(s.electionId)).isEmpty()
        assertThat(
            voteRepository.findByGameIdAndVoteContextAndDayNumber(s.gameId, VoteContext.ELIMINATION, 1),
        ).isEmpty()
        assertThat(gameEventRepository.findByGameIdOrderByCreatedAtAsc(s.gameId)).isEmpty()
        assertThat(eliminationHistoryRepository.findByGameId(s.gameId)).isEmpty()
        assertThat(perkActivationRepository.findById(s.activationId)).isEmpty
        assertThat(gameSettlementRepository.findById(s.gameId)).isEmpty
        assertThat(
            creditTransactionRepository.findAll()
                .filter { it.gameId == s.gameId || it.perkActivationId == s.activationId },
        ).isEmpty()

        // Users and wallets deliberately survive — guest identities are shared
        // across rooms within a test session.
        assertThat(userRepository.findById(s.host)).isPresent
        assertThat(userRepository.findById(s.p2)).isPresent
        assertThat(walletRepository.findById(s.p2).map { it.balance }).contains(70)
    }

    @Test
    fun `unknown room code returns null - controller answers 404`() {
        assertThat(service.deleteRoomCascade("000")).isNull()
        assertThat(controller.deleteRoom("000").statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `second delete of the same room returns null (idempotent cleanup)`() {
        val s = seedFullGraph()
        assertThat(service.deleteRoomCascade(s.roomCode)).isNotNull
        assertThat(service.deleteRoomCascade(s.roomCode)).isNull()
        assertThat(controller.deleteRoom(s.roomCode).statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `controller returns per-table deletion counts`() {
        val s = seedFullGraph()
        val response = controller.deleteRoom(s.roomCode)
        assertThat(response.statusCode.value()).isEqualTo(200)
        val body = response.body ?: error("body expected on 200")
        assertThat(body["rooms"]).isEqualTo(1)
        assertThat(body["games"]).isEqualTo(1)
        assertThat(body["room_players"]).isEqualTo(2)
    }
}
