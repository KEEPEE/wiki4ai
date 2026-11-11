package com.wiki4ai.service;

import com.wiki4ai.dto.EncryptedField;
import com.wiki4ai.dto.VaultEntryRequestDTO;
import com.wiki4ai.dto.VaultEntryResponseDTO;
import com.wiki4ai.dto.VaultV2InitKeyRequestDTO;
import com.wiki4ai.dto.VaultV2ParamsResponseDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.VaultV2ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-197 (D2): a v2 client must be able to write v2 rows through the ordinary
 * entry endpoints, and a v1-shaped write must stop being accepted once the vault has
 * a v2 key.
 *
 * <p>These are the two halves of one invariant — "the new client cannot produce a v1
 * row" — and neither half is enough alone. Deleting the v1 encryption function on the
 * client stops <em>this</em> client from writing v1; the guard here stops a stale tab
 * and the MCP server, which never got the update, from doing it instead.
 *
 * <p>H2, so no V29 CHECK and no trigger: these tests cover what the service decides,
 * not what the database enforces. {@code VaultV2SchemaPostgresTests} covers the other
 * half.
 */
@SpringBootTest
@ActiveProfiles("test")
class VaultV2WriteIntegrationTests {

    /** Base64 of three arbitrary bytes — the server never opens these. */
    private static final String V2_BLOB = "AQIDBAoK";

    @Autowired
    private VaultService vaultService;

    @Autowired
    private VaultV2Service vaultV2Service;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private String username;
    private String keyId;

    /**
     * A user of our own, per test.
     *
     * <p>The vault endpoints take a {@code userId} that is not checked against
     * {@code users} — the other vault tests write entries for ids 1 and 2, which are
     * not rows here at all. A v2 write has to be, because the key guard reads the
     * user's v2 columns, so this class cannot borrow those ids. Creating a fresh user
     * per test also means nothing leaks into any other class: the v2 columns live on
     * {@code users}, and the H2 schema is built once per test context rather than once
     * per class, so a vault left initialised here would otherwise turn every v1 create
     * elsewhere into a 409 — which is precisely the behaviour under test.
     */
    @BeforeEach
    void setUp() {
        username = "d2write-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO users (username, email, password, role, language, created_at, updated_at)
                VALUES (?, ?, 'x', 'USER', 'en', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, username, username + "@example.com");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);

        vaultV2Service.initSalt(userId);
        VaultV2ParamsResponseDTO params = vaultV2Service.initKey(
                userId,
                VaultV2InitKeyRequestDTO.builder()
                        .wrappedKey("YWJjZGVmZ2hpams=")
                        .keyId("a".repeat(64))
                        .build());
        keyId = params.getKeyId();
    }

    @AfterEach
    void tearDown() {
        for (VaultEntryResponseDTO entry : vaultService.getEntriesByUser(userId)) {
            try {
                vaultService.deleteEntry(entry.getId(), userId);
            } catch (Exception ignored) {
                // The row may already be gone; nothing here depends on it.
            }
        }
        jdbc.update("DELETE FROM users WHERE username = ?", username);
    }

    private VaultEntryRequestDTO v1Request(String title) {
        return VaultEntryRequestDTO.builder()
                .title(title)
                .passwordEncrypted(EncryptedField.builder().ciphertext("BAUG").iv("Cg==").build())
                .build();
    }

    private VaultEntryRequestDTO v2Request(String title) {
        return VaultEntryRequestDTO.builder()
                .title(title)
                .cryptoVersion(2)
                .keyId(keyId)
                .passwordV2(V2_BLOB)
                .build();
    }

    /**
     * A row as a rolled-back image or the MCP server would have left it: v1 bytes in
     * the v1 columns, no key id.
     *
     * <p>It goes in through JDBC because the service can no longer produce it — once
     * the vault has a v2 key, a v1 create is refused, which is the behaviour the other
     * nested class tests. Anything that wants a v1 row while the vault is v2-managed
     * has to write it the way an old client would.
     */
    private long insertV1Row(String title) {
        jdbc.update("""
                INSERT INTO vault_entries
                        (user_id, title, password_encrypted, iv, crypto_version,
                         entry_uuid, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, title, new byte[]{4, 5, 6}, new byte[]{10},
                java.util.UUID.randomUUID());
        return jdbc.queryForObject(
                "SELECT id FROM vault_entries WHERE user_id = ? AND title = ?",
                Long.class, userId, title);
    }

    @Nested
    @DisplayName("v2 writes")
    class V2Writes {

        @Test
        @DisplayName("a v2 create stores the v2 columns, the key id and version 2")
        void createV2Entry() {
            VaultEntryResponseDTO created = vaultService.createEntry(
                    userId, v2Request("v2 entry"));

            assertThat(created.getCryptoVersion()).isEqualTo(2);
            assertThat(created.getKeyId()).isEqualTo(keyId);
            assertThat(created.getPasswordV2()).isEqualTo(V2_BLOB);
            assertThat(created.getEntryUuid()).isNotBlank();
            // A row created under v2 never had a v1 copy, so it must not pretend to have one.
            assertThat(created.getPasswordEncrypted()).isNull();
            assertThat(created.getUsernameEncrypted()).isNull();
            assertThat(created.getNotesEncrypted()).isNull();
        }

        @Test
        @DisplayName("the client's entryUuid is kept, because its AAD was built from it")
        void keepsClientEntryUuid() {
            String uuid = "3f9a1c22-7777-4b0e-9a11-0d0d0d0d0d0d";
            VaultEntryRequestDTO dto = v2Request("uuid entry");
            dto.setEntryUuid(uuid);

            VaultEntryResponseDTO created = vaultService.createEntry(userId, dto);

            assertThat(created.getEntryUuid()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("a v2 create under a key this vault does not hold is refused")
        void refusesForeignKeyId() {
            VaultEntryRequestDTO dto = v2Request("foreign");
            dto.setKeyId("b".repeat(64));

            assertThatThrownBy(() -> vaultService.createEntry(userId, dto))
                    .isInstanceOf(VaultV2ConflictException.class)
                    .hasMessageContaining("different vault key");

            assertThat(vaultService.getEntriesByUser(userId)).isEmpty();
        }

        @Test
        @DisplayName("a v2 create without a key id is a 400, not a database error")
        void requiresKeyId() {
            VaultEntryRequestDTO dto = v2Request("no key");
            dto.setKeyId(null);

            assertThatThrownBy(() -> vaultService.createEntry(userId, dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Key id");
        }

        @Test
        @DisplayName("a v2 create without a password blob is a 400")
        void requiresPasswordV2() {
            VaultEntryRequestDTO dto = v2Request("no password");
            dto.setPasswordV2(null);

            assertThatThrownBy(() -> vaultService.createEntry(userId, dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("v2 password");
        }

        @Test
        @DisplayName("a v2 field that is not Base64 is refused instead of stored mangled")
        void rejectsNonBase64() {
            VaultEntryRequestDTO dto = v2Request("broken base64");
            dto.setPasswordV2("not base64 !!!");

            assertThatThrownBy(() -> vaultService.createEntry(userId, dto))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("passwordV2");
        }

        @Test
        @DisplayName("a v2 update of a v1 row moves it to v2 and drops the stale v1 copy")
        void upgradesV1Row() {
            long row = insertV1Row("was v1");

            VaultEntryResponseDTO updated = vaultService.updateEntry(row, userId,
                    v2Request("now v2"));

            assertThat(updated.getCryptoVersion()).isEqualTo(2);
            assertThat(updated.getKeyId()).isEqualTo(keyId);
            assertThat(updated.getPasswordV2()).isEqualTo(V2_BLOB);
            // The v1 columns of a v2 row are a rollback copy. This write replaced the
            // values they hold, so keeping them would show the user a password they had
            // already changed after a rollback.
            assertThat(updated.getPasswordEncrypted()).isNull();
            assertThat(updated.getUsernameEncrypted()).isNull();
            assertThat(updated.getNotesEncrypted()).isNull();
        }

        @Test
        @DisplayName("a v2 update of a v2 row keeps working")
        void updatesV2Row() {
            VaultEntryResponseDTO created = vaultService.createEntry(userId, v2Request("v2"));

            VaultEntryRequestDTO edit = v2Request("v2 edited");
            edit.setNotesV2("BwYU");
            VaultEntryResponseDTO updated = vaultService.updateEntry(
                    created.getId(), userId, edit);

            assertThat(updated.getTitle()).isEqualTo("v2 edited");
            assertThat(updated.getNotesV2()).isEqualTo("BwYU");
            assertThat(updated.getCryptoVersion()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("v1 writes after the vault has a v2 key")
    class V1WritesAfterV2Init {

        @Test
        @DisplayName("a v1 create is refused once the vault is v2-managed")
        void refusesV1Create() {
            assertThatThrownBy(() -> vaultService.createEntry(userId, v1Request("stale tab")))
                    .isInstanceOf(VaultV2ConflictException.class)
                    .hasMessageContaining("v1-shaped write");
        }

        @Test
        @DisplayName("a v1 update of a v1 row is refused once the vault is v2-managed")
        void refusesV1Update() {
            // A v1 row has to exist while the vault is already v2-managed, which the
            // service can no longer produce — that is the point of the create guard.
            // So it goes in the way the old client left it: raw v1 bytes in the table.
            long row = insertV1Row("legacy row");

            assertThatThrownBy(() -> vaultService.updateEntry(row, userId,
                    v1Request("old client")))
                    .isInstanceOf(VaultV2ConflictException.class)
                    .hasMessageContaining("v1-shaped write");
        }

        @Test
        @DisplayName("a v1-shaped update of a v2 row still says the row is v2")
        void refusesV1ShapedUpdateOfV2Row() {
            VaultEntryResponseDTO created = vaultService.createEntry(userId, v2Request("row"));

            assertThatThrownBy(() -> vaultService.updateEntry(created.getId(), userId,
                    v1Request("old client")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("crypto v2");
        }

        @Test
        @DisplayName("without a v2 key the v1 path is untouched")
        void v1StillWorksBeforeInit() {
            jdbc.update("""
                    UPDATE users
                       SET vault_v2_salt = NULL,
                           vault_v2_kdf = NULL,
                           vault_v2_wrapped_key = NULL,
                           vault_v2_key_id = NULL
                     WHERE id = ?
                    """, userId);

            VaultEntryResponseDTO created = vaultService.createEntry(userId, v1Request("plain v1"));

            assertThat(created.getCryptoVersion()).isEqualTo(1);
            assertThat(created.getPasswordEncrypted()).isNotNull();
            assertThat(created.getKeyId()).isNull();
        }
    }
}
