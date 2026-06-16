package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.CreditTxType
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * GET /api/wallet contract: balance + a `recent` ledger list whose rows carry
 * the signed amount, balanceAfter snapshot, type, and note the frontend renders.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WalletControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var walletService: WalletService

    companion object {
        const val WALLET_URL = "/api/wallet"
    }

    @Suppress("UNCHECKED_CAST")
    private fun login(nickname: String): Pair<String, String> {
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java).body!!
        val token = body[FIELD_TOKEN] as String
        val userId = (body[FIELD_USER] as Map<*, *>)[FIELD_USER_ID] as String
        return token to userId
    }

    private fun authHeaders(token: String) = HttpHeaders().also { it.setBearerAuth(token) }

    @Suppress("UNCHECKED_CAST")
    private fun getWallet(token: String) = restTemplate.exchange(
        WALLET_URL, HttpMethod.GET, HttpEntity<Nothing>(authHeaders(token)), Map::class.java,
    )

    @Test
    fun `GET wallet returns balance and recent ledger rows with signed amounts and balanceAfter`() {
        val (token, userId) = login("WalletA")
        walletService.credit(userId, 100, CreditTxType.GAME_REWARD, note = "seed")
        walletService.debit(userId, 30, CreditTxType.PERK_SPEND, note = "perk")

        val resp = getWallet(token)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val body = resp.body!! as Map<String, Any?>
        assertThat(body["balance"]).isEqualTo(70)

        @Suppress("UNCHECKED_CAST")
        val recent = body["recent"] as List<Map<String, Any?>>
        assertThat(recent).hasSize(2)
        // Locate rows by type to avoid relying on same-millisecond ordering.
        val credit = recent.first { it["type"] == "GAME_REWARD" }
        val debit = recent.first { it["type"] == "PERK_SPEND" }
        // Enum serializes to its name (the frontend keys off these strings).
        assertThat(credit["type"]).isEqualTo("GAME_REWARD")
        assertThat(debit["type"]).isEqualTo("PERK_SPEND")
        assertThat(credit["amount"]).isEqualTo(100)
        assertThat(credit["balanceAfter"]).isEqualTo(100)
        assertThat(credit["note"]).isEqualTo("seed")
        assertThat(debit["amount"]).isEqualTo(-30)
        assertThat(debit["balanceAfter"]).isEqualTo(70)
        assertThat(debit["note"]).isEqualTo("perk")
        assertThat(debit["createdAt"]).isNotNull()
    }

    @Test
    fun `GET wallet caps the recent list at 20 rows`() {
        val (token, userId) = login("WalletB")
        repeat(25) { walletService.credit(userId, 1, CreditTxType.GAME_REWARD, note = "tx$it") }

        val resp = getWallet(token)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val recent = (resp.body!! as Map<String, Any?>)["recent"] as List<*>
        assertThat(recent).hasSize(20)
        assertThat((resp.body!! as Map<String, Any?>)["balance"]).isEqualTo(25)
    }

    @Test
    fun `GET wallet without a token is rejected`() {
        val resp = restTemplate.getForEntity(WALLET_URL, Map::class.java)
        assertThat(resp.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }
}
