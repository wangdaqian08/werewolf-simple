package com.werewolf.unit.service

import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrder
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.SelfRefundStore
import com.werewolf.service.SelfRefundBlock
import com.werewolf.service.SelfRefundNotAllowedException
import com.werewolf.service.SelfRefundPolicy
import com.werewolf.service.SelfRefundService
import com.werewolf.service.SelfRefundView
import com.werewolf.service.StripeGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.dao.DuplicateKeyException
import java.util.Optional

/**
 * Two taps on two orders: the V44 index refuses the second open mark (Postgres only, so not
 * reachable on H2). The service answers PENDING and never calls Stripe.
 */
class SelfRefundOpenElsewhereTest {

    @Test
    fun `a second open refund of the same player answers PENDING without calling Stripe`() {
        val orderId = 7
        val order = PaymentOrder(id = orderId, orderNo = "o2", userId = "google:p", productId = 1, credits = 330, amountCents = 499, currency = "aud")
            .also { it.stripePaymentIntentId = "pi_2"; it.stripeChargeId = "ch_2" }
        val repo = mock<PaymentOrderRepository> { on { findByOrderNo("o2") } doReturn Optional.of(order) }
        val policy = mock<SelfRefundPolicy> { on { viewsFor(any(), any()) } doReturn mapOf(orderId to SelfRefundView(true, null)) }
        val store = mock<SelfRefundStore> { on { mark(any(), any(), any()) } doThrow DuplicateKeyException("ux_payment_orders_one_open_self_refund") }
        val gateway = mock<StripeGateway>()
        val service = SelfRefundService(policy, store, repo, gateway, mock(), props())

        assertThatThrownBy { service.request("google:p", "o2") }
            .isInstanceOfSatisfying(SelfRefundNotAllowedException::class.java) { assertThat(it.reason).isEqualTo(SelfRefundBlock.PENDING) }
        verifyNoInteractions(gateway)
    }

    private fun props() = PaymentProperties(
        enabled = true, frontendBaseUrl = "", stripeSecretKey = "sk_test",
        sessionExpiryMinutes = 31, reconcileIntervalMs = 600_000, reconcileMinAgeMinutes = 10,
        sweepLookbackDays = 30, statusCheckThrottleSeconds = 5, ordersPageSize = 20,
        selfRefundDays = 14, selfRefundWindowDays = 30, selfRefundMaxPerWindow = 1,
    )
}
