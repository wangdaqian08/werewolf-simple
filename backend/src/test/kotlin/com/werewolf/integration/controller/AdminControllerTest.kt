package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.CreditTxType
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles

/**
 * /api/admin contract: only the hard-coded admin (guest "Ray") may search
 * players and grant credits; a grant needs the ADMIN_PIN, 1..100 credits and a
 * player that exists, and is journaled as ADMIN_GRANT.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = ["werewolf.admin.pin=4321"])
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository

    companion object {
        const val PIN = "4321"
    }

    @Suppress("UNCHECKED_CAST")
    private fun login(nickname: String): Pair<String, String> {
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java).body!!
        val token = body[FIELD_TOKEN] as String
        val userId = (body[FIELD_USER] as Map<*, *>)[FIELD_USER_ID] as String
        return token to userId
    }

    private fun headers(token: String) = HttpHeaders().also { it.setBearerAuth(token) }

    private fun get(url: String, token: String): ResponseEntity<Any> =
        restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Nothing>(headers(token)), Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun grant(token: String, userId: String, amount: Int, pin: String = PIN): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(
            "/api/admin/grant", HttpMethod.POST,
            HttpEntity(mapOf("userId" to userId, "amount" to amount, "pin" to pin), headers(token)),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any?>>

    @Test
    fun `status says admin only for Ray, matched the way login matches nicknames`() {
        val (rayToken, rayId) = login("  RAY ")
        val (otherToken, _) = login("AdminStatusOther")
        assertThat(rayId).isEqualTo("guest:ray")
        assertThat(get("/api/admin/status", rayToken).body).isEqualTo(mapOf("admin" to true))
        assertThat(get("/api/admin/status", otherToken).body).isEqualTo(mapOf("admin" to false))
    }

    @Test
    fun `non-admin can neither search nor grant`() {
        val (token, selfId) = login("AdminSneaky")
        val search = get("/api/admin/players?q=a", token)
        assertThat(search.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val resp = grant(token, selfId, 50)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(resp.body!!["code"]).isEqualTo("NOT_ADMIN")
        assertThat(walletService.balance(selfId)).isEqualTo(0)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `admin search lists matching existing players with their balance`() {
        val (rayToken, _) = login("Ray")
        val (_, aliceId) = login("AdminSearchAlice")
        walletService.credit(aliceId, 7, CreditTxType.GAME_REWARD)

        val resp = get("/api/admin/players?q=adminsearchal", rayToken)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val rows = resp.body as List<Map<String, Any?>>
        assertThat(rows).containsExactly(mapOf("userId" to aliceId, "nickname" to "AdminSearchAlice", "balance" to 7))
        assertThat(get("/api/admin/players?q=%20", rayToken).body as List<*>).isEmpty()
    }

    @Test
    fun `grant credits an existing player and journals ADMIN_GRANT`() {
        val (rayToken, _) = login("Ray")
        val (_, bobId) = login("AdminGrantBob")

        val resp = grant(rayToken, bobId, 30)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body).isEqualTo(mapOf("userId" to bobId, "nickname" to "AdminGrantBob", "balance" to 30))
        val tx = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(bobId).single()
        assertThat(tx.type).isEqualTo(CreditTxType.ADMIN_GRANT)
        assertThat(tx.amount).isEqualTo(30)
        assertThat(tx.balanceAfter).isEqualTo(30)
    }

    @Test
    fun `amount must be 1 to 100 per grant, with no cap on the resulting balance`() {
        val (rayToken, _) = login("Ray")
        val (_, carolId) = login("AdminGrantCarol")

        for (bad in listOf(0, -5, 101)) {
            val resp = grant(rayToken, carolId, bad)
            assertThat(resp.statusCode).`as`("amount $bad").isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(resp.body!!["code"]).isEqualTo("INVALID_AMOUNT")
        }
        assertThat(grant(rayToken, carolId, 100).body!!["balance"]).isEqualTo(100)
        assertThat(grant(rayToken, carolId, 100).body!!["balance"]).isEqualTo(200)
    }

    @Test
    fun `wrong PIN is refused and changes nothing`() {
        val (rayToken, _) = login("Ray")
        val (_, daveId) = login("AdminGrantDave")

        val resp = grant(rayToken, daveId, 10, pin = "0000")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(resp.body!!["code"]).isEqualTo("WRONG_PIN")
        assertThat(walletService.balance(daveId)).isEqualTo(0)
    }

    @Test
    fun `player must already exist`() {
        val (rayToken, _) = login("Ray")

        val resp = grant(rayToken, "guest:nobody-ever-logged-in", 10)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(resp.body!!["code"]).isEqualTo("PLAYER_NOT_FOUND")
        assertThat(walletService.balance("guest:nobody-ever-logged-in")).isEqualTo(0)
    }

    @Test
    fun `grant is POST only`() {
        val (rayToken, _) = login("Ray")
        assertThat(get("/api/admin/grant", rayToken).statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
    }
}
