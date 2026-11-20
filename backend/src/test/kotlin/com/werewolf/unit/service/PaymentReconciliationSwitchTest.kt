package com.werewolf.unit.service

import com.werewolf.config.PaymentProperties
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.service.PaymentChargeService
import com.werewolf.service.PaymentFulfillmentService
import com.werewolf.service.PaymentOrderWriter
import com.werewolf.service.PaymentReconciliationJob
import com.werewolf.service.ReconcileReport
import com.werewolf.service.StripeGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

/** Payments switched off: reconciliation touches neither the DB nor Stripe. */
class PaymentReconciliationSwitchTest {

    @Test
    fun `reconcile with payments disabled does nothing`() {
        val repo = mock<PaymentOrderRepository>()
        val fulfillment = mock<PaymentFulfillmentService>()
        val writer = mock<PaymentOrderWriter>()
        val gateway = mock<StripeGateway>()
        val chargeService = mock<PaymentChargeService>()
        val job = PaymentReconciliationJob(
            PaymentProperties(
                enabled = false, frontendBaseUrl = "", stripeSecretKey = "sk_test_x",
                sessionExpiryMinutes = 31, reconcileIntervalMs = 600_000, reconcileMinAgeMinutes = 10,
                sweepLookbackDays = 30, statusCheckThrottleSeconds = 5,
            ),
            repo, fulfillment, writer, gateway, chargeService,
        )

        assertThat(job.reconcile()).isEqualTo(ReconcileReport(0, 0, 0, 0, 0, 0, 0))
        verifyNoInteractions(repo, fulfillment, writer, gateway, chargeService)
    }
}
