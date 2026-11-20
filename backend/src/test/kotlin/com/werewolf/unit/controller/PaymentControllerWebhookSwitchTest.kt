package com.werewolf.unit.controller

import com.werewolf.config.PaymentProperties
import com.werewolf.controller.PaymentController
import com.werewolf.service.PaymentService
import com.werewolf.service.StripeWebhookVerifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

/** Payments switched off: the webhook answers 503 (Stripe keeps retrying) and touches nothing. */
class PaymentControllerWebhookSwitchTest {

    private val props = PaymentProperties(
        enabled = false,
        frontendBaseUrl = "",
        sessionExpiryMinutes = 30,
        reconcileIntervalMs = 600_000,
        reconcileMinAgeMinutes = 10,
        sweepLookbackDays = 30,
        statusCheckThrottleSeconds = 5, ordersPageSize = 20,
    )

    @Test
    fun `webhook returns 503 before verifying the signature or recording the event`() {
        val service = mock<PaymentService>()
        val verifier = mock<StripeWebhookVerifier>()

        val resp = PaymentController(service, verifier, props).webhook("""{"id":"evt_x"}""", "t=1,v1=sig")

        assertThat(resp.statusCode.value()).isEqualTo(503)
        verifyNoInteractions(verifier, service) // no handleEvent → no payment_events row
    }
}
