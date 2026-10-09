package com.werewolf.integration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

}
