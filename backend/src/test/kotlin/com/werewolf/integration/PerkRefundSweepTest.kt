package com.werewolf.integration

import com.werewolf.model.CreditTxType
import com.werewolf.model.Game
import com.werewolf.model.GameConfig
import com.werewolf.model.GamePhase
import com.werewolf.model.PerkActivation
import com.werewolf.model.PerkActivationStatus
import com.werewolf.model.Room
import com.werewolf.model.User
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GameRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.PERK_NIGHT1_IMMUNITY
import com.werewolf.service.PerkRefundSweep
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * The scheduled safety nets: (1) activations still ACTIVE with no bound game
 * after 24h (never-started rooms) are refunded; (2) live activations bound to
 * an already-ended game are settled (self-heal for missed game-end settles).
 * In-flight-game-bound or recent activations are left for the normal
 * settle-at-game-end lifecycle.
 */
@SpringBootTest
@ActiveProfiles("test")
class PerkRefundSweepTest {

    @Autowired lateinit var sweep: PerkRefundSweep
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var creditTransactionRepository: com.werewolf.repository.CreditTransactionRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private var seq = 0

    private fun newUser(): String {
        val id = "guest:sweep-${++seq}-${System.nanoTime()}"
        userRepository.save(User(userId = id, nickname = "s"))
        return id
    }

    private fun newRoom(host: String): Int {
        val room = roomRepository.save(
            Room(
                // avoid RoomControllerTest's fixed codes 111/222/333 (shared H2 schema)
                roomCode = (400 + ROOM_CODE_SEQ.getAndIncrement() % 600).toString(),
                hostUserId = host,
                totalPlayers = 6,
                config = GameConfig(),
            ),
        )
        return room.roomId ?: error("room not persisted")
    }

    companion object {
        private val ROOM_CODE_SEQ = AtomicInteger(0)
    }

    private fun saveActivation(roomId: Int, userId: String, gameId: Int?, price: Int = 30): Int {
        val a = perkActivationRepository.save(
            PerkActivation(
                roomId = roomId, gameId = gameId, userId = userId,
                perkCode = PERK_NIGHT1_IMMUNITY, status = PerkActivationStatus.ACTIVE, pricePaid = price,
            ),
        )
        return a.id ?: error("activation not persisted")
    }

    /** Backdate created_at past the sweep cutoff — @CreationTimestamp set it to now on insert. */
    private fun backdate(activationId: Int, hoursAgo: Long) {
        jdbcTemplate.update(
            "UPDATE perk_activations SET created_at = ? WHERE id = ?",
            Timestamp.valueOf(LocalDateTime.now().minusHours(hoursAgo)), activationId,
        )
    }

    @Test
    fun `refunds a stale ACTIVE activation with no bound game and credits the wallet`() {
        val user = newUser()
        val roomId = newRoom(user)
        val id = saveActivation(roomId, user, gameId = null, price = 30)
        backdate(id, 25)

        sweep.refundStaleActivations()

        assertThat(perkActivationRepository.findById(id).orElseThrow().status)
            .isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(walletService.balance(user)).isEqualTo(30)
        // A REFUND ledger row was written.
        val refund = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
            .first { it.type == CreditTxType.REFUND }
        assertThat(refund.amount).isEqualTo(30)
    }

    @Test
    fun `leaves a recent ACTIVE activation untouched`() {
        val user = newUser()
        val roomId = newRoom(user)
        val id = saveActivation(roomId, user, gameId = null) // created just now

        sweep.refundStaleActivations()

        assertThat(perkActivationRepository.findById(id).orElseThrow().status)
            .isEqualTo(PerkActivationStatus.ACTIVE)
        assertThat(walletService.balance(user)).isEqualTo(0)
    }

    @Test
    fun `leaves an activation just inside the 24h window untouched`() {
        val user = newUser()
        val roomId = newRoom(user)
        val id = saveActivation(roomId, user, gameId = null)
        backdate(id, 23) // younger than the 24h cutoff

        sweep.refundStaleActivations()

        assertThat(perkActivationRepository.findById(id).orElseThrow().status)
            .isEqualTo(PerkActivationStatus.ACTIVE)
        assertThat(walletService.balance(user)).isEqualTo(0)
    }

    @Test
    fun `leaves an activation bound to an in-flight game untouched even when old`() {
        val user = newUser()
        val roomId = newRoom(user)
        // The game is still in flight (endedAt null) — neither sweep pass may touch it.
        val game = gameRepository.save(Game(roomId = roomId, hostUserId = user))
        val gameId = game.gameId ?: error("game not persisted")
        val id = saveActivation(roomId, user, gameId = gameId)
        backdate(id, 25)

        sweep.refundStaleActivations()
        sweep.settleLeftoversForEndedGames()

        // refundStaleActivations filters gameId IS NULL; settleLeftovers only
        // looks at ENDED games — an in-flight bound activation is never swept.
        val activation = perkActivationRepository.findById(id).orElseThrow()
        assertThat(activation.status).isEqualTo(PerkActivationStatus.ACTIVE)
        assertThat(activation.settledAt).isNull()
        assertThat(walletService.balance(user)).isEqualTo(0)
    }

    @Test
    fun `settles a triggered leftover bound to an ended winnerless game (cancellation precedence, refund)`() {
        val user = newUser()
        val roomId = newRoom(user)
        // Ended but winner == null → the sweep's own `cancelled = game.winner == null`
        // mapping must treat it as cancelled and refund EVEN THOUGH triggeredAt is set.
        val game = gameRepository.save(
            Game(roomId = roomId, hostUserId = user).also {
                it.phase = GamePhase.GAME_OVER
                it.endedAt = LocalDateTime.now()
            },
        )
        val gameId = game.gameId ?: error("game not persisted")
        val activation = perkActivationRepository.save(
            PerkActivation(
                roomId = roomId, gameId = gameId, userId = user,
                perkCode = PERK_NIGHT1_IMMUNITY, status = PerkActivationStatus.ACTIVE, pricePaid = 30,
            ).also { it.triggeredAt = LocalDateTime.now() },
        )
        val id = activation.id ?: error("activation not persisted")

        sweep.settleLeftoversForEndedGames()

        val settled = perkActivationRepository.findById(id).orElseThrow()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(settled.settledAt).isNotNull()
        assertThat(walletService.balance(user)).isEqualTo(30)
        val refunds = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
            .filter { it.type == CreditTxType.REFUND }
        assertThat(refunds).hasSize(1)
        assertThat(refunds.single().perkActivationId).isEqualTo(id)
    }

    @Test
    fun `settles a leftover ACTIVE activation bound to an ended game (refund, untriggered)`() {
        val user = newUser()
        val roomId = newRoom(user)
        val game = gameRepository.save(
            Game(roomId = roomId, hostUserId = user).also {
                it.phase = GamePhase.GAME_OVER
                it.winner = WinnerSide.VILLAGER
                it.endedAt = LocalDateTime.now()
            },
        )
        val gameId = game.gameId ?: error("game not persisted")
        val id = saveActivation(roomId, user, gameId = gameId, price = 30)

        sweep.settleLeftoversForEndedGames()

        val activation = perkActivationRepository.findById(id).orElseThrow()
        assertThat(activation.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(activation.settledAt).isNotNull()
        assertThat(walletService.balance(user)).isEqualTo(30)
        val refunds = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
            .filter { it.type == CreditTxType.REFUND }
        assertThat(refunds).hasSize(1)
        assertThat(refunds.single().perkActivationId).isEqualTo(id)

        // Second pass is a no-op on the already-REFUNDED row: no double credit.
        sweep.settleLeftoversForEndedGames()
        assertThat(walletService.balance(user)).isEqualTo(30)
        assertThat(
            creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
                .filter { it.type == CreditTxType.REFUND },
        ).hasSize(1)
    }
}
