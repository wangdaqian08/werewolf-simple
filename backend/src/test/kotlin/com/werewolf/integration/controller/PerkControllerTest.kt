package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.Perk
import com.werewolf.model.PerkActivationStatus
import com.werewolf.model.PlayerRole
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.PerkRepository
import com.werewolf.service.PERK_NIGHT1_IMMUNITY
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

/**
 * PerkController endpoint contract: catalog read, activate/withdraw success,
 * and the exception→HTTP-400 mapping (FCFS taken, insufficient credits, perks
 * disabled). FCFS-taken and insufficient return {success:false, error}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PerkControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var perkRepository: PerkRepository
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository

    companion object {
        const val PERKS_URL = "/api/perks"
        const val ACTIVATE_URL = "/api/room/perk/activate"
        const val WITHDRAW_URL = "/api/room/perk/withdraw"
        val DEFAULT_ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)
    }

    /** Flyway is disabled on H2 — seed the perk the migration would insert. */
    @BeforeEach
    fun seedPerk() {
        if (perkRepository.findById(PERK_NIGHT1_IMMUNITY).isEmpty) {
            perkRepository.save(
                Perk(
                    perkCode = PERK_NIGHT1_IMMUNITY,
                    name = "First Night Immunity",
                    description = "If wolves target you on night 1 and you are not a wolf, the kill fails.",
                    priceCredits = 30,
                ),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun login(nickname: String): Pair<String, String> {
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java).body!!
        return (body[FIELD_TOKEN] as String) to ((body[FIELD_USER] as Map<*, *>)[FIELD_USER_ID] as String)
    }

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    @Suppress("UNCHECKED_CAST")
    private fun createRoom(hostToken: String, perksAllowed: Boolean = true): Pair<Int, String> {
        val config = mapOf(
            FIELD_TOTAL_PLAYERS to 6,
            FIELD_ROLES to DEFAULT_ROLES,
            "perksAllowed" to perksAllowed,
        )
        val room = restTemplate.postForEntity(
            CREATE_ROOM_URL, HttpEntity(mapOf(FIELD_CONFIG to config), headers(hostToken)), Map::class.java,
        ).body!! as Map<String, Any?>
        return (room[FIELD_ROOM_ID] as String).toInt() to (room[FIELD_ROOM_CODE] as String)
    }

    private fun join(token: String, roomCode: String) {
        restTemplate.postForEntity(
            JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(token)), Map::class.java,
        )
    }

    private fun activate(token: String, roomId: Int, perkCode: String = PERK_NIGHT1_IMMUNITY) =
        restTemplate.postForEntity(
            ACTIVATE_URL, HttpEntity(mapOf("roomId" to roomId, "perkCode" to perkCode), headers(token)), Map::class.java,
        )

    private fun withdraw(token: String, roomId: Int, perkCode: String = PERK_NIGHT1_IMMUNITY) =
        restTemplate.postForEntity(
            WITHDRAW_URL, HttpEntity(mapOf("roomId" to roomId, "perkCode" to perkCode), headers(token)), Map::class.java,
        )

    @Test
    fun `GET perks returns the active catalog including NIGHT1_IMMUNITY`() {
        val (token, _) = login("PerkCat")
        val resp = restTemplate.exchange(
            PERKS_URL, org.springframework.http.HttpMethod.GET, HttpEntity<Nothing>(headers(token)), List::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val perks = resp.body!! as List<Map<String, Any?>>
        val immunity = perks.first { it["perkCode"] == PERK_NIGHT1_IMMUNITY }
        assertThat(immunity["name"]).isNotNull()
        assertThat(immunity["priceCredits"]).isEqualTo(30)
        assertThat(immunity["description"]).isNotNull()
    }

    @Test
    fun `POST activate returns 200 and creates an ACTIVE activation`() {
        val (token, userId) = login("PerkAct")
        val (roomId, _) = createRoom(token)
        walletService.credit(userId, 50, com.werewolf.model.CreditTxType.GAME_REWARD)

        val resp = activate(token, roomId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.body!! as Map<String, Any?>)["success"]).isEqualTo(true)
        val active = perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
        assertThat(active).hasSize(1)
        assertThat(active.single().userId).isEqualTo(userId)
        assertThat(walletService.balance(userId)).isEqualTo(20)
    }

    @Test
    fun `POST activate returns 400 with success=false when the perk is already taken`() {
        val (hostToken, hostId) = login("PerkHost")
        val (roomId, code) = createRoom(hostToken)
        val (guestToken, guestId) = login("PerkGuest")
        join(guestToken, code)
        walletService.credit(hostId, 50, com.werewolf.model.CreditTxType.GAME_REWARD)
        walletService.credit(guestId, 50, com.werewolf.model.CreditTxType.GAME_REWARD)

        assertThat(activate(hostToken, roomId).statusCode).isEqualTo(HttpStatus.OK)
        val second = activate(guestToken, roomId)

        assertThat(second.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        @Suppress("UNCHECKED_CAST")
        val body = second.body!! as Map<String, Any?>
        assertThat(body["success"]).isEqualTo(false)
        assertThat(body["error"] as String).isNotBlank()
        // The rejected player keeps their credits.
        assertThat(walletService.balance(guestId)).isEqualTo(50)
    }

    @Test
    fun `POST activate returns 400 when the player has insufficient credits`() {
        val (token, userId) = login("PerkBroke")
        val (roomId, _) = createRoom(token)
        walletService.credit(userId, 10, com.werewolf.model.CreditTxType.GAME_REWARD) // perk costs 30

        val resp = activate(token, roomId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.body!! as Map<String, Any?>)["error"] as String).contains("Insufficient")
    }

    @Test
    fun `POST activate returns 400 when perks are disabled for the room`() {
        val (token, userId) = login("PerkOff")
        val (roomId, _) = createRoom(token, perksAllowed = false)
        walletService.credit(userId, 50, com.werewolf.model.CreditTxType.GAME_REWARD)

        val resp = activate(token, roomId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        @Suppress("UNCHECKED_CAST")
        assertThat((resp.body!! as Map<String, Any?>)["error"] as String).isNotBlank()
    }

    @Test
    fun `GET perks-my returns the caller's activations with all 8 fields`() {
        val (tokenA, userIdA) = login("PerkMyA")
        val (tokenB, userIdB) = login("PerkMyB")
        val (roomIdA, _) = createRoom(tokenA)
        val (roomIdB, codeB) = createRoom(tokenB)
        join(tokenA, codeB)
        walletService.credit(userIdA, 100, com.werewolf.model.CreditTxType.GAME_REWARD)
        walletService.credit(userIdB, 100, com.werewolf.model.CreditTxType.GAME_REWARD)

        // A activates in room A; B activates in room B
        activate(tokenA, roomIdA)
        activate(tokenB, roomIdB)

        val resp = restTemplate.exchange(
            "/api/perks/my",
            org.springframework.http.HttpMethod.GET,
            HttpEntity<Nothing>(headers(tokenA)),
            List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val rows = resp.body!! as List<Map<String, Any?>>

        // Only A's activation returned (not B's)
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["perkCode"]).isEqualTo(PERK_NIGHT1_IMMUNITY)
        assertThat(row["perkName"]).isNotNull()
        assertThat(row["status"]).isEqualTo("ACTIVE")
        assertThat(row["pricePaid"]).isEqualTo(30)
        assertThat(row["roomId"]).isNotNull()
        // gameId is null pre-game — key must be present
        assertThat(row.containsKey("gameId")).isTrue()
        assertThat(row["createdAt"]).isNotNull()
        // settledAt is null pre-game — key must be present
        assertThat(row.containsKey("settledAt")).isTrue()
    }

    @Test
    fun `GET perks-my returns activations in most-recent-first order`() {
        val (token, userId) = login("PerkMyOrder")
        walletService.credit(userId, 200, com.werewolf.model.CreditTxType.GAME_REWARD)

        // Seed 3 activations across separate rooms (withdraw after each so we can activate again)
        repeat(3) {
            val (roomId, _) = createRoom(token)
            activate(token, roomId)
            // Each activation is in its own room; no need to withdraw — rooms are independent.
        }

        val resp = restTemplate.exchange(
            "/api/perks/my",
            org.springframework.http.HttpMethod.GET,
            HttpEntity<Nothing>(headers(token)),
            List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val rows = resp.body!! as List<Map<String, Any?>>
        assertThat(rows).hasSize(3)
        // Timestamps must be non-ascending (most-recent first)
        val timestamps = rows.map { it["createdAt"] as String }
        assertThat(timestamps).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    fun `GET perks-my caps the response at 50 rows`() {
        val (token, userId) = login("PerkMyCap")
        // Seed straight through the repository — the cap is a read-side contract,
        // independent of how the rows were created (FCFS would block 51 buys).
        // Sentinel roomIds (roomId has no FK): real ids start at 1 in the shared
        // schema, and squatting an ACTIVE perk on a real room would poison that
        // room's FCFS exclusivity check for whichever test legitimately owns it.
        repeat(51) {
            perkActivationRepository.save(
                com.werewolf.model.PerkActivation(
                    roomId = 910_000 + it, userId = userId, perkCode = PERK_NIGHT1_IMMUNITY, pricePaid = 30,
                ),
            )
        }

        val resp = restTemplate.exchange(
            "/api/perks/my",
            org.springframework.http.HttpMethod.GET,
            HttpEntity<Nothing>(headers(token)),
            List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!).hasSize(50)
    }

    @Test
    fun `GET perks-my returns 200 and an empty list when the caller has no activations`() {
        val (token, _) = login("PerkMyEmpty")

        val resp = restTemplate.exchange(
            "/api/perks/my",
            org.springframework.http.HttpMethod.GET,
            HttpEntity<Nothing>(headers(token)),
            List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!).isEmpty()
    }

    @Test
    fun `GET perks-my for a guest-prefixed userId returns only that guest's activations`() {
        // /api/user/login mints guest JWTs ("guest:<nickname>") — the same way
        // every other test in this file authenticates.
        val (token, userId) = login("PerkMyGuest")
        val (_, otherId) = login("PerkMyGuestOther")
        assertThat(userId).startsWith("guest:")

        // Sentinel roomIds (no FK) — never squat an ACTIVE perk on a real room.
        perkActivationRepository.save(
            com.werewolf.model.PerkActivation(
                roomId = 920_000, userId = userId, perkCode = PERK_NIGHT1_IMMUNITY, pricePaid = 30,
            ),
        )
        perkActivationRepository.save(
            com.werewolf.model.PerkActivation(
                roomId = 920_001, userId = otherId, perkCode = PERK_NIGHT1_IMMUNITY, pricePaid = 30,
            ),
        )

        val resp = restTemplate.exchange(
            "/api/perks/my",
            org.springframework.http.HttpMethod.GET,
            HttpEntity<Nothing>(headers(token)),
            List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val rows = resp.body!! as List<Map<String, Any?>>
        // Scoped to the caller: only the one row seeded for this guest.
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["perkCode"]).isEqualTo(PERK_NIGHT1_IMMUNITY)
    }

    @Test
    fun `GET perks-my without token is rejected with 401 or 403`() {
        val resp = restTemplate.getForEntity("/api/perks/my", Map::class.java)
        assertThat(resp.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    @Test
    fun `POST withdraw returns 200 and refunds the activation`() {
        val (token, userId) = login("PerkWd")
        val (roomId, _) = createRoom(token)
        walletService.credit(userId, 50, com.werewolf.model.CreditTxType.GAME_REWARD)
        activate(token, roomId)

        val resp = withdraw(token, roomId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(walletService.balance(userId)).isEqualTo(50) // fully refunded
        assertThat(perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)).isEmpty()
    }

}
