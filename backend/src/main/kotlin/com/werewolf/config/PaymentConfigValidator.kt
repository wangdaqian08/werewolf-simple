package com.werewolf.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/** Fails startup when payments are enabled but a required setting is blank. */
@Component
class PaymentConfigValidator(private val props: PaymentProperties) {

    @PostConstruct
    fun validate() {
        if (!props.enabled) return
        check(props.stripeSecretKey.isNotBlank()) { "app.payment.enabled=true but app.payment.stripe-secret-key is blank" }
        check(props.stripeWebhookSecret.isNotBlank()) { "app.payment.enabled=true but app.payment.stripe-webhook-secret is blank" }
        check(props.frontendBaseUrl.isNotBlank()) { "app.payment.enabled=true but app.payment.frontend-base-url is blank" }
    }
}
