package com.werewolf.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Stripe + payment configuration. Test-mode keys for now (decision 1);
 * swap STRIPE_SECRET_KEY / STRIPE_WEBHOOK_SECRET env vars to go live.
 */
@ConfigurationProperties(prefix = "app.payment")
data class PaymentProperties(
    /** Payments switch. When false: checkout and webhook answer 503 and reconciliation is off. */
    val enabled: Boolean,
    /** Base URL the Checkout success/cancel redirects return to (required when enabled). */
    val frontendBaseUrl: String,
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
    /** Checkout Session lifetime in minutes. */
    val sessionExpiryMinutes: Long,
    /** How often the reconciliation job runs, in ms. */
    val reconcileIntervalMs: Long,
    /** Only orders still CREATED after this many minutes are reconciled. */
    val reconcileMinAgeMinutes: Long,
    /** How far back the Stripe sweep looks, in days. */
    val sweepLookbackDays: Long,
    /** Minimum seconds between Stripe status checks per order from the return page. */
    val statusCheckThrottleSeconds: Long,
    /** Orders per page of the player's top-up history. */
    val ordersPageSize: Int,
)
