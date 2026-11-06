package com.werewolf.integration

import com.werewolf.game.action.GameActionResult
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/** Role-pick card choose/view: charge, switch (refund then debit, atomically), locks, leave-refund. */
@SpringBootTest
@ActiveProfiles("test")
class RolePickServiceIntegrationTest {

    @Autowired lateinit var rolePickService: RolePickService
    @Autowired lateinit var roomService: RoomService
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var perkRepository: PerkRepository
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var perkService: PerkService
    @Autowired lateinit var gameService: GameService
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var perkSettlementService: PerkSettlementService

    private var seq = 0

    private fun ensureRolePickPerks() {
        listOf("ROLE_PICK_WOLF", "ROLE_PICK_GOD", "ROLE_PICK_VILLAGER").forEach { code ->
            if (perkRepository.findById(code).isEmpty)
                perkRepository.save(Perk(perkCode = code, name = code, description = "test", priceCredits = 20))
        }
    }

    private fun newUser(prefix: String): String {
        val id = "guest:$prefix-${++seq}-${System.nanoTime()}"
        userRepository.save(User(userId = id, nickname = prefix))
        return id
    }

    /** 6 seats: 2 wolves + seer/witch/hunter + 1 villager by default. */
    private fun newRoom(hostId: String, perksAllowed: Boolean = true, guard: Boolean = false): Int {
        ensureRolePickPerks()
        val room = roomRepository.save(
            Room(
                roomCode = (100..999).random().toString(),
                hostUserId = hostId,
                totalPlayers = 6,
                hasSeer = true, hasWitch = true, hasHunter = true, hasGuard = guard,
                config = GameConfig(perksAllowed = perksAllowed),
            ),
        )
        val roomId = room.roomId ?: error("room not persisted")
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = hostId, host = true))
        return roomId
    }

    private fun fund(userId: String, amount: Int) {
        walletService.credit(userId, amount, CreditTxType.GAME_REWARD, note = "test-seed")
    }

    private fun rolePickOf(roomId: Int, userId: String) =
        roomPlayerRepository.findByRoomIdAndUserId(roomId, userId).get().rolePick

    private fun rows(roomId: Int, userId: String) =
        perkActivationRepository.findAll().filter { it.roomId == roomId && it.userId == userId }

    @Test
    fun `choosing WOLF debits 20 and myPick shows choice WOLF, frozenCredits 20`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)

        val view = rolePickService.choose(host, roomId, RolePickChoice.WOLF)

        assertThat(walletService.balance(host)).isEqualTo(80)
        assertThat(view.choice).isEqualTo(RolePickChoice.WOLF)
        assertThat(view.frozenCredits).isEqualTo(20)
        assertThat(rolePickService.myPick(host, roomId).choice).isEqualTo(RolePickChoice.WOLF)
        assertThat(view.prices[RolePickChoice.WOLF]).isEqualTo(20)
        assertThat(view.seats[RolePickChoice.WOLF]).isEqualTo(2)
    }

    @Test
    fun `choosing NONE charges nothing and records NONE`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)

        val view = rolePickService.choose(host, roomId, RolePickChoice.NONE)

        assertThat(walletService.balance(host)).isEqualTo(100)
        assertThat(view.choice).isEqualTo(RolePickChoice.NONE)
        assertThat(rolePickOf(roomId, host)).isEqualTo(RolePickChoice.NONE)
        assertThat(rows(roomId, host)).isEmpty()
    }

    @Test
    fun `myPick before any choice has null choice`() {
        val host = newUser("h"); val roomId = newRoom(host)
        assertThat(rolePickService.myPick(host, roomId).choice).isNull()
    }

    @Test
    fun `switching WOLF to GOD refunds 20 then debits 20`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)
        rolePickService.choose(host, roomId, RolePickChoice.WOLF)

        val view = rolePickService.choose(host, roomId, RolePickChoice.GOD)

        assertThat(view.choice).isEqualTo(RolePickChoice.GOD)
        assertThat(rolePickOf(roomId, host)).isEqualTo(RolePickChoice.GOD)
        assertThat(walletService.balance(host)).isEqualTo(80)
        val ledger = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host)
            .map { it.type to it.amount }
        assertThat(ledger).contains(CreditTxType.REFUND to 20, CreditTxType.PERK_SPEND to -20)
        assertThat(ledger.count { it.first == CreditTxType.PERK_SPEND }).isEqualTo(2)
        val active = rows(roomId, host).filter { it.status == PerkActivationStatus.ACTIVE }
        assertThat(active).hasSize(1)
        assertThat(active.first().perkCode).isEqualTo("ROLE_PICK_GOD")
    }

    @Test
    fun `switching WOLF to NONE refunds 20`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)
        rolePickService.choose(host, roomId, RolePickChoice.WOLF)

        val view = rolePickService.choose(host, roomId, RolePickChoice.NONE)

        assertThat(walletService.balance(host)).isEqualTo(100)
        assertThat(view.choice).isEqualTo(RolePickChoice.NONE)
        assertThat(view.frozenCredits).isEqualTo(0)
        assertThat(rolePickOf(roomId, host)).isEqualTo(RolePickChoice.NONE)
    }

    @Test
    fun `same choice again is a no-op`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)
        rolePickService.choose(host, roomId, RolePickChoice.WOLF)
        val before = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host).size

        rolePickService.choose(host, roomId, RolePickChoice.WOLF)

        assertThat(creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(host)).hasSize(before)
        assertThat(walletService.balance(host)).isEqualTo(80)
    }

    @Test
    fun `insufficient credits for the new card keeps the old card`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 25)
        rolePickService.choose(host, roomId, RolePickChoice.WOLF)
        assertThat(walletService.balance(host)).isEqualTo(5)

        // The switch refunds WOLF first (5 + 20 = 25), so GOD must cost more than that to be unaffordable.
        val god = perkRepository.findById("ROLE_PICK_GOD").get()
        perkRepository.save(Perk(perkCode = god.perkCode, name = god.name, description = god.description, priceCredits = 50))
        try {
            assertThatThrownBy { rolePickService.choose(host, roomId, RolePickChoice.GOD) }
                .isInstanceOf(InsufficientCreditsException::class.java)
        } finally {
            perkRepository.save(Perk(perkCode = god.perkCode, name = god.name, description = god.description, priceCredits = 20))
        }

        assertThat(rolePickOf(roomId, host)).isEqualTo(RolePickChoice.WOLF)
        assertThat(walletService.balance(host)).isEqualTo(5)
        val wolf = rows(roomId, host).single { it.perkCode == "ROLE_PICK_WOLF" }
        assertThat(wolf.status).isEqualTo(PerkActivationStatus.ACTIVE)
    }

    @Test
    fun `choose while READY throws RolePickLockedException`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)
        roomPlayerRepository.findByRoomIdAndUserId(roomId, host).get().let {
            it.status = ReadyStatus.READY; roomPlayerRepository.save(it)
        }

        assertThatThrownBy { rolePickService.choose(host, roomId, RolePickChoice.WOLF) }
            .isInstanceOf(RolePickLockedException::class.java)
    }

    @Test
    fun `paid choice for a camp with 0 seats throws RolePickUnavailableException`() {
        val host = newUser("h"); val roomId = newRoom(host, guard = true); fund(host, 100)

        assertThatThrownBy { rolePickService.choose(host, roomId, RolePickChoice.VILLAGER) }
            .isInstanceOf(RolePickUnavailableException::class.java)
        assertThat(walletService.balance(host)).isEqualTo(100)
    }

    @Test
    fun `perks disabled throws PerksDisabledException`() {
        val host = newUser("h"); val roomId = newRoom(host, perksAllowed = false); fund(host, 100)
        assertThatThrownBy { rolePickService.choose(host, roomId, RolePickChoice.WOLF) }
            .isInstanceOf(PerksDisabledException::class.java)
    }

    @Test
    fun `room not WAITING throws RoomNotOpenException`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)
        roomRepository.findById(roomId).get().let { it.status = RoomStatus.IN_GAME; roomRepository.save(it) }
        assertThatThrownBy { rolePickService.choose(host, roomId, RolePickChoice.WOLF) }
            .isInstanceOf(RoomNotOpenException::class.java)
    }

    @Test
    fun `not in room throws PlayerNotInRoomException`() {
        val host = newUser("h"); val roomId = newRoom(host)
        val stranger = newUser("s"); fund(stranger, 100)
        assertThatThrownBy { rolePickService.choose(stranger, roomId, RolePickChoice.WOLF) }
            .isInstanceOf(PlayerNotInRoomException::class.java)
    }

    @Test
    fun `two players may both hold WOLF in one room`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)
        val other = newUser("o"); fund(other, 100)
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = other))

        rolePickService.choose(host, roomId, RolePickChoice.WOLF)
        rolePickService.choose(other, roomId, RolePickChoice.WOLF)

        assertThat(perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
            .count { it.perkCode == "ROLE_PICK_WOLF" }).isEqualTo(2)
    }

    @Test
    fun `leaving the room refunds a held card immediately`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)
        val other = newUser("o"); fund(other, 100)
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = other))
        rolePickService.choose(other, roomId, RolePickChoice.GOD)
        assertThat(walletService.balance(other)).isEqualTo(80)

        roomService.leaveRoom(other, roomId)

        assertThat(walletService.balance(other)).isEqualTo(100)
        assertThat(rows(roomId, other).single().status).isEqualTo(PerkActivationStatus.REFUNDED)
    }

    @Test
    fun `myPick prices omit a card whose perks row is missing`() {
        val host = newUser("h"); val roomId = newRoom(host)
        perkRepository.deleteById("ROLE_PICK_GOD")
        try {
            val prices = rolePickService.myPick(host, roomId).prices
            assertThat(prices).containsOnlyKeys(RolePickChoice.WOLF, RolePickChoice.VILLAGER)
        } finally {
            ensureRolePickPerks()
        }
    }

    @Test
    fun `re-choosing a paid card whose activation was refunded elsewhere charges again`() {
        val host = newUser("h"); val roomId = newRoom(host); fund(host, 100)
        rolePickService.choose(host, roomId, RolePickChoice.WOLF)
        // Something else (e.g. the stale-room sweep) refunds the card but leaves role_pick = WOLF.
        rows(roomId, host).single().let { it.status = PerkActivationStatus.REFUNDED; perkActivationRepository.save(it) }
        walletService.credit(host, 20, CreditTxType.REFUND, note = "sweep")
        assertThat(walletService.balance(host)).isEqualTo(100)
        assertThat(rolePickOf(roomId, host)).isEqualTo(RolePickChoice.WOLF)

        val view = rolePickService.choose(host, roomId, RolePickChoice.WOLF)

        assertThat(view.choice).isEqualTo(RolePickChoice.WOLF)
        assertThat(walletService.balance(host)).isEqualTo(80)
        assertThat(perkService.liveRolePicks(roomId)).containsEntry(host, RolePickChoice.WOLF)
    }

    @Test
    fun `host board edit to zero villagers after a VILLAGER pick - game starts, pick untriggered, settlement refunds 20`() {
        val host = newUser("h"); val roomId = newRoom(host)
        roomPlayerRepository.findByRoomIdAndUserId(roomId, host).get().let { it.seatIndex = 1; roomPlayerRepository.save(it) }
        val guests = (2..6).map { seat ->
            newUser("g").also { g -> roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = g, seatIndex = seat)) }
        }
        val picker = guests.first(); fund(picker, 100)
        rolePickService.choose(picker, roomId, RolePickChoice.VILLAGER)

        // 6 seats = 2 wolves + 4 gods: the VILLAGER camp now has 0 seats.
        roomService.updateBoard(host, roomId, 6, 2,
            listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER, PlayerRole.GUARD))
        rolePickService.choose(host, roomId, RolePickChoice.NONE)
        guests.drop(1).forEach { rolePickService.choose(it, roomId, RolePickChoice.NONE) }
        guests.forEach { roomService.setReady(it, roomId, true) }

        assertThat(gameService.startGame(host, roomId)).isInstanceOf(GameActionResult.Success::class.java)

        val gameId = gameRepository.findAll().single { it.roomId == roomId }.gameId ?: error("no game id")
        val card = rows(roomId, picker).single()
        assertThat(card.gameId).isEqualTo(gameId)
        assertThat(card.triggeredAt).isNull()
        assertThat(walletService.balance(picker)).isEqualTo(80)

        perkSettlementService.settleForGame(gameId, cancelled = false)

        assertThat(rows(roomId, picker).single().status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(walletService.balance(picker)).isEqualTo(100)
    }
}
