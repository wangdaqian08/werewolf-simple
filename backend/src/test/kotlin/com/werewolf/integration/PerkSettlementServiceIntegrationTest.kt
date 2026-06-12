package com.werewolf.integration

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.PERK_NIGHT1_IMMUNITY
import com.werewolf.service.PerkService
import com.werewolf.service.PerkSettlementService
import com.werewolf.service.RewardSettlementService
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exactly-once game-end settlement of perk activations on the real H2 schema:
 * took effect → CONSUMED (credits kept); untriggered / wolf-VOID / cancelled /
 * unknown perk → REFUNDED with a single wallet credit, even under double or
 * concurrent settle calls (settleIfLive conditional-UPDATE gate).
 */
@SpringBootTest
@ActiveProfiles("test")
class PerkSettlementServiceIntegrationTest {

    @Autowired lateinit var perkService: PerkService
    @Autowired lateinit var perkSettlementService: PerkSettlementService
    @Autowired lateinit var rewardSettlementService: RewardSettlementService
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var perkRepository: PerkRepository
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository

    private var seq = 0

    /** Flyway is disabled on H2 — seed the perk row the migration would insert. */
    private fun ensurePerk(): Perk =
        perkRepository.findById(PERK_NIGHT1_IMMUNITY).orElseGet {
            perkRepository.save(
                Perk(
                    perkCode = PERK_NIGHT1_IMMUNITY,
                    name = "First Night Immunity",
                    description = "test perk",
                    priceCredits = 30,
                ),
            )
        }

    private fun newUser(prefix: String): String {
        val id = "guest:$prefix-${++seq}-${System.nanoTime()}"
        userRepository.save(User(userId = id, nickname = prefix))
        return id
    }

    private fun newRoom(hostId: String): Int {
        val room = roomRepository.save(
            Room(
                // avoid RoomControllerTest's fixed codes 111/222/333 (shared H2 schema)
                roomCode = (400 + ROOM_CODE_SEQ.getAndIncrement() % 600).toString(),
                hostUserId = hostId,
                totalPlayers = 6,
                config = GameConfig(perksAllowed = true),
            ),
        )
        val roomId = room.roomId ?: error("room not persisted")
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = hostId, host = true))
        return roomId
    }

    private fun fund(userId: String, amount: Int) {
        walletService.credit(userId, amount, CreditTxType.GAME_REWARD, note = "test-seed")
    }

    // JUnit creates a fresh test instance per method but the H2 schema is
    // shared across the class — gameIds must be unique JVM-wide, not
    // per-instance, or findByGameId(...) picks up rows from earlier tests.
    private fun nextGameId() = GAME_ID_SEQ.incrementAndGet()

    companion object {
        private val GAME_ID_SEQ = AtomicInteger(900_000)
        private val ROOM_CODE_SEQ = AtomicInteger(0)
    }

    /** Full purchase path: fund 100, activate (-30), bind to [gameId] with [role]. */
    private fun activateAndBind(userId: String, roomId: Int, gameId: Int, role: PlayerRole): PerkActivation {
        fund(userId, 100)
        perkService.activate(userId, roomId, PERK_NIGHT1_IMMUNITY)
        perkService.onGameStart(
            roomId, gameId,
            listOf(GamePlayer(gameId = gameId, userId = userId, seatIndex = 0, role = role)),
        )
        return perkActivationRepository.findByGameId(gameId).single { it.userId == userId }
    }

    private fun refundRows(userId: String) =
        creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId)
            .filter { it.type == CreditTxType.REFUND }

    @Test
    fun `triggered activation is CONSUMED - no refund, balance unchanged`() {
        ensurePerk()
        val user = newUser("c")
        val roomId = newRoom(user)
        val gameId = nextGameId()
        activateAndBind(user, roomId, gameId, PlayerRole.SEER)
        perkService.markNight1Triggered(gameId, setOf(user))

        perkSettlementService.settleForGame(gameId, cancelled = false)

        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.CONSUMED)
        assertThat(settled.settledAt).isNotNull()
        assertThat(walletService.balance(user)).isEqualTo(70)
        assertThat(refundRows(user)).isEmpty()
    }

    @Test
    fun `untriggered ACTIVE activation is REFUNDED - exact ledger chain`() {
        ensurePerk()
        val user = newUser("r")
        val roomId = newRoom(user)
        val gameId = nextGameId()
        val activation = activateAndBind(user, roomId, gameId, PlayerRole.VILLAGER)

        perkSettlementService.settleForGame(gameId, cancelled = false)

        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(settled.settledAt).isNotNull()
        assertThat(walletService.balance(user)).isEqualTo(100)

        // Exact ledger: seed +100 → PERK_SPEND −30 → REFUND +30, one row each,
        // with a consistent balanceAfter chain (which itself proves the order)
        // and the refund linked back to the activation.
        val ledger = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
        assertThat(ledger).hasSize(3)
        val refund = ledger.single { it.type == CreditTxType.REFUND }
        val spend = ledger.single { it.type == CreditTxType.PERK_SPEND }
        val seedTx = ledger.single { it.type == CreditTxType.GAME_REWARD }
        assertThat(seedTx.amount).isEqualTo(100)
        assertThat(seedTx.balanceAfter).isEqualTo(100)
        assertThat(spend.amount).isEqualTo(-30)
        assertThat(spend.balanceAfter).isEqualTo(70)
        assertThat(refund.amount).isEqualTo(30)
        assertThat(refund.balanceAfter).isEqualTo(100)
        assertThat(refund.perkActivationId).isEqualTo(activation.id)
        assertThat(refund.note).isEqualTo(PERK_NIGHT1_IMMUNITY)
    }

    @Test
    fun `wolf-dealt VOID activation is REFUNDED at settlement`() {
        ensurePerk()
        val user = newUser("w")
        val roomId = newRoom(user)
        val gameId = nextGameId()
        val activation = activateAndBind(user, roomId, gameId, PlayerRole.WEREWOLF)
        assertThat(activation.status).isEqualTo(PerkActivationStatus.VOID)
        assertThat(walletService.balance(user)).isEqualTo(70) // not refunded mid-game

        perkSettlementService.settleForGame(gameId, cancelled = false)

        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(walletService.balance(user)).isEqualTo(100)
        assertThat(refundRows(user)).hasSize(1)
    }

    @Test
    fun `cancelled game refunds even a triggered activation (cancellation precedence)`() {
        ensurePerk()
        val user = newUser("x")
        val roomId = newRoom(user)
        val gameId = nextGameId()
        activateAndBind(user, roomId, gameId, PlayerRole.SEER)
        perkService.markNight1Triggered(gameId, setOf(user))

        perkSettlementService.settleForGame(gameId, cancelled = true)

        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(walletService.balance(user)).isEqualTo(100)
        assertThat(refundRows(user)).hasSize(1)
    }

    @Test
    fun `double settleForGame credits exactly once`() {
        ensurePerk()
        val user = newUser("d")
        val roomId = newRoom(user)
        val gameId = nextGameId()
        activateAndBind(user, roomId, gameId, PlayerRole.VILLAGER)

        perkSettlementService.settleForGame(gameId, cancelled = false)
        perkSettlementService.settleForGame(gameId, cancelled = false)

        assertThat(walletService.balance(user)).isEqualTo(100)
        assertThat(refundRows(user)).hasSize(1)
    }

    @Test
    fun `concurrent settleForGame credits exactly once per activation`() {
        ensurePerk()
        val user = newUser("p")
        val roomId = newRoom(user)
        val gameId = nextGameId()
        activateAndBind(user, roomId, gameId, PlayerRole.VILLAGER)

        val threads = 3
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = AtomicInteger(0)
        repeat(threads) {
            pool.submit {
                start.await()
                try {
                    perkSettlementService.settleForGame(gameId, cancelled = false)
                } catch (e: Exception) {
                    failures.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue()
        assertThat(failures.get()).isEqualTo(0)

        // Exactly one refund, no lost/double credit.
        assertThat(perkActivationRepository.findByGameId(gameId).single().status)
            .isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(walletService.balance(user)).isEqualTo(100)
        assertThat(refundRows(user)).hasSize(1)
    }

    @Test
    fun `unknown perkCode fails safe to refund`() {
        val user = newUser("u")
        val roomId = newRoom(user)
        val gameId = nextGameId()
        // Direct seed: a future perk type this settlement build doesn't know,
        // even "triggered" — tookEffect must fail safe to refund.
        val activation = perkActivationRepository.save(
            PerkActivation(
                roomId = roomId, gameId = gameId, userId = user,
                perkCode = "FUTURE_UNKNOWN_PERK", pricePaid = 50,
            ).also { it.triggeredAt = LocalDateTime.now() },
        )

        perkSettlementService.settleForGame(gameId, cancelled = false)

        val settled = perkActivationRepository.findById(activation.id ?: error("no id")).orElseThrow()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(walletService.balance(user)).isEqualTo(50)
        assertThat(refundRows(user).single().amount).isEqualTo(50)
    }

    @Test
    fun `mixed game settles each activation on its own merits in one call`() {
        val roomId = newRoom(newUser("host"))
        val gameId = nextGameId()
        val triggered = newUser("t")
        val untriggered = newUser("n")
        val wolf = newUser("v")
        // Direct seed (bypasses FCFS — settlement must handle any mix).
        val aTriggered = perkActivationRepository.save(
            PerkActivation(roomId = roomId, gameId = gameId, userId = triggered, perkCode = PERK_NIGHT1_IMMUNITY, pricePaid = 30)
                .also { it.triggeredAt = LocalDateTime.now() },
        )
        val aUntriggered = perkActivationRepository.save(
            PerkActivation(roomId = roomId, gameId = gameId, userId = untriggered, perkCode = PERK_NIGHT1_IMMUNITY, pricePaid = 30),
        )
        val aVoid = perkActivationRepository.save(
            PerkActivation(
                roomId = roomId, gameId = gameId, userId = wolf, perkCode = PERK_NIGHT1_IMMUNITY,
                status = PerkActivationStatus.VOID, pricePaid = 30,
            ),
        )

        perkSettlementService.settleForGame(gameId, cancelled = false)

        val byId = perkActivationRepository.findByGameId(gameId).associateBy { it.id }
        assertThat(byId[aTriggered.id]?.status).isEqualTo(PerkActivationStatus.CONSUMED)
        assertThat(byId[aUntriggered.id]?.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(byId[aVoid.id]?.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(walletService.balance(triggered)).isEqualTo(0)
        assertThat(walletService.balance(untriggered)).isEqualTo(30)
        assertThat(walletService.balance(wolf)).isEqualTo(30)
    }

    // ------------------------------------------------------------------
    // Full lifecycle through the REAL settle path:
    // RewardSettlementService.settle(gameId, winner) drives BOTH perk
    // settlement and game-reward credits on the same wallet. Expected
    // reward amounts come from werewolf.rewards (application.yml /
    // RewardProperties defaults): winBonus=20, participation=5,
    // wolfPerDaySurvived=4. All holders below are villager-side winners,
    // so the reward is winBonus = 20.
    // ------------------------------------------------------------------

    /** Persist a real Game row + a seated GamePlayer so settle() finds both. */
    private fun newGameWithPlayer(roomId: Int, userId: String, role: PlayerRole): Int {
        val game = gameRepository.save(Game(roomId = roomId, hostUserId = userId))
        val gameId = game.gameId ?: error("game not persisted")
        gamePlayerRepository.save(GamePlayer(gameId = gameId, userId = userId, seatIndex = 0, role = role))
        return gameId
    }

    /** Full ledger in insertion order (IDENTITY id is monotonic). */
    private fun ledgerInOrder(userId: String): List<CreditTransaction> =
        creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId)
            .sortedBy { it.id ?: error("tx not persisted") }

    /** Every row's balanceAfter must equal the running sum of amounts so far. */
    private fun assertBalanceChain(ledger: List<CreditTransaction>) {
        var running = 0
        ledger.forEach { tx ->
            running += tx.amount
            assertThat(tx.balanceAfter)
                .describedAs("balanceAfter of %s tx id=%s", tx.type, tx.id)
                .isEqualTo(running)
        }
    }

    @Test
    fun `full lifecycle - attacked holder - settle(winner) consumes perk and pays reward`() {
        ensurePerk()
        val user = newUser("flc")
        val roomId = newRoom(user)
        val gameId = newGameWithPlayer(roomId, user, PlayerRole.SEER)
        activateAndBind(user, roomId, gameId, PlayerRole.SEER) // fund +100, PERK_SPEND -30, bind
        perkService.markNight1Triggered(gameId, setOf(user))

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.CONSUMED)
        assertThat(settled.settledAt).isNotNull()
        // 100 seed − 30 perk + 20 winBonus (holder on the winning villager side)
        assertThat(walletService.balance(user)).isEqualTo(90)

        val ledger = ledgerInOrder(user)
        assertThat(ledger).hasSize(3)
        assertThat(ledger.map { it.type })
            .containsExactly(CreditTxType.GAME_REWARD, CreditTxType.PERK_SPEND, CreditTxType.GAME_REWARD)
        assertThat(ledger.map { it.amount }).containsExactly(100, -30, 20)
        assertThat(ledger[2].gameId).isEqualTo(gameId)
        assertBalanceChain(ledger)
        assertThat(refundRows(user)).isEmpty()
    }

    @Test
    fun `full lifecycle - never-attacked holder - settle(winner) refunds perk and pays reward`() {
        ensurePerk()
        val user = newUser("flr")
        val roomId = newRoom(user)
        val gameId = newGameWithPlayer(roomId, user, PlayerRole.VILLAGER)
        val activation = activateAndBind(user, roomId, gameId, PlayerRole.VILLAGER)
        // markNight1Triggered never called — same observable contract when the
        // holder WAS attacked but a guard save covered it (orchestrator skips
        // the mark): settle must refund.

        rewardSettlementService.settle(gameId, WinnerSide.VILLAGER)

        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(settled.settledAt).isNotNull()
        // 100 seed − 30 perk + 30 refund + 20 winBonus
        assertThat(walletService.balance(user)).isEqualTo(120)

        val ledger = ledgerInOrder(user)
        assertThat(ledger).hasSize(4)
        assertThat(ledger.map { it.type }).containsExactly(
            CreditTxType.GAME_REWARD, CreditTxType.PERK_SPEND, CreditTxType.REFUND, CreditTxType.GAME_REWARD,
        )
        assertThat(ledger.map { it.amount }).containsExactly(100, -30, 30, 20)
        assertThat(ledger[2].perkActivationId).isEqualTo(activation.id)
        assertThat(ledger[3].gameId).isEqualTo(gameId)
        assertBalanceChain(ledger)
    }

    @Test
    fun `full lifecycle - cancelled game refunds perk and pays no reward`() {
        ensurePerk()
        val user = newUser("flx")
        val roomId = newRoom(user)
        val gameId = newGameWithPlayer(roomId, user, PlayerRole.SEER)
        val activation = activateAndBind(user, roomId, gameId, PlayerRole.SEER)

        rewardSettlementService.settle(gameId, null) // cancelled — no winner

        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(settled.settledAt).isNotNull()
        assertThat(walletService.balance(user)).isEqualTo(100)

        val ledger = ledgerInOrder(user)
        assertThat(ledger).hasSize(3)
        assertThat(ledger.map { it.type })
            .containsExactly(CreditTxType.GAME_REWARD, CreditTxType.PERK_SPEND, CreditTxType.REFUND)
        assertThat(ledger.map { it.amount }).containsExactly(100, -30, 30)
        assertThat(ledger[2].perkActivationId).isEqualTo(activation.id)
        // no game reward was paid: the only GAME_REWARD row is the test seed (gameId == null)
        assertThat(ledger.filter { it.type == CreditTxType.GAME_REWARD && it.gameId != null }).isEmpty()
        assertBalanceChain(ledger)
    }
}
