package com.werewolf.support

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles

/** The one shared payment test configuration: Stripe is replaced by [FakeStripeGateway]. */
@TestConfiguration
class PaymentTestConfig {
    @Bean
    @Primary
    fun fakeStripeGateway() = FakeStripeGateway()
}

/**
 * Put this on every payment integration test instead of spelling out the annotations,
 * so they all share ONE cached Spring context (each context holds its own DB pool and
 * CI Postgres has only 100 connections).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PaymentTestConfig::class)
annotation class PaymentIntegrationTest
