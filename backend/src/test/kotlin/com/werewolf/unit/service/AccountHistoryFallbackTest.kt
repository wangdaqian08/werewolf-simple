package com.werewolf.unit.service

import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PerkActivation
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.PerkRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.PaymentChargeService
import com.werewolf.service.PaymentEventLog
import com.werewolf.service.PaymentFulfillmentService
import com.werewolf.service.PaymentOrderWriter
import com.werewolf.service.PaymentService
import com.werewolf.service.PerkService
import com.werewolf.service.StripeGateway
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.data.domain.SliceImpl
import com.werewolf.service.StompPublisher
import org.mockito.quality.Strictness

/**
 * Name-resolution fallbacks for the account-page history endpoints.
 *
 * The DB foreign keys (perk_activations.perk_code → perks, payment_orders
 * .product_id → products) make orphan rows impossible in practice — the
 * fallbacks are defense-in-depth, so they are covered at the unit level
 * with mocked repositories. (Controller-level seeding would require a
 * vendor-specific FK bypass: H2's SET REFERENTIAL_INTEGRITY broke CI's
 * Postgres run.)
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountHistoryFallbackTest {

    @Mock lateinit var perkRepository: PerkRepository
    @Mock lateinit var perkActivationRepository: PerkActivationRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var walletService: WalletService
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var productRepository: ProductRepository
    @Mock lateinit var paymentOrderRepository: PaymentOrderRepository
    @Mock lateinit var paymentEventLog: PaymentEventLog
    @Mock lateinit var paymentFulfillmentService: PaymentFulfillmentService
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var paymentOrderWriter: PaymentOrderWriter
    @Mock lateinit var paymentChargeService: PaymentChargeService
    @Mock lateinit var stripeGateway: StripeGateway

    @Test
    fun `myActivations falls back to the perkCode when the catalog row is missing`() {
        val service = PerkService(
            perkRepository, perkActivationRepository, roomRepository,
            roomPlayerRepository, walletService, stompPublisher,
        )
        whenever(perkRepository.findAll()).thenReturn(emptyList())
        whenever(perkActivationRepository.findTop50ByUserIdOrderByCreatedAtDesc("google:u1"))
            .thenReturn(
                listOf(PerkActivation(roomId = 1, userId = "google:u1", perkCode = "GHOST_PERK", pricePaid = 30)),
            )

        val rows = service.myActivations("google:u1")

        assertThat(rows).hasSize(1)
        assertThat(rows.first()["perkName"]).isEqualTo("GHOST_PERK")
        assertThat(rows.first()["perkCode"]).isEqualTo("GHOST_PERK")
    }

    @Test
    fun `listOrders falls back to an empty productName when the product row is missing`() {
        val service = PaymentService(
            productRepository, paymentOrderRepository, userRepository, paymentEventLog,
            paymentFulfillmentService, paymentOrderWriter, paymentChargeService, stripeGateway, PaymentProperties(
                enabled = true, frontendBaseUrl = "https://example.test", sessionExpiryMinutes = 30,
                reconcileIntervalMs = 600_000, reconcileMinAgeMinutes = 10, sweepLookbackDays = 30,
                statusCheckThrottleSeconds = 5, ordersPageSize = 20,
            ),
        )
        whenever(productRepository.findAll()).thenReturn(emptyList())
        whenever(paymentOrderRepository.findByUserIdOrderByCreatedAtDescIdDesc(eq("google:u1"), any()))
            .thenReturn(
                SliceImpl(
                    listOf(
                        PaymentOrder(
                            orderNo = "o-1", userId = "google:u1", productId = 999_999,
                            credits = 100, amountCents = 199, currency = "usd",
                        ),
                    ),
                ),
            )

        @Suppress("UNCHECKED_CAST")
        val rows = service.listOrders("google:u1", 0)["orders"] as List<Map<String, Any?>>

        assertThat(rows).hasSize(1)
        assertThat(rows.first()["productName"]).isEqualTo("")
        assertThat(rows.first()["orderNo"]).isEqualTo("o-1")
    }
}
