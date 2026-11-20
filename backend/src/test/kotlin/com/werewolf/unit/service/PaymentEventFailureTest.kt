package com.werewolf.unit.service

import com.stripe.model.Event
import com.werewolf.config.PaymentProperties
import com.werewolf.service.PaymentEventLog
import com.werewolf.service.PaymentService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

/** Recording a failed event must never hide why the event failed. */
class PaymentEventFailureTest {

    @Test
    fun `when recording the failure also fails, the original exception is rethrown`() {
        val event = Event().also { it.id = "evt_1"; it.type = "customer.created" }
        val eventLog = mock<PaymentEventLog> {
            on { begin(event) } doReturn true
            on { markProcessed(any(), anyOrNull()) } doThrow IllegalStateException("original")
            on { markFailed(any(), any()) } doThrow IllegalStateException("db down")
        }
        val service = PaymentService(
            mock(), mock(), mock(), eventLog, mock(), mock(), mock(), mock(),
            PaymentProperties(
                enabled = true, frontendBaseUrl = "", stripeSecretKey = "",
                sessionExpiryMinutes = 31, reconcileIntervalMs = 600_000, reconcileMinAgeMinutes = 10,
                sweepLookbackDays = 30, statusCheckThrottleSeconds = 5, ordersPageSize = 20,
            ),
        )

        assertThatThrownBy { service.handleEvent(event) }.hasMessage("original")
    }
}
