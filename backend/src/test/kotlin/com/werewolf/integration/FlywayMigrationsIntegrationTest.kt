package com.werewolf.integration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import com.werewolf.config.FlywayV26RepairConfig
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the real Flyway migrations against Postgres.
 *
 * The test and e2e profiles disable Flyway (Hibernate create-drop builds the
 * schema), so no other test executes the migration SQL — a broken migration
 * would only surface at prod startup. Each test migrates a throwaway schema,
 * so it never touches the create-drop tables the other tests share.
 *
 * Needs Postgres: runs when SPRING_DATASOURCE_URL points at one (CI's backend
 * job sets it) and is skipped on the default in-memory H2 setup.
 */
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = "jdbc:postgresql:.*")
class FlywayMigrationsIntegrationTest {

    private val schema = "flyway_migrations_check"
    private val url = System.getenv("SPRING_DATASOURCE_URL")
    private val username = System.getenv("SPRING_DATASOURCE_USERNAME")
    private val password = System.getenv("SPRING_DATASOURCE_PASSWORD")

    private fun flyway(targetVersion: String? = null): Flyway =
        Flyway.configure()
            .dataSource(url, username, password)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .apply { if (targetVersion != null) target(targetVersion) }
            .load()

    private fun <T> sql(block: Connection.() -> T): T =
        DriverManager.getConnection(url, username, password).use { conn ->
            conn.createStatement().use { it.execute("SET search_path TO $schema") }
            conn.block()
        }

    private fun Connection.exec(statement: String) = createStatement().use { it.execute(statement) }

    @BeforeEach
    fun freshSchema() = dropSchema()

    @AfterEach
    fun dropSchema() {
        DriverManager.getConnection(url, username, password).use { it.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    @Test
    fun `every migration applies cleanly to an empty Postgres schema`() {
        val result = flyway().migrate()

        assertThat(result.success).isTrue()
        val info = flyway().info()
        assertThat(info.pending()).isEmpty()
        assertThat(info.applied()).isNotEmpty.allSatisfy { assertThat(it.state.isFailed).isFalse() }
    }

    @Test
    fun `V22 adds votes_sheriff_vote and marks only the sitting sheriff's elimination votes`() {
        flyway(targetVersion = "21").migrate()
        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host'), ('s', 's'), ('p1', 'p1'), ('p2', 'p2')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players) VALUES (1, '101', 'host', 6), (2, '102', 'host', 6)")
            exec(
                """
                 INSERT INTO games (game_id, room_id, host_user_id, phase, sub_phase, day_number, sheriff_user_id) VALUES
                     (1, 1, 'host', 'DAY_VOTING', 'VOTING', 2, 's'),
                     (2, 2, 'host', 'DAY_VOTING', 'VOTING', 1, NULL)
                 """
            )
            exec(
                """
                 INSERT INTO votes (game_id, vote_context, day_number, voter_user_id, target_user_id) VALUES
                     (1, 'ELIMINATION',      2, 's',  'p1'),
                     (1, 'ELIMINATION',      2, 'p1', 'p2'),
                     (1, 'SHERIFF_ELECTION', 1, 's',  's'),
                     (2, 'ELIMINATION',      1, 'p2', 'p1')
                 """
            )
        }

        flyway().migrate()

        sql {
            val column = prepareStatement(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = 'votes' AND column_name = 'sheriff_vote'"
            ).use { st ->
                st.setString(1, schema)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).describedAs("votes.sheriff_vote exists").isTrue()
                    Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                }
            }
            assertThat(column).isEqualTo(Triple("boolean", "NO", "false"))

            val flags = createStatement().use { st ->
                st.executeQuery("SELECT game_id, vote_context, voter_user_id, sheriff_vote FROM votes").use { rs ->
                    generateSequence { if (rs.next()) rs else null }
                        .map { "${it.getInt(1)}/${it.getString(2)}/${it.getString(3)}" to it.getBoolean(4) }
                        .toMap()
                }
            }
            assertThat(flags).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "1/ELIMINATION/s" to true, // the sheriff's own elimination vote
                    "1/ELIMINATION/p1" to false, // a regular player
                    "1/SHERIFF_ELECTION/s" to false, // election votes are never weighted
                    "2/ELIMINATION/p2" to false, // game without a sheriff
                ),
            )
        }
    }

    @Test
    fun `V23 stores WHITE_WOLF_KING roles, the room flag and the taken player`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host'), ('k', 'k'), ('h', 'h')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players, has_white_wolf_king) VALUES (1, '101', 'host', 6, TRUE)")
            exec(
                "INSERT INTO games (game_id, room_id, host_user_id, phase, day_number, self_destruct_user_id, self_destruct_taken_user_id) " +
                        "VALUES (1, 1, 'host', 'DAY_DISCUSSION', 2, 'k', 'h')"
            )
            exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'k', 1, 'WHITE_WOLF_KING'), (1, 'h', 2, 'HUNTER')")
            exec(
                "INSERT INTO elimination_history (game_id, day_number, eliminated_user_id, eliminated_role, hunter_shot_user_id, hunter_shot_role) " +
                        "VALUES (1, 1, 'k', 'WHITE_WOLF_KING', 'h', 'WHITE_WOLF_KING')"
            )
            // Old rooms keep working: the flag defaults to FALSE.
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players) VALUES (2, '102', 'host', 6)")
            val flag = createStatement().use { st ->
                st.executeQuery("SELECT has_white_wolf_king FROM rooms WHERE room_id = 2").use { rs -> rs.next(); rs.getBoolean(1) }
            }
            assertThat(flag).isFalse()
        }
        // The role CHECK still rejects unknown roles.
        assertThatThrownBy { sql { exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'host', 3, 'NOT_A_ROLE')") } }
            .hasMessageContaining("game_players_role_check")
    }

    @Test
    fun `V24 stores the PK re-vote candidates on the game`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players) VALUES (1, '101', 'host', 6)")
            exec(
                "INSERT INTO games (game_id, room_id, host_user_id, phase, day_number, pk_candidate_user_ids) " +
                        "VALUES (1, 1, 'host', 'DAY_VOTING', 1, 'a,b')"
            )
            val pk = createStatement().use { st ->
                st.executeQuery("SELECT pk_candidate_user_ids FROM games WHERE game_id = 1").use { rs -> rs.next(); rs.getString(1) }
            }
            assertThat(pk).isEqualTo("a,b")
        }
    }

    @Test
    fun `V26 stores BLACK_WOLF_KING roles, the room flag and the pending shooter`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host'), ('k', 'k'), ('h', 'h')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players, has_black_wolf_king) VALUES (1, '101', 'host', 9, TRUE)")
            exec(
                "INSERT INTO games (game_id, room_id, host_user_id, phase, day_number, pending_shooter_user_id) " +
                        "VALUES (1, 1, 'host', 'DAY_VOTING', 1, 'h')"
            )
            exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'k', 1, 'BLACK_WOLF_KING'), (1, 'h', 2, 'HUNTER')")
            exec(
                "INSERT INTO elimination_history (game_id, day_number, eliminated_user_id, eliminated_role, hunter_shot_user_id, hunter_shot_role) " +
                        "VALUES (1, 1, 'k', 'BLACK_WOLF_KING', 'h', 'BLACK_WOLF_KING')"
            )
            // Old rooms keep working: the flag defaults to FALSE.
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players) VALUES (2, '102', 'host', 9)")
            val flag = createStatement().use { st ->
                st.executeQuery("SELECT has_black_wolf_king FROM rooms WHERE room_id = 2").use { rs -> rs.next(); rs.getBoolean(1) }
            }
            assertThat(flag).isFalse()
        }
        // The role CHECK still rejects unknown roles.
        assertThatThrownBy { sql { exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'host', 3, 'NOT_A_ROLE')") } }
            .hasMessageContaining("game_players_role_check")
    }

    @Test
    fun `V32 stores KNIGHT roles, the room flag and the duel columns`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host'), ('k', 'k'), ('w', 'w')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players, has_knight) VALUES (1, '101', 'host', 9, TRUE)")
            // Both duel columns are nullable: a game without a duel inserts without them.
            exec("INSERT INTO games (game_id, room_id, host_user_id, phase, day_number) VALUES (1, 1, 'host', 'DAY_DISCUSSION', 1)")
            exec("UPDATE games SET knight_duel_target_user_id = 'w', knight_duel_day = 1 WHERE game_id = 1")
            exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'k', 1, 'KNIGHT'), (1, 'w', 2, 'WEREWOLF')")
            exec(
                "INSERT INTO elimination_history (game_id, day_number, eliminated_user_id, eliminated_role, hunter_shot_user_id, hunter_shot_role) " +
                        "VALUES (1, 1, 'w', 'WEREWOLF', 'k', 'KNIGHT')"
            )
            val nullable = createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name, is_nullable FROM information_schema.columns " +
                            "WHERE table_schema = '$schema' AND table_name = 'games' " +
                            "AND column_name IN ('knight_duel_target_user_id', 'knight_duel_day')"
                ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            }
            assertThat(nullable).containsEntry("knight_duel_target_user_id", "YES").containsEntry("knight_duel_day", "YES")
            // Old rooms keep working: the flag defaults to FALSE.
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players) VALUES (2, '102', 'host', 9)")
            val flag = createStatement().use { st ->
                st.executeQuery("SELECT has_knight FROM rooms WHERE room_id = 2").use { rs -> rs.next(); rs.getBoolean(1) }
            }
            assertThat(flag).isFalse()
        }
        // The role CHECK still rejects unknown roles.
        assertThatThrownBy { sql { exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'host', 3, 'NOT_A_ROLE')") } }
            .hasMessageContaining("game_players_role_check")
    }

    @Test
    fun `V33 stores GRAVEKEEPER roles, the room flag and the night result columns`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host'), ('g', 'g'), ('w', 'w')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players, has_gravekeeper) VALUES (1, '101', 'host', 9, TRUE)")
            exec("INSERT INTO games (game_id, room_id, host_user_id, phase, day_number) VALUES (1, 1, 'host', 'NIGHT', 2)")
            exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'g', 1, 'GRAVEKEEPER'), (1, 'w', 2, 'WEREWOLF')")
            exec(
                "INSERT INTO elimination_history (game_id, day_number, eliminated_user_id, eliminated_role, hunter_shot_user_id, hunter_shot_role) " +
                        "VALUES (1, 1, 'g', 'GRAVEKEEPER', 'g', 'GRAVEKEEPER')"
            )
            exec(
                "INSERT INTO night_phases (game_id, day_number, sub_phase, gravekeeper_result, gravekeeper_exiled_user_id) " +
                        "VALUES (1, 2, 'GRAVEKEEPER_RESULT', 'WOLF', 'w')"
            )
            // Both result columns are nullable: a night without a gravekeeper inserts without them.
            exec("INSERT INTO night_phases (game_id, day_number, sub_phase) VALUES (1, 3, 'WEREWOLF_PICK')")
            val stored = createStatement().use { st ->
                st.executeQuery("SELECT gravekeeper_result, gravekeeper_exiled_user_id FROM night_phases WHERE game_id = 1 AND day_number = 2")
                    .use { rs -> rs.next(); rs.getString(1) to rs.getString(2) }
            }
            assertThat(stored).isEqualTo("WOLF" to "w")
            // Old rooms keep working: the flag defaults to FALSE.
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players) VALUES (2, '102', 'host', 9)")
            val flag = createStatement().use { st ->
                st.executeQuery("SELECT has_gravekeeper FROM rooms WHERE room_id = 2").use { rs -> rs.next(); rs.getBoolean(1) }
            }
            assertThat(flag).isFalse()
        }
        // The role CHECK still rejects unknown roles, and the sub-phase CHECK unknown sub-phases.
        assertThatThrownBy { sql { exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'host', 3, 'NOT_A_ROLE')") } }
            .hasMessageContaining("game_players_role_check")
        assertThatThrownBy { sql { exec("INSERT INTO night_phases (game_id, day_number, sub_phase) VALUES (1, 4, 'NOT_A_SUB_PHASE')") } }
            .hasMessageContaining("night_phases_sub_phase_check")
    }

    @Test
    fun `V38 stores WOLF_BEAUTY, its sub-phase, the room flag and the new columns`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host'), ('b', 'b')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players, has_wolf_beauty) VALUES (1, '101', 'host', 12, TRUE)")
            exec("INSERT INTO games (game_id, room_id, host_user_id, phase, day_number, charm_death_user_id) VALUES (1, 1, 'host', 'NIGHT', 1, 'b')")
            exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'b', 1, 'WOLF_BEAUTY')")
            exec(
                "INSERT INTO elimination_history (game_id, day_number, eliminated_user_id, eliminated_role, hunter_shot_user_id, hunter_shot_role) " +
                        "VALUES (1, 1, 'b', 'WOLF_BEAUTY', 'b', 'WOLF_BEAUTY')"
            )
            exec("INSERT INTO night_phases (game_id, day_number, sub_phase, wolf_beauty_charm_user_id) VALUES (1, 1, 'WOLF_BEAUTY_PICK', 'b')")
            val charm = createStatement().use { st ->
                st.executeQuery("SELECT wolf_beauty_charm_user_id FROM night_phases WHERE game_id = 1").use { rs -> rs.next(); rs.getString(1) }
            }
            assertThat(charm).isEqualTo("b")
        }
    }

    @Test
    fun `V37 stores GARGOYLE roles, its sub-phases, the room flag and the night check columns`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host'), ('g', 'g'), ('w', 'w'), ('x', 'x')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players, has_gargoyle) VALUES (1, '101', 'host', 12, TRUE)")
            exec("INSERT INTO games (game_id, room_id, host_user_id, phase, day_number) VALUES (1, 1, 'host', 'NIGHT', 1)")
            exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'g', 1, 'GARGOYLE'), (1, 'w', 2, 'WEREWOLF')")
            exec(
                "INSERT INTO elimination_history (game_id, day_number, eliminated_user_id, eliminated_role, hunter_shot_user_id, hunter_shot_role) " +
                        "VALUES (1, 1, 'g', 'GARGOYLE', 'g', 'GARGOYLE')"
            )
            exec(
                "INSERT INTO night_phases (game_id, day_number, sub_phase, gargoyle_checked_user_id, gargoyle_result_role) " +
                        "VALUES (1, 1, 'GARGOYLE_PICK', 'w', 'WEREWOLF')"
            )
            exec("INSERT INTO night_phases (game_id, day_number, sub_phase) VALUES (1, 2, 'GARGOYLE_RESULT')")
            val stored = createStatement().use { st ->
                st.executeQuery("SELECT gargoyle_checked_user_id, gargoyle_result_role FROM night_phases WHERE game_id = 1 AND day_number = 1")
                    .use { rs -> rs.next(); rs.getString(1) to rs.getString(2) }
            }
            assertThat(stored).isEqualTo("w" to "WEREWOLF")
            // Old rooms keep working: the flag defaults to FALSE.
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players) VALUES (2, '102', 'host', 9)")
            val flag = createStatement().use { st ->
                st.executeQuery("SELECT has_gargoyle FROM rooms WHERE room_id = 2").use { rs -> rs.next(); rs.getBoolean(1) }
            }
            assertThat(flag).isFalse()
            // Earlier roles and sub-phases still pass.
            exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'host', 3, 'GRAVEKEEPER')")
            exec("INSERT INTO night_phases (game_id, day_number, sub_phase) VALUES (1, 3, 'GRAVEKEEPER_RESULT')")
        }
        assertThatThrownBy { sql { exec("INSERT INTO game_players (game_id, user_id, seat_index, role) VALUES (1, 'x', 4, 'NOT_A_ROLE')") } }
            .hasMessageContaining("game_players_role_check")
        assertThatThrownBy { sql { exec("INSERT INTO night_phases (game_id, day_number, sub_phase) VALUES (1, 4, 'NOT_A_SUB_PHASE')") } }
            .hasMessageContaining("night_phases_sub_phase_check")
    }

    @Test
    fun `V39 adds the Stripe audit columns and existing events read back PROCESSED`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('p', 'p')")
            exec("UPDATE users SET stripe_customer_id = 'cus_1' WHERE user_id = 'p'")
            exec("INSERT INTO payment_events (stripe_event_id, event_type) VALUES ('evt_1', 'x')")
            val st = prepareStatement("SELECT status, attempts FROM payment_events WHERE stripe_event_id = 'evt_1'")
            st.executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString(1)).isEqualTo("PROCESSED")
                assertThat(rs.getInt(2)).isEqualTo(1)
            }
            exec(
                "INSERT INTO payment_orders (order_no, user_id, product_id, credits, amount_cents, currency, status, " +
                    "stripe_charge_id, receipt_url, session_expires_at, review_reason, updated_at) " +
                    "VALUES ('o1', 'p', 1, 10, 100, 'usd', 'COMPLETED', 'ch_1', 'http://r', CURRENT_TIMESTAMP, 'why', CURRENT_TIMESTAMP)"
            )
            val flag = prepareStatement("SELECT needs_review FROM payment_orders WHERE order_no = 'o1'")
            flag.executeQuery().use { rs -> rs.next(); assertThat(rs.getBoolean(1)).isFalse() }
            exec("INSERT INTO payment_events (stripe_event_id, event_type, object_id, payment_order_id, last_error, processed_at) VALUES ('evt_2', 'x', 'cs_1', 1, 'e', CURRENT_TIMESTAMP)")
        }
        assertThatThrownBy { sql { exec("INSERT INTO payment_events (stripe_event_id, event_type, status) VALUES ('evt_3', 'x', 'BAD')") } }
            .hasMessageContaining("payment_events_status_check")
        assertThatThrownBy { sql { exec("INSERT INTO users (user_id, nickname, stripe_customer_id) VALUES ('q', 'q', 'cus_1')") } }
            .hasMessageContaining("duplicate key")
    }

    @Test
    fun `V40 accepts refund and dispute statuses and reversal ledger types, and a wallet can go negative`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('p', 'p')")
            listOf("REFUNDED", "DISPUTED", "DISPUTE_LOST").forEachIndexed { i, status ->
                exec(
                    "INSERT INTO payment_orders (order_no, user_id, product_id, credits, amount_cents, currency, status) " +
                        "VALUES ('o$i', 'p', 1, 10, 100, 'usd', '$status')"
                )
            }
            val flag = prepareStatement("SELECT credits_reversed FROM payment_orders WHERE order_no = 'o0'")
            flag.executeQuery().use { rs -> rs.next(); assertThat(rs.getBoolean(1)).isFalse() }
            exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'PURCHASE_REVERSAL', -60, -50)")
            exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'PURCHASE_REINSTATED', 60, 10)")
            exec("INSERT INTO wallets (user_id, balance) VALUES ('p', -50)")
        }
        assertThatThrownBy {
            sql { exec("INSERT INTO payment_orders (order_no, user_id, product_id, credits, amount_cents, currency, status) VALUES ('ox', 'p', 1, 10, 100, 'usd', 'NOPE')") }
        }.hasMessageContaining("payment_orders_status_check")
    }

    @Test
    fun `V43 adds the self-refund columns`() {
        flyway().migrate()

        val row = sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('p', 'p')")
            exec(
                "INSERT INTO payment_orders (order_no, user_id, product_id, credits, amount_cents, currency, status, " +
                    "self_refund_requested_at, self_refund_id, self_refund_settled_at) VALUES ('o1', 'p', " +
                    "(SELECT id FROM products WHERE product_key = 'value'), 330, 499, 'aud', 'COMPLETED', " +
                    "TIMESTAMP '2026-11-23 10:00:00', 're_1', TIMESTAMP '2026-11-23 10:00:05')",
            )
            createStatement().use { st ->
                st.executeQuery("SELECT self_refund_id, self_refund_requested_at, self_refund_settled_at FROM payment_orders WHERE order_no = 'o1'")
                    .use { rs -> rs.next(); Triple(rs.getString(1), rs.getTimestamp(2).toString(), rs.getTimestamp(3).toString()) }
            }
        }
        assertThat(row).isEqualTo(Triple("re_1", "2026-11-23 10:00:00.0", "2026-11-23 10:00:05.0"))
    }

    @Test
    fun `V42 prices every pack in AUD and retires the starter pack`() {
        flyway().migrate()

        fun strings(query: String): List<String> = sql {
            createStatement().use { st ->
                st.executeQuery(query).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
        assertThat(strings("SELECT DISTINCT currency FROM products")).containsExactly("aud")
        assertThat(strings("SELECT active::text FROM products WHERE product_key = 'starter'")).containsExactly("false")
        assertThat(strings("SELECT product_key FROM products WHERE active ORDER BY sort_order")).containsExactly("value", "big")
    }

    @Test
    fun `V41 accepts DISPUTE_REVERSAL ledger rows and still rejects unknown types`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('p', 'p')")
            exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'DISPUTE_REVERSAL', -60, -60)")
        }
        assertThatThrownBy {
            sql { exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'NOPE', 1, 1)") }
        }.hasMessageContaining("credit_transactions_type_check")
    }

    @Test
    fun `V28 accepts ADMIN_GRANT ledger rows and still rejects unknown types`() {
        flyway().migrate()

        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('p', 'p')")
            exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'ADMIN_GRANT', 50, 50)")
            // Existing types still pass.
            exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'REFUND', 5, 55)")
        }
        assertThatThrownBy {
            sql { exec("INSERT INTO credit_transactions (user_id, type, amount, balance_after) VALUES ('p', 'NOT_A_TYPE', 1, 56)") }
        }.hasMessageContaining("credit_transactions_type_check")
    }

    @Test
    fun `V27 renames the shooter column and frees games stuck in the removed shot sub-phases`() {
        // A database that ran the SHIPPED V26 (#156): chain_shooter_user_id, HUNTER_SHOOT sub-phases still in use.
        flyway(targetVersion = "26").migrate()
        sql {
            exec("INSERT INTO users (user_id, nickname) VALUES ('host', 'host'), ('h', 'h'), ('s', 's'), ('c', 'c'), ('w', 'w'), ('v', 'v')")
            exec("INSERT INTO rooms (room_id, room_code, host_user_id, total_players) VALUES (1, '101', 'host', 9), (2, '102', 'host', 9), (3, '103', 'host', 9), (4, '104', 'host', 9), (5, '105', 'host', 9)")
            exec(
                """
                 INSERT INTO games (game_id, room_id, host_user_id, phase, sub_phase, day_number, sheriff_user_id, chain_shooter_user_id) VALUES
                     (1, 1, 'host', 'DAY_VOTING',     'HUNTER_SHOOT',             2, NULL, NULL), -- hunter h voted out
                     (2, 2, 'host', 'DAY_VOTING',     'HUNTER_SHOOT',             2, 's',  NULL), -- the sheriff s (a hunter) voted out: badge still owed
                     (3, 3, 'host', 'DAY_VOTING',     'HUNTER_SHOOT',             2, NULL, 'c'),  -- a chained shot already pending for c
                     (4, 4, 'host', 'DAY_DISCUSSION', 'HUNTER_SHOOT_NIGHT_DEATH', 3, NULL, NULL), -- wolves killed hunter w at night
                     (5, 5, 'host', 'DAY_VOTING',     'VOTE_RESULT',              2, NULL, NULL)  -- an ordinary game: untouched
                 """
            )
            exec(
                """
                 INSERT INTO game_players (game_id, user_id, seat_index, role, is_alive) VALUES
                     (1, 'h', 1, 'HUNTER', FALSE), (1, 'v', 2, 'VILLAGER', TRUE),
                     (2, 's', 1, 'HUNTER', FALSE), (2, 'v', 2, 'VILLAGER', TRUE),
                     (3, 'h', 1, 'HUNTER', FALSE), (3, 'c', 2, 'BLACK_WOLF_KING', FALSE),
                     (4, 'w', 1, 'HUNTER', FALSE), (4, 'v', 2, 'VILLAGER', TRUE),
                     (5, 'v', 1, 'VILLAGER', FALSE)
                 """
            )
            exec(
                """
                 INSERT INTO elimination_history (game_id, day_number, eliminated_user_id, eliminated_role) VALUES
                     (1, 2, 'h', 'HUNTER'), (2, 2, 's', 'HUNTER'), (3, 2, 'h', 'HUNTER'), (5, 2, 'v', 'VILLAGER')
                 """
            )
            exec("INSERT INTO night_phases (game_id, day_number, wolf_target_user_id) VALUES (4, 3, 'w')")
        }

        flyway().migrate()

        sql {
            val columns = prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'games' " +
                        "AND column_name IN ('chain_shooter_user_id', 'pending_shooter_user_id')"
            ).use { st ->
                st.setString(1, schema)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
            assertThat(columns).containsExactly("pending_shooter_user_id")

            val games = createStatement().use { st ->
                st.executeQuery("SELECT game_id, sub_phase, pending_shooter_user_id FROM games").use { rs ->
                    generateSequence { if (rs.next()) rs else null }
                        .map { it.getInt(1) to (it.getString(2) to it.getString(3)) }
                        .toMap()
                }
            }
            assertThat(games).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    1 to ("VOTE_RESULT" to "h"), // the voted-out hunter shoots from the normal post-vote sub-phase
                    2 to ("BADGE_HANDOVER" to "s"), // a dead sheriff still hands the badge over, and still has the shot
                    3 to ("VOTE_RESULT" to "c"), // the chained shooter keeps its turn
                    4 to ("RESULT_REVEALED" to "w"), // the night victim shoots from the revealed morning
                    5 to ("VOTE_RESULT" to null), // an ordinary game is left alone
                ),
            )
        }
        // The removed sub-phases can never be stored again.
        assertThatThrownBy { sql { exec("UPDATE games SET sub_phase = 'HUNTER_SHOOT' WHERE game_id = 5") } }
            .hasMessageContaining("games_sub_phase_check")
    }

    @Test
    fun `a database that ran the edited V26 of PR 157 still migrates, its checksum is realigned and the rename skipped`() {
        flyway(targetVersion = "26").migrate()
        sql {
            // What #157's edited V26 left behind: the new column name and a different stored checksum.
            exec("ALTER TABLE games RENAME COLUMN chain_shooter_user_id TO pending_shooter_user_id")
            exec("UPDATE flyway_schema_history SET checksum = 1234567 WHERE version = '26'")
        }
        // Plain Flyway refuses, as it did at startup before this fix.
        assertThatThrownBy { flyway().migrate() }.hasMessageContaining("checksum mismatch")

        FlywayV26RepairConfig().migrate(flyway())

        sql {
            val columns = prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'games' " +
                        "AND column_name IN ('chain_shooter_user_id', 'pending_shooter_user_id')"
            ).use { st ->
                st.setString(1, schema)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
            assertThat(columns).containsExactly("pending_shooter_user_id")
        }
        assertThat(flyway().info().pending()).isEmpty()
        assertThat(flyway().validateWithResult().validationSuccessful).isTrue()
    }

    @Test
    fun `the V26 repair does not hide a checksum mismatch on any other migration`() {
        flyway(targetVersion = "26").migrate()
        sql { exec("UPDATE flyway_schema_history SET checksum = 1234567 WHERE version = '25'") }

        assertThatThrownBy { FlywayV26RepairConfig().migrate(flyway()) }.hasMessageContaining("checksum mismatch")
    }
}
