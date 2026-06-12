package com.werewolf.integration

import com.werewolf.model.CreditTxType
import com.werewolf.model.GameConfig
import com.werewolf.model.PerkActivation
import com.werewolf.model.PerkActivationStatus
import com.werewolf.model.Room
import com.werewolf.model.User
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

/**
 * The scheduled safety net for rooms that never started a game: activations
 * still ACTIVE with no bound game after 24h are refunded. Game-bound or recent
 * activations are left for the normal CONSUMED/VOID/withdraw lifecycle.
 */
@SpringBootTest
@ActiveProfiles("test")
class PerkRefundSweepTest {

    @Autowired lateinit var sweep: PerkRefundSweep
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roomRepository: RoomRepository
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
            Room(roomCode = (100..999).random().toString(), hostUserId = host, totalPlayers = 6, config = GameConfig()),
        )
        return room.roomId ?: error("room not persisted")
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
    fun `leaves a game-bound activation untouched even when old`() {
        val user = newUser()
        val roomId = newRoom(user)
        val id = saveActivation(roomId, user, gameId = 123_456) // bound to a game
        backdate(id, 25)

        sweep.refundStaleActivations()

        // The finder filters gameId IS NULL, so a bound activation is never swept.
        assertThat(perkActivationRepository.findById(id).orElseThrow().status)
            .isEqualTo(PerkActivationStatus.ACTIVE)
        assertThat(walletService.balance(user)).isEqualTo(0)
    }
}
