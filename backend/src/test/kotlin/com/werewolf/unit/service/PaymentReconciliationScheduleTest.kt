package com.werewolf.unit.service

import com.werewolf.service.PaymentReconciliationJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled

/** The first reconcile runs on the scheduler soon after startup, never on the startup thread (a slow Stripe must not delay boot). */
class PaymentReconciliationScheduleTest {

    @Test
    fun `the first reconcile is scheduled after reconcile-initial-delay-ms and nothing reconciles on a startup event`() {
        val methods = PaymentReconciliationJob::class.java.declaredMethods
        assertThat(methods.filter { it.isAnnotationPresent(EventListener::class.java) }).isEmpty()
        val scheduled = methods.mapNotNull { it.getAnnotation(Scheduled::class.java) }.single()
        assertThat(scheduled.initialDelayString).isEqualTo("\${app.payment.reconcile-initial-delay-ms}")
        assertThat(scheduled.fixedDelayString).isEqualTo("\${app.payment.reconcile-interval-ms}")
    }
}
