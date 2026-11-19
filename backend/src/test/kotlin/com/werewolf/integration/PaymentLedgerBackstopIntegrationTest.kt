package com.werewolf.integration

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager

/**
 * V39's partial unique index is DB-level only (Hibernate create-drop does not build it),
 * so like FlywayMigrationsIntegrationTest this runs the real migrations on Postgres.
 */
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = "jdbc:postgresql:.*")
class PaymentLedgerBackstopIntegrationTest {

    private val schema = "payment_backstop_check"
    private val url = System.getenv("SPRING_DATASOURCE_URL")
    private val username = System.getenv("SPRING_DATASOURCE_USERNAME")
    private val password = System.getenv("SPRING_DATASOURCE_PASSWORD")

    private fun <T> sql(block: Connection.() -> T): T =
        DriverManager.getConnection(url, username, password).use { conn ->
            conn.createStatement().use { it.execute("SET search_path TO $schema") }
            conn.block()
        }

    private fun Connection.exec(statement: String) = createStatement().use { it.execute(statement) }

    @BeforeEach
    fun migrate() {
        dropSchema()
        Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate()
        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('p', 'p')")
            exec(
                "INSERT INTO payment_orders (order_no, user_id, product_id, credits, amount_cents, currency) " +
                    "VALUES ('o1', 'p', 1, 10, 100, 'usd')"
            )
        }
    }

    @AfterEach
    fun dropSchema() {
        DriverManager.getConnection(url, username, password).use { it.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    @Test
    fun `a second PURCHASE row for the same order is rejected`() {
        sql { exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after, payment_order_id) VALUES ('p', 'PURCHASE', 10, 10, 1)") }
        assertThatThrownBy {
            sql { exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after, payment_order_id) VALUES ('p', 'PURCHASE', 10, 20, 1)") }
        }.hasMessageContaining("ux_ct_purchase_order")
    }

    @Test
    fun `rows without an order are unaffected`() {
        sql {
            exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'GAME_REWARD', 5, 5)")
            exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'GAME_REWARD', 5, 10)")
        }
    }
}
