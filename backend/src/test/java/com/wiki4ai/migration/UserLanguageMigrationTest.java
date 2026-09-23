package com.wiki4ai.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-73: verifies the V12 migration SQL against a real H2 database.
 * <p>
 * The app test profile runs with Flyway disabled (schema generated from entities),
 * so this test executes the actual migration file to prove:
 * <ul>
 *   <li>the column is added to an existing {@code users} table;</li>
 *   <li>it is NOT NULL with default 'en';</li>
 *   <li>legacy rows (created before V12) are backfilled with 'en'.</li>
 * </ul>
 */
class UserLanguageMigrationTest {

    private static final String JDBC_URL = "jdbc:h2:mem:v12migration;DB_CLOSE_DELAY=-1";

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        // Pre-V12 users table — exactly the V1 DDL (the state of every existing DB
        // before this migration runs).
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id BIGSERIAL PRIMARY KEY,
                        username VARCHAR(255) NOT NULL UNIQUE,
                        email VARCHAR(255) NOT NULL UNIQUE,
                        password VARCHAR(255) NOT NULL,
                        role VARCHAR(255) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
                        created_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL
                    )""");
            // Legacy account that predates the language column.
            st.execute("""
                    INSERT INTO users (username, email, password, role, created_at, updated_at)
                    VALUES ('legacy', 'legacy@example.com', '$2a$10.hash', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    private String readMigrationSql() throws IOException {
        var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V12__add_user_language.sql");
        assertThat(stream).as("V12 migration file must be on the classpath").isNotNull();
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("V12 adds language VARCHAR(5) NOT NULL DEFAULT 'en' and backfills legacy rows")
    void v12AddsLanguageColumnWithDefault() throws Exception {
        // when — run the real migration file
        try (Statement st = connection.createStatement()) {
            st.execute(readMigrationSql());
        }

        // then — legacy row is backfilled with 'en'
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT language FROM users WHERE username = ?")) {
            ps.setString(1, "legacy");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("language")).isEqualTo("en");
            }
        }

        // and — new rows without an explicit language get the column default
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    INSERT INTO users (username, email, password, role, created_at, updated_at)
                    VALUES ('fresh', 'fresh@example.com', '$2a$10.hash', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT language FROM users WHERE username = ?")) {
            ps.setString(1, "fresh");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("language")).isEqualTo("en");
            }
        }

        // and — the column is NOT NULL (explicit NULL insert is rejected)
        assertThatThrownBy(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (username, email, password, role, language, created_at, updated_at) " +
                            "VALUES ('nulllang', 'null@example.com', '$2a$10.hash', 'USER', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                ps.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("V12 accepts explicit supported values (en/sk)")
    void v12AcceptsExplicitValues() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute(readMigrationSql());
            st.execute("""
                    INSERT INTO users (username, email, password, role, language, created_at, updated_at)
                    VALUES ('skuser', 'sk@example.com', '$2a$10.hash', 'USER', 'sk', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT language FROM users WHERE username = ?")) {
            ps.setString(1, "skuser");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("language")).isEqualTo("sk");
            }
        }
    }
}
