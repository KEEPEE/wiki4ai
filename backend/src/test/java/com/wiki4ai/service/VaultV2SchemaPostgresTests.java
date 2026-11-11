package com.wiki4ai.service;

import com.wiki4ai.dto.VaultV2InitKeyRequestDTO;
import com.wiki4ai.dto.VaultV2MigrateEntryDTO;
import com.wiki4ai.dto.VaultV2MigrateRequestDTO;
import com.wiki4ai.dto.VaultV2ParamsResponseDTO;
import com.wiki4ai.exception.VaultV2ConflictException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-197 (D1): V29 and the v2 service, run against the database they will
 * actually meet.
 *
 * <h2>Why this class exists at all</h2>
 *
 * Every other test in this suite runs on H2 with {@code ddl-auto: create-drop} and
 * Flyway switched off ({@code application-test.properties}). So the schema those tests
 * run against is generated from the entity classes, and the migration files are never
 * executed. Concretely, that means the H2 suite cannot see:
 *
 * <ul>
 *   <li>the trigger — it is PL/pgSQL, H2 has no such thing;</li>
 *   <li>the CHECK constraints as written, because Hibernate's generated schema does
 *       not contain them;</li>
 *   <li>the type question. {@code crypto_version} is INTEGER because Hibernate's
 *       {@code ddl-auto: validate} rejects {@code int2} behind an Integer field, and
 *       the backend then refuses to start. A schema built from the entity would agree
 *       with the entity by construction and prove nothing.</li>
 * </ul>
 *
 * <p>So the guard on the part of this story that matters most — the rollback plan
 * depends on the old image surviving above V29 — was, before this class, tested
 * nowhere.
 *
 * <h2>What runs where</h2>
 *
 * <p>{@code disabledWithoutDocker} is not decoration. The GitLab {@code test-backend}
 * job runs {@code mvn -q test} in a plain maven image with no docker service, so
 * there the whole class is skipped and the suite stays green. It runs where a daemon
 * is reachable, which today means the dev host, with the socket mounted and the
 * daemon's API version handed to docker-java:
 *
 * <pre>{@code
 * docker run --rm --network host  *   -v /tmp/vaultD1:/src -w /src -v ~/.m2:/root/.m2  *   -v /var/run/docker.sock:/var/run/docker.sock  *   -e TESTCONTAINERS_RYUK_DISABLED=true  *   maven:3-eclipse-temurin-21 mvn -B test  *   -DargLine=-Dapi.version=1.52 -Dtest=VaultV2SchemaPostgresTests
 * }</pre>
 *
 * <p>{@code -Dapi.version} is needed because docker-java asks for API 1.32 and Docker
 * Engine 29 will not answer below 1.44. Without it the daemon answers 400,
 * Testcontainers decides there is no Docker, and the class skips — which is the right
 * failure mode, but it is a skip and not a pass. A skipped class is not a passing
 * class, and this paragraph is where that is stated rather than hidden.
 *
 * <h2>Two databases, on purpose</h2>
 *
 * <p>The Spring context uses the container's own database, migrated straight to the
 * latest version: that is the state a deployed backend starts in, and it is what
 * {@code ddl-auto: validate} is checked against.
 *
 * <p>The staged-migration checks use a second database in the same container, taken to
 * V28, filled with rows written the way the current image writes them, and only then
 * migrated to V29. That ordering is the point: rows that already exist when the ALTER
 * runs are the rows the rollback plan has to protect, and inserting them afterwards
 * would only ever exercise the DEFAULT clause.
 */
@DisplayName("V29 + VaultV2Service on PostgreSQL")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VaultV2SchemaPostgresTests {

    /**
     * pgvector, not plain postgres: V10 does {@code CREATE EXTENSION IF NOT EXISTS
     * vector}, so a vanilla image fails the migration chain for a reason that has
     * nothing to do with V29. Same tag as the deployment.
     */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VaultV2Service vaultV2Service;

    @DynamicPropertySource
    static void postgresProfile(DynamicPropertyRegistry registry) {
        // Started here rather than left to the Testcontainers extension. JUnit does not
        // guarantee that one extension's beforeAll runs before another's, and Spring's
        // does read these properties — if the container is still down when
        // spring.datasource.url is resolved, getJdbcUrl() throws "mapped port can only
        // be obtained after the container is started" and the context fails to load.
        // start() is idempotent, so the extension starting it again is a no-op.
        POSTGRES.start();

        // application-test.properties points at H2 with Flyway off. Everything the
        // deployed postgres profile does has to be restated here, or this class would
        // quietly run on H2 like the rest of the suite.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    // Ids of rows written into the staged database before V29 ran.
    private static final List<Long> STAGED_IDS = new ArrayList<>();
    private static final List<byte[]> STAGED_PASSWORDS = new ArrayList<>();
    private static final List<byte[]> STAGED_IVS = new ArrayList<>();
    private static final List<String> STAGED_UPDATED_AT = new ArrayList<>();
    private static final List<UUID> STAGED_UUIDS = new ArrayList<>();

    /** True once the staged database has been taken through V28 -> rows -> V29. */
    private static boolean stagedReady;

    // ==================== STAGED MIGRATION ====================

    /**
     * V28, then rows written the way the current image writes them, then V29.
     *
     * <p>Runs on its own JDBC connection to its own database so that nothing about it
     * depends on the Spring context, and so the "before" state is a real V28 schema
     * rather than something Hibernate assembled from entity annotations.
     */
    @BeforeAll
    void migrateTheStagedDatabaseInStages() throws Exception {
        if (stagedReady) {
            return;
        }
        String adminUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/postgres";
        try (Connection admin = open(adminUrl)) {
            try (Statement st = admin.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS vaultv2_staged WITH (FORCE)");
                st.execute("CREATE DATABASE vaultv2_staged");
            }
        }

        String stagedUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/vaultv2_staged";

        Flyway.configure()
                .dataSource(stagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .baselineOnMigrate(true)
                .target("28")
                .load()
                .migrate();

        try (Connection staged = open(stagedUrl); Statement st = staged.createStatement()) {
            st.execute("""
                    INSERT INTO users (username, email, password, role, created_at, updated_at)
                    VALUES ('staged-owner', 'staged@x', 'x', 'USER', now(), now())
                    """);
            // Three rows, inserted exactly as the current image does it: no
            // crypto_version, no entry_uuid, no key_id, no v2 columns. That is the
            // INSERT a rolled-back image will still send, and it has to keep working.
            try (PreparedStatement ps = staged.prepareStatement("""
                    INSERT INTO vault_entries (user_id, title, username_encrypted,
                                              password_encrypted, notes_encrypted,
                                              url, group_path, iv, created_at, updated_at)
                    VALUES ((SELECT id FROM users WHERE username = 'staged-owner'), ?, ?, ?, ?,
                            'https://example.com', '/work', ?, '2024-01-01 00:00:00', ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                // Six rows, not three: the trigger tests and the CHECK tests need
                // rows of their own, because nested classes run in an order JUnit
                // chooses and a promoted row is no longer a v1 row for the next test.
                for (int i = 1; i <= 6; i++) {
                    byte[] password = new byte[]{(byte) (0x10 + i), (byte) (0x20 + i), (byte) (0x30 + i)};
                    byte[] iv = new byte[]{(byte) i, (byte) (i * 2), (byte) (i * 3), (byte) (i * 4)};
                    ps.setString(1, "staged entry " + i);
                    ps.setBytes(2, new byte[]{(byte) i});                 // username_encrypted
                    ps.setBytes(3, password);                              // password_encrypted
                    ps.setBytes(4, new byte[]{(byte) (i + 10)});           // notes_encrypted
                    ps.setBytes(5, iv);                                    // iv
                    // A fixed timestamp, so "the migration did not move updated_at"
                    // is a comparison against a value that cannot drift.
                    ps.setTimestamp(6, Timestamp.valueOf("2024-01-01 00:00:00"));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        STAGED_IDS.add(keys.getLong(1));
                    }
                    STAGED_PASSWORDS.add(password);
                    STAGED_IVS.add(iv);
                    STAGED_UPDATED_AT.add("2024-01-01 00:00:00");
                }
            }
        }

        Flyway.configure()
                .dataSource(stagedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .baselineOnMigrate(true)
                .load()
                .migrate();

        try (Connection staged = open(stagedUrl); PreparedStatement ps = staged.prepareStatement("""
                SELECT id, crypto_version, entry_uuid, key_id, password_encrypted, iv,
                       username_v2, password_v2, notes_v2, updated_at::text
                  FROM vault_entries
                 WHERE user_id = (SELECT id FROM users WHERE username = 'staged-owner')
                 ORDER BY id
                """); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                assertThat(rs.getInt("crypto_version"))
                        .as("crypto_version of pre-existing row %d after V29", rs.getLong("id"))
                        .isEqualTo(1);
                assertThat(rs.getObject("entry_uuid"))
                        .as("entry_uuid of pre-existing row %d is filled by the column default",
                                rs.getLong("id"))
                        .isNotNull();
                assertThat(rs.getString("key_id")).isNull();
                assertThat(rs.getBytes("username_v2")).isNull();
                assertThat(rs.getBytes("password_v2")).isNull();
                assertThat(rs.getBytes("notes_v2")).isNull();
                assertThat(rs.getString("updated_at")).isEqualTo("2024-01-01 00:00:00");

                int index = STAGED_IDS.indexOf(rs.getLong("id"));
                assertThat(rs.getBytes("password_encrypted"))
                        .as("v1 password bytes of a pre-existing row, unchanged by the ALTER")
                        .isEqualTo(STAGED_PASSWORDS.get(index));
                assertThat(rs.getBytes("iv"))
                        .as("v1 iv of a pre-existing row, unchanged by the ALTER")
                        .isEqualTo(STAGED_IVS.get(index));
                STAGED_UUIDS.add((UUID) rs.getObject("entry_uuid"));
            }
        }

        assertThat(STAGED_IDS).as("rows written before V29").hasSize(6);
        assertThat(STAGED_UUIDS).as("one distinct entry_uuid per pre-existing row").hasSize(6);
        assertThat(STAGED_UUIDS.stream().distinct().count())
                .as("entry_uuid values are distinct")
                .isEqualTo(6);

        stagedReady = true;
    }

    private static Connection open(String url) throws Exception {
        return java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** A connection to the staged database, which is the one holding the pre-V29 rows. */
    private Connection staged() throws Exception {
        return open("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/vaultv2_staged");
    }

    @Nested
    @DisplayName("the trigger, which is the only guard that survives an image rollback")
    class TriggerRules {

        /** Move one staged row to v2 so the guard has something to guard. */
        private void promoteToV2(Connection c, long id) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE vault_entries
                       SET crypto_version = 2, key_id = 'k-staged', password_v2 = ?, notes_v2 = NULL,
                           username_v2 = NULL
                     WHERE id = ? AND crypto_version = 1
                    """)) {
                ps.setBytes(1, new byte[]{(byte) 0x99, (byte) 0x98});
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            try (PreparedStatement read = c.prepareStatement(
                    "SELECT crypto_version FROM vault_entries WHERE id = ?")) {
                read.setLong(1, id);
                try (ResultSet rs = read.executeQuery()) {
                    assertThat(rs.next() && rs.getInt(1) == 2)
                            .as("entry %d is a v2 row before the guard is tested", id)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("a v1 write onto a v2 row is refused")
        void refusesV1WriteOntoV2Row() throws Exception {
            try (Connection c = staged()) {
                promoteToV2(c, STAGED_IDS.get(0));
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vault_entries SET password_encrypted = ? WHERE id = ?")) {
                        ps.setBytes(1, new byte[]{(byte) 0xFF});
                        ps.setLong(2, STAGED_IDS.get(0));
                        ps.executeUpdate();
                    }
                }).isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("v1 write onto a v2 row");
            }
        }

        @Test
        @DisplayName("clearing all four v1 columns of a v2 row is allowed, and is how D3 releases them")
        void allowsReleasingV1Columns() throws Exception {
            try (Connection c = staged()) {
                long id = STAGED_IDS.get(1);
                promoteToV2(c, id);
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE vault_entries
                           SET password_encrypted = NULL, username_encrypted = NULL,
                               notes_encrypted = NULL, iv = NULL
                         WHERE id = ?
                        """)) {
                    ps.setLong(1, id);
                    assertThat(ps.executeUpdate()).as("all-NULL is the one v1 write a v2 row accepts").isEqualTo(1);
                }
            }
        }

        @Test
        @DisplayName("a v2 row may still be rewritten under v2")
        void allowsV2OntoV2() throws Exception {
            try (Connection c = staged()) {
                long id = STAGED_IDS.get(2);
                promoteToV2(c, id);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE vault_entries SET notes_v2 = ? WHERE id = ?")) {
                    ps.setBytes(1, new byte[]{(byte) 0x77});
                    ps.setLong(2, id);
                    assertThat(ps.executeUpdate()).isEqualTo(1);
                }
            }
        }

        @Test
        @DisplayName("crypto_version may never go back to 1")
        void refusesDowngrade() throws Exception {
            try (Connection c = staged()) {
                long id = STAGED_IDS.get(3);
                promoteToV2(c, id);
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vault_entries SET crypto_version = 1 WHERE id = ?")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                }).isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("crypto_version downgrade is forbidden");
            }
        }
    }

    @Nested
    @DisplayName("the CHECK constraints, which replace the NOT NULL that had to go")
    class CheckRules {

        @Test
        @DisplayName("a v1 row cannot lose its password ciphertext")
        void v1RowKeepsItsPassword() throws Exception {
            try (Connection c = staged()) {
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vault_entries SET password_encrypted = NULL WHERE id = ?")) {
                        ps.setLong(1, STAGED_IDS.get(4));
                        ps.executeUpdate();
                    }
                }).isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("ck_vault_v1_complete");
            }
        }

        @Test
        @DisplayName("a v2 row cannot exist without the key it was written under")
        void v2RowNeedsItsKey() throws Exception {
            try (Connection c = staged()) {
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE vault_entries
                               SET crypto_version = 2, key_id = NULL, password_v2 = ?
                             WHERE id = ?
                            """)) {
                        ps.setBytes(1, new byte[]{(byte) 0x01});
                        ps.setLong(2, STAGED_IDS.get(5));
                        ps.executeUpdate();
                    }
                }).isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("ck_vault_v2_complete");
            }
        }

        @Test
        @DisplayName("crypto_version is only ever 1 or 2")
        void versionIsBounded() throws Exception {
            try (Connection c = staged()) {
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vault_entries SET crypto_version = 3 WHERE id = ?")) {
                        ps.setLong(1, STAGED_IDS.get(5));
                        ps.executeUpdate();
                    }
                }).isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("ck_vault_crypto_ver");
            }
        }

        @Test
        @DisplayName("an INSERT that never mentions the new columns still lands, which is what a rolled-back image sends")
        void legacyInsertStillWorks() throws Exception {
            try (Connection c = staged(); PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO vault_entries (user_id, title, password_encrypted, iv, created_at, updated_at)
                    VALUES ((SELECT id FROM users WHERE username = 'staged-owner'),
                            'legacy insert', ?, ?, now(), now())
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setBytes(1, new byte[]{(byte) 0x01});
                ps.setBytes(2, new byte[]{(byte) 0x02});
                assertThat(ps.executeUpdate()).isEqualTo(1);
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    long id = keys.getLong(1);
                    try (PreparedStatement read = c.prepareStatement(
                            "SELECT crypto_version, entry_uuid FROM vault_entries WHERE id = ?")) {
                        read.setLong(1, id);
                        try (ResultSet rs = read.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getInt("crypto_version"))
                                    .as("the DEFAULT, not the entity, decides the version here")
                                    .isEqualTo(1);
                            assertThat(rs.getObject("entry_uuid")).isNotNull();
                        }
                    }
                }
            }
        }
    }

    // ==================== SCHEMA AS THE DEPLOYED PROFILE SEES IT ====================

    @Nested
    @DisplayName("the schema the deployed postgres profile validates against")
    class DeployedSchema {

        @Test
        @DisplayName("Flyway reached 29 and every migration succeeded")
        void flywayHeadIsTwentyNine() {
            List<Map<String, Object>> history = jdbc.queryForList(
                    "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1");
            assertThat(history).hasSize(1);
            assertThat(history.get(0).get("version")).isEqualTo("29");
            assertThat(history.get(0).get("success")).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("crypto_version is integer, because validate rejects int2 behind an Integer field")
        void cryptoVersionIsIntegerNotSmallInt() {
            String dataType = jdbc.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                     WHERE table_name = 'vault_entries' AND column_name = 'crypto_version'
                    """, String.class);
            // SMALLINT would come back as "smallint" / "int2" and Hibernate would refuse
            // the entity on startup. H2 cannot show this: its schema is generated from
            // the entity, so it agrees by construction.
            assertThat(dataType).isEqualTo("integer");
        }

        @Test
        @DisplayName("the trigger and the four constraints exist in the migrated database")
        void guardObjectsExist() {
            Integer triggers = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_vault_entries_guard_v2' AND NOT tgisinternal",
                    Integer.class);
            assertThat(triggers).as("trg_vault_entries_guard_v2").isEqualTo(1);

            List<String> constraints = jdbc.queryForList("""
                    SELECT conname FROM pg_constraint
                     WHERE conrelid = 'vault_entries'::regclass
                       AND conname IN ('uq_vault_entry_uuid', 'ck_vault_crypto_ver',
                                       'ck_vault_v1_complete', 'ck_vault_v2_complete')
                     ORDER BY conname
                    """, String.class);
            assertThat(constraints).containsExactly(
                    "ck_vault_crypto_ver", "ck_vault_v1_complete", "ck_vault_v2_complete",
                    "uq_vault_entry_uuid");
        }

        @Test
        @DisplayName("the four v2 columns exist on users")
        void userColumnsExist() {
            List<String> columns = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_name = 'users' AND column_name LIKE 'vault_v2%'
                     ORDER BY column_name
                    """, String.class);
            assertThat(columns).containsExactly(
                    "vault_v2_kdf", "vault_v2_key_id", "vault_v2_salt", "vault_v2_wrapped_key");
        }

        @Test
        @DisplayName("the context booted with ddl-auto validate over the migrated schema")
        void entityAndSchemaAgree() {
            // Reaching this line is most of the assertion: with ddl-auto: validate the
            // EntityManagerFactory is not created at all when an entity and the migrated
            // schema disagree, and the context would have failed to start.
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            assertThat(vaultV2Service).isNotNull();
        }
    }

    // ==================== SERVICE, ON POSTGRES ====================

    /**
     * Shared fixture: one fresh user per test, its own entries, deleted afterwards.
     * Scoped to a username rather than to a table total so nothing here depends on
     * which class the runner happened to execute first.
     */
    private final AtomicLong userSequence = new AtomicLong();

    private String createUser() {
        String username = "v2-pg-" + userSequence.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (username, email, password, role, created_at, updated_at)
                VALUES (?, ?, 'x', 'USER', now(), now())
                """, username, username + "@example.com");
        return username;
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private void dropUser(String username) {
        jdbc.update("DELETE FROM vault_entries WHERE user_id = (SELECT id FROM users WHERE username = ?)", username);
        jdbc.update("DELETE FROM users WHERE username = ?", username);
    }

    /** Insert an entry the way the current image does, with a known updated_at. */
    private long insertV1Entry(long userId, byte[] password, byte[] iv) {
        jdbc.update("""
                INSERT INTO vault_entries (user_id, title, password_encrypted, iv, created_at, updated_at)
                VALUES (?, 'v1 entry', ?, ?, '2024-01-01 00:00:00', '2024-01-01 00:00:00')
                """, userId, password, iv);
        return jdbc.queryForObject("""
                SELECT id FROM vault_entries
                 WHERE user_id = ? AND title = 'v1 entry'
                 ORDER BY id DESC LIMIT 1
                """, Long.class, userId);
    }

    private Map<String, Object> readEntry(long id) {
        return jdbc.queryForMap("SELECT * FROM vault_entries WHERE id = ?", id);
    }

    @Nested
    @DisplayName("init is write-once, because overwriting it loses the vault")
    class WriteOnceInit {

        private String username;

        @BeforeEach
        void setUp() {
            username = createUser();
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            dropUser(username);
        }

        @Test
        @DisplayName("a second init-salt returns the salt that is stored, not a new one")
        void saltIsWriteOnce() {
            VaultV2ParamsResponseDTO first = vaultV2Service.initSalt(userId(username));
            VaultV2ParamsResponseDTO second = vaultV2Service.initSalt(userId(username));

            assertThat(first.getSalt()).isNotBlank();
            assertThat(second.getSalt()).isEqualTo(first.getSalt());
            assertThat(second.getKdf()).isEqualTo(first.getKdf());
            assertThat(second.isInitialized()).isFalse();
        }

        @Test
        @DisplayName("a second init-key conflicts and leaves the stored key untouched")
        void keyIsWriteOnce() {
            long id = userId(username);
            vaultV2Service.initSalt(id);
            vaultV2Service.initKey(id, new VaultV2InitKeyRequestDTO("ZGVrLW9uZQ==", "key-one"));

            assertThatThrownBy(() -> vaultV2Service.initKey(id,
                    new VaultV2InitKeyRequestDTO("ZGVrLXR3bw==", "key-two")))
                    .isInstanceOf(VaultV2ConflictException.class)
                    .hasMessageContaining("already initialised");

            VaultV2ParamsResponseDTO params = vaultV2Service.getParams(id);
            assertThat(params.getWrappedKey()).isEqualTo("ZGVrLW9uZQ==");
            assertThat(params.getKeyId()).isEqualTo("key-one");
            assertThat(params.isInitialized()).isTrue();
        }

        @Test
        @DisplayName("storing a key before asking for a salt conflicts instead of half-initialising")
        void keyNeedsSaltFirst() {
            long id = userId(username);
            assertThatThrownBy(() -> vaultV2Service.initKey(id,
                    new VaultV2InitKeyRequestDTO("ZGVr", "key-x")))
                    .isInstanceOf(VaultV2ConflictException.class)
                    .hasMessageContaining("salt is not initialised");

            assertThat(vaultV2Service.getParams(id).isInitialized()).isFalse();
        }

        @Test
        @DisplayName("init-key replaces the v1 verifier with a sentinel that fails but still reads as set")
        void verifierBecomesSentinel() {
            long id = userId(username);
            jdbc.update("UPDATE users SET vault_master_password_hash = ? WHERE id = ?",
                    "c2FsdA==:aGFzaA==", id);
            vaultV2Service.initSalt(id);
            vaultV2Service.initKey(id, new VaultV2InitKeyRequestDTO("ZGVr", "key-sentinel"));

            String stored = jdbc.queryForObject(
                    "SELECT vault_master_password_hash FROM users WHERE id = ?", String.class, id);
            assertThat(stored).isEqualTo(VaultMasterPasswordService.V2_MANAGED_SENTINEL);
            // Both halves matter: blank would send the old client to the setup screen,
            // and a colon would let the old verifier keep accepting the v1 value.
            assertThat(stored).doesNotContain(":");
            assertThat(jdbc.queryForObject("SELECT length(vault_master_password_hash) FROM users WHERE id = ?",
                    Integer.class, id)).isPositive();
        }
    }

    @Nested
    @DisplayName("migrate is one transaction, or it is nothing")
    class AtomicMigrate {

        private String username;
        private long userId;

        @BeforeEach
        void setUp() {
            username = createUser();
            userId = userId(username);
            vaultV2Service.initSalt(userId);
            vaultV2Service.initKey(userId, new VaultV2InitKeyRequestDTO("ZGVrLWJhdGNo", "key-batch"));
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            dropUser(username);
        }

        private VaultV2MigrateEntryDTO entryFor(long entryId, String digest) {
            return VaultV2MigrateEntryDTO.builder()
                    .id(entryId)
                    .expectedV1Digest(digest)
                    .passwordV2("cHcy")
                    .build();
        }

        private VaultV2MigrateRequestDTO batch(VaultV2MigrateEntryDTO... entries) {
            return VaultV2MigrateRequestDTO.builder()
                    .keyId("key-batch")
                    .entries(List.of(entries))
                    .build();
        }

        @Test
        @DisplayName("one bad digest refuses the whole batch and changes nothing")
        void badDigestRollsBackTheWholeBatch() {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(insertV1Entry(userId, new byte[]{(byte) (i + 1)}, new byte[]{(byte) (i + 20)}));
            }

            List<VaultV2MigrateEntryDTO> entries = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                String digest = VaultV1Digest.of(null, new byte[]{(byte) (i + 1)}, null,
                        new byte[]{(byte) (i + 20)});
                if (i == 3) {
                    digest = "0".repeat(64); // the row that moved since the client read it
                }
                entries.add(entryFor(ids.get(i), digest));
            }

            assertThatThrownBy(() -> vaultV2Service.migrate(userId,
                    VaultV2MigrateRequestDTO.builder().keyId("key-batch").entries(entries).build()))
                    .isInstanceOf(VaultV2ConflictException.class)
                    .satisfies(ex -> {
                        assertThat(((VaultV2ConflictException) ex).getConflictingIds())
                                .containsExactly(ids.get(3));
                        assertThat(((VaultV2ConflictException) ex).getReasons())
                                .containsEntry(ids.get(3), "the v1 bytes changed since this batch was built");
                    });

            for (long id : ids) {
                Map<String, Object> row = readEntry(id);
                assertThat(row.get("crypto_version")).as("entry %d stayed v1", id).isEqualTo(1);
                assertThat(row.get("password_v2")).as("entry %d has no v2 password", id).isNull();
                assertThat(row.get("key_id")).as("entry %d has no key", id).isNull();
                assertThat(row.get("updated_at").toString())
                        .as("entry %d still reports the timestamp it had", id)
                        .startsWith("2024-01-01");
            }
        }

        @Test
        @DisplayName("a clean batch moves every row and leaves updated_at alone")
        void cleanBatchMovesEverything() {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ids.add(insertV1Entry(userId, new byte[]{(byte) (i + 1)}, new byte[]{(byte) (i + 40)}));
            }
            List<VaultV2MigrateEntryDTO> entries = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                entries.add(entryFor(ids.get(i), VaultV1Digest.of(null, new byte[]{(byte) (i + 1)}, null,
                        new byte[]{(byte) (i + 40)})));
            }

            var result = vaultV2Service.migrate(userId, batch(entries.toArray(VaultV2MigrateEntryDTO[]::new)));

            assertThat(result.getMigrated()).isEqualTo(4);
            assertThat(result.getIds()).containsExactlyElementsOf(ids);

            for (long id : ids) {
                Map<String, Object> row = readEntry(id);
                assertThat(row.get("crypto_version")).as("entry %d is v2", id).isEqualTo(2);
                assertThat(row.get("key_id")).as("entry %d records the key", id).isEqualTo("key-batch");
                assertThat(row.get("password_v2")).as("entry %d carries v2 bytes", id).isNotNull();
                // The v1 copy survives: it is what makes a rollback survivable, and D3
                // is the phase that removes it.
                assertThat(row.get("password_encrypted")).as("entry %d keeps its v1 copy", id).isNotNull();
                assertThat(row.get("updated_at").toString())
                        .as("a re-encryption is not an edit the user made (entry %d)", id)
                        .startsWith("2024-01-01");
            }
        }

        @Test
        @DisplayName("a batch encrypted under another key is refused before anything is written")
        void foreignKeyIsRefused() {
            long id = insertV1Entry(userId, new byte[]{(byte) 7}, new byte[]{(byte) 8});
            VaultV2MigrateRequestDTO wrongKey = VaultV2MigrateRequestDTO.builder()
                    .keyId("some-other-key")
                    .entries(List.of(entryFor(id, VaultV1Digest.of(null, new byte[]{(byte) 7}, null,
                            new byte[]{(byte) 8}))))
                    .build();

            assertThatThrownBy(() -> vaultV2Service.migrate(userId, wrongKey))
                    .isInstanceOf(VaultV2ConflictException.class)
                    .hasMessageContaining("different vault key");
            assertThat(readEntry(id).get("crypto_version")).isEqualTo(1);
        }

        @Test
        @DisplayName("re-sending a batch that already committed conflicts on the rows that moved")
        void alreadyMigratedIsAConflict() {
            long id = insertV1Entry(userId, new byte[]{(byte) 9}, new byte[]{(byte) 10});
            String digest = VaultV1Digest.of(null, new byte[]{(byte) 9}, null, new byte[]{(byte) 10});

            vaultV2Service.migrate(userId, batch(entryFor(id, digest)));

            assertThatThrownBy(() -> vaultV2Service.migrate(userId, batch(entryFor(id, digest))))
                    .isInstanceOf(VaultV2ConflictException.class)
                    .satisfies(ex -> assertThat(((VaultV2ConflictException) ex).getReasons())
                            .containsEntry(id, "already encrypted under vault crypto v2"));
        }

        @Test
        @DisplayName("an entry belonging to someone else is refused without saying whether it exists")
        void foreignEntryIsNotAdmissible() {
            String other = createUser();
            try {
                long foreignId = insertV1Entry(userId(other), new byte[]{(byte) 11}, new byte[]{(byte) 12});
                assertThatThrownBy(() -> vaultV2Service.migrate(userId, batch(entryFor(foreignId,
                        VaultV1Digest.of(null, new byte[]{(byte) 11}, null, new byte[]{(byte) 12})))))
                        .isInstanceOf(VaultV2ConflictException.class)
                        .satisfies(ex -> assertThat(((VaultV2ConflictException) ex).getReasons())
                                .containsEntry(foreignId, "no such entry, or it does not belong to this user"));
            } finally {
                dropUser(other);
            }
        }
    }
}
