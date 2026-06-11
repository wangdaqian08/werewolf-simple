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
    /**
     * Feature flag for the Stripe sandbox integration tests
     * (StripeSandboxIntegrationTest). Never true in prod.
     *
     * The JUnit gate reads the STRIPE_SANDBOX_TESTS_ENABLED env var
     * directly (@EnabledIfEnvironmentVariable); this property mirrors it
     * so the switch is visible in config, not buried in a test annotation.
     */
    val sandboxTestsEnabled: Boolean = false,
)
