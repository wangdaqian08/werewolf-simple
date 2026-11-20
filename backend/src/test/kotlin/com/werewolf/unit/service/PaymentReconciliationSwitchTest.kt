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
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import com.werewolf.service.ChargeNotSettledException
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

/** Payments switched off: reconciliation touches neither the DB nor Stripe. */
@ExtendWith(OutputCaptureExtension::class)
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

    @Test
    fun `an unexpected IllegalStateException from a charge reconcile is warned about, not swallowed as NOT_SETTLED`(output: CapturedOutput) {
        val gateway = mock<StripeGateway>()
        whenever(gateway.listRecentChargeIdsWithRefundsOrDisputes(any())).thenReturn(sequenceOf("ch_boom", "ch_unsettled"))
        val chargeService = mock<PaymentChargeService>()
        whenever(chargeService.reconcile(eq("ch_boom"), any())).thenThrow(IllegalStateException("order not persisted"))
        whenever(chargeService.reconcile(eq("ch_unsettled"), any())).thenThrow(ChargeNotSettledException("not settled"))
        val job = PaymentReconciliationJob(
            PaymentProperties(
                enabled = true, frontendBaseUrl = "", stripeSecretKey = "sk_test_x",
                sessionExpiryMinutes = 31, reconcileIntervalMs = 600_000, reconcileMinAgeMinutes = 10,
                sweepLookbackDays = 30, statusCheckThrottleSeconds = 5,
            ),
            mock<PaymentOrderRepository>(), mock<PaymentFulfillmentService>(), mock<PaymentOrderWriter>(), gateway, chargeService,
        )

        job.reconcile()

        assertThat(output.all).contains("WARN").contains("charge ch_boom failed: order not persisted")
        assertThat(output.all).doesNotContain("charge ch_unsettled failed")
    }
}
