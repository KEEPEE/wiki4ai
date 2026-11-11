package com.wiki4ai.service;

import com.wiki4ai.dto.EncryptedField;
import com.wiki4ai.dto.EncryptedVaultEntryDTO;
import com.wiki4ai.dto.VaultEntryRequestDTO;
import com.wiki4ai.dto.VaultEntryResponseDTO;
import com.wiki4ai.dto.VaultV2ParamsResponseDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.VaultV2ConflictException;
import com.wiki4ai.model.VaultEntry;
import com.wiki4ai.repository.VaultEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service layer for Vault entries.
 * Works only with encrypted blobs - never decrypts data.
 * All mutating operations validate ownership via userId parameter.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VaultService {

    private final VaultEntryRepository vaultEntryRepository;

    /**
     * WIKI4AI-197 (D2): read for the key guards only. This class still never decrypts
     * anything — it asks one question about the caller's vault (does it have a v2 key,
     * and which one) and refuses writes that contradict the answer.
     */
    private final VaultV2Service vaultV2Service;

    /**
     * Create a new vault entry for the given user.
     */
    @Transactional
    public VaultEntryResponseDTO createEntry(Long userId, VaultEntryRequestDTO dto) {
        validateCreateDTO(dto);

        if (dto.isV2Write()) {
            requireVaultKey(userId, dto.getKeyId());

            VaultEntry entry = VaultEntry.builder()
                    .userId(userId)
                    .title(dto.getTitle())
                    .url(dto.getUrl())
                    .groupPath(dto.getGroupPath())
                    .cryptoVersion(2)
                    .keyId(dto.getKeyId())
                    .entryUuid(parseEntryUuid(dto.getEntryUuid()))
                    .usernameV2(decodeV2Field(dto.getUsernameV2(), "usernameV2"))
                    .passwordV2(decodeV2Field(dto.getPasswordV2(), "passwordV2"))
                    .notesV2(decodeV2Field(dto.getNotesV2(), "notesV2"))
                    .build();

            // The v1 columns stay null. A row created under v2 never had a v1 copy, and
            // ck_vault_v1_complete asks for those columns only of a v1 row.
            return convertToResponseDTO(vaultEntryRepository.save(entry));
        }

        requireV1WritesAllowed(userId);

        byte[] usernameEncrypted = decodeEncryptedField(dto.getUsernameEncrypted());
        byte[] passwordEncrypted = decodeEncryptedField(dto.getPasswordEncrypted());
        byte[] notesEncrypted = decodeEncryptedField(dto.getNotesEncrypted());

        VaultEntry entry = VaultEntry.builder()
                .userId(userId)
                .title(dto.getTitle())
                .usernameEncrypted(usernameEncrypted)
                .passwordEncrypted(passwordEncrypted)
                .notesEncrypted(notesEncrypted)
                .url(dto.getUrl())
                .groupPath(dto.getGroupPath())
                .iv(extractIv(dto.getPasswordEncrypted()))
                .build();

        VaultEntry saved = vaultEntryRepository.save(entry);
        return convertToResponseDTO(saved);
    }

    /**
     * Get all vault entries for a user.
     */
    public List<VaultEntryResponseDTO> getEntriesByUser(Long userId) {
        return vaultEntryRepository.findByUserId(userId)
                .stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a single vault entry by ID, verifying ownership.
     */
    public VaultEntryResponseDTO getEntryById(Long id, Long userId) {
        VaultEntry entry = vaultEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vault entry not found with id: " + id));

        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Vault entry does not belong to the specified user");
        }

        return convertToResponseDTO(entry);
    }

    /**
     * Update an existing vault entry, verifying ownership.
     */
    @Transactional
    public VaultEntryResponseDTO updateEntry(Long id, Long userId, VaultEntryRequestDTO dto) {
        VaultEntry entry = vaultEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vault entry not found with id: " + id));

        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Vault entry does not belong to the specified user");
        }

        // WIKI4AI-197 (D1): a v1-shaped update onto a row that has already been
        // re-encrypted under v2 would overwrite nothing the v2 client reads, but it
        // would rewrite the v1 columns the row keeps only as a rollback copy — and the
        // next v2 reader has no way to tell that copy from the data. Refused here so
        // the caller gets 409 and reloads, rather than a 500 from the database trigger
        // that exists to catch exactly this when the writer is not this application.
        //
        // WIKI4AI-197 (D2): the same row accepts a v2-shaped update, which is how a v2
        // client edits an entry it has already migrated.
        boolean rowIsV2 = entry.getCryptoVersion() != null && entry.getCryptoVersion() == 2;
        if (rowIsV2 && !dto.isV2Write()) {
            throw new IllegalArgumentException(
                    "Vault entry " + id + " is encrypted under vault crypto v2; "
                            + "reload the vault and update it with a v2 client");
        }

        validateUpdateDTO(dto);

        if (dto.isV2Write()) {
            requireVaultKey(userId, dto.getKeyId());

            entry.setTitle(dto.getTitle());
            entry.setUrl(dto.getUrl());
            entry.setGroupPath(dto.getGroupPath());
            entry.setUsernameV2(decodeV2Field(dto.getUsernameV2(), "usernameV2"));
            entry.setPasswordV2(decodeV2Field(dto.getPasswordV2(), "passwordV2"));
            entry.setNotesV2(decodeV2Field(dto.getNotesV2(), "notesV2"));
            entry.setKeyId(dto.getKeyId());
            entry.markV2();

            if (!rowIsV2) {
                // The row was v1 and is now v2, and this write did not come from the
                // migration batch — so there is no v1 snapshot of the previous values
                // being preserved for a rollback, only of values that are about to be
                // replaced. Keeping a stale v1 copy is worse than dropping it: after a
                // rollback the user would read a password they had already changed.
                // The V29 trigger allows exactly this (nulling the v1 columns is the
                // one change it permits on a v2 row); ck_vault_v1_complete stops
                // mattering because the row is no longer v1.
                entry.setUsernameEncrypted(null);
                entry.setPasswordEncrypted(null);
                entry.setNotesEncrypted(null);
                entry.setIv(null);
            }

            return convertToResponseDTO(vaultEntryRepository.save(entry));
        }

        requireV1WritesAllowed(userId);

        byte[] usernameEncrypted = decodeEncryptedField(dto.getUsernameEncrypted());
        byte[] passwordEncrypted = decodeEncryptedField(dto.getPasswordEncrypted());
        byte[] notesEncrypted = decodeEncryptedField(dto.getNotesEncrypted());

        entry.setTitle(dto.getTitle());
        entry.setUsernameEncrypted(usernameEncrypted);
        entry.setPasswordEncrypted(passwordEncrypted);
        entry.setNotesEncrypted(notesEncrypted);
        entry.setUrl(dto.getUrl());
        entry.setGroupPath(dto.getGroupPath());
        entry.setIv(extractIv(dto.getPasswordEncrypted()));

        VaultEntry saved = vaultEntryRepository.save(entry);
        return convertToResponseDTO(saved);
    }

    /**
     * Delete a vault entry, verifying ownership.
     */
    @Transactional
    public void deleteEntry(Long id, Long userId) {
        VaultEntry entry = vaultEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vault entry not found with id: " + id));

        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Vault entry does not belong to the specified user");
        }

        vaultEntryRepository.delete(entry);
    }

    /**
     * Search vault entries by title and URL for a specific user.
     * Optionally filters by group_path prefix.
     */
    public List<VaultEntryResponseDTO> searchEntries(Long userId, String query, String groupPathPrefix) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<VaultEntry> entries;
        if (groupPathPrefix != null && !groupPathPrefix.isBlank()) {
            entries = vaultEntryRepository.findByUserIdAndGroupPathStartingWithAndTitleContainingIgnoreCaseOrUrlContainingIgnoreCase(
                    userId, groupPathPrefix, query, query);
        } else {
            entries = vaultEntryRepository.findByUserIdAndTitleContainingIgnoreCaseOrUrlContainingIgnoreCase(
                    userId, query, query);
        }

        return entries.stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    // ==================== VALIDATION ====================

    private void validateCreateDTO(VaultEntryRequestDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("DTO cannot be null");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (dto.isV2Write()) {
            validateV2Fields(dto);
            return;
        }
        if (dto.getPasswordEncrypted() == null) {
            throw new IllegalArgumentException("Password encrypted field is required");
        }
        if (dto.getPasswordEncrypted().getIv() == null || dto.getPasswordEncrypted().getIv().isBlank()) {
            throw new IllegalArgumentException("IV is required in password encrypted field");
        }
    }

    private void validateUpdateDTO(VaultEntryRequestDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("DTO cannot be null");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        // WIKI4AI-197 (D1): an update carries the password ciphertext, always.
        //
        // The update path has "a null field means the field is cleared" semantics, so
        // a request that simply omits passwordEncrypted does not leave the password
        // alone — it writes NULL over the ciphertext. Until V29 the NOT NULL on
        // password_encrypted turned that into a database error, which was wrong but
        // loud. V29 has to drop that NOT NULL, because a v2 row has no v1 password to
        // store, and with it gone the same request quietly destroys an entry: the
        // current frontend really does send title-only updates, and the MCP server
        // sends one whenever it has no password to preserve.
        //
        // The database CHECK says the same thing from the other side. This is the
        // version that can name the missing field.
        if (dto.isV2Write()) {
            validateV2Fields(dto);
            return;
        }
        if (dto.getPasswordEncrypted() == null) {
            throw new IllegalArgumentException("Password encrypted field is required");
        }
        if (dto.getPasswordEncrypted().getIv() == null || dto.getPasswordEncrypted().getIv().isBlank()) {
            throw new IllegalArgumentException("IV is required in password encrypted field");
        }
    }

    /**
     * A v2 write carries the password blob and the key it was sealed under.
     *
     * <p>The same reasoning as above, in the v2 columns: {@code ck_vault_v2_complete}
     * requires {@code password_v2} and {@code key_id} on a v2 row, and a request that
     * omits either would otherwise reach the database and fail there with a message
     * that cannot say which field the client forgot.
     */
    private void validateV2Fields(VaultEntryRequestDTO dto) {
        if (dto.getKeyId() == null || dto.getKeyId().isBlank()) {
            throw new IllegalArgumentException("Key id is required for a vault crypto v2 write");
        }
        if (dto.getPasswordV2() == null || dto.getPasswordV2().isBlank()) {
            throw new IllegalArgumentException("Encrypted v2 password is required");
        }
    }

    // ==================== V2 KEY GUARDS ====================

    /**
     * Refuse a v1-shaped write once this user's vault has a v2 key.
     *
     * <p>Without this, a browser tab left open across the upgrade, or the MCP server —
     * both of which only know how to write v1 — keep producing v1 rows in a vault whose
     * owner now reads it with a v2 client. Those rows are not corrupt, but the v2
     * client cannot read them until the next unlock migrates them, and the MCP write
     * path in particular silently drops the fields it could not decrypt on the way in.
     *
     * <p>409 and not 400: the request is well formed, and the caller fixes it by
     * reloading with a client that holds the v2 key.
     */
    private void requireV1WritesAllowed(Long userId) {
        if (vaultV2Service.isV2Managed(userId)) {
            throw new VaultV2ConflictException(
                    "This vault has been initialised for crypto v2, so a v1-shaped write would create "
                            + "a row its owner cannot read. Reload the vault and write it with a v2 client.");
        }
    }

    /**
     * Refuse a v2 write whose key this vault does not hold.
     *
     * <p>The server cannot check anything about the ciphertext itself — it cannot open
     * it — but it can refuse a row sealed under a key that is not the one this user's
     * vault was initialised with, which is the difference between a blob that is
     * unreadable for a reason and a blob that is unreadable forever.
     */
    private void requireVaultKey(Long userId, String keyId) {
        VaultV2ParamsResponseDTO params = vaultV2Service.getParams(userId);
        if (!params.isInitialized()) {
            throw new VaultV2ConflictException(
                    "The vault v2 key is not initialised for this user; a v2 write has nowhere to belong.");
        }
        if (!params.getKeyId().equals(keyId)) {
            throw new VaultV2ConflictException(
                    "This entry was encrypted under a different vault key than the one this vault is initialised with.");
        }
    }

    /**
     * The client sends the uuid its AAD was built from; a fresh one is generated when
     * it does not. Anything that is not a uuid is a client bug, said out loud.
     */
    private UUID parseEntryUuid(String entryUuid) {
        if (entryUuid == null || entryUuid.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(entryUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("entryUuid is not a valid UUID: " + entryUuid);
        }
    }

    /**
     * Decode one v2 field. Unlike {@link #decodeEncryptedField}, a value that is
     * present but not Base64 is an error rather than something to wave through: the
     * bytes are the whole field, and storing a mangled copy makes the entry unreadable.
     */
    private byte[] decodeV2Field(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(fieldName + " is not valid Base64");
        }
    }

    // ==================== CONVERSION ====================

    private VaultEntryResponseDTO convertToResponseDTO(VaultEntry entry) {
        return VaultEntryResponseDTO.builder()
                .id(entry.getId())
                .title(entry.getTitle())
                .usernameEncrypted(toEncryptedField(entry.getUsernameEncrypted(), entry.getIv()))
                .passwordEncrypted(toEncryptedField(entry.getPasswordEncrypted(), entry.getIv()))
                .notesEncrypted(toEncryptedField(entry.getNotesEncrypted(), entry.getIv()))
                .url(entry.getUrl())
                .groupPath(entry.getGroupPath())
                .createdAt(entry.getCreatedAt())
                .updatedAt(entry.getUpdatedAt())
                // WIKI4AI-197 (D1): the v2 half of the row, alongside the v1 fields
                // rather than in place of them. A client that knows nothing about v2
                // ignores all of this; a v2 client needs the format marker, the row
                // identity its AAD is built from, and the digest it has to echo back
                // to be allowed to re-encrypt the row.
                .cryptoVersion(entry.getCryptoVersion())
                .entryUuid(entry.getEntryUuid() == null ? null : entry.getEntryUuid().toString())
                .keyId(entry.getKeyId())
                .usernameV2(toBase64(entry.getUsernameV2()))
                .passwordV2(toBase64(entry.getPasswordV2()))
                .notesV2(toBase64(entry.getNotesV2()))
                .v1Digest(VaultV1Digest.of(entry.getUsernameEncrypted(),
                        entry.getPasswordEncrypted(), entry.getNotesEncrypted(), entry.getIv()))
                .build();
    }

    private String toBase64(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        return Base64.getEncoder().encodeToString(data);
    }

    private EncryptedField toEncryptedField(byte[] data, byte[] iv) {
        if (data == null || data.length == 0) {
            return null;
        }
        var builder = EncryptedField.builder()
                .ciphertext(Base64.getEncoder().encodeToString(data));
        if (iv != null && iv.length > 0) {
            builder.iv(Base64.getEncoder().encodeToString(iv));
        }
        return builder.build();
    }

    private byte[] decodeEncryptedField(EncryptedField field) {
        if (field == null || field.getCiphertext() == null || field.getCiphertext().isBlank()) {
            return null;
        }
        return Base64.getDecoder().decode(field.getCiphertext());
    }

    private byte[] extractIv(EncryptedField field) {
        if (field == null || field.getIv() == null || field.getIv().isBlank()) {
            return null;
        }
        return Base64.getDecoder().decode(field.getIv());
    }
}
