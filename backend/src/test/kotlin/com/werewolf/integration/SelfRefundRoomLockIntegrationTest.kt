package com.werewolf.integration

import com.werewolf.dto.CreateRoomRequest
import com.werewolf.dto.JoinRoomRequest
import com.werewolf.dto.RoomConfigRequest
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.RoomService
import com.werewolf.support.PaymentIntegrationTest
import com.werewolf.util.JwtUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.LocalDateTime
import java.util.UUID

/** While a self refund is in progress the player cannot enter a game (spec D11). */
@PaymentIntegrationTest
class SelfRefundRoomLockIntegrationTest {
    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var jwtUtil: JwtUtil
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var roomService: RoomService

    private fun newUser(): String = "google:lock-${UUID.randomUUID()}".also {
        userRepository.save(User(userId = it, nickname = "P"))
    }

    /** An order whose self refund was requested and is not yet confirmed: the account is locked. */
    private fun lockedOrder(userId: String): PaymentOrder {
        val product = productRepository.findByProductKeyAndActiveTrue("test-pack").orElseGet {
            productRepository.save(Product(productKey = "test-pack", name = "Pack", credits = 330, priceCents = 499))
        }
        return paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(), userId = userId, productId = product.id ?: error("no product"),
                credits = 330, amountCents = 499, currency = "aud",
            ).also {
                it.status = PaymentOrderStatus.REFUNDED
                it.completedAt = LocalDateTime.now().minusDays(1)
                it.selfRefundRequestedAt = LocalDateTime.now()
                it.selfRefundId = "re_pending"
            },
        )
    }

    private fun headers(userId: String) = HttpHeaders().also { it.setBearerAuth(jwtUtil.generateToken(userId, "P", null)) }

    private fun create(userId: String): ResponseEntity<Map<*, *>> =
        restTemplate.postForEntity(TestConstants.CREATE_ROOM_URL, HttpEntity(CreateRoomRequest(RoomConfigRequest()), headers(userId)), Map::class.java)

    private fun join(userId: String, code: String): ResponseEntity<Map<*, *>> =
        restTemplate.postForEntity(TestConstants.JOIN_ROOM_URL, HttpEntity(JoinRoomRequest(code), headers(userId)), Map::class.java)

    @Test
    fun `a locked player cannot create or join a room, and can once the refund is confirmed`() {
        val userId = newUser()
        val order = lockedOrder(userId)
        val code = roomService.createRoom(newUser(), "Host", null, RoomConfigRequest()).roomCode

        assertThat(create(userId).let { it.statusCode to it.body?.get("code") }).isEqualTo(HttpStatus.CONFLICT to "REFUND_PENDING")
        assertThat(join(userId, code).let { it.statusCode to it.body?.get("code") }).isEqualTo(HttpStatus.CONFLICT to "REFUND_PENDING")

        order.selfRefundSettledAt = LocalDateTime.now()
        paymentOrderRepository.save(order)

        assertThat(join(userId, code).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a player with no refund in progress is not affected`() {
        assertThat(create(newUser()).statusCode).isEqualTo(HttpStatus.OK)
    }
}
