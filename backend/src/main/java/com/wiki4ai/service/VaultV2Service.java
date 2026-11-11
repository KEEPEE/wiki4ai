package com.wiki4ai.service;

import com.wiki4ai.dto.VaultV2InitKeyRequestDTO;
import com.wiki4ai.dto.VaultV2MigrateEntryDTO;
import com.wiki4ai.dto.VaultV2MigrateRequestDTO;
import com.wiki4ai.dto.VaultV2MigrateResponseDTO;
import com.wiki4ai.dto.VaultV2ParamsResponseDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.VaultV2ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WIKI4AI-197 (D1): the server side of the vault v2 key hierarchy.
 *
 * <h2>What this class does not do</h2>
 *
 * It does not decrypt anything. The re-encryption from v1 to v2 is done by the client,
 * which is the only party holding the password; the server's job is to accept the
 * result atomically and to refuse it when the row it was computed from is no longer
 * the row in the table. That asymmetry — the client does the work, the server does the
 * deciding — is the whole design, and it is why there is no "migrate the vault" job
 * here: the server has no key with which to do one.
 *
 * <h2>Why a new class rather than a method on VaultService</h2>
 *
 * {@link VaultService} is annotated {@code @Transactional(readOnly = true)} at class
 * level. A new writing method added there without its own annotation would run
 * read-only: on Postgres the write fails, and on H2 — where the tests run — it can
 * quietly not flush. Those are different failures in the two environments, which is
 * the worst possible pairing. This class states the transaction property per method.
 *
 * <h2>Why JdbcTemplate and not JPA</h2>
 *
 * Three reasons, and the third is the one that matters most:
 *
 * <ol>
 *   <li>The init writes are compare-and-set on a column being null
 *       ({@code WHERE vault_v2_salt IS NULL}). That is one statement with the
 *       database deciding, and it cannot be expressed as "read, check, write" without
 *       a window in which two callers both pass the check.</li>
 *   <li>The row lock in {@link #migrate} has to be taken by the same statement that
 *       reads the bytes being compared, or the comparison is against a snapshot taken
 *       before the lock existed.</li>
 *   <li>Loading a {@code VaultEntry} means a later flush can fire its
 *       {@code @PreUpdate}, which rewrites {@code updated_at}. A re-encryption batch
 *       changes nothing the user did; it must not tell them they edited all 122 of
 *       their entries today. Native SQL never loads an entity, so the callback never
 *       runs and the timestamps survive.</li>
 * </ol>
 *
 * <p>One consequence of staying off JPA worth naming: the {@code User} entity the
 * controller loaded to identify the caller is still in the persistence context with
 * its pre-update values. Nothing here dirties it, so nothing flushes those values back
 * over the native write, and every read in this class goes through the same JDBC
 * connection and therefore sees the committed row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class VaultV2Service {

    /**
     * The key-derivation parameters a client needs to reproduce the KEK, stored
     * alongside the salt rather than baked into the client.
     *
     * <p>600 000 iterations is PBKDF2-HMAC-SHA256 at the work factor OWASP recommends
     * for a password that is also the thing standing between an attacker with a
     * database dump and the data — which, after V29, is exactly what this is. v1's
     * 100 000 was chosen when the server also held the salt and the verifier, and it
     * is the weaker of the two gates today.
     */
    static final String KDF_PARAMS = "{\"alg\":\"PBKDF2-HMAC-SHA256\",\"iter\":600000,\"dkLen\":256}";

    /** 256 bits of server entropy for the v2 salt. Not secret, but must be unpredictable. */
    private static final int SALT_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;

    // ==================== READ ====================

    /**
     * The caller's v2 parameters: salt, KDF, wrapped key and key fingerprint.
     *
     * <p>Safe to call before anything has been initialised — it answers
     * {@code initialized = false} with the rest null, which is what tells a client
     * whether it has a DEK to unwrap or a vault to initialise.
     */
    public VaultV2ParamsResponseDTO getParams(Long userId) {
        return readParams(userId);
    }

    /**
     * Whether this user's vault is already under a v2 key.
     *
     * <p>A cheaper, total version of {@link #getParams} for a caller that only has to
     * branch on the answer. Total means it does not throw when the user row is missing:
     * the entry endpoints take a {@code userId} that is not always a row in
     * {@code users} (the vault has no foreign key to it), and a caller that is deciding
     * which format to accept should not fail a plain v1 write because of it.
     */
    public boolean isV2Managed(Long userId) {
        List<Boolean> found = jdbc.query(
                "SELECT vault_v2_wrapped_key FROM users WHERE id = :id",
                new MapSqlParameterSource("id", userId),
                (rs, rowNum) -> {
                    String wrapped = rs.getString(1);
                    return wrapped != null && !wrapped.isBlank();
                });
        return !found.isEmpty() && found.get(0);
    }

    // ==================== WRITE-ONCE INITIALISATION ====================

    /**
     * Hand out the v2 salt, generating it only if the user does not have one.
     *
     * <p>Write-once is the property, not "generate a salt". Two browsers opening an
     * uninitialised vault both call this, and they must be given the same salt —
     * otherwise each derives a different KEK, and whichever DEK commits first makes
     * the other's unrecoverable. The {@code WHERE vault_v2_salt IS NULL} is what
     * enforces that: the loser's update matches no row, and it is handed the winner's
     * salt on the read that follows.
     *
     * <p>The salt is not the protection. v1's salt is public too. What v2 changes is
     * that the KDF input is the raw password rather than the SHA-256 of it that v1
     * sends to this server, so the KEK is no longer recomputable by the party that
     * stores the salt.
     */
    @Transactional
    public VaultV2ParamsResponseDTO initSalt(Long userId) {
        byte[] raw = new byte[SALT_BYTES];
        RANDOM.nextBytes(raw);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("salt", Base64.getEncoder().encodeToString(raw))
                .addValue("kdf", KDF_PARAMS);

        jdbc.update("""
                UPDATE users
                   SET vault_v2_salt = :salt,
                       vault_v2_kdf  = :kdf
                 WHERE id = :id
                   AND vault_v2_salt IS NULL
                   AND vault_v2_kdf  IS NULL
                """, params);

        // The generated salt is deliberately discarded when the update matched nothing.
        // Returning it would hand a second browser a salt that is not the one in the
        // row, and it would not find out until every entry it encrypted failed to
        // decrypt.
        return readParams(userId);
    }

    /**
     * Store the wrapped DEK, once.
     *
     * <p>Two things happen in the one statement, and they have to happen together: the
     * key is stored, and the v1 verifier is replaced with the sentinel that makes the
     * old client stop using it. Doing the second after the first — in a separate
     * request — leaves a window in which a v2 key exists and the v1 verifier still
     * accepts the SHA-256 of the password, which is the state in which a stale tab can
     * still write v1 bytes.
     *
     * <p>Zero rows updated is not an error to hide: it means another caller got there
     * first, and the right answer is to hand this caller the key that is stored so it
     * can unwrap that one instead of discarding its own.
     */
    @Transactional
    public VaultV2ParamsResponseDTO initKey(Long userId, VaultV2InitKeyRequestDTO dto) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("wrappedKey", dto.getWrappedKey())
                .addValue("keyId", dto.getKeyId())
                .addValue("sentinel", VaultMasterPasswordService.V2_MANAGED_SENTINEL);

        int updated = jdbc.update("""
                UPDATE users
                   SET vault_v2_wrapped_key       = :wrappedKey,
                       vault_v2_key_id            = :keyId,
                       vault_master_password_hash = :sentinel
                 WHERE id = :id
                   AND vault_v2_wrapped_key IS NULL
                   AND vault_v2_salt IS NOT NULL
                """, params);

        if (updated == 0) {
            VaultV2ParamsResponseDTO current = readParams(userId);
            if (current.isInitialized()) {
                throw new VaultV2ConflictException(
                        "A vault v2 key is already initialised for this user. "
                                + "Fetch it and unwrap it with the password instead of writing a new one.");
            }
            throw new VaultV2ConflictException(
                    "The vault v2 salt is not initialised; request a salt before storing a key.");
        }

        return readParams(userId);
    }

    // ==================== LAZY RE-ENCRYPTION ====================

    /**
     * Move a batch of rows from v1 to v2, or move none of them.
     *
     * <p>The client did the cryptographic work; this method's job is to decide whether
     * the work is still valid. For every row in the batch that means: it is still the
     * caller's row, it is still v1, it has not already been given a key, and — the one
     * that catches the cases the others miss — its v1 bytes are still the bytes the
     * client said it read.
     *
     * <p>That last check is the compare-and-set, and it is what makes this safe
     * against writers that never heard of v2. A rolled-back image and the MCP server
     * both write v1 and neither touches a version column, so a version check would
     * pass them; the digest of the bytes themselves cannot be satisfied by a writer
     * that changed them.
     *
     * <p>Any single failure refuses the whole batch with 409 and the ids at fault, and
     * nothing in the batch is written. A vault that is half migrated is not merely
     * untidy: the surviving v1 copy is what makes a rollback survivable, so a batch
     * that moved some rows and not others has quietly changed what rolling back means.
     *
     * <p>The v1 columns are deliberately left alone. Dropping them is phase D3, once
     * no user is running a client that can only read v1.
     */
    @Transactional
    public VaultV2MigrateResponseDTO migrate(Long userId, VaultV2MigrateRequestDTO dto) {
        VaultV2ParamsResponseDTO params = readParams(userId);
        if (!params.isInitialized()) {
            throw new VaultV2ConflictException(
                    "The vault v2 key is not initialised for this user; nothing can be re-encrypted yet.");
        }
        if (!params.getKeyId().equals(dto.getKeyId())) {
            throw new VaultV2ConflictException(
                    "This batch was encrypted under a different vault key than the one this vault is initialised with.");
        }

        List<VaultV2MigrateEntryDTO> entries = dto.getEntries();
        List<Long> ids = distinctIds(entries);

        Map<Long, V1Row> rows = lockV1Rows(userId, ids);

        List<Long> conflicts = new ArrayList<>();
        Map<Long, String> reasons = new LinkedHashMap<>();
        for (VaultV2MigrateEntryDTO entry : entries) {
            String reason = checkRow(entry, rows.get(entry.getId()));
            if (reason != null) {
                conflicts.add(entry.getId());
                reasons.put(entry.getId(), reason);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new VaultV2ConflictException(
                    "Vault entries changed since this batch was built; no entry was re-encrypted.",
                    conflicts, reasons);
        }

        for (VaultV2MigrateEntryDTO entry : entries) {
            applyV2(userId, dto.getKeyId(), entry);
        }

        return VaultV2MigrateResponseDTO.builder()
                .migrated(entries.size())
                .ids(List.copyOf(ids))
                .build();
    }

    // ==================== INTERNALS ====================

    /**
     * A row as it looks from the v2 side: enough to decide whether a migration is
     * admissible, and nothing that could be mistaken for plaintext.
     */
    private record V1Row(long id, int cryptoVersion, String keyId, byte[] usernameEncrypted,
                         byte[] passwordEncrypted, byte[] notesEncrypted, byte[] iv) {
    }

    private VaultV2ParamsResponseDTO readParams(Long userId) {
        List<VaultV2ParamsResponseDTO> found = jdbc.query("""
                SELECT vault_v2_salt        AS salt,
                       vault_v2_kdf         AS kdf,
                       vault_v2_wrapped_key AS wrapped_key,
                       vault_v2_key_id      AS key_id
                  FROM users
                 WHERE id = :id
                """, new MapSqlParameterSource("id", userId),
                (rs, rowNum) -> {
                    String wrappedKey = rs.getString("wrapped_key");
                    return VaultV2ParamsResponseDTO.builder()
                            .initialized(wrappedKey != null && !wrappedKey.isBlank())
                            .salt(rs.getString("salt"))
                            .kdf(rs.getString("kdf"))
                            .wrappedKey(wrappedKey)
                            .keyId(rs.getString("key_id"))
                            .build();
                });

        if (found.isEmpty()) {
            throw new BadRequestException("The authenticated user no longer exists.");
        }
        return found.get(0);
    }

    private List<Long> distinctIds(List<VaultV2MigrateEntryDTO> entries) {
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (VaultV2MigrateEntryDTO entry : entries) {
            if (entry.getId() == null) {
                throw new BadRequestException("Every entry in the batch needs an id.");
            }
            if (!seen.add(entry.getId())) {
                // Two rows for one id would both pass the digest check and the second
                // write would match nothing, so the batch would be refused for a
                // reason the client did not cause.
                throw new BadRequestException(
                        "Entry id " + entry.getId() + " appears twice in the batch.");
            }
            ids.add(entry.getId());
        }
        return ids;
    }

    /**
     * Read the caller's rows and lock them in the same statement.
     *
     * <p>Scoped to {@code user_id} here rather than filtered afterwards so a batch
     * cannot lock rows belonging to someone else, and so an id that is not the
     * caller's is indistinguishable from an id that does not exist.
     *
     * <p>Ordered by id so two batches racing over overlapping sets of rows queue up
     * instead of deadlocking.
     */
    private Map<Long, V1Row> lockV1Rows(Long userId, List<Long> ids) {
        List<V1Row> locked = jdbc.query("""
                SELECT id,
                       crypto_version,
                       key_id,
                       username_encrypted,
                       password_encrypted,
                       notes_encrypted,
                       iv
                  FROM vault_entries
                 WHERE id IN (:ids)
                   AND user_id = :userId
                 ORDER BY id
                   FOR UPDATE
                """, new MapSqlParameterSource("ids", ids).addValue("userId", userId),
                (RowMapper<V1Row>) (rs, rowNum) -> new V1Row(
                        rs.getLong("id"),
                        rs.getInt("crypto_version"),
                        rs.getString("key_id"),
                        rs.getBytes("username_encrypted"),
                        rs.getBytes("password_encrypted"),
                        rs.getBytes("notes_encrypted"),
                        rs.getBytes("iv")));

        Map<Long, V1Row> byId = new HashMap<>();
        for (V1Row row : locked) {
            byId.put(row.id(), row);
        }
        return byId;
    }

    /**
     * @return why this entry may not be migrated, or null if it may.
     */
    private String checkRow(VaultV2MigrateEntryDTO entry, V1Row row) {
        if (row == null) {
            return "no such entry, or it does not belong to this user";
        }
        if (row.cryptoVersion() != 1) {
            return "already encrypted under vault crypto v2";
        }
        if (row.keyId() != null) {
            return "already carries a v2 key id";
        }
        String actual = VaultV1Digest.of(row.usernameEncrypted(), row.passwordEncrypted(),
                row.notesEncrypted(), row.iv());
        if (!actual.equalsIgnoreCase(entry.getExpectedV1Digest())) {
            return "the v1 bytes changed since this batch was built";
        }
        return null;
    }

    /**
     * Write one row's v2 half.
     *
     * <p>{@code updated_at} is not in the SET list, and that omission is the point:
     * see the class comment. The {@code crypto_version = 1} in the WHERE is redundant
     * while the row lock is held, and is kept because a write that silently matched no
     * row is worse than a write that throws.
     */
    private void applyV2(Long userId, String keyId, VaultV2MigrateEntryDTO entry) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", entry.getId())
                .addValue("userId", userId)
                .addValue("keyId", keyId)
                .addValue("usernameV2", decode(entry.getUsernameV2(), entry.getId(), "usernameV2"))
                .addValue("passwordV2", decode(entry.getPasswordV2(), entry.getId(), "passwordV2"))
                .addValue("notesV2", decode(entry.getNotesV2(), entry.getId(), "notesV2"));

        int updated = jdbc.update("""
                UPDATE vault_entries
                   SET crypto_version = 2,
                       key_id         = :keyId,
                       username_v2    = :usernameV2,
                       password_v2    = :passwordV2,
                       notes_v2       = :notesV2
                 WHERE id = :id
                   AND user_id = :userId
                   AND crypto_version = 1
                """, params);

        if (updated != 1) {
            throw new VaultV2ConflictException(
                    "Vault entry " + entry.getId() + " could not be re-encrypted; no entry was changed.",
                    Collections.singletonList(entry.getId()),
                    Map.of(entry.getId(), "the row was modified by another writer"));
        }
    }

    private byte[] decode(String base64, Long entryId, String field) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            // Not a conflict: nothing raced this request, the value was never valid
            // base64. Naming the entry and the field is what makes it fixable.
            throw new BadRequestException(
                    "Entry " + entryId + " has a malformed " + field + " value.");
        }
    }
}
