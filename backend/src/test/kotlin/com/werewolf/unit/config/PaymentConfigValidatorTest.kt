package com.werewolf.unit.config

import com.werewolf.config.PaymentConfigValidator
import com.werewolf.config.PaymentProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PaymentConfigValidatorTest {

    private fun props(
        enabled: Boolean = true,
        key: String = "sk_test_x",
        whsec: String = "whsec_x",
        base: String = "https://example.test",
    ) = PaymentProperties(
        enabled = enabled,
        frontendBaseUrl = base,
        stripeSecretKey = key,
        stripeWebhookSecret = whsec,
        sandboxTestsEnabled = false,
        sessionExpiryMinutes = 30,
        reconcileIntervalMs = 600_000,
        reconcileMinAgeMinutes = 10,
        sweepLookbackDays = 30,
        statusCheckThrottleSeconds = 5, ordersPageSize = 20,
    )

    @Test
    fun `disabled with blank values does not throw`() {
        assertThatCode { PaymentConfigValidator(props(false, "", "", "")).validate() }.doesNotThrowAnyException()
    }

    @Test
    fun `enabled with everything set does not throw`() {
        assertThatCode { PaymentConfigValidator(props()).validate() }.doesNotThrowAnyException()
    }

    @Test
    fun `enabled with blank secret key names stripe-secret-key`() {
        assertThatThrownBy { PaymentConfigValidator(props(key = " ")).validate() }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("stripe-secret-key")
    }

    @Test
    fun `enabled with blank webhook secret names stripe-webhook-secret`() {
        assertThatThrownBy { PaymentConfigValidator(props(whsec = "")).validate() }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("stripe-webhook-secret")
    }

    @Test
    fun `enabled with blank base url names frontend-base-url`() {
        assertThatThrownBy { PaymentConfigValidator(props(base = "")).validate() }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("frontend-base-url")
    }
}
