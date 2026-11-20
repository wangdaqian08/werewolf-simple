package com.werewolf.unit.service

import com.werewolf.service.CheckoutSnapshot
import com.werewolf.service.PaymentFulfillmentService
import com.werewolf.service.PaymentOrderWriter
import com.werewolf.service.StripeGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/** Money taken but the credit cannot be written: always a `[payment-alert]`, whichever caller hit it. */
@ExtendWith(OutputCaptureExtension::class)
class PaymentFulfillmentAlertTest {

    @Test
    fun `a paid session whose credit write fails raises a payment-alert and rethrows`(output: CapturedOutput) {
        val paid = CheckoutSnapshot(
            sessionId = "cs_test_db_down", status = "complete", paymentStatus = "paid",
            orderNo = "order-1", clientReferenceId = "order-1", paymentIntentId = "pi_1",
            chargeId = "ch_1", receiptUrl = null, url = null, expiresAtEpochSec = null,
        )
        val gateway = mock<StripeGateway> { on { retrieveSession("cs_test_db_down") } doReturn paid }
        val writer = mock<PaymentOrderWriter> { on { applyPaid(any()) } doThrow IllegalStateException("db down") }

        assertThatThrownBy { PaymentFulfillmentService(gateway, writer).fulfill("cs_test_db_down", "reconcile") }
            .isInstanceOf(IllegalStateException::class.java).hasMessage("db down")
        assertThat(output.all).contains("[payment-alert] paid session cs_test_db_down could not be credited (source=reconcile)")
    }
}
