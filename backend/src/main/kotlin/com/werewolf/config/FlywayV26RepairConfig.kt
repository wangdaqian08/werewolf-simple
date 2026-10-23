package com.werewolf.config

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.CoreErrorCode
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * V26 shipped in two versions: #156 published it, then #157 edited it in place
 * (chain_shooter_user_id → pending_shooter_user_id). Every database that ran
 * either copy stores that copy's checksum, so whichever text the repo keeps,
 * the other population fails validation at startup and never reaches V27.
 *
 * The repo keeps the shipped text. This strategy realigns the stored checksum
 * for version 26 only (Flyway's `repair`), then migrates as usual: V27 then
 * brings both populations to the same schema. Any other checksum mismatch
 * still fails startup, as before.
 */
@Configuration
@Profile("!dev-clean")
class FlywayV26RepairConfig {

    private val log = LoggerFactory.getLogger(FlywayV26RepairConfig::class.java)

    @Bean
    fun v26RepairingMigrationStrategy(): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway -> migrate(flyway) }

    fun migrate(flyway: Flyway) {
        // Pending migrations (V27 itself) are "invalid" to validate but fine for migrate.
        val invalid = flyway.validateWithResult().invalidMigrations
            .filterNot { it.errorDetails?.errorCode == CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED }
        val onlyV26Checksum = invalid.isNotEmpty() && invalid.all {
            it.version == "26" && it.errorDetails?.errorCode == CoreErrorCode.CHECKSUM_MISMATCH
        }
        if (onlyV26Checksum) {
            log.warn("V26 checksum differs from the shipped migration (#157 edited it in place); realigning it once with repair")
            flyway.repair()
        }
        flyway.migrate()
    }
}
