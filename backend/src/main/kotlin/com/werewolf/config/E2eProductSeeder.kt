package com.werewolf.config

import com.werewolf.model.Product
import com.werewolf.repository.ProductRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * The e2e profile runs on H2 with Flyway disabled, so the catalog rows that
 * V19__credits_perks_payments.sql seeds in real databases don't exist.
 * Mirror them here so the lobby buy sheet (and the payment E2E) sees the
 * same products as prod.
 */
@Configuration
@Profile("e2e")
class E2eProductSeeder {

    @Bean
    fun seedProducts(products: ProductRepository) = CommandLineRunner {
        if (products.count() == 0L) {
            products.save(Product(productKey = "starter", name = "入门包", credits = 100, bonusCredits = 0, priceCents = 199, sortOrder = 1))
            products.save(Product(productKey = "value", name = "超值包", credits = 300, bonusCredits = 30, priceCents = 499, sortOrder = 2))
            products.save(Product(productKey = "big", name = "豪华包", credits = 700, bonusCredits = 100, priceCents = 999, sortOrder = 3))
        }
    }
}
