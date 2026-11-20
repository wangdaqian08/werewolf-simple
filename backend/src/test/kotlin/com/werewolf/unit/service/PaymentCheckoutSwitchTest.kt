package com.werewolf.unit.service

import com.werewolf.config.PaymentProperties
import com.werewolf.service.PaymentService
import com.werewolf.service.PaymentsUnavailableException
import com.werewolf.service.StripeGateway
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

/** Payments switched off: checkout is PaymentsUnavailable (503) even with a key set, and Stripe is never called. */
class PaymentCheckoutSwitchTest {

    @Test
    fun `checkout with payments disabled is PaymentsUnavailable and calls nothing`() {
        val gateway = mock<StripeGateway>()
        val service = PaymentService(
            mock(), mock(), mock(), mock(), mock(), mock(), mock(), gateway,
            PaymentProperties(
                enabled = false, frontendBaseUrl = "", stripeSecretKey = "sk_test_x",
                sessionExpiryMinutes = 30, reconcileIntervalMs = 600_000, reconcileMinAgeMinutes = 10,
                sweepLookbackDays = 30, statusCheckThrottleSeconds = 5,
            ),
        )

        assertThatThrownBy { service.createCheckout("google:u1", "starter") }
            .isInstanceOf(PaymentsUnavailableException::class.java)
        verifyNoInteractions(gateway)
    }
}
