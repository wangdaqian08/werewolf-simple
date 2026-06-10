package com.werewolf.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Stripe + payment configuration. Test-mode keys for now (decision 1);
 * swap STRIPE_SECRET_KEY / STRIPE_WEBHOOK_SECRET env vars to go live.
 */
@ConfigurationProperties(prefix = "app.payment")
data class PaymentProperties(
    /** Base URL the Checkout success/cancel redirects return to. */
    val frontendBaseUrl: String = "https://www.youplay123.online",
    val stripeSecretKey: String = "",
    val stripeWebhookSecret: String = "",
)
