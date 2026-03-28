package com.werewolf.config

import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Dev-only: drop and recreate the schema on every startup so migration files
 * can be freely edited during development without manual DB cleanup.
 */
@Configuration
@Profile("dev")
class DevFlywayConfig {

    @Bean
    fun flywayMigrationStrategy(): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway: Flyway ->
        flyway.clean()
        flyway.migrate()
    }
}
