package com.werewolf.integration

import com.werewolf.model.CreditTxType
import com.werewolf.model.User
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.UserRepository
import com.werewolf.repository.WalletRepository
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WalletService on the real H2 schema: every balance change is an atomic
 * conditional UPDATE journaled with a signed ledger row carrying balanceAfter.
 * The headline guarantee is double-spend safety — the conditional debit is
 * all-or-nothing even under concurrency, with no row lock.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletServiceIntegrationTest {

    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var walletRepository: WalletRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var userRepository: UserRepository

    private var seq = 0

    private fun newUser(): String {
        val id = "guest:w-${++seq}-${System.nanoTime()}"
        userRepository.save(User(userId = id, nickname = "w"))
        return id
    }

    @Test
    fun `debit with insufficient funds returns null and writes no ledger row`() {
        val user = newUser()
        walletService.credit(user, 50, CreditTxType.GAME_REWARD, note = "seed")

        val result = walletService.debit(user, 100, CreditTxType.PERK_SPEND, note = "too-much")

        assertThat(result).isNull()
        assertThat(walletService.balance(user)).isEqualTo(50) // unchanged
        // Only the seed credit row exists — no debit row was written.
        val rows = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().type).isEqualTo(CreditTxType.GAME_REWARD)
    }

    @Test
    fun `credit writes a positive ledger row with balanceAfter`() {
        val user = newUser()

        val balance = walletService.credit(user, 100, CreditTxType.GAME_REWARD, gameId = null, note = "win")

        assertThat(balance).isEqualTo(100)
        assertThat(walletService.balance(user)).isEqualTo(100)
        val tx = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user).single()
        assertThat(tx.type).isEqualTo(CreditTxType.GAME_REWARD)
        assertThat(tx.amount).isEqualTo(100)
        assertThat(tx.balanceAfter).isEqualTo(100)
        assertThat(tx.note).isEqualTo("win")
        assertThat(tx.createdAt).isNotNull()
    }

    @Test
    fun `debit writes a negative ledger row with balanceAfter`() {
        val user = newUser()
        walletService.credit(user, 200, CreditTxType.GAME_REWARD, note = "seed")

        val balance = walletService.debit(user, 50, CreditTxType.PERK_SPEND, note = "NIGHT1_IMMUNITY")

        assertThat(balance).isEqualTo(150)
        assertThat(walletService.balance(user)).isEqualTo(150)
        // newest row is the debit (located by type, not index, to avoid
        // same-timestamp ordering flakiness)
        val debit = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
            .first { it.type == CreditTxType.PERK_SPEND }
        assertThat(debit.amount).isEqualTo(-50)
        assertThat(debit.balanceAfter).isEqualTo(150)
        assertThat(debit.note).isEqualTo("NIGHT1_IMMUNITY")
    }

    @Test
    fun `getOrCreate is idempotent - repeated calls yield a single wallet`() {
        val user = newUser()
        val first = walletService.getOrCreate(user)
        val second = walletService.getOrCreate(user)

        assertThat(first.userId).isEqualTo(user)
        assertThat(second.userId).isEqualTo(user)
        assertThat(first.balance).isEqualTo(0)
        assertThat(walletRepository.findAll().count { it.userId == user }).isEqualTo(1)
    }

    @Test
    fun `balance reflects committed credit then debit`() {
        val user = newUser()
        walletService.credit(user, 100, CreditTxType.GAME_REWARD)
        assertThat(walletService.balance(user)).isEqualTo(100)
        walletService.debit(user, 30, CreditTxType.PERK_SPEND)
        assertThat(walletService.balance(user)).isEqualTo(70)
    }

    @Test
    fun `concurrent debits - only one of three succeeds (double-spend safe)`() {
        val user = newUser()
        walletService.credit(user, 100, CreditTxType.GAME_REWARD, note = "seed")

        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val failures = AtomicInteger(0)
        // Capture any thread exception so a swallowed error fails the test
        // loudly instead of masquerading as a counter mismatch.
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(3) {
            pool.submit {
                try {
                    start.await()
                    if (walletService.debit(user, 70, CreditTxType.PERK_SPEND) != null) {
                        wins.incrementAndGet()
                    } else {
                        failures.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue()

        assertThat(errors).`as`("no debit thread should throw").isEmpty()
        assertThat(wins.get()).isEqualTo(1)
        assertThat(failures.get()).isEqualTo(2)
        assertThat(walletService.balance(user)).isEqualTo(30)
        // Exactly one debit ledger row was written.
        val debits = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
            .filter { it.type == CreditTxType.PERK_SPEND }
        assertThat(debits).hasSize(1)
    }
}
