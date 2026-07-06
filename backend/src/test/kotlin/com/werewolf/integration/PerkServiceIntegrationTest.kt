package com.werewolf.integration

import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.InsufficientCreditsException
import com.werewolf.service.PERK_NIGHT1_IMMUNITY
import com.werewolf.service.PerkService
import com.werewolf.service.PerkSettlementService
import com.werewolf.service.PerkTakenException
import com.werewolf.service.PerksDisabledException
import com.werewolf.service.RoomNotOpenException
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Perk activation lifecycle on the real H2 schema: FCFS exclusivity (incl.
 * under concurrency via the pessimistic room lock), charge/refund wallet
 * consistency, VOID on wolf assignment, trigger recording mid-game, and the
 * game-end settlement that decides CONSUMED vs REFUNDED.
 */
@SpringBootTest
@ActiveProfiles("test")
class PerkServiceIntegrationTest {

    @Autowired lateinit var perkService: PerkService
    @Autowired lateinit var perkSettlementService: PerkSettlementService
    @Autowired lateinit var walletService: WalletService
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

    private fun newRoom(hostId: String, perksAllowed: Boolean = true): Int {
        val room = roomRepository.save(
            Room(
                roomCode = (100..999).random().toString(),
                hostUserId = hostId,
                totalPlayers = 6,
                config = GameConfig(perksAllowed = perksAllowed),
            ),
        )
        val roomId = room.roomId ?: error("room not persisted")
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = hostId, host = true))
        return roomId
    }

    private fun join(roomId: Int, userId: String) {
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = userId))
    }

    private fun fund(userId: String, amount: Int) {
        walletService.credit(userId, amount, CreditTxType.GAME_REWARD, note = "test-seed")
    }

    @Test
    fun `activate charges the wallet and is visible as ACTIVE`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 100)

        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)

        assertThat(walletService.balance(host)).isEqualTo(70)
        val active = perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
        assertThat(active).hasSize(1)
        assertThat(active.first().userId).isEqualTo(host)
        // Ledger row links back to the activation
        val tx = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host).first()
        assertThat(tx.type).isEqualTo(CreditTxType.PERK_SPEND)
        assertThat(tx.amount).isEqualTo(-30)
        assertThat(tx.perkActivationId).isEqualTo(active.first().id)
    }

    @Test
    fun `second activation of the same perk is rejected (FCFS)`() {
        ensurePerk()
        val host = newUser("h")
        val guest = newUser("g")
        val roomId = newRoom(host)
        join(roomId, guest)
        fund(host, 100)
        fund(guest, 100)

        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)
        assertThatThrownBy { perkService.activate(guest, roomId, PERK_NIGHT1_IMMUNITY) }
            .isInstanceOf(PerkTakenException::class.java)
        // Loser is not charged
        assertThat(walletService.balance(guest)).isEqualTo(100)
    }

    @Test
    fun `insufficient credits rejects and leaves no activation`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 10) // perk costs 30

        assertThatThrownBy { perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY) }
            .isInstanceOf(InsufficientCreditsException::class.java)
        assertThat(walletService.balance(host)).isEqualTo(10)
        assertThat(perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)).isEmpty()
    }

    @Test
    fun `perks disabled in room config rejects activation`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host, perksAllowed = false)
        fund(host, 100)

        assertThatThrownBy { perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY) }
            .isInstanceOf(PerksDisabledException::class.java)
    }

    @Test
    fun `withdraw refunds the full price`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 100)

        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)
        perkService.withdraw(host, roomId, PERK_NIGHT1_IMMUNITY)

        assertThat(walletService.balance(host)).isEqualTo(100)
        assertThat(perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)).isEmpty()
        // After withdraw the perk is free again for someone else
        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)
        assertThat(walletService.balance(host)).isEqualTo(70)
    }

    @Test
    fun `onGameStart binds gameId and VOIDs a wolf holder, settlement refunds at game end`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 100)
        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)

        val gameId = 999_000 + seq
        perkService.onGameStart(
            roomId, gameId,
            listOf(com.werewolf.model.GamePlayer(gameId = gameId, userId = host, seatIndex = 0, role = PlayerRole.WEREWOLF)),
        )

        val activation = perkActivationRepository.findByGameId(gameId).single()
        assertThat(activation.status).isEqualTo(PerkActivationStatus.VOID)
        assertThat(walletService.balance(host)).isEqualTo(70) // not refunded yet — wolf identity would leak
        assertThat(perkService.night1ImmuneUserIds(gameId)).isEmpty()

        // Game-end settlement refunds the inapplicable (wolf-dealt) activation.
        perkSettlementService.settleForGame(gameId, cancelled = false)
        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(settled.settledAt).isNotNull()
        assertThat(walletService.balance(host)).isEqualTo(100)
        // Exactly one REFUND ledger row, linked back to the activation.
        val refunds = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host)
            .filter { it.type == CreditTxType.REFUND }
        assertThat(refunds).hasSize(1)
        assertThat(refunds.single().amount).isEqualTo(30)
        assertThat(refunds.single().perkActivationId).isEqualTo(settled.id)
    }

    @Test
    fun `non-wolf holder stays ACTIVE mid-game, trigger marks triggeredAt, settlement consumes without refund`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 100)
        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)

        val gameId = 998_000 + seq
        perkService.onGameStart(
            roomId, gameId,
            listOf(com.werewolf.model.GamePlayer(gameId = gameId, userId = host, seatIndex = 0, role = PlayerRole.SEER)),
        )
        assertThat(perkService.night1ImmuneUserIds(gameId)).containsExactly(host)

        // Decisive save on night 1: trigger is recorded, status stays ACTIVE
        // (still in the immune set so kill-list re-computations stay consistent).
        perkService.markNight1Triggered(gameId, setOf(host))
        val triggered = perkActivationRepository.findByGameId(gameId).single()
        assertThat(triggered.status).isEqualTo(PerkActivationStatus.ACTIVE)
        assertThat(triggered.triggeredAt).isNotNull()
        assertThat(perkService.night1ImmuneUserIds(gameId)).containsExactly(host)

        // Game-end settlement: triggered → CONSUMED, credits kept, no refund row.
        perkSettlementService.settleForGame(gameId, cancelled = false)
        val settled = perkActivationRepository.findByGameId(gameId).single()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.CONSUMED)
        assertThat(settled.settledAt).isNotNull()
        assertThat(walletService.balance(host)).isEqualTo(70)
        assertThat(
            creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host)
                .filter { it.type == CreditTxType.REFUND },
        ).isEmpty()
        // Still reported immune after settlement (CONSUMED stays in the set).
        assertThat(perkService.night1ImmuneUserIds(gameId)).containsExactly(host)
    }

    @Test
    fun `onGameStart never resurrects a withdrawn activation`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 100)
        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)
        val activationId = perkActivationRepository
            .findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
            .single().id ?: error("activation not persisted")

        // Withdraw refunds and terminates the row (models the audit race:
        // a withdraw committing between onGameStart's read and its commit).
        perkService.withdraw(host, roomId, PERK_NIGHT1_IMMUNITY)
        assertThat(walletService.balance(host)).isEqualTo(100)

        val gameId = 997_000 + seq
        perkService.onGameStart(
            roomId, gameId,
            listOf(com.werewolf.model.GamePlayer(gameId = gameId, userId = host, seatIndex = 0, role = PlayerRole.SEER)),
        )

        // The conditional bind must not touch the REFUNDED row.
        val row = perkActivationRepository.findById(activationId).orElseThrow()
        assertThat(row.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(row.gameId).isNull()
        assertThat(walletService.balance(host)).isEqualTo(100)

        // Nothing got bound, so game-end settlement finds nothing to refund.
        perkSettlementService.settleForGame(gameId, cancelled = false)
        assertThat(walletService.balance(host)).isEqualTo(100)
        assertThat(
            creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host)
                .filter { it.type == CreditTxType.REFUND },
        ).hasSize(1) // only the withdraw refund — never a second one
    }

    @Test
    fun `withdraw after game start is rejected - stays ACTIVE and bound, no refund`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 100)
        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)

        val gameId = 996_000 + seq
        perkService.onGameStart(
            roomId, gameId,
            listOf(com.werewolf.model.GamePlayer(gameId = gameId, userId = host, seatIndex = 0, role = PlayerRole.SEER)),
        )
        // GameService.startGame flips the room out of WAITING in the same tx.
        val room = roomRepository.findById(roomId).orElseThrow()
        room.status = RoomStatus.IN_GAME
        roomRepository.save(room)

        assertThatThrownBy { perkService.withdraw(host, roomId, PERK_NIGHT1_IMMUNITY) }
            .isInstanceOf(RoomNotOpenException::class.java)

        val row = perkActivationRepository.findByGameId(gameId).single()
        assertThat(row.status).isEqualTo(PerkActivationStatus.ACTIVE)
        assertThat(row.gameId).isEqualTo(gameId)
        assertThat(walletService.balance(host)).isEqualTo(70) // still charged
        assertThat(
            creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host)
                .filter { it.type == CreditTxType.REFUND },
        ).isEmpty()
    }

    @Test
    fun `refundActiveForUser never touches a game-bound activation`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 100)
        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)

        val gameId = 994_000 + seq
        perkService.onGameStart(
            roomId, gameId,
            listOf(com.werewolf.model.GamePlayer(gameId = gameId, userId = host, seatIndex = 0, role = PlayerRole.SEER)),
        )

        // Models the kick/sweep refund path firing after a concurrent game
        // start bound the row (both read the room as WAITING before the start
        // committed): bound rows belong to game-end settlement — a mid-game
        // refund here would both strip the paid immunity from the kill
        // computation and race the settle paths.
        perkService.refundActiveForUser(roomId, host)

        val row = perkActivationRepository.findByGameId(gameId).single()
        assertThat(row.status).isEqualTo(PerkActivationStatus.ACTIVE)
        assertThat(row.gameId).isEqualTo(gameId)
        assertThat(walletService.balance(host)).isEqualTo(70) // still charged
        assertThat(
            creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host)
                .filter { it.type == CreditTxType.REFUND },
        ).isEmpty()
    }

    @Test
    fun `markNight1Triggered is idempotent - second call keeps the first timestamp, one CONSUME, no refund`() {
        ensurePerk()
        val host = newUser("h")
        val roomId = newRoom(host)
        fund(host, 100)
        perkService.activate(host, roomId, PERK_NIGHT1_IMMUNITY)

        val gameId = 995_000 + seq
        perkService.onGameStart(
            roomId, gameId,
            listOf(com.werewolf.model.GamePlayer(gameId = gameId, userId = host, seatIndex = 0, role = PlayerRole.SEER)),
        )

        perkService.markNight1Triggered(gameId, setOf(host))
        val first = perkActivationRepository.findByGameId(gameId).single().triggeredAt
        assertThat(first).isNotNull()

        // Let CURRENT_TIMESTAMP move on so a buggy re-mark would be visible.
        Thread.sleep(50)
        perkService.markNight1Triggered(gameId, setOf(host))
        val second = perkActivationRepository.findByGameId(gameId).single().triggeredAt
        assertThat(second).isEqualTo(first)

        // Settlement still consumes exactly once with no refund row.
        perkSettlementService.settleForGame(gameId, cancelled = false)
        assertThat(perkActivationRepository.findByGameId(gameId).single().status)
            .isEqualTo(PerkActivationStatus.CONSUMED)
        assertThat(walletService.balance(host)).isEqualTo(70)
        assertThat(
            creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host)
                .filter { it.type == CreditTxType.REFUND },
        ).isEmpty()
    }

    @Test
    fun `concurrent activations - exactly one wins, loser is not charged`() {
        ensurePerk()
        val a = newUser("a")
        val b = newUser("b")
        val roomId = newRoom(a)
        join(roomId, b)
        fund(a, 100)
        fund(b, 100)

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val rejections = AtomicInteger(0)
        listOf(a, b).forEach { userId ->
            pool.submit {
                start.await()
                try {
                    perkService.activate(userId, roomId, PERK_NIGHT1_IMMUNITY)
                    wins.incrementAndGet()
                } catch (e: PerkTakenException) {
                    rejections.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue()

        assertThat(wins.get()).isEqualTo(1)
        assertThat(rejections.get()).isEqualTo(1)
        assertThat(perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)).hasSize(1)
        // Exactly one wallet was debited
        assertThat(walletService.balance(a) + walletService.balance(b)).isEqualTo(170)
    }
}
